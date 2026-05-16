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
 * Thin client around the vManager {@code /rest/vplan/get} endpoint used to
 * obtain VPLAN_LEVEL attribute values for a build.
 *
 * <p>Payload shape:</p>
 * <pre>{
 *   "hierarchy": "...",                              // omitted when blank
 *   "sticky-context": {
 *     "runs-rs": {
 *       "filter": { "@c":".RelationFilter", "relationName":"session",
 *                   "filter": { "@c":".InFilter", "attName":"name",
 *                               "operand":"IN", "values":[...] } }
 *     },
 *     "refinement-files":       [...],
 *     "vplan":                  "APB_UART",
 *     "db-vplan":               true,
 *     "vplan-refinement-files": [...]
 *   },
 *   "projection": { "selection": ["attr_id_1", "attr_id_2"] }
 * }</pre>
 *
 * <p>The response is either a single JSON object whose keys match the
 * projected attribute ids, or a JSON array of such objects. Numeric values
 * are summed across rows; sentinel value {@code -1} is rewritten to
 * {@code 0.0}.</p>
 *
 * <p>Per-build routing is handled via {@link VplanRoutingContext}: the first
 * call for a given {@code (vplan, type)} pair sends only
 * {@code x-vmgr-routing-retain: 1}; subsequent calls echo back the
 * {@code x-vmgr-routing-oid} returned by the previous response for the
 * same pair. The same pair may carry its OID across chart boundaries within
 * one build.</p>
 */
public final class VManagerVplanClient {

    private VManagerVplanClient() {
        // static utility
    }

    /**
     * Posts a vplan-get query and returns the summed numeric value for each
     * requested attribute id.
     *
     * @param baseUrl                 vManager server base URL.
     * @param hierarchy               hierarchy path; if {@code null}/blank the
     *                                {@code hierarchy} key is omitted entirely.
     * @param sessionNames            session names; {@code null}/empty returns zeros.
     * @param refinementFiles         filesystem paths to refinement files; may be
     *                                {@code null}/empty.
     * @param vplanRefinementFiles    filesystem paths to vPlan refinement files;
     *                                may be {@code null}/empty.
     * @param vplan                   vPlan name (e.g. {@code "APB_UART"}).
     * @param dbVplan                 {@code true} for DB-stored vPlan, {@code false}
     *                                for file-based vPlan.
     * @param attributeIds            attribute ids to project; {@code null}/empty
     *                                returns an empty map.
     * @param creds                   HTTP Basic credentials; may be {@code null}.
     * @param routingCtx              per-build routing-OID holder keyed by
     *                                {@code (vplan, type)}; may be {@code null}.
     * @param listener                build console for payload / header logging;
     *                                may be {@code null}.
     * @return map of attribute id &rarr; summed value (entries pre-seeded to
     *         {@code 0.0}). Never {@code null}.
     */
    public static Map<String, Double> fetchVplanMetricSums(
            String baseUrl,
            String hierarchy,
            Collection<String> sessionNames,
            Collection<String> refinementFiles,
            Collection<String> vplanRefinementFiles,
            String vplan,
            boolean dbVplan,
            Collection<String> attributeIds,
            StandardUsernamePasswordCredentials creds,
            VplanRoutingContext routingCtx,
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
        String url  = base + "/rest/vplan/get";

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

        JSONArray vplanRefinementArr = new JSONArray();
        if (vplanRefinementFiles != null) {
            for (String f : vplanRefinementFiles) {
                if (f != null && !f.isBlank()) vplanRefinementArr.add(f);
            }
        }

        JSONObject stickyContext = new JSONObject();
        stickyContext.put("runs-rs",                runsRs);
        stickyContext.put("refinement-files",       refinementArr);
        stickyContext.put("vplan",                  vplan == null ? "" : vplan);
        stickyContext.put("db-vplan",               dbVplan);
        stickyContext.put("vplan-refinement-files", vplanRefinementArr);

        JSONArray selection = new JSONArray();
        for (String id : out.keySet()) selection.add(id);
        JSONObject projection = new JSONObject();
        projection.put("selection", selection);

        JSONObject body = new JSONObject();
        // hierarchy is omitted entirely when blank.
        if (hierarchy != null && !hierarchy.isBlank()) {
            body.put("hierarchy", hierarchy);
        }
        body.put("sticky-context", stickyContext);
        body.put("projection",     projection);

        // ── Headers (routing) ────────────────────────────────────────────
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(VplanRoutingContext.HDR_RETAIN, "1");
        if (routingCtx != null && routingCtx.hasOid(vplan, dbVplan ? "DB" : "FILE")) {
            headers.put(VplanRoutingContext.HDR_OID,
                    routingCtx.getOid(vplan, dbVplan ? "DB" : "FILE"));
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

        if (routingCtx != null) {
            String newOid = resp.header(VplanRoutingContext.HDR_OID);
            if (newOid != null && !newOid.isBlank()) {
                routingCtx.setOid(vplan, dbVplan ? "DB" : "FILE", newOid);
                if (listener != null && BuildLog.isVerbose()) {
                    listener.getLogger().println("[vManager Charts]   response "
                            + VplanRoutingContext.HDR_OID + ": " + newOid);
                }
            }
        }

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
        // vManager's "no value" sentinel.
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
