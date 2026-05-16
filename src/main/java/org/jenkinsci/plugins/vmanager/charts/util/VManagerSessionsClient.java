package org.jenkinsci.plugins.vmanager.charts.util;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.IOException;
import java.util.Collection;

/**
 * Thin client around the vManager {@code /rest/sessions/list} endpoint
 * used to obtain run-state counts for a list of session names.
 *
 * <p>The endpoint expects a POST with JSON body of the form:</p>
 * <pre>{
 *   "filter":     { "@c":".InFilter", "attName":"session_name", "operand":"IN", "values":[...] },
 *   "grouping":   ["region_identifier"],
 *   "projection": { "type":"SELECTION_ONLY",
 *                   "selection":[ "region_identifier","failed_runs","passed_runs",
 *                                 "running","waiting","other_runs","number_of_entities" ] }
 * }</pre>
 *
 * <p>The response is a JSON array of objects, one per region group, with the
 * fields above. {@link #fetchAggregated} sums the run-state counts across
 * all returned rows.</p>
 *
 * <p>For internal vManager servers with self-signed certs we trust all
 * certificates and hostnames (consistent with the rest of the plugin).</p>
 */
public final class VManagerSessionsClient {

    private VManagerSessionsClient() {
        // static utility
    }

    /**
     * Aggregated run-state totals for a set of sessions.
     */
    public static final class SessionAggregates {
        public final int passedRuns;
        public final int failedRuns;
        public final int running;
        public final int waiting;
        public final int otherRuns;
        public final int numberOfEntities;
        public final int rowCount;

        SessionAggregates(int passedRuns, int failedRuns, int running, int waiting,
                          int otherRuns, int numberOfEntities, int rowCount) {
            this.passedRuns       = passedRuns;
            this.failedRuns       = failedRuns;
            this.running          = running;
            this.waiting          = waiting;
            this.otherRuns        = otherRuns;
            this.numberOfEntities = numberOfEntities;
            this.rowCount         = rowCount;
        }
    }

    /**
     * Posts the sessions list query and returns the summed counts across all
     * region groups returned by the server.
     *
     * @param baseUrl       vManager server base URL (e.g. {@code https://host:port/vmgr/vapi})
     * @param sessionNames  one or more session names; {@code null}/empty returns zeros.
     * @param creds         credentials for HTTP Basic auth; may be {@code null} (no auth header).
     */
    public static SessionAggregates fetchAggregated(
            String baseUrl,
            Collection<String> sessionNames,
            StandardUsernamePasswordCredentials creds) throws IOException {
        return fetchAggregated(baseUrl, sessionNames, creds, null);
    }

    /**
     * Same as {@link #fetchAggregated(String, Collection, StandardUsernamePasswordCredentials)}
     * but logs the URL, headers and payload to {@code listener} (if non-null).
     */
    public static SessionAggregates fetchAggregated(
            String baseUrl,
            Collection<String> sessionNames,
            StandardUsernamePasswordCredentials creds,
            TaskListener listener) throws IOException {

        if (sessionNames == null || sessionNames.isEmpty()
                || baseUrl == null || baseUrl.isBlank()) {
            return new SessionAggregates(0, 0, 0, 0, 0, 0, 0);
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url  = base + "/rest/sessions/list";

        JSONObject filter = new JSONObject();
        filter.put("@c",      ".InFilter");
        filter.put("attName", "session_name");
        filter.put("operand", "IN");
        filter.put("values",  JSONArray.fromObject(sessionNames));

        JSONArray selection = new JSONArray();
        selection.add("region_identifier");
        selection.add("failed_runs");
        selection.add("passed_runs");
        selection.add("running");
        selection.add("waiting");
        selection.add("other_runs");
        selection.add("number_of_entities");

        JSONObject projection = new JSONObject();
        projection.put("type",      "SELECTION_ONLY");
        projection.put("selection", selection);

        JSONArray grouping = new JSONArray();
        grouping.add("region_identifier");

        JSONObject body = new JSONObject();
        body.put("filter",     filter);
        body.put("grouping",   grouping);
        body.put("projection", projection);

        String payload = body.toString();
        logPost(listener, url, payload, creds);
        String responseBody = VManagerHttpClient.postJson(url, payload, creds);

        Object parsed = JSONSerializer.toJSON(responseBody);
        if (!(parsed instanceof JSONArray)) {
            return new SessionAggregates(0, 0, 0, 0, 0, 0, 0);
        }
        JSONArray rows = (JSONArray) parsed;

        int passed = 0, failed = 0, run = 0, wait = 0, other = 0, ents = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            passed += optInt(row, "passed_runs");
            failed += optInt(row, "failed_runs");
            run    += optInt(row, "running");
            wait   += optInt(row, "waiting");
            other  += optInt(row, "other_runs");
            ents   += optInt(row, "number_of_entities");
        }
        return new SessionAggregates(passed, failed, run, wait, other, ents, rows.size());
    }

    /**
     * Posts a sessions-list query selecting an arbitrary set of attribute ids
     * and returns the summed numeric value for each attribute across all
     * returned rows.
     *
     * @param baseUrl       vManager server base URL.
     * @param sessionNames  one or more session names; {@code null}/empty returns an empty map.
     * @param attributeIds  attribute ids to project (e.g. {@code "passed_runs"},
     *                      {@code "MyCustomAtt"}); {@code null}/empty returns an empty map.
     * @param creds         credentials for HTTP Basic auth; may be {@code null}.
     * @return map of attribute id → summed value (only numeric fields are summed; non-numeric
     *         attributes are skipped). Never {@code null}.
     */
    public static java.util.Map<String, Double> fetchSessionAttributeSums(
            String baseUrl,
            Collection<String> sessionNames,
            Collection<String> attributeIds,
            StandardUsernamePasswordCredentials creds) throws IOException {
        return fetchSessionAttributeSums(baseUrl, sessionNames, attributeIds, creds, null);
    }

    /**
     * Same as {@link #fetchSessionAttributeSums(String, Collection, Collection,
     * StandardUsernamePasswordCredentials)} but logs the URL, headers and
     * payload to {@code listener} (if non-null).
     */
    public static java.util.Map<String, Double> fetchSessionAttributeSums(
            String baseUrl,
            Collection<String> sessionNames,
            Collection<String> attributeIds,
            StandardUsernamePasswordCredentials creds,
            TaskListener listener) throws IOException {

        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        if (sessionNames == null || sessionNames.isEmpty()
                || attributeIds == null || attributeIds.isEmpty()
                || baseUrl == null || baseUrl.isBlank()) {
            return out;
        }
        for (String id : attributeIds) {
            if (id != null && !id.isBlank()) out.put(id, 0.0);
        }
        if (out.isEmpty()) return out;

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url  = base + "/rest/sessions/list";

        JSONObject filter = new JSONObject();
        filter.put("@c",      ".InFilter");
        filter.put("attName", "session_name");
        filter.put("operand", "IN");
        filter.put("values",  JSONArray.fromObject(sessionNames));

        JSONArray selection = new JSONArray();
        for (String id : out.keySet()) selection.add(id);

        JSONObject projection = new JSONObject();
        projection.put("type",      "SELECTION_ONLY");
        projection.put("selection", selection);

        JSONArray grouping = new JSONArray();
        grouping.add("region_identifier");

        JSONObject body = new JSONObject();
        body.put("filter",     filter);
        body.put("grouping",   grouping);
        body.put("projection", projection);

        String payload = body.toString();
        logPost(listener, url, payload, creds);
        String responseBody = VManagerHttpClient.postJson(url, payload, creds);

        Object parsed = JSONSerializer.toJSON(responseBody);
        if (!(parsed instanceof JSONArray)) {
            return out;
        }
        JSONArray rows = (JSONArray) parsed;
        for (int i = 0; i < rows.size(); i++) {
            Object rowObj = rows.get(i);
            if (!(rowObj instanceof JSONObject)) continue;
            JSONObject row = (JSONObject) rowObj;
            for (String id : out.keySet()) {
                Double v = optDouble(row, id);
                if (v != null) {
                    out.put(id, out.get(id) + v);
                }
            }
        }
        return out;
    }

    /** @return numeric value for {@code key} (parsing strings if needed), or {@code null}. */
    private static Double optDouble(JSONObject o, String key) {
        if (!o.has(key) || o.get(key) == null) return null;
        Object raw = o.get(key);
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try { return Double.parseDouble(String.valueOf(raw)); }
        catch (Exception e) { return null; }
    }

    private static int optInt(JSONObject o, String key) {
        if (!o.has(key) || o.get(key) == null) return 0;
        try { return o.getInt(key); }
        catch (Exception e) {
            try { return (int) Math.round(Double.parseDouble(String.valueOf(o.get(key)))); }
            catch (Exception e2) { return 0; }
        }
    }

    /** Logs the outgoing POST URL, headers, and payload to the build console. */
    private static void logPost(TaskListener listener, String url, String payload,
                                StandardUsernamePasswordCredentials creds) {
        if (listener == null || !BuildLog.isVerbose()) return;
        listener.getLogger().println("[vManager Charts] POST " + url);
        listener.getLogger().println("[vManager Charts]   request headers:");
        listener.getLogger().println("[vManager Charts]     Content-Type: application/json; charset=UTF-8");
        listener.getLogger().println("[vManager Charts]     Accept: application/json, */*;q=0.8");
        listener.getLogger().println("[vManager Charts]     Authorization: "
                + (creds == null ? "<none>" : "Basic <redacted>"));
        listener.getLogger().println("[vManager Charts]   payload: " + payload);
    }
}
