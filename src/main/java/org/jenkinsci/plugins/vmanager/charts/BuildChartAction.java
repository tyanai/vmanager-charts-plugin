package org.jenkinsci.plugins.vmanager.charts;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Run;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.jenkinsci.plugins.vmanager.charts.util.JsonConfigLoader;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerChartsLayoutStore;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.bind.JavaScriptMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Build-level action that adds a "vManager Charts" link to every individual
 * build's left sidebar. The page hosts one or more build-scope charts.
 *
 * <p>Currently the page hosts a single chart, the
 * <strong>Regression Optimization Chart</strong> &mdash; a scatter of all
 * runs in the build's vManager session(s), bucketed by duration into
 * Small (green), Medium (orange) and Large (red) thirds. Additional
 * build-level charts can be added to the same page over time.</p>
 *
 * <p>The chart's data is computed at the end of each build by
 * {@code CustomMetricsRunListener} and stored on the build as a
 * {@link RegressionOptimizationBuildAction}; this action simply hands that
 * stored data to the front-end ECharts code via a Stapler proxy.</p>
 */
public class BuildChartAction implements Action {

    private final Run<?, ?> run;

    public BuildChartAction(Run<?, ?> run) {
        this.run = run;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @Override
    @CheckForNull
    public String getIconFileName() {
        return "symbol-stats-chart-outline plugin-ionicons-api";
    }

    @Override
    @CheckForNull
    public String getDisplayName() {
        return "vManager Charts";
    }

    @Override
    @CheckForNull
    public String getUrlName() {
        return "vmanager-chart";
    }

    /** Page heading: "vManager Charts for build #N". */
    public String getPageHeading() {
        if (run == null) return "vManager Charts";
        return "vManager Charts for build #" + run.getNumber();
    }

    /** Whether the Regression Optimization Chart is enabled on the parent job. */
    public boolean isShowRegressionOptimizationChart() {
        if (run == null) return false;
        VManagerChartsJobProperty gui = (VManagerChartsJobProperty)
                run.getParent().getProperty(VManagerChartsJobProperty.class);
        VManagerChartsJobProperty p = JsonConfigLoader.effectiveForRun(run, gui);
        return p != null && p.isEnabled()
                && p.isShowBuildLevelCharts()
                && p.isShowRegressionOptimizationChart();
    }

    /**
     * vManager session names this build was associated with (resolved from
     * {@code .sessions.input} or, as a fallback, {@code .session_launch.output}).
     * Returns an empty list when no build-level chart data was recorded yet.
     * Exposed to the build-level charts page so it can list them under the heading.
     */
    public List<String> getSessions() {
        if (run == null) return Collections.emptyList();
        RegressionOptimizationBuildAction stored =
                run.getAction(RegressionOptimizationBuildAction.class);
        if (stored == null) return Collections.emptyList();
        return stored.getSessions();
    }

    /**
     * Per-session warning text (e.g. TAT misconfiguration). Sessions without
     * a warning are absent from the returned map. Exposed to the build-level
     * charts page so it can render an inline disclaimer next to each session
     * name.
     */
    public Map<String, String> getSessionWarnings() {
        if (run == null) return Collections.emptyMap();
        RegressionOptimizationBuildAction stored =
                run.getAction(RegressionOptimizationBuildAction.class);
        if (stored == null) return Collections.emptyMap();
        return stored.getSessionWarnings();
    }

    // ── Regression Optimization Chart (chart #1) ─────────────────────────

    /**
     * Result returned to the front-end Stapler proxy. Two views (start-time
     * and end-time), each split into Small/Medium/Large groups of
     * {@code [xMinutes, durationMinutes]} points.
     */
    public static final class RegressionOptimizationData {
        private final List<double[]> small;
        private final List<double[]> medium;
        private final List<double[]> large;
        private final List<double[]> smallEnd;
        private final List<double[]> mediumEnd;
        private final List<double[]> largeEnd;
        private final String error;

        RegressionOptimizationData(List<double[]> small,    List<double[]> medium,    List<double[]> large,
                                   List<double[]> smallEnd, List<double[]> mediumEnd, List<double[]> largeEnd,
                                   String error) {
            this.small     = small     == null ? Collections.<double[]>emptyList() : small;
            this.medium    = medium    == null ? Collections.<double[]>emptyList() : medium;
            this.large     = large     == null ? Collections.<double[]>emptyList() : large;
            this.smallEnd  = smallEnd  == null ? Collections.<double[]>emptyList() : smallEnd;
            this.mediumEnd = mediumEnd == null ? Collections.<double[]>emptyList() : mediumEnd;
            this.largeEnd  = largeEnd  == null ? Collections.<double[]>emptyList() : largeEnd;
            this.error     = error;
        }

        public List<double[]> getSmall()     { return small;     }
        public List<double[]> getMedium()    { return medium;    }
        public List<double[]> getLarge()     { return large;     }
        public List<double[]> getSmallEnd()  { return smallEnd;  }
        public List<double[]> getMediumEnd() { return mediumEnd; }
        public List<double[]> getLargeEnd()  { return largeEnd;  }
        public String getError()             { return error;     }
    }

    /**
     * Stapler/JS-callable: returns the Runs Duration chart data that was
     * fetched and stored on this build at build-completion time.
     */
    @JavaScriptMethod
    public RegressionOptimizationData getRegressionOptimizationData() {
        if (run == null) {
            return new RegressionOptimizationData(null, null, null, null, null, null,
                    "No build context available.");
        }
        RegressionOptimizationBuildAction stored =
                run.getAction(RegressionOptimizationBuildAction.class);
        if (stored == null) {
            return new RegressionOptimizationData(null, null, null, null, null, null,
                    "No runs-duration data stored on this build "
                            + "(it is computed at build completion; rerun the build "
                            + "after enabling the chart, or check the build log for errors).");
        }
        return new RegressionOptimizationData(
                new ArrayList<>(stored.getSmall()),
                new ArrayList<>(stored.getMedium()),
                new ArrayList<>(stored.getLarge()),
                new ArrayList<>(stored.getSmallEnd()),
                new ArrayList<>(stored.getMediumEnd()),
                new ArrayList<>(stored.getLargeEnd()),
                null);
    }

    // ── Dashboard layout (order + per-card width) ─────────────────────────

    /** Current persisted build-page layout, opaque JSON; {@code "{}"} if none. */
    public String getLayoutJson() {
        if (run == null) return "{}";
        return VManagerChartsLayoutStore.loadBuildLayout(run.getParent());
    }

    /** Whether the current user may persist a new layout for this page. */
    public boolean isCanConfigure() {
        return run != null && run.getParent().hasPermission(Item.CONFIGURE);
    }

    @RequirePOST
    public void doSaveLayout(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        if (run == null) {
            rsp.sendError(404, "no build context");
            return;
        }
        run.getParent().checkPermission(Item.CONFIGURE);
        String body = readBody(req, VManagerChartsLayoutStore.MAX_LAYOUT_BYTES);
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
        VManagerChartsLayoutStore.saveBuildLayout(run.getParent(), body);
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
