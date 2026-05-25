package org.jenkinsci.plugins.vmanager.charts;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.jenkinsci.plugins.vmanager.charts.data.BuildStatisticsCollector;
import org.jenkinsci.plugins.vmanager.charts.data.TestResultsCollector;
import org.jenkinsci.plugins.vmanager.charts.model.ChartData;
import org.jenkinsci.plugins.vmanager.charts.model.ChartDefinition;
import org.jenkinsci.plugins.vmanager.charts.model.MetricDefinition;
import org.jenkinsci.plugins.vmanager.charts.util.JsonConfigLoader;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerChartsLayoutStore;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.BufferedReader;
import java.io.IOException;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Action that adds "vManager Charts" link to job sidebar.
 * Handles rendering of the vManager charts page.
 */
public class VManagerChartsAction implements Action {

    @SuppressWarnings("unused") // read by Stapler / Jelly via getJob()
    private final Job<?, ?> job;

    public VManagerChartsAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return "symbol-stats-chart-outline plugin-ionicons-api";
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return Messages.VManagerCharts_DisplayName();
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return "vmanager-charts";
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public boolean isShowBuildDuration() {
        return getProperty() == null || getProperty().isShowBuildDuration();
    }

    public boolean isShowSuccessRate() {
        return getProperty() == null || getProperty().isShowSuccessRate();
    }

    public boolean isShowTestResults() {
        return getProperty() == null || getProperty().isShowTestResults();
    }

    public boolean isShowFailureTriageChart() {
        VManagerChartsJobProperty p = getProperty();
        return p != null && p.isEnabled()
                && p.isShowGroupedRunsCharts()
                && !p.getGroupedRunsCharts().isEmpty();
    }

    /**
     * Cards for index.jelly rendering of grouped-runs heat-maps.
     */
    public List<GroupedRunsCard> getGroupedRunsCards() {
        VManagerChartsJobProperty p = getProperty();
        if (p == null || !p.isShowGroupedRunsCharts()) {
            return Collections.emptyList();
        }
        List<GroupedRunsCard> out = new ArrayList<>();
        int idx = 0;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition gc
                : p.getGroupedRunsCharts()) {
            String base = slug(gc.getTitle());
            if (base.isEmpty()) base = "grouped-runs-" + idx;
            String s = base;
            int n = 2;
            while (!used.add(s)) {
                s = base + "-" + n++;
            }
            out.add(new GroupedRunsCard(
                    gc.getTitle(), gc.getSubtitle(), idx, "grouped-" + s));
            idx++;
        }
        return out;
    }

    /** Lightweight row used by index.jelly to render a grouped-runs card. */
    public static final class GroupedRunsCard {
        private final String title;
        private final String subtitle;
        private final int    index;
        private final String chartId;
        GroupedRunsCard(String title, String subtitle, int index, String chartId) {
            this.title    = title;
            this.subtitle = subtitle;
            this.index    = index;
            this.chartId  = chartId;
        }
        public String getTitle()    { return title; }
        public String getSubtitle() { return subtitle; }
        public int    getIndex()    { return index; }
        public String getChartId()  { return chartId; }
    }

    private VManagerChartsJobProperty getProperty() {
        VManagerChartsJobProperty gui =
                (VManagerChartsJobProperty) job.getProperty(VManagerChartsJobProperty.class);
        // When configSource=FILE, the GUI checkboxes (showBuildDuration, etc.)
        // remain at their defaults because they are hidden in the form.
        // Overlay them from the JSON file that the build listener mirrored
        // next to the build log so this view reflects what the build is
        // actually producing.
        return JsonConfigLoader.effectiveForJob(job, gui);
    }

    public boolean isShowCustomMetrics() {
        VManagerChartsJobProperty p = getProperty();
        return p != null && p.isEnabled() && !p.getCustomCharts().isEmpty();
    }

    /** Used by index.jelly to render one container per configured chart. */
    public List<String> getCustomChartTitles() {
        VManagerChartsJobProperty p = getProperty();
        if (p == null) {
            return Collections.emptyList();
        }
        List<String> titles = new ArrayList<>();
        for (ChartDefinition c : p.getCustomCharts()) {
            titles.add(c.getTitle());
        }
        return titles;
    }

    /**
     * Used by index.jelly to render one dashboard card per configured custom
     * chart. Each entry exposes the human title, the per-build chart-div
     * {@code index} (already used by assets.js) and a stable {@code slug} used
     * as the dashboard layout key so reorder/width settings survive title
     * changes only if the title stays the same. Two charts with the same
     * slug fall back to {@code -2}, {@code -3}, &hellip; suffixes.
     */
    public List<CustomChartCard> getCustomChartCards() {
        VManagerChartsJobProperty p = getProperty();
        if (p == null) {
            return Collections.emptyList();
        }
        List<CustomChartCard> out = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        int idx = 0;
        for (ChartDefinition c : p.getCustomCharts()) {
            String base = slug(c.getTitle());
            if (base.isEmpty()) base = "custom-" + idx;
            String s = base;
            int n = 2;
            while (!used.add(s)) {
                s = base + "-" + n++;
            }
            out.add(new CustomChartCard(c.getTitle(), idx, "custom-" + s));
            idx++;
        }
        return out;
    }

    private static String slug(String title) {
        if (title == null) return "";
        StringBuilder sb = new StringBuilder(title.length());
        boolean lastDash = false;
        for (int i = 0; i < title.length(); i++) {
            char ch = Character.toLowerCase(title.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
                lastDash = false;
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '-') end--;
        return sb.substring(0, end);
    }

    /** Lightweight row used by index.jelly to render a custom-chart card. */
    public static final class CustomChartCard {
        private final String title;
        private final int    index;
        private final String chartId;
        CustomChartCard(String title, int index, String chartId) {
            this.title = title;
            this.index = index;
            this.chartId = chartId;
        }
        public String getTitle()   { return title; }
        public int    getIndex()   { return index; }
        public String getChartId() { return chartId; }
    }

    /**
     * Returns one {@link ChartData} per configured custom chart, where each
     * ChartData contains one series per metric in that chart (each series may
     * carry its own type: line/bar/column).
     */
    @JavaScriptMethod
    public List<ChartData> getCustomMetricsData() {
        VManagerChartsJobProperty p = getProperty();
        if (p == null || p.getCustomCharts().isEmpty()) {
            return Collections.emptyList();
        }

        List<ChartData> result = new ArrayList<>();
        for (ChartDefinition chart : p.getCustomCharts()) {
            int chartMax = chart.getMaxBuilds(); // 0 = unlimited

            // Per-chart: walk newest -> oldest builds that have a CustomMetricsBuildAction,
            // bounded by this chart's own maxBuilds.
            List<String> buildLabels = new ArrayList<>();
            Map<String, List<Double>> valuesByKey = new LinkedHashMap<>();
            for (MetricDefinition md : chart.getMetrics()) {
                valuesByKey.put(
                        CustomMetricsRunListener.key(chart.getTitle(), md.getSeriesKey()),
                        new ArrayList<>());
            }

            int matched = 0;
            for (Run<?, ?> build : job.getBuilds()) {
                if (chartMax > 0 && matched >= chartMax) break;
                CustomMetricsBuildAction action = build.getAction(CustomMetricsBuildAction.class);
                if (action == null) continue;

                buildLabels.add("#" + build.getNumber());
                for (Map.Entry<String, List<Double>> e : valuesByKey.entrySet()) {
                    Double val = action.getMetrics().get(e.getKey());
                    e.getValue().add(val != null ? val : 0.0);
                }
                matched++;
            }

            Collections.reverse(buildLabels);
            for (List<Double> values : valuesByKey.values()) {
                Collections.reverse(values);
            }

            ChartData chartData = new ChartData();
            chartData.setLabels(buildLabels);
            chartData.setChartType("line");
            chartData.setOption("title", chart.getTitle());
            for (MetricDefinition md : chart.getMetrics()) {
                String k = CustomMetricsRunListener.key(chart.getTitle(), md.getSeriesKey());
                chartData.addSeries(md.getDisplayName(), valuesByKey.get(k), md.getChartType());
            }
            result.add(chartData);
        }
        return result;
    }

    @JavaScriptMethod
    public ChartData getBuildDurationData() {
        return new BuildStatisticsCollector(job, getMaxBuilds()).collectBuildDurations();
    }

    @JavaScriptMethod
    public ChartData getSuccessRateData() {
        return new BuildStatisticsCollector(job, getMaxBuilds()).collectSuccessRates();
    }

    @JavaScriptMethod
    public ChartData getTestResultsData() {
        return new TestResultsCollector(job, getMaxBuilds()).collectTestResults();
    }

    /**
     * Returns one heat-map dataset per configured
     * {@link org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition}
     * (in declaration order, matching {@link #getGroupedRunsCards()}). Each
     * entry has the same shape as the legacy single-chart payload:
     * {@code labels, yLabels, yTitles, cells, maxValue} plus a
     * {@code title} / {@code subtitle} for chart rendering.
     */
    @JavaScriptMethod
    public net.sf.json.JSONArray getGroupedRunsChartsData() {
        net.sf.json.JSONArray out = new net.sf.json.JSONArray();
        VManagerChartsJobProperty p = getProperty();
        if (p == null || !p.isShowGroupedRunsCharts()) return out;

        List<org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition> defs =
                p.getGroupedRunsCharts();
        for (org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition gc : defs) {
            out.add(buildGroupedRunsPayload(gc));
        }
        return out;
    }

    /** Builds one heat-map payload for the given chart definition. */
    private JSONObject buildGroupedRunsPayload(
            org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition gc) {

        JSONObject result = new JSONObject();
        result.put("title",    gc.getTitle()    == null ? "" : gc.getTitle());
        result.put("subtitle", gc.getSubtitle() == null ? "" : gc.getSubtitle());

        net.sf.json.JSONArray empty = new net.sf.json.JSONArray();
        result.put("labels",   empty);
        result.put("yLabels",  empty);
        result.put("yTitles",  empty);
        result.put("cells",    empty);
        result.put("maxValue", 0);

        int max     = gc.getMaxBuilds();
        int yLimit  = gc.getYAxisLimit();
        String wantedTitle = gc.getTitle() == null ? "" : gc.getTitle();

        // ── Collect per-build counts (newest first) for THIS chart only ──
        List<String> buildLabels = new ArrayList<>();
        List<Map<String, Integer>> perBuild = new ArrayList<>();
        int matched = 0;
        boolean allowLegacyFallback = isFirstChart(gc);
        for (Run<?, ?> build : job.getBuilds()) {
            if (max > 0 && matched >= max) break;
            FailureTriageBuildAction action = pickAction(build, wantedTitle, allowLegacyFallback);
            if (action == null) continue;
            buildLabels.add("#" + build.getNumber());
            perBuild.add(action.getCounts());
            matched++;
        }
        if (buildLabels.isEmpty()) return result;

        final int TRUNCATE_LEN = 50;
        Map<String, int[]> agg = new LinkedHashMap<>();
        for (int i = 0; i < perBuild.size(); i++) {
            for (Map.Entry<String, Integer> e : perBuild.get(i).entrySet()) {
                String desc = e.getKey() == null ? "" : e.getKey();
                int    c    = e.getValue() == null ? 0 : e.getValue();
                if (!agg.containsKey(desc)) {
                    agg.put(desc, new int[]{ i, c });
                }
            }
        }

        List<Map.Entry<String, int[]>> rows = new ArrayList<>(agg.entrySet());
        rows.sort((a, b) -> {
            int byLast = Integer.compare(a.getValue()[0], b.getValue()[0]);
            if (byLast != 0) return byLast;
            return Integer.compare(b.getValue()[1], a.getValue()[1]);
        });
        if (yLimit > 0 && rows.size() > yLimit) rows = rows.subList(0, yLimit);
        java.util.Collections.reverse(rows);

        net.sf.json.JSONArray yLabels = new net.sf.json.JSONArray();
        net.sf.json.JSONArray yTitles = new net.sf.json.JSONArray();
        java.util.Map<String, Integer> descToY = new java.util.HashMap<>();
        for (int yi = 0; yi < rows.size(); yi++) {
            String full = rows.get(yi).getKey();
            String oneLine = full.replaceAll("[\\r\\n\\t]+", " ")
                    .replaceAll(" {2,}", " ").trim();
            String shown = oneLine.length() > TRUNCATE_LEN
                    ? oneLine.substring(0, TRUNCATE_LEN) + "\u2026"
                    : oneLine;
            yLabels.add(shown);
            yTitles.add(full);
            descToY.put(full, yi);
        }

        net.sf.json.JSONArray cells = new net.sf.json.JSONArray();
        int maxValue = 0;
        for (int xi = 0; xi < perBuild.size(); xi++) {
            for (Map.Entry<String, Integer> e : perBuild.get(xi).entrySet()) {
                String desc = e.getKey() == null ? "" : e.getKey();
                Integer yi  = descToY.get(desc);
                if (yi == null) continue;
                int c = e.getValue() == null ? 0 : e.getValue();
                if (c <= 0) continue;
                net.sf.json.JSONArray cell = new net.sf.json.JSONArray();
                cell.add(xi);
                cell.add(yi);
                cell.add(c);
                cells.add(cell);
                if (c > maxValue) maxValue = c;
            }
        }

        net.sf.json.JSONArray xLabels = new net.sf.json.JSONArray();
        xLabels.addAll(buildLabels);

        result.put("labels",   xLabels);
        result.put("yLabels",  yLabels);
        result.put("yTitles",  yTitles);
        result.put("cells",    cells);
        result.put("maxValue", maxValue);
        return result;
    }

    /**
     * Picks the {@link FailureTriageBuildAction} on {@code build} that
     * corresponds to {@code wantedTitle}. When {@code allowLegacy} is true
     * AND no titled match is found, falls back to the first action with an
     * empty {@code chartTitle} (data written by the pre-multi-chart version
     * of the plugin).
     */
    private static FailureTriageBuildAction pickAction(
            Run<?, ?> build, String wantedTitle, boolean allowLegacy) {
        FailureTriageBuildAction legacy = null;
        for (FailureTriageBuildAction a : build.getActions(FailureTriageBuildAction.class)) {
            String t = a.getChartTitle();
            if (wantedTitle.equals(t)) return a;
            if (t.isEmpty() && legacy == null) legacy = a;
        }
        return allowLegacy ? legacy : null;
    }

    /** True when {@code gc} is the first configured grouped-runs chart. */
    private boolean isFirstChart(
            org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition gc) {
        VManagerChartsJobProperty p = getProperty();
        if (p == null) return false;
        List<org.jenkinsci.plugins.vmanager.charts.model.GroupedRunsChartDefinition> defs =
                p.getGroupedRunsCharts();
        return !defs.isEmpty() && defs.get(0) == gc;
    }

    /**
     * Resolve the user-configured max-builds setting from the
     * {@link VManagerChartsJobProperty}, falling back to {@code 50} when
     * the property is not attached (legacy jobs).
     */
    private int getMaxBuilds() {
        VManagerChartsJobProperty p = job.getProperty(VManagerChartsJobProperty.class);
        return p != null ? p.getMaxBuilds() : 50;
    }

    // ── Dashboard layout (order + per-card width) ─────────────────────────

    /** Current persisted layout, opaque JSON. Returns {@code "{}"} if none. */
    public String getLayoutJson() {
        return VManagerChartsLayoutStore.loadJobLayout(job);
    }

    /**
     * Used by the dashboard JS to decide whether to show the
     * drag/width/save UI or stay read-only. Anyone without
     * {@link Item#CONFIGURE} sees the layout but cannot change it.
     */
    public boolean isCanConfigure() {
        return job != null && job.hasPermission(Item.CONFIGURE);
    }

    /**
     * POST endpoint that persists the dashboard layout JSON sent by the
     * front-end. Requires {@link Item#CONFIGURE} on the job and rejects
     * payloads that don't parse as a JSON object or exceed the size cap.
     */
    @RequirePOST
    public void doSaveLayout(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        job.checkPermission(Item.CONFIGURE);
        String body = readBody(req, VManagerChartsLayoutStore.MAX_LAYOUT_BYTES);
        // Reject anything that isn't a JSON object so callers can't dump
        // arbitrary text into the file.
        try {
            Object parsed = JSONSerializer.toJSON(body);
            if (!(parsed instanceof JSONObject)) {
                rsp.sendError(400, "layout must be a JSON object");
                return;
            }
        } catch (RuntimeException e) {
            rsp.sendError(400, "invalid JSON: " + e.getMessage());
            return;
        }
        VManagerChartsLayoutStore.saveJobLayout(job, body);
        rsp.setStatus(204);
    }

    private static String readBody(StaplerRequest2 req, int maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = req.getReader()) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                if (sb.length() + n > maxBytes) {
                    throw new IOException("layout payload exceeds " + maxBytes + " bytes");
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
