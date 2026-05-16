package org.jenkinsci.plugins.vmanager.charts.util;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.TaskListener;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client around the vManager {@code /rest/metrics/get} endpoint used to
 * obtain coverage / vPlan attribute values for a build.
 *
 * <p>The endpoint expects a POST with a JSON body of the form:</p>
 * <pre>{
 *   "hierarchy": "Verification Metrics/Instances",
 *   "sticky-context": {
 *     "runs-rs": {
 *       "filter": {
 *         "@c":         ".RelationFilter",
 *         "relationName":"session",
 *         "filter": { "@c":".InFilter", "attName":"name", "operand":"IN",
 *                     "values":["session_1","session_2"] }
 *       }
 *     },
 *     "refinement-files": ["/abs/path/to/file1", "/abs/path/to/file2"]
 *   },
 *   "projection": { "selection": ["attribute_id_1", "attribute_id_2"] },
 *   "verification-scope": "..."
 * }</pre>
 *
 * <p>The response is a JSON array of objects; each object contains the
 * projected attribute ids as keys. Numeric values are summed across rows.</p>
 */
public final class VManagerMetricsClient {

    /** Default value used when the user-supplied hierarchy is blank. */
    public static final String DEFAULT_HIERARCHY = "Verification Metrics/Instances";

    private VManagerMetricsClient() {
        // static utility
    }

    /**
     * Posts a metrics-get query and returns the summed numeric value for each
     * requested attribute id across all rows the server returns.
     *
     * @param baseUrl           vManager server base URL.
     * @param hierarchy         coverage / vPlan hierarchy path; if {@code null}/blank,
     *                          {@link #DEFAULT_HIERARCHY} is used.
     * @param verificationScope verification scope string; {@code null} is sent as empty.
     * @param sessionNames      session names that drive the {@code RelationFilter};
     *                          {@code null}/empty returns zeros.
     * @param refinementFiles   absolute filesystem paths to refinement files; may be
     *                          {@code null}/empty.
     * @param attributeIds      attribute ids to project; {@code null}/empty returns
     *                          an empty map.
     * @param creds             HTTP Basic credentials; may be {@code null}.
     * @param routingCtx        per-build routing-OID holder. The first call within
     *                          a build is sent without {@code x-vmgr-routing-oid};
     *                          subsequent calls echo back the value the server
     *                          returned in the previous response. May be
     *                          {@code null} (no routing headers sent).
     * @param listener          build console for payload / header logging; may be
     *                          {@code null}.
     * @return map of attribute id → summed value (entries pre-seeded to {@code 0.0}
     *         for every requested id). Never {@code null}.
     */
    public static Map<String, Double> fetchMetricSums(
            String baseUrl,
            String hierarchy,
            String verificationScope,
            Collection<String> sessionNames,
            Collection<String> refinementFiles,
            Collection<String> attributeIds,
            StandardUsernamePasswordCredentials creds,
            CoverageRoutingContext routingCtx,
            TaskListener listener) throws IOException {

        Map<String, Double> out = new LinkedHashMap<>();
        if (attributeIds != null) {
            for (String id : attributeIds) {
                if (id != null && !id.isBlank()) out.put(id, 0.0);
            }
        }
        if (out.isEmpty() || baseUrl == null || baseUrl.isBlank()) {
            return out;
        }
        if (sessionNames == null || sessionNames.isEmpty()) {
            return out;
        }

        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url  = base + "/rest/metrics/get";

        // ── Build payload ────────────────────────────────────────────────
        JSONObject innerFilter = new JSONObject();
        innerFilter.put("@c",      ".InFilter");
        innerFilter.put("attName", "name");
        innerFilter.put("operand", "IN");
        innerFilter.put("values",  JSONArray.fromObject(sessionNames));

        JSONObject relationFilter = new JSONObject();
        relationFilter.put("@c",           ".RelationFilter");
        relationFilter.put("relationName", "session");
        relationFilter.put("filter",       innerFilter);

        JSONObject runsRs = new JSONObject();
        runsRs.put("filter", relationFilter);

        JSONArray refinementArr = new JSONArray();
        if (refinementFiles != null) {
            for (String f : refinementFiles) {
                if (f != null && !f.isBlank()) refinementArr.add(f);
            }
        }

        JSONObject stickyContext = new JSONObject();
        stickyContext.put("runs-rs",          runsRs);
        stickyContext.put("refinement-files", refinementArr);

        JSONArray selection = new JSONArray();
        for (String id : out.keySet()) selection.add(id);
        JSONObject projection = new JSONObject();
        projection.put("selection", selection);

        JSONObject body = new JSONObject();
        body.put("hierarchy",         (hierarchy == null || hierarchy.isBlank())
                                          ? DEFAULT_HIERARCHY : hierarchy);
        body.put("sticky-context",    stickyContext);
        body.put("projection",        projection);
        // Only send verification-scope when the user actually provided one.
        if (verificationScope != null && !verificationScope.isBlank()) {
            body.put("verification-scope", verificationScope);
        }

        // ── Call ─────────────────────────────────────────────────────────
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CoverageRoutingContext.HDR_RETAIN, "1");
        if (routingCtx != null && routingCtx.hasOid()) {
            headers.put(CoverageRoutingContext.HDR_OID, routingCtx.getOid());
        }

        String payload = body.toString();
        if (listener != null && BuildLog.isVerbose()) {
            listener.getLogger().println("[vManager Charts] POST " + url);
            listener.getLogger().println("[vManager Charts]   request headers:");
            listener.getLogger().println("[vManager Charts]     Content-Type: application/json; charset=UTF-8");
            listener.getLogger().println("[vManager Charts]     Accept: application/json, */*;q=0.8");
            listener.getLogger().println("[vManager Charts]     Authorization: "
                    + (creds == null ? "<none>" : "Basic <redacted>"));
            for (Map.Entry<String, String> h : headers.entrySet()) {
                listener.getLogger().println("[vManager Charts]     " + h.getKey() + ": " + h.getValue());
            }
            listener.getLogger().println("[vManager Charts]   payload: " + payload);
        }

        VManagerHttpClient.Response resp = VManagerHttpClient.postJsonFull(
                url, payload, creds, headers);

        // Capture the server-issued routing OID so the next call within this
        // build echoes it back.
        if (routingCtx != null) {
            String newOid = resp.header(CoverageRoutingContext.HDR_OID);
            if (newOid != null && !newOid.isBlank()) {
                routingCtx.setOid(newOid);
                if (listener != null && BuildLog.isVerbose()) {
                    listener.getLogger().println("[vManager Charts]   response "
                            + CoverageRoutingContext.HDR_OID + ": " + newOid);
                }
            }
        }

        // The server may return either a single JSON object (one row of named
        // attribute values, e.g. {"code_grade": 53.57, "block_average_grade": 92.30})
        // or a JSON array of such objects. Accept both shapes.
        Object parsed = JSONSerializer.toJSON(resp.body);
        if (parsed instanceof JSONObject) {
            sumRow((JSONObject) parsed, out);
        } else if (parsed instanceof JSONArray) {
            JSONArray rows = (JSONArray) parsed;
            for (int i = 0; i < rows.size(); i++) {
                Object rowObj = rows.get(i);
                if (rowObj instanceof JSONObject) {
                    sumRow((JSONObject) rowObj, out);
                }
            }
        }
        // vManager returns -1 for "no value / not applicable" — surface that
        // as 0.0 so the chart isn't skewed by a sentinel.
        for (Map.Entry<String, Double> e : out.entrySet()) {
            if (e.getValue() != null && e.getValue() == -1.0) {
                e.setValue(0.0);
            }
        }
        return out;
    }

    private static void sumRow(JSONObject row, Map<String, Double> out) {
        for (String id : out.keySet()) {
            Double v = optDouble(row, id);
            if (v != null) {
                out.put(id, out.get(id) + v);
            }
        }
    }

    private static Double optDouble(JSONObject o, String key) {
        if (!o.has(key) || o.get(key) == null) return null;
        Object raw = o.get(key);
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try { return Double.parseDouble(String.valueOf(raw)); }
        catch (Exception e) { return null; }
    }
}
