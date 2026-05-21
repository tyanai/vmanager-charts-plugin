package org.jenkinsci.plugins.vmanager.charts.util;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(VManagerSessionsClient.class.getName());

    private VManagerSessionsClient() {
        // static utility
    }

    /**
     * Translates a list of session <em>ids</em> (e.g. lines of a
     * {@code .session_launch.output} file produced by the vManager Jenkins
     * Plugin in launch mode) into a list of session <em>names</em> by
     * POSTing to {@code /rest/sessions/list} with an {@code .InFilter}
     * over {@code id} and a {@code SELECTION_ONLY} projection of
     * {@code name}.
     *
     * <p>Trace lines mirror the rest of the plugin: the outbound URL /
     * headers / payload and the response summary go to the build console
     * when {@link BuildLog#isVerbose()} is on, and the same information is
     * mirrored to Jenkins' system log at {@link Level#FINE}.</p>
     *
     * @return the session names returned by the server (empty if the input
     *         is empty, the base URL is blank, or the response shape is
     *         unexpected). Never {@code null}.
     */
    public static List<String> fetchSessionNamesByIds(
            String baseUrl,
            Collection<String> ids,
            StandardUsernamePasswordCredentials creds,
            TaskListener listener) throws IOException {

        List<String> out = new ArrayList<>();
        if (ids == null || ids.isEmpty()
                || baseUrl == null || baseUrl.isBlank()) {
            return out;
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url  = base + "/rest/sessions/list";

        JSONObject filter = new JSONObject();
        filter.put("@c",      ".InFilter");
        filter.put("attName", "id");
        filter.put("values",  JSONArray.fromObject(ids));
        filter.put("operand", "IN");

        JSONArray selection = new JSONArray();
        selection.add("name");

        JSONObject projection = new JSONObject();
        projection.put("type",      "SELECTION_ONLY");
        projection.put("selection", selection);

        JSONObject body = new JSONObject();
        body.put("filter",     filter);
        body.put("projection", projection);

        String payload = body.toString();
        LOGGER.log(Level.FINE,
                "POST {0} (ids={1}, creds={2})",
                new Object[]{ url, ids.size(),
                              creds == null ? "<none>" : creds.getId() });
        logPost(listener, url, payload, creds);

        String responseBody = VManagerHttpClient.postJson(url, payload, creds);

        Object parsed = JSONSerializer.toJSON(responseBody);
        if (!(parsed instanceof JSONArray)) {
            LOGGER.log(Level.FINE,
                    "sessions/list (by id): unexpected response shape — {0}",
                    parsed == null ? "<null>" : parsed.getClass().getSimpleName());
            return out;
        }
        JSONArray rows = (JSONArray) parsed;
        for (int i = 0; i < rows.size(); i++) {
            Object rowObj = rows.get(i);
            if (!(rowObj instanceof JSONObject)) continue;
            JSONObject row = (JSONObject) rowObj;
            if (!row.has("name") || row.get("name") == null) continue;
            String name = String.valueOf(row.get("name")).trim();
            if (!name.isEmpty()) out.add(name);
        }
        LOGGER.log(Level.FINE,
                "sessions/list (by id): resolved {0} name(s) from {1} id(s)",
                new Object[]{ out.size(), ids.size() });
        if (listener != null && BuildLog.isVerbose()) {
            listener.getLogger().println(
                    "[vManager Charts]   sessions/list (by id): resolved "
                            + out.size() + " name(s) from " + ids.size() + " id(s)");
        }
        return out;
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
            for (Map.Entry<String, Double> e : out.entrySet()) {
                Double v = optDouble(row, e.getKey());
                if (v != null) {
                    e.setValue(e.getValue() + v);
                }
            }
        }
        return out;
    }

    /**
     * Posts a sessions-list query for the given session names and inspects
     * three vManager attributes per session &mdash; {@code total_runs_in_session},
     * {@code real_max_runs_in_parallel_vmgr} and {@code max_runs_in_parallel}
     * &mdash; to detect sessions whose configured parallelism could not be
     * met at runtime. Returns a map of {@code sessionName} &rarr; warning
     * text for every such session; sessions that do not match the
     * condition are absent from the returned map.
     *
     * <p>The condition for adding a warning is:</p>
     * <pre>
     *   total_runs_in_session   &gt; real_max_runs_in_parallel_vmgr
     *   AND
     *   max_runs_in_parallel    &gt; 1.25 * real_max_runs_in_parallel_vmgr
     * </pre>
     */
    public static java.util.Map<String, String> fetchSessionTatWarnings(
            String baseUrl,
            Collection<String> sessionNames,
            StandardUsernamePasswordCredentials creds,
            TaskListener listener) throws IOException {

        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (sessionNames == null || sessionNames.isEmpty()
                || baseUrl == null || baseUrl.isBlank()) {
            return out;
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url  = base + "/rest/sessions/list";

        JSONObject filter = new JSONObject();
        filter.put("@c",      ".InFilter");
        filter.put("attName", "session_name");
        filter.put("operand", "IN");
        filter.put("values",  JSONArray.fromObject(sessionNames));

        JSONArray selection = new JSONArray();
        selection.add("name");
        selection.add("total_runs_in_session");
        selection.add("real_max_runs_in_parallel_vmgr");
        selection.add("max_runs_in_parallel");

        JSONObject projection = new JSONObject();
        projection.put("type",      "SELECTION_ONLY");
        projection.put("selection", selection);

        JSONObject body = new JSONObject();
        body.put("filter",     filter);
        body.put("projection", projection);

        String payload = body.toString();
        logPost(listener, url, payload, creds);
        String responseBody = VManagerHttpClient.postJson(url, payload, creds);

        Object parsed = JSONSerializer.toJSON(responseBody);
        if (!(parsed instanceof JSONArray)) {
            LOGGER.log(Level.FINE,
                    "sessions/list (TAT): unexpected response shape \u2014 {0}",
                    parsed == null ? "<null>" : parsed.getClass().getSimpleName());
            return out;
        }
        JSONArray rows = (JSONArray) parsed;
        for (int i = 0; i < rows.size(); i++) {
            Object rowObj = rows.get(i);
            if (!(rowObj instanceof JSONObject)) continue;
            JSONObject row = (JSONObject) rowObj;
            if (!row.has("name") || row.get("name") == null) continue;
            String name = String.valueOf(row.get("name")).trim();
            if (name.isEmpty()) continue;

            Double total = optDouble(row, "total_runs_in_session");
            Double real  = optDouble(row, "real_max_runs_in_parallel_vmgr");
            Double max   = optDouble(row, "max_runs_in_parallel");
            if (total == null || real == null || max == null) continue;

            if (total > real && max > 1.25 * real) {
                out.put(name,
                        "Session might not be configured for maximum TAT. "
                                + "The actual number of parallel runs is more than 25% lower than the maximum configured setting.");
            }
        }
        if (listener != null && BuildLog.isVerbose()) {
            listener.getLogger().println(
                    "[vManager Charts]   sessions/list (TAT): "
                            + out.size() + " session(s) flagged with TAT warning");
        }
        LOGGER.log(Level.FINE,
                "sessions/list (TAT): {0} flagged of {1} requested",
                new Object[]{ out.size(), sessionNames.size() });
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
