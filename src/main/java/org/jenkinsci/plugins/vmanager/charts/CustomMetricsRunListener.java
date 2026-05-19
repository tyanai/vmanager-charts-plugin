package org.jenkinsci.plugins.vmanager.charts;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import org.jenkinsci.plugins.vmanager.charts.model.ChartDefinition;
import org.jenkinsci.plugins.vmanager.charts.util.BuildLog;
import org.jenkinsci.plugins.vmanager.charts.util.CoverageRoutingContext;
import org.jenkinsci.plugins.vmanager.charts.util.JsonConfigLoader;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerChartsUtil;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerRunsClient;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerSessionsClient;
import org.jenkinsci.plugins.vmanager.charts.util.VplanRoutingContext;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires after every build completes. For each configured Custom Chart, fetches
 * each metric's value from the vManager REST API and stores the values
 * (keyed by "chartTitle::attributeName") on a {@link CustomMetricsBuildAction}.
 */
@Extension
public class CustomMetricsRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(CustomMetricsRunListener.class.getName());

    public static String key(String chartTitle, String attributeName) {
        return (chartTitle == null ? "" : chartTitle) + "::" + (attributeName == null ? "" : attributeName);
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Job<?, ?> job = run.getParent();
        VManagerChartsJobProperty property =
                (VManagerChartsJobProperty) job.getProperty(VManagerChartsJobProperty.class);

        if (property == null || !property.isEnabled()) {
            return;
        }

        // Gate all chatty [vManager Charts] build-console lines behind the
        // job's "Verbose logging" flag for the duration of this build.
        BuildLog.setVerbose(property.isVerboseLogging());
        try {
            doOnCompleted(run, job, property, listener);
        } finally {
            BuildLog.clear();
        }
    }

    private void doOnCompleted(Run<?, ?> run, Job<?, ?> job,
                               VManagerChartsJobProperty property,
                               TaskListener listener) {

        // ── Optional: replace the property with values loaded from a JSON
        //    file in the workspace. credentialsId is always taken from the
        //    GUI-saved property and re-applied to the loaded one. ──
        if ("FILE".equalsIgnoreCase(property.getConfigSource())) {
            VManagerChartsJobProperty loaded = loadConfigFromWorkspace(run, property, listener);
            if (loaded == null) {
                // Reason already logged by the loader; abort the post-build work.
                return;
            }
            // Server URL, credentials and vManager Session always come from
            // the GUI — overlay them on top of the JSON-loaded chart config.
            loaded.setCredentialsId(property.getCredentialsId());
            loaded.setServerUrl(property.getServerUrl());
            loaded.setSessionSource(property.getSessionSource());
            loaded.setSessionInputFile(property.getSessionInputFile());
            // Also overlay the verbose-logging flag from the GUI — the JSON
            // file is intentionally about chart definitions, not log levels.
            loaded.setVerboseLogging(property.isVerboseLogging());
            property = loaded;
            if (!property.isEnabled()) {
                listener.getLogger().println(
                        "[vManager Charts] JSON config has enabled=false. Skipping.");
                return;
            }
        } else {
            BuildLog.trace(listener,
                    "[vManager Charts] Configuration source: GUI (job configuration page).");
        }

        String serverUrl = property.getServerUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            listener.getLogger().println(
                    "[vManager Charts] WARNING: vManager Server URL not configured for this job. Skipping.");
            return;
        }

        StandardUsernamePasswordCredentials creds = lookupCredentials(job, property.getCredentialsId(), serverUrl);
        if (creds == null) {
            listener.getLogger().println(
                    "[vManager Charts] WARNING: credentials not found (id='"
                            + property.getCredentialsId() + "'). Skipping.");
            return;
        }

        // Resolve which vManager session(s) this build is associated with.
        FilePath workspace = VManagerChartsUtil.getCurrentWorkspace(run);
        FilePath sessionsFile = VManagerChartsUtil.resolveSessionsInputFile(
                run, workspace, property.getSessionSource(), property.getSessionInputFile());
        List<String> sessions;
        boolean inputFileExists = false;
        try {
            inputFileExists = sessionsFile != null && sessionsFile.exists();
            sessions = inputFileExists
                    ? VManagerChartsUtil.readSessionNames(sessionsFile)
                    : java.util.Collections.<String>emptyList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read sessions input file "
                    + (sessionsFile == null ? "<null>" : sessionsFile.getRemote()), e);
            listener.getLogger().println(
                    "[vManager Charts] WARNING: could not read sessions input file "
                    + (sessionsFile == null ? "<null>" : sessionsFile.getRemote())
                    + ": " + e.getMessage());
            sessions = java.util.Collections.emptyList();
        }
        if (sessionsFile != null && inputFileExists) {
            BuildLog.trace(listener, "[vManager Charts] sessions input file: "
                    + sessionsFile.getRemote()
                    + " (" + sessions.size() + " session" + (sessions.size() == 1 ? "" : "s") + ")");
        }

        // Fallback: if the .sessions.input file is missing (or empty), look
        // for a sibling .session_launch.output file dropped by the vManager
        // Jenkins Plugin in launch mode. It contains session ids, which we
        // translate to session names via /rest/sessions/list before
        // continuing with the normal flow.
        FilePath launchOutput = null;
        boolean launchOutputExists = false;
        if (sessions.isEmpty()) {
            launchOutput = VManagerChartsUtil.resolveSessionLaunchOutputFile(
                    run, sessionsFile, workspace);
            try {
                launchOutputExists = launchOutput != null && launchOutput.exists();
                if (launchOutputExists) {
                    List<String> ids = VManagerChartsUtil.readSessionNames(launchOutput);
                    BuildLog.trace(listener,
                            "[vManager Charts] sessions input file missing; using launch output: "
                                    + launchOutput.getRemote()
                                    + " (" + ids.size() + " id" + (ids.size() == 1 ? "" : "s") + ")");
                    LOGGER.log(Level.FINE,
                            "sessions fallback: reading ids from {0} ({1} id(s))",
                            new Object[]{ launchOutput.getRemote(), ids.size() });
                    if (!ids.isEmpty()) {
                        sessions = VManagerSessionsClient.fetchSessionNamesByIds(
                                serverUrl, ids, creds, listener);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to resolve sessions from launch-output file "
                        + (launchOutput == null ? "<null>" : launchOutput.getRemote()), e);
                listener.getLogger().println(
                        "[vManager Charts] WARNING: could not resolve sessions from launch-output file "
                                + (launchOutput == null ? "<null>" : launchOutput.getRemote())
                                + ": " + e.getMessage());
            }
        }

        // Always-shown diagnostic when neither source produced any session
        // names. Without this, downstream charts silently record zeros and
        // there's nothing in the build console to explain why.
        if (sessions.isEmpty()) {
            String inputPath  = sessionsFile  == null ? "<no workspace>" : sessionsFile.getRemote();
            String launchPath = launchOutput  == null ? "<no workspace>" : launchOutput.getRemote();
            listener.getLogger().println(
                    "[vManager Charts] WARNING: no sessions resolved for this build. "
                            + "Tried sessions input file: " + inputPath
                            + (inputFileExists ? " (present, empty)" : " (not found)")
                            + "; tried launch-output file: " + launchPath
                            + (launchOutputExists ? " (present, empty/unresolved)" : " (not found)")
                            + ". Custom-metric values will be recorded as 0 for this build.");
            LOGGER.log(Level.WARNING,
                    "No sessions resolved (input={0} present={1}, launch={2} present={3})",
                    new Object[]{ inputPath, inputFileExists, launchPath, launchOutputExists });
        }

        for (String s : sessions) {
            BuildLog.trace(listener, "[vManager Charts]   session: " + s);
        }

        // ── Per-build session run-state aggregates (used by Success/Failure chart) ──
        boolean savedAction = false;
        if (property.isShowSuccessRate() && !sessions.isEmpty()) {
            try {
                VManagerSessionsClient.SessionAggregates agg =
                        VManagerSessionsClient.fetchAggregated(serverUrl, sessions, creds, listener);
                run.addAction(new SessionStatsBuildAction(
                        agg.passedRuns, agg.failedRuns, agg.running,
                        agg.waiting, agg.otherRuns, sessions.size()));
                savedAction = true;
                BuildLog.trace(listener, String.format(
                        "[vManager Charts] session run state: passed=%d failed=%d running=%d waiting=%d other=%d (rows=%d)",
                        agg.passedRuns, agg.failedRuns, agg.running, agg.waiting,
                        agg.otherRuns, agg.rowCount));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch session aggregates from vManager", e);
                listener.getLogger().println(
                        "[vManager Charts] WARNING: could not fetch session aggregates: " + e.getMessage());
            }
        }

        // ── Per-build Regression Optimization Chart data ─────────────────────
        if (property.isShowBuildLevelCharts()
                && property.isShowRegressionOptimizationChart()
                && !sessions.isEmpty()) {
            try {
                long sessionStartMs = VManagerRunsClient.fetchSessionStartMillis(
                        serverUrl, sessions, creds, listener);
                List<VManagerRunsClient.RunPoint> points =
                        VManagerRunsClient.fetchRunPoints(
                                serverUrl, sessions, sessionStartMs, creds, listener);
                int n = points.size();
                List<double[]> small     = new java.util.ArrayList<>();
                List<double[]> medium    = new java.util.ArrayList<>();
                List<double[]> large     = new java.util.ArrayList<>();
                List<double[]> smallEnd  = new java.util.ArrayList<>();
                List<double[]> mediumEnd = new java.util.ArrayList<>();
                List<double[]> largeEnd  = new java.util.ArrayList<>();
                if (n > 0) {
                    int third       = n / 3;
                    int smallBound  = third;
                    int mediumBound = third + third;
                    for (int i = 0; i < n; i++) {
                        VManagerRunsClient.RunPoint pt = points.get(i);
                        // Third element carries the vManager "estimated_duration_vmgr"
                        // (already converted to minutes); it is not plotted on the axes,
                        // only shown in the tooltip.
                        double[] xyStart = new double[]{ pt.timeToStartMinutes, pt.durationMinutes, pt.estimatedDurationMinutes };
                        double[] xyEnd   = new double[]{ pt.timeToEndMinutes,   pt.durationMinutes, pt.estimatedDurationMinutes };
                        if (i < smallBound) {
                            small.add(xyStart);
                            smallEnd.add(xyEnd);
                        } else if (i < mediumBound) {
                            medium.add(xyStart);
                            mediumEnd.add(xyEnd);
                        } else {
                            large.add(xyStart);
                            largeEnd.add(xyEnd);
                        }
                    }
                }
                run.addAction(new RegressionOptimizationBuildAction(
                        small, medium, large, smallEnd, mediumEnd, largeEnd));
                savedAction = true;
                BuildLog.trace(listener, String.format(
                        "[vManager Charts] runs duration: rows=%d small=%d medium=%d large=%d",
                        n, small.size(), medium.size(), large.size()));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch regression-optimization data from vManager", e);
                listener.getLogger().println(
                        "[vManager Charts] WARNING: could not fetch regression-optimization data: " + e.getMessage());
            }
        }

        // ── Custom-metric collection (delegated to CustomMetricsCollector) ────
        List<ChartDefinition> charts = property.isShowCustomMetrics()
                ? property.getCustomCharts() : java.util.Collections.<ChartDefinition>emptyList();

        Map<String, Double> collected = new LinkedHashMap<>();
        // Per-build routing-OID holder shared across every COVERAGE_LEVEL call
        // for every chart in this build. Reset implicitly each build because
        // a fresh context is created here.
        CoverageRoutingContext routingCtx = new CoverageRoutingContext();
        // Per-build routing-OID holder for VPLAN_LEVEL calls, keyed by
        // (vplan, type). Same (vplan, type) may carry its OID across charts
        // within this build; a different (vplan, type) starts a fresh chain.
        VplanRoutingContext vplanRoutingCtx = new VplanRoutingContext();
        for (ChartDefinition chart : charts) {
            collected.putAll(
                    org.jenkinsci.plugins.vmanager.charts.util.CustomMetricsCollector.collect(
                            serverUrl, sessions, chart, creds, routingCtx, vplanRoutingCtx, listener));
        }

        if (!collected.isEmpty()) {
            run.addAction(new CustomMetricsBuildAction(collected));
            savedAction = true;
        }

        if (savedAction) {
            try {
                run.save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to persist build actions for " + run, e);
            }
        }
    }

    private StandardUsernamePasswordCredentials lookupCredentials(Job<?, ?> job, String credentialsId, String serverUrl) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        List<StandardUsernamePasswordCredentials> candidates = CredentialsProvider.lookupCredentialsInItem(
                StandardUsernamePasswordCredentials.class,
                job,
                ACL.SYSTEM2,
                URIRequirementBuilder.fromUri(serverUrl).build());
        return CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.withId(credentialsId));
    }

    /**
     * Resolves and reads the JSON config file from the build's workspace,
     * returning a fully-populated property. Returns {@code null} (and logs
     * the reason to the build log) if the file is missing or unparseable.
     */
    private VManagerChartsJobProperty loadConfigFromWorkspace(
            Run<?, ?> run, VManagerChartsJobProperty guiProperty, TaskListener listener) {

        FilePath workspace = VManagerChartsUtil.getCurrentWorkspace(run);
        if (workspace == null) {
            listener.getLogger().println(
                    "[vManager Charts] WARNING: configSource=FILE but no workspace is available for this build. Skipping.");
            return null;
        }

        String requested = guiProperty.getConfigFilePath();
        FilePath cfg;
        if (requested == null || requested.isBlank()) {
            cfg = workspace.child("vmanager-charts-config.json");
        } else {
            // Allow either workspace-relative or absolute paths.
            FilePath asAbs = new FilePath(workspace.getChannel(), requested);
            FilePath asRel = workspace.child(requested);
            try {
                cfg = asAbs.exists() ? asAbs : asRel;
            } catch (IOException | InterruptedException e) {
                cfg = asRel;
            }
        }

        try {
            if (!cfg.exists()) {
                listener.getLogger().println(
                        "[vManager Charts] WARNING: configSource=FILE but JSON config file not found: "
                                + cfg.getRemote() + ". Skipping.");
                return null;
            }
            String json = cfg.readToString();
            BuildLog.trace(listener,
                    "[vManager Charts] Configuration source: JSON file from workspace ("
                            + cfg.getRemote() + "). Credentials still taken from GUI.");
            return JsonConfigLoader.load(json);
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load JSON config from workspace", e);
            listener.getLogger().println(
                    "[vManager Charts] WARNING: failed to load JSON config "
                            + cfg.getRemote() + ": " + e.getMessage() + ". Skipping.");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
