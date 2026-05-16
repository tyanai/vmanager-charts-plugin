package org.jenkinsci.plugins.vmanager.charts;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.vmanager.charts.model.ChartDefinition;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.jenkinsci.plugins.vmanager.charts.util.JsonConfigLoader;
import net.sf.json.JSONObject;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Job property that enables vManager Charts for a job and configures
 * which charts are displayed. Rendered in the job's General configuration section.
 */
public class VManagerChartsJobProperty extends JobProperty<Job<?, ?>> {

    private boolean enabled = true;
    private boolean showBuildDuration = false;
    private boolean showSuccessRate = false;
    private boolean showTestResults = false;
    private boolean showCustomMetrics = false;
    private boolean showBuildLevelCharts = false;
    private boolean showRegressionOptimizationChart = false;
    private String serverUrl;
    private String credentialsId;
    private String vManagerSchema = "latest";
    /** Number of most-recent builds to include in built-in charts; 0 = no limit. */
    private int maxBuilds = 50;
    /**
     * How the vManager session name(s) for this build are obtained.
     * "PLUGIN" = leverage info recorded by the vManager Jenkins Plugin.
     * "FILE"   = read session names from a text file (one per line).
     */
    private String sessionSource = "PLUGIN";
    /**
     * When {@link #sessionSource} = "FILE", absolute or workspace-relative path
     * to the file containing session names (one per line). When blank, the
     * plugin will look in the build's workspace for
     * {@code ${BUILD_NUMBER}.${BUILD_ID}.sessions.input}.
     */
    private String sessionInputFile = "";
    private List<ChartDefinition> customCharts = new ArrayList<>();

    /**
     * Where the chart configuration is sourced from at build time.
     * "GUI"  = use the values stored on this property (the default).
     * "FILE" = at build completion, load a JSON file from the build's
     *          workspace and apply its values, ignoring everything below
     *          this on the property except for {@link #credentialsId},
     *          which always comes from the GUI.
     */
    private String configSource = "GUI";
    /**
     * When {@link #configSource} = "FILE", absolute or workspace-relative
     * path to the JSON file. When blank, the listener looks for
     * {@code vmanager-charts.config.json} in the build's workspace.
     */
    private String configFilePath = "";

    /**
     * When {@code true}, the listener emits chatty {@code [vManager Charts]}
     * trace lines (REST URLs, headers, payloads, per-session listings, etc.)
     * to the build's console. When {@code false} (default) only WARNING / error
     * lines are printed. Note: this only controls build-console output; the
     * separate {@code java.util.logging} traces (in MetricDefinition,
     * ChartDefinition, VManagerRunsClient) are at FINE level — enable them
     * via Manage Jenkins → System Log.
     */
    private boolean verboseLogging = false;

    @DataBoundConstructor
    public VManagerChartsJobProperty() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowBuildDuration() {
        return showBuildDuration;
    }

    @DataBoundSetter
    public void setShowBuildDuration(boolean showBuildDuration) {
        this.showBuildDuration = showBuildDuration;
    }

    public boolean isShowSuccessRate() {
        return showSuccessRate;
    }

    @DataBoundSetter
    public void setShowSuccessRate(boolean showSuccessRate) {
        this.showSuccessRate = showSuccessRate;
    }

    public boolean isShowTestResults() {
        return showTestResults;
    }

    @DataBoundSetter
    public void setShowTestResults(boolean showTestResults) {
        this.showTestResults = showTestResults;
    }

    public boolean isShowCustomMetrics() {
        return showCustomMetrics;
    }

    @DataBoundSetter
    public void setShowCustomMetrics(boolean showCustomMetrics) {
        this.showCustomMetrics = showCustomMetrics;
    }

    public boolean isShowBuildLevelCharts() {
        return showBuildLevelCharts;
    }

    @DataBoundSetter
    public void setShowBuildLevelCharts(boolean showBuildLevelCharts) {
        this.showBuildLevelCharts = showBuildLevelCharts;
    }

    public boolean isShowRegressionOptimizationChart() {
        return showRegressionOptimizationChart;
    }

    @DataBoundSetter
    public void setShowRegressionOptimizationChart(boolean showRegressionOptimizationChart) {
        this.showRegressionOptimizationChart = showRegressionOptimizationChart;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public String getVManagerSchema() {
        return vManagerSchema == null || vManagerSchema.isBlank() ? "latest" : vManagerSchema;
    }

    @DataBoundSetter
    public void setVManagerSchema(String vManagerSchema) {
        this.vManagerSchema = vManagerSchema;
    }

    public List<ChartDefinition> getCustomCharts() {
        if (!showCustomMetrics) {
            return Collections.emptyList();
        }
        return customCharts == null ? Collections.emptyList() : customCharts;
    }

    @DataBoundSetter
    public void setCustomCharts(List<ChartDefinition> customCharts) {
        this.customCharts = customCharts != null ? customCharts : new ArrayList<>();
    }

    /** Number of most-recent builds to include in built-in charts; 0 = unlimited. */
    public int getMaxBuilds() {
        return maxBuilds;
    }

    @DataBoundSetter
    public void setMaxBuilds(int maxBuilds) {
        this.maxBuilds = Math.max(0, maxBuilds);
    }

    public String getSessionSource() {
        return sessionSource == null || sessionSource.isBlank() ? "PLUGIN" : sessionSource;
    }

    @DataBoundSetter
    public void setSessionSource(String sessionSource) {
        this.sessionSource = sessionSource == null || sessionSource.isBlank()
                ? "PLUGIN" : sessionSource;
    }

    public String getSessionInputFile() {
        return sessionInputFile == null ? "" : sessionInputFile;
    }

    @DataBoundSetter
    public void setSessionInputFile(String sessionInputFile) {
        this.sessionInputFile = sessionInputFile == null ? "" : sessionInputFile.trim();
    }

    public String getConfigSource() {
        return configSource == null || configSource.isBlank() ? "GUI" : configSource;
    }

    @DataBoundSetter
    public void setConfigSource(String configSource) {
        this.configSource = configSource == null || configSource.isBlank()
                ? "GUI" : configSource;
    }

    public String getConfigFilePath() {
        return configFilePath == null ? "" : configFilePath;
    }

    @DataBoundSetter
    public void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath == null ? "" : configFilePath.trim();
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    @DataBoundSetter
    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Nonnull
        @Override
        public String getDisplayName() {
            return "vManager Charts";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item,
                                                     @QueryParameter String credentialsId,
                                                     @QueryParameter String serverUrl) {
            StandardListBoxModel result = new StandardListBoxModel();
            if (item == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result.includeCurrentValue(credentialsId);
                }
            } else {
                if (!item.hasPermission(Item.EXTENDED_READ)
                        && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result.includeCurrentValue(credentialsId);
                }
            }
            return result
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.fromUri(serverUrl).build(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        public FormValidation doCheckServerUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("vManager Server URL is required.");
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                return FormValidation.error("URL must start with http:// or https://");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckCredentialsId(@AncestorInPath Item item,
                                                    @QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Credentials are required.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillSessionSourceItems() {
            ListBoxModel m = new ListBoxModel();
            m.add("Leverage vManager Jenkins Plugin Information", "PLUGIN");
            m.add("Input file name",                              "FILE");
            return m;
        }

        public FormValidation doCheckMaxBuilds(@QueryParameter String value) {            if (value == null || value.isBlank()) {
                return FormValidation.error("Maximum builds is required (use 0 for unlimited).");
            }
            int n;
            try {
                n = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return FormValidation.error("Must be a whole number (0 for unlimited).");
            }
            if (n < 0) {
                return FormValidation.error("Must be 0 or greater (0 = unlimited).");
            }
            if (n == 0) {
                return FormValidation.warning(
                        "0 means no limit — all builds in the job history will be scanned. "
                        + "This may be slow on jobs with very long history.");
            }
            return FormValidation.ok();
        }

        /**
         * Exports the currently-edited (unsaved) plugin configuration as a
         * downloadable JSON file. The browser POSTs the full job-config form
         * tree (built via Jenkins' {@code buildFormTree}); we walk it to find
         * the subtree that holds this plugin's fields, bind it to a transient
         * {@link VManagerChartsJobProperty}, and serialize via
         * {@link JsonConfigLoader#toJson(VManagerChartsJobProperty)}.
         * The {@code credentialsId} field is never included in the output.
         */
        @RequirePOST
        public void doExportConfig(StaplerRequest req, StaplerResponse rsp) throws Exception {
            JSONObject form = req.getSubmittedForm();
            JSONObject ours = findPluginSubtree(form);
            if (ours == null) {
                rsp.sendError(StaplerResponse.SC_BAD_REQUEST,
                        "Could not locate vManager Charts configuration in the submitted form.");
                return;
            }
            VManagerChartsJobProperty bound;
            try {
                bound = req.bindJSON(VManagerChartsJobProperty.class, ours);
            } catch (Exception bindEx) {
                rsp.sendError(StaplerResponse.SC_BAD_REQUEST,
                        "Failed to parse configuration: " + bindEx.getMessage());
                return;
            }
            String body = JsonConfigLoader.toJson(bound);
            rsp.setContentType("application/json;charset=UTF-8");
            rsp.setHeader("Content-Disposition",
                    "attachment; filename=\"vmanager-charts.config.json\"");
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            rsp.setContentLength(bytes.length);
            rsp.getOutputStream().write(bytes);
        }

        /**
         * Recursively searches a submitted form JSON tree for the subtree
         * that contains this plugin's configuration. The marker is the
         * presence of {@code serverUrl} together with at least one of the
         * other distinctive fields produced by our {@code config.jelly}.
         * Inside an {@code <f:optionalBlock name="enabled">} the contents
         * appear nested under the {@code "enabled"} key; this walk handles
         * any level of nesting that Jenkins may add around the property.
         */
        private static JSONObject findPluginSubtree(Object node) {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                if (obj.has("serverUrl") &&
                        (obj.has("vManagerSchema") || obj.has("sessionSource")
                                || obj.has("showBuildDuration") || obj.has("showCustomMetrics"))) {
                    return obj;
                }
                for (Object key : obj.keySet()) {
                    JSONObject hit = findPluginSubtree(obj.get((String) key));
                    if (hit != null) return hit;
                }
            } else if (node instanceof net.sf.json.JSONArray) {
                net.sf.json.JSONArray arr = (net.sf.json.JSONArray) node;
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject hit = findPluginSubtree(arr.get(i));
                    if (hit != null) return hit;
                }
            }
            return null;
        }
    }
}