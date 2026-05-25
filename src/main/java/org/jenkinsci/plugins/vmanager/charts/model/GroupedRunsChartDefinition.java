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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One instance of the "Custom Grouped Run's Chart (Heatmap)" feature.
 * Each instance corresponds to a single heat-map shown on the job's
 * vManager Charts page. The heat-map groups the runs of the build's
 * resolved vManager sessions by the chosen RUN_LEVEL attribute and
 * counts the number of entities per group.
 *
 * <p>{@code groupByAttribute} stores the value in the format
 * {@code "Title (id)"} (the displayed combobox value). Use
 * {@link #getGroupByAttributeId()} when making REST calls and
 * {@link #getGroupByAttributeTitle()} for display.</p>
 */
public class GroupedRunsChartDefinition extends AbstractDescribableImpl<GroupedRunsChartDefinition> {

    private final String title;
    private final String subtitle;
    /** Stored as {@code "Title (id)"} when populated from the server dropdown. */
    private final String groupByAttribute;
    /** Maximum number of distinct group values shown on the Y-axis. */
    private int yAxisLimit = 30;
    /** Maximum number of most-recent builds to scan for this chart. */
    private int maxBuilds = 30;
    /**
     * Comma-separated list of run statuses to include in the chart. When
     * empty, all statuses are included (no status filter applied to the
     * REST payload). When non-empty, only runs whose {@code status} is
     * in this list are counted.
     *
     * <p>Stored as a single CSV string so a textbox-driven UI can manage
     * it; the Java side exposes {@link #getStatusFilterList()} for the
     * REST client. The descriptor exposes {@link DescriptorImpl#ALL_STATUSES}
     * for the multi-select widget.</p>
     */
    private String statusFilters = "";

    @DataBoundConstructor
    public GroupedRunsChartDefinition(String title, String subtitle, String groupByAttribute) {
        this.title            = title == null ? "" : title;
        this.subtitle         = subtitle == null ? "" : subtitle;
        this.groupByAttribute = groupByAttribute == null ? "" : groupByAttribute;
    }

    public String getTitle()    { return title; }
    public String getSubtitle() { return subtitle; }

    public String getGroupByAttribute() {
        return groupByAttribute == null ? "" : groupByAttribute;
    }

    /** Human-friendly title parsed out of {@code "Title (id)"}. */
    public String getGroupByAttributeTitle() {
        String v = getGroupByAttribute();
        int open = v.lastIndexOf('(');
        return (open > 1) ? v.substring(0, open).trim() : v;
    }

    /** Machine-readable id parsed out of {@code "Title (id)"}. */
    public String getGroupByAttributeId() {
        String v = getGroupByAttribute();
        int open  = v.lastIndexOf('(');
        int close = v.lastIndexOf(')');
        return (open >= 0 && close > open) ? v.substring(open + 1, close).trim() : v;
    }

    public int getYAxisLimit() {
        return yAxisLimit <= 0 ? 30 : yAxisLimit;
    }

    @org.kohsuke.stapler.DataBoundSetter
    public void setYAxisLimit(int yAxisLimit) {
        this.yAxisLimit = yAxisLimit <= 0 ? 30 : yAxisLimit;
    }

    public int getMaxBuilds() {
        return maxBuilds <= 0 ? 30 : maxBuilds;
    }

    @org.kohsuke.stapler.DataBoundSetter
    public void setMaxBuilds(int maxBuilds) {
        this.maxBuilds = maxBuilds <= 0 ? 30 : maxBuilds;
    }

    /** Raw CSV value as stored (used by the jelly textbox to round-trip). */
    public String getStatusFilters() {
        return statusFilters == null ? "" : statusFilters;
    }

    @org.kohsuke.stapler.DataBoundSetter
    public void setStatusFilters(String statusFilters) {
        this.statusFilters = statusFilters == null ? "" : statusFilters.trim();
    }

    /**
     * Parsed status list. Empty list means "no status filter — include all
     * runs". Order from the CSV is preserved; blanks and duplicates are
     * dropped. Values are normalised to lower-case to match what vManager
     * stores in the {@code status} attribute.
     */
    public java.util.List<String> getStatusFilterList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = getStatusFilters();
        if (s.isEmpty()) return out;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String part : s.split(",")) {
            String v = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.isEmpty() && seen.add(v)) out.add(v);
        }
        return out;
    }

    // -------------------------------------------------------------------------

    @Extension
    public static class DescriptorImpl extends Descriptor<GroupedRunsChartDefinition> {

        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        /**
         * All vManager run statuses that may be passed to the {@code .InFilter}
         * on the {@code status} attribute. Exposed so the multi-select widget
         * in {@code config.jelly} can enumerate them.
         */
        public static final java.util.List<String> ALL_STATUSES =
                java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                        "running", "finished", "other",
                        "waiting", "stopped", "passed", "failed"));

        /** Bean-style getter so Jelly can read {@code descriptor.allStatuses}. */
        public java.util.List<String> getAllStatuses() {
            return ALL_STATUSES;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Grouped Runs Heatmap";
        }

        @POST
        public FormValidation doCheckTitle(@AncestorInPath Item item,
                                           @QueryParameter String value) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            if (value == null || value.isBlank()) {
                return FormValidation.error("Chart Title is required.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckYAxisLimit(@AncestorInPath Item item,
                                                @QueryParameter String value) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            return positiveInt(value, "Y-axis limit");
        }

        @POST
        public FormValidation doCheckMaxBuilds(@AncestorInPath Item item,
                                               @QueryParameter String value) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            return positiveInt(value, "Max builds");
        }

        private static FormValidation positiveInt(String value, String label) {
            if (value == null || value.isBlank()) {
                return FormValidation.error(label + " is required.");
            }
            try {
                int n = Integer.parseInt(value.trim());
                if (n <= 0) return FormValidation.error(label + " must be greater than 0.");
            } catch (NumberFormatException e) {
                return FormValidation.error(label + " must be a whole number.");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckGroupByAttribute(
                @QueryParameter String value,
                @RelativePath("..") @QueryParameter String serverUrl,
                @RelativePath("..") @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            if (serverUrl != null && !serverUrl.isBlank()) {
                String url = buildRunsListUrl(serverUrl);
                try {
                    StandardUsernamePasswordCredentials creds =
                            lookupCredentials(item, credentialsId, serverUrl);
                    VManagerHttpClient.getJson(url, creds);
                } catch (Exception e) {
                    return FormValidation.error("vManager server error: " + friendlyMessage(e));
                }
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Group-by attribute is required.");
            }
            return FormValidation.ok();
        }

        /**
         * Populates the combobox by hitting the same RUN_LEVEL schema endpoint
         * as the metrics descriptor uses for SESSION_LEVEL, but against the
         * {@code runs} component and WITHOUT filtering by attribute type
         * (i.e. strings, numbers, booleans, etc. are all returned).
         *
         * <p><b>Debug logging</b>: this method always logs (at INFO level) the
         * exact REST URL it calls, the credentials id used, the HTTP response
         * size and a head-snippet of the body, plus the number of attributes
         * parsed. Enable verbose Stapler logging or filter the Jenkins log on
         * {@code GroupedRunsChartDefinition} to see it. The full request
         * payload is empty (this is a GET), so only the URL is logged.
         */
        @POST
        public ComboBoxModel doFillGroupByAttributeItems(
                @RelativePath("..") @QueryParameter String serverUrl,
                @RelativePath("..") @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            ComboBoxModel m = new ComboBoxModel();
            LOGGER.log(Level.INFO,
                    "[GroupedRuns] doFillGroupByAttributeItems called. serverUrl=''{0}'' credentialsId=''{1}'' item=''{2}''",
                    new Object[]{ serverUrl, credentialsId,
                            item == null ? "<null>" : item.getFullName() });

            if (serverUrl == null || serverUrl.isBlank()) {
                LOGGER.log(Level.WARNING,
                        "[GroupedRuns] serverUrl is empty \u2014 combobox will be empty. "
                                + "Check @RelativePath in the descriptor matches the depth of the config.jelly.");
                return m;
            }

            String url = buildRunsListUrl(serverUrl);
            LOGGER.log(Level.INFO,
                    "[GroupedRuns] REST GET {0}  (payload: <none>; GET request)", url);
            try {
                StandardUsernamePasswordCredentials creds =
                        lookupCredentials(item, credentialsId, serverUrl);
                LOGGER.log(Level.INFO,
                        "[GroupedRuns] credentials resolved: {0}",
                        creds == null ? "<none>" : creds.getId());
                String body = VManagerHttpClient.getJson(url, creds);
                int bodyLen = body == null ? -1 : body.length();
                String head = body == null ? "<null>"
                        : (body.length() > 800 ? body.substring(0, 800) + "\u2026" : body);
                LOGGER.log(Level.INFO,
                        "[GroupedRuns] HTTP 200, body length={0}; head:\n{1}",
                        new Object[]{ bodyLen, head });

                List<String[]> attrs = parseAllAttributes(body);
                LOGGER.log(Level.INFO,
                        "[GroupedRuns] parsed {0} attribute(s) from response.", attrs.size());
                attrs.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
                for (String[] attr : attrs) {
                    m.add(attr[1] + " (" + attr[0] + ")");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "[GroupedRuns] Could not fetch RUN_LEVEL attribute list from vManager: " + url, e);
            }
            return m;
        }

        // ── helpers ─────────────────────────────────────────────────────────

        /** Same shape as the SESSION_LEVEL URL but with {@code component=runs}. */
        private static String buildRunsListUrl(String baseUrl) {
            String base = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            return base + "/rest/$schema/response?action=list"
                    + "&component=runs&extended=true";
        }

        private static String friendlyMessage(Throwable t) {
            String m = t.getMessage();
            return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
        }

        private static StandardUsernamePasswordCredentials lookupCredentials(
                Item item, String credentialsId, String serverUrl) {
            if (credentialsId == null || credentialsId.isBlank() || item == null) {
                return null;
            }
            List<StandardUsernamePasswordCredentials> candidates =
                    CredentialsProvider.lookupCredentialsInItem(
                            StandardUsernamePasswordCredentials.class,
                            item,
                            ACL.SYSTEM2,
                            URIRequirementBuilder.fromUri(serverUrl).build());
            return CredentialsMatchers.firstOrNull(
                    candidates,
                    CredentialsMatchers.withId(credentialsId));
        }

        /**
         * Parses the vManager schema response and returns every property
         * regardless of its declared {@code type} (RUN_LEVEL grouping is
         * useful on string attributes like {@code first_failure_description}
         * as well as numeric ones). Each entry is {@code [id, title]}.
         */
        private static List<String[]> parseAllAttributes(String body) {
            Map<String, String[]> seen = new LinkedHashMap<>();
            if (body == null || body.isBlank()) return new ArrayList<>(seen.values());
            try {
                JSONObject root = JSONObject.fromObject(body.trim());
                Object itemsRaw = root.get("items");
                if (!(itemsRaw instanceof JSONObject)) return new ArrayList<>(seen.values());
                JSONObject items = (JSONObject) itemsRaw;
                Object oneOfRaw = items.get("oneOf");
                if (oneOfRaw instanceof JSONArray) {
                    JSONArray oneOf = (JSONArray) oneOfRaw;
                    for (int i = 0; i < oneOf.size(); i++) {
                        Object elem = oneOf.get(i);
                        if (elem instanceof JSONObject) {
                            collect((JSONObject) elem, seen);
                        }
                    }
                } else {
                    collect(items, seen);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to parse RUN_LEVEL attribute list JSON", e);
            }
            return new ArrayList<>(seen.values());
        }

        private static void collect(JSONObject schema, Map<String, String[]> seen) {
            Object propsRaw = schema.get("properties");
            if (!(propsRaw instanceof JSONObject)) return;
            JSONObject properties = (JSONObject) propsRaw;
            @SuppressWarnings("unchecked")
            java.util.Set<Map.Entry<String, Object>> entries = properties.entrySet();
            for (Map.Entry<String, Object> entry : entries) {
                Object propRaw = entry.getValue();
                if (!(propRaw instanceof JSONObject)) continue;
                JSONObject prop = (JSONObject) propRaw;
                String id    = entry.getKey();
                Object t     = prop.containsKey("title") ? prop.get("title") : null;
                String title = (t == null || "null".equals(String.valueOf(t)))
                        ? id : String.valueOf(t);
                if (id == null || id.isEmpty()) continue;
                if (!seen.containsKey(id)) {
                    seen.put(id, new String[]{ id, title });
                }
            }
        }
    }
}
