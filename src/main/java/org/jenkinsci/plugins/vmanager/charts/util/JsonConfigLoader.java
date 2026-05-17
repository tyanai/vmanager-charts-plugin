package org.jenkinsci.plugins.vmanager.charts.util;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.jenkinsci.plugins.vmanager.charts.VManagerChartsJobProperty;
import org.jenkinsci.plugins.vmanager.charts.model.ChartDefinition;
import org.jenkinsci.plugins.vmanager.charts.model.MetricDefinition;
import org.jenkinsci.plugins.vmanager.charts.model.RefinementFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link VManagerChartsJobProperty} from a JSON configuration
 * (typically exported from the job-property UI and dropped into the build's
 * workspace). The loader intentionally ignores any {@code credentialsId}
 * field that may be present in the JSON: credentials are never imported,
 * they always come from the GUI.
 */
public final class JsonConfigLoader {

    private JsonConfigLoader() {
        // static utility
    }

    /**
     * Serializes a {@link VManagerChartsJobProperty} into the canonical JSON
     * shape that {@link #load(String)} can read back (round-trippable).
     * The following fields are intentionally NEVER exported and NEVER
     * imported — they always come from the GUI at build time:
     * <ul>
     *   <li>{@code credentialsId}</li>
     *   <li>{@code serverUrl}</li>
     *   <li>{@code sessionSource} / {@code sessionInputFile}</li>
     * </ul>
     */
    public static String toJson(VManagerChartsJobProperty p) {
        JSONObject root = new JSONObject();
        root.put("enabled",                         p.isEnabled());
        root.put("vManagerSchema",                  p.getVManagerSchema() == null ? "latest" : p.getVManagerSchema());
        root.put("maxBuilds",                       p.getMaxBuilds());
        root.put("showBuildLevelCharts",            p.isShowBuildLevelCharts());
        root.put("showRegressionOptimizationChart", p.isShowRegressionOptimizationChart());
        root.put("showBuildDuration",               p.isShowBuildDuration());
        root.put("showSuccessRate",                 p.isShowSuccessRate());
        // showTestResults (Regression Anomaly Detection Summary) is
        // feature-flagged off in the UI; do not export it until the
        // feature ships. The Java field is preserved for future use.
        root.put("showCustomMetrics",               p.isShowCustomMetrics());

        JSONArray charts = new JSONArray();
        List<ChartDefinition> defs = p.getCustomCharts();
        if (defs != null) {
            for (ChartDefinition c : defs) {
                JSONObject cj = new JSONObject();
                cj.put("title",     c.getTitle()     == null ? "" : c.getTitle());
                cj.put("vPlanType", c.getVPlanType() == null ? "" : c.getVPlanType());
                cj.put("vPlanPath", c.getVPlanPath() == null ? "" : c.getVPlanPath());
                cj.put("maxBuilds", c.getMaxBuilds());
                JSONArray metrics = new JSONArray();
                if (c.getMetrics() != null) {
                    for (MetricDefinition m : c.getMetrics()) {
                        JSONObject mj = new JSONObject();
                        mj.put("entityType",         nz(m.getEntityType()));
                        mj.put("attributeName",      nz(m.getAttributeName()));
                        mj.put("chartType",          nz(m.getChartType()));
                        mj.put("hierarchyPath",      nz(m.getHierarchyPath()));
                        mj.put("verificationScope",  nz(m.getVerificationScope()));
                        mj.put("coverageHierarchy",  nz(m.getCoverageHierarchy()));
                        mj.put("nickname",           nz(m.getNickname()));
                        mj.put("refinementFiles",      refinementsToJson(m.getRefinementFiles()));
                        mj.put("vplanRefinementFiles", refinementsToJson(m.getVplanRefinementFiles()));
                        metrics.add(mj);
                    }
                }
                cj.put("metrics", metrics);
                charts.add(cj);
            }
        }
        root.put("customCharts", charts);
        return root.toString(2);
    }

    private static JSONArray refinementsToJson(List<RefinementFile> files) {
        JSONArray arr = new JSONArray();
        if (files != null) {
            for (RefinementFile rf : files) {
                JSONObject o = new JSONObject();
                o.put("path", nz(rf.getPath()));
                arr.add(o);
            }
        }
        return arr;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Parses {@code jsonText} and returns a populated property (no credentials). */
    public static VManagerChartsJobProperty load(String jsonText) {
        Object parsed = JSONSerializer.toJSON(jsonText == null ? "{}" : jsonText);
        if (!(parsed instanceof JSONObject)) {
            throw new IllegalArgumentException(
                    "Top-level JSON value must be an object.");
        }
        JSONObject root = (JSONObject) parsed;

        VManagerChartsJobProperty p = new VManagerChartsJobProperty();
        p.setEnabled(root.optBoolean("enabled", true));
        // serverUrl, sessionSource and sessionInputFile are intentionally
        // NOT loaded from JSON; the listener overlays them from the GUI
        // property after this method returns.
        p.setVManagerSchema(root.optString("vManagerSchema", "latest"));
        p.setMaxBuilds(root.optInt("maxBuilds", 50));

        p.setShowBuildLevelCharts(root.optBoolean("showBuildLevelCharts", false));
        p.setShowRegressionOptimizationChart(root.optBoolean("showRegressionOptimizationChart", false));
        p.setShowBuildDuration(root.optBoolean("showBuildDuration", false));
        p.setShowSuccessRate(root.optBoolean("showSuccessRate", false));
        // showTestResults is feature-flagged off; force false regardless
        // of what an older JSON file may contain.
        p.setShowTestResults(false);
        p.setShowCustomMetrics(root.optBoolean("showCustomMetrics", false));

        // configSource / configFilePath are deliberately NOT carried over: when a
        // build has already loaded a JSON config, subsequent reads should not
        // re-trigger another file load via the same property instance.
        p.setConfigSource("GUI");
        p.setConfigFilePath("");

        p.setCustomCharts(parseCharts(root.opt("customCharts")));

        return p;
    }

    private static List<ChartDefinition> parseCharts(Object raw) {
        List<ChartDefinition> out = new ArrayList<>();
        for (JSONObject row : asObjectList(raw)) {
            String title     = row.optString("title", "");
            String vPlanType = row.optString("vPlanType", "");
            String vPlanPath = row.optString("vPlanPath", "");
            List<MetricDefinition> metrics = parseMetrics(row.opt("metrics"));
            ChartDefinition c = new ChartDefinition(title, metrics, vPlanType, vPlanPath);
            c.setMaxBuilds(row.optInt("maxBuilds", 50));
            out.add(c);
        }
        return out;
    }

    private static List<MetricDefinition> parseMetrics(Object raw) {
        List<MetricDefinition> out = new ArrayList<>();
        for (JSONObject row : asObjectList(raw)) {
            MetricDefinition m = new MetricDefinition(
                    row.optString("entityType", ""),
                    row.optString("attributeName", ""),
                    row.optString("chartType", "line"),
                    row.optString("hierarchyPath", ""),
                    row.optString("verificationScope", ""));
            m.setCoverageHierarchy(row.optString("coverageHierarchy", ""));
            m.setNickname(row.optString("nickname", ""));
            m.setRefinementFiles(parseRefinementFiles(row.opt("refinementFiles")));
            m.setVplanRefinementFiles(parseRefinementFiles(row.opt("vplanRefinementFiles")));
            out.add(m);
        }
        return out;
    }

    private static List<RefinementFile> parseRefinementFiles(Object raw) {
        List<RefinementFile> out = new ArrayList<>();
        for (JSONObject row : asObjectList(raw)) {
            out.add(new RefinementFile(row.optString("path", "")));
        }
        return out;
    }

    /** Accept either a JSONArray of objects, a single JSONObject, or null. */
    private static List<JSONObject> asObjectList(Object raw) {
        List<JSONObject> out = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            for (int i = 0; i < arr.size(); i++) {
                Object e = arr.get(i);
                if (e instanceof JSONObject) out.add((JSONObject) e);
            }
        } else if (raw instanceof JSONObject) {
            out.add((JSONObject) raw);
        }
        return out;
    }
}
