package org.jenkinsci.plugins.vmanager.charts.model;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.RelativePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerChartsUtil;
import org.jenkinsci.plugins.vmanager.charts.util.VManagerHttpClient;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Defines one custom chart shown on the vManager Charts page.
 * Each chart has a title (rendered above it) and one or more
 * {@link MetricDefinition} entries that become series on that chart.
 */
public class ChartDefinition extends AbstractDescribableImpl<ChartDefinition> {

    private final String title;
    private final List<MetricDefinition> metrics;
    /** Empty string = not set; "DB" = from server; "FILE" = local file path */
    private final String vPlanType;
    /** vplan_name when type=DB, full file path when type=FILE */
    private final String vPlanPath;
    /** Number of most-recent builds to scan for this chart; 0 = no limit. */
    private int maxBuilds = 50;

    @DataBoundConstructor
    public ChartDefinition(String title, List<MetricDefinition> metrics,
                           String vPlanType, String vPlanPath) {
        this.title     = title;
        this.metrics   = metrics != null ? metrics : new ArrayList<>();
        this.vPlanType = vPlanType == null ? "" : vPlanType;
        this.vPlanPath = vPlanPath == null ? "" : vPlanPath;
    }

    public String getTitle() {
        return title;
    }

    public List<MetricDefinition> getMetrics() {
        return metrics == null ? Collections.emptyList() : metrics;
    }

    public String getVPlanType() {
        return vPlanType;
    }

    public String getVPlanPath() {
        return vPlanPath;
    }

    public int getMaxBuilds() {
        return maxBuilds;
    }

    @org.kohsuke.stapler.DataBoundSetter
    public void setMaxBuilds(int maxBuilds) {
        this.maxBuilds = Math.max(0, maxBuilds);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ChartDefinition> {

        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        @NonNull
        @Override
        public String getDisplayName() {
            return "Custom Chart";
        }

        @Override
        public ChartDefinition newInstance(org.kohsuke.stapler.StaplerRequest2 req,
                                           @NonNull net.sf.json.JSONObject formData)
                throws FormException {
            // Forbid duplicate nicknames within the same chart. Nicknames are
            // the per-chart unique series identifier when set, so allowing
            // duplicates would collapse two metrics' values into one bucket.
            Object metricsObj = formData.opt("metrics");
            java.util.List<net.sf.json.JSONObject> metricRows = new java.util.ArrayList<>();
            if (metricsObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) metricsObj;
                for (int i = 0; i < arr.size(); i++) {
                    Object e = arr.get(i);
                    if (e instanceof JSONObject) metricRows.add((JSONObject) e);
                }
            } else if (metricsObj instanceof JSONObject) {
                metricRows.add((JSONObject) metricsObj);
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JSONObject m : metricRows) {
                String nick = m.optString("nickname", "").trim();
                if (nick.isEmpty()) continue;
                String key = nick.toLowerCase(java.util.Locale.ROOT);
                if (!seen.add(key)) {
                    throw new FormException(
                            "Nickname '" + nick + "' is used more than once in this chart. "
                            + "Each nickname must be unique within a chart.",
                            "nickname");
                }
            }
            // When the same attribute appears more than once in a chart, every
            // occurrence MUST carry a nickname so the values can be told apart
            // on the chart and in the build-action map.
            java.util.Map<String, java.util.List<JSONObject>> byAttr = new java.util.LinkedHashMap<>();
            for (JSONObject m : metricRows) {
                String attr = m.optString("attributeName", "").trim();
                if (attr.isEmpty()) continue;
                byAttr.computeIfAbsent(attr.toLowerCase(java.util.Locale.ROOT),
                        k -> new java.util.ArrayList<>()).add(m);
            }
            for (java.util.Map.Entry<String, java.util.List<JSONObject>> e : byAttr.entrySet()) {
                if (e.getValue().size() < 2) continue;
                for (JSONObject m : e.getValue()) {
                    if (m.optString("nickname", "").trim().isEmpty()) {
                        throw new FormException(
                                "Attribute '" + m.optString("attributeName")
                                + "' is used more than once in this chart. "
                                + "Every duplicate occurrence must have a unique nickname.",
                                "nickname");
                    }
                }
            }
            return super.newInstance(req, formData);
        }

        @POST
        public FormValidation doCheckTitle(@AncestorInPath Item item,
                                           @QueryParameter String value) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Chart title is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckMaxBuilds(@AncestorInPath Item item,
                                               @QueryParameter String value) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            if (value == null || value.isBlank()) {
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
         * Validates the vPlan field. When vPlan Type is {@code DB} this also pings
         * the vManager REST endpoint so any server-side error (auth, 404, connection
         * refused, TLS, etc.) is surfaced inline under the field in red. Triggered
         * via {@code checkDependsOn} in the Jelly when {@code vPlanType},
         * {@code serverUrl} or {@code credentialsId} change.
         */
        @POST
        public FormValidation doCheckVPlanPath(
                @QueryParameter String value,
                @QueryParameter String vPlanType,
                @RelativePath("..") @QueryParameter String serverUrl,
                @RelativePath("..") @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            if (!"DB".equals(vPlanType)) {
                return FormValidation.ok();
            }
            if (serverUrl == null || serverUrl.isBlank()) {
                return FormValidation.ok();
            }
            String base = serverUrl.endsWith("/")
                    ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
            String url = base + "/rest/vplan/list-vplans";
            try {
                StandardUsernamePasswordCredentials creds =
                        lookupCredentials(item, credentialsId, serverUrl);
                postJson(url, "{}", creds);
            } catch (Exception e) {
                String m = e.getMessage();
                return FormValidation.error("vManager server error: "
                        + (m == null || m.isBlank() ? e.getClass().getSimpleName() : m));
            }
            return FormValidation.ok();
        }

        @POST
        public ListBoxModel doFillVPlanTypeItems(@AncestorInPath Item item) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            ListBoxModel m = new ListBoxModel();
            m.add("-- None --",         "");
            m.add("DB (from server)",    "DB");
            m.add("File (local path)",   "FILE");
            return m;
        }

        /**
         * For DB type: POST to {@code {serverUrl}/rest/vplan/list-vplans} and return
         * the {@code vplan_name} of each entry.
         * For FILE type or when no server details are available: return empty list
         * so the user can type a path freely.
         */
        @POST
        public ComboBoxModel doFillVPlanPathItems(
                @QueryParameter String vPlanType,
                @RelativePath("..")
                @QueryParameter String serverUrl,
                @RelativePath("..")
                @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            ComboBoxModel m = new ComboBoxModel();

            if (!"DB".equals(vPlanType)
                    || serverUrl == null || serverUrl.isBlank()) {
                return m;
            }

            String base = serverUrl.endsWith("/")
                    ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
            String url = base + "/rest/vplan/list-vplans";

            try {
                StandardUsernamePasswordCredentials creds =
                        lookupCredentials(item, credentialsId, serverUrl);
                LOGGER.log(Level.FINE, "Fetching vPlan list: POST {0} (creds={1})",
                        new Object[]{url, creds == null ? "<none>" : creds.getId()});
                String body = postJson(url, "{}", creds);
                parseVPlanNames(body, m);
                LOGGER.log(Level.FINE, "vPlan list: fetched {0} plan(s)",
                        new Object[]{m.size()});
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Could not fetch vPlan list from vManager: " + url, e);
            }
            return m;
        }

        // ── Credentials lookup ────────────────────────────────────────────────

        private StandardUsernamePasswordCredentials lookupCredentials(
                Item item, String credentialsId, String serverUrl) {
            if (credentialsId == null || credentialsId.isBlank() || item == null) {
                return null;
            }
            List<StandardUsernamePasswordCredentials> candidates =
                    CredentialsProvider.lookupCredentialsInItem(
                            StandardUsernamePasswordCredentials.class,
                            item, ACL.SYSTEM2,
                            URIRequirementBuilder.fromUri(serverUrl).build());
            return CredentialsMatchers.firstOrNull(candidates,
                    CredentialsMatchers.withId(credentialsId));
        }

        // ── HTTP POST ─────────────────────────────────────────────────────────

        private String postJson(String urlStr, String jsonBody,
                                StandardUsernamePasswordCredentials creds)
                throws Exception {
            return VManagerHttpClient.postJson(urlStr, jsonBody, creds);
        }

        // ── JSON parsing ──────────────────────────────────────────────────────

        private void parseVPlanNames(String body, ComboBoxModel m) {
            if (body == null || body.isBlank()) return;
            try {
                String trimmed = body.trim();
                if (!trimmed.startsWith("[")) return;
                JSONArray arr = JSONArray.fromObject(trimmed);
                List<String> names = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    Object elem = arr.get(i);
                    if (!(elem instanceof JSONObject)) continue;
                    Object nameVal = ((JSONObject) elem).get("vplan_name");
                    if (nameVal == null) continue;
                    String name = nameVal.toString();
                    if (!"null".equals(name) && !name.isBlank()) {
                        names.add(name);
                    }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                for (String name : names) {
                    m.add(name);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to parse vPlan list JSON", e);
            }
        }
    }
}
