package org.jenkinsci.plugins.vmanager.charts.model;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
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
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
import hudson.RelativePath;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Defines one custom metric: which vManager entity level to query and the attribute name.
 *
 * <p>{@code attributeName} stores the value in the format {@code "Title (id)"}
 * (e.g. {@code "Expression Covered (CoverageAttributes.EXPRESSION_HIT)"}).
 * This keeps the combobox in sync on reopen (saved value = displayed value) while
 * preserving both pieces of information. Use {@link #getAttributeTitle()} for the
 * chart legend and {@link #getAttributeId()} for the vManager REST fetch.
 * Plain values without parentheses (manually typed or legacy) are treated as-is.
 */
public class MetricDefinition extends AbstractDescribableImpl<MetricDefinition> {

    /** One of: SESSION_LEVEL, VPLAN_LEVEL, COVERAGE_LEVEL */
    private final String entityType;

    /**
     * Stored as {@code "Title (id)"} when populated from the server dropdown.
     * Falls back to a plain string when typed manually.
     */
    private final String attributeName;

    /** One of: line, bar, scatter */
    private final String chartType;

    /** Optional: path within the vPlan hierarchy. Only meaningful for VPLAN_LEVEL. */
    private final String hierarchyPath;

    /** Optional: verification scope filter. Only meaningful for COVERAGE_LEVEL. */
    private final String verificationScope;

    /** Optional: hierarchy path within the coverage model. Only meaningful for COVERAGE_LEVEL. */
    private String coverageHierarchy;

    /**
     * Optional: full filesystem paths to vManager refinement files.
     * Meaningful for both COVERAGE_LEVEL and VPLAN_LEVEL.
     */
    private List<RefinementFile> refinementFiles;

    /** Optional: vPlan name (e.g. {@code "APB_UART"}). Only meaningful for VPLAN_LEVEL. */
    private List<RefinementFile> vplanRefinementFiles;

    /** Optional: user-supplied label for this metric. When set, the chart
     *  legend uses this instead of the attribute title, and it also acts as
     *  the per-chart unique series identifier (so two metrics that pick the
     *  same attribute can be told apart). Must be unique within a chart. */
    private String nickname;

    @DataBoundConstructor
    public MetricDefinition(String entityType, String attributeName, String chartType,
                            String hierarchyPath, String verificationScope) {
        this.entityType        = entityType;
        this.attributeName     = attributeName;
        this.chartType         = chartType == null || chartType.isBlank() ? "line" : chartType;
        this.hierarchyPath     = hierarchyPath == null ? "" : hierarchyPath;
        this.verificationScope = verificationScope == null ? "" : verificationScope;
    }

    public String getEntityType() {
        return entityType;
    }

    /** Raw stored value, e.g. {@code "Expression Covered (CoverageAttributes.EXPRESSION_HIT)"}. */
    public String getAttributeName() {
        return attributeName == null ? "" : attributeName;
    }

    /**
     * Extracts the human-friendly title from a {@code "Title (id)"} value.
     * Returns the whole string if no {@code (id)} suffix is present.
     */
    public String getAttributeTitle() {
        String v = getAttributeName();
        int open = v.lastIndexOf('(');
        return (open > 1) ? v.substring(0, open).trim() : v;
    }

    /**
     * Extracts the machine-readable id from a {@code "Title (id)"} value.
     * Returns the whole string if no {@code (id)} suffix is present.
     */
    public String getAttributeId() {
        String v = getAttributeName();
        int open = v.lastIndexOf('(');
        int close = v.lastIndexOf(')');
        return (open >= 0 && close > open) ? v.substring(open + 1, close).trim() : v;
    }

    public String getChartType() {
        return chartType;
    }

    public String getHierarchyPath() {
        return hierarchyPath == null ? "" : hierarchyPath;
    }

    public String getVerificationScope() {
        return verificationScope == null ? "" : verificationScope;
    }

    public String getCoverageHierarchy() {
        return coverageHierarchy == null ? "" : coverageHierarchy;
    }

    @DataBoundSetter
    public void setCoverageHierarchy(String coverageHierarchy) {
        this.coverageHierarchy = coverageHierarchy == null ? "" : coverageHierarchy;
    }

    public List<RefinementFile> getRefinementFiles() {
        return refinementFiles == null ? Collections.emptyList() : refinementFiles;
    }

    @DataBoundSetter
    public void setRefinementFiles(List<RefinementFile> refinementFiles) {
        this.refinementFiles = refinementFiles;
    }

    public List<RefinementFile> getVplanRefinementFiles() {
        return vplanRefinementFiles == null ? Collections.emptyList() : vplanRefinementFiles;
    }

    @DataBoundSetter
    public void setVplanRefinementFiles(List<RefinementFile> vplanRefinementFiles) {
        this.vplanRefinementFiles = vplanRefinementFiles;
    }

    public String getNickname() {
        return nickname == null ? "" : nickname;
    }

    @DataBoundSetter
    public void setNickname(String nickname) {
        this.nickname = nickname == null ? "" : nickname.trim();
    }

    /** Label shown on the chart legend: nickname if set, else attribute title. */
    public String getDisplayName() {
        String nick = getNickname();
        return nick.isEmpty() ? getAttributeTitle() : nick;
    }

    /** Per-chart unique key for this metric's series in the build action map.
     *  Uses the nickname when set (forbidden to repeat within a chart), else
     *  the attribute name. */
    public String getSeriesKey() {
        String nick = getNickname();
        return nick.isEmpty() ? getAttributeName() : nick;
    }

    /** Chart-series legend label: the attribute title. */
    public String getName() {
        return getDisplayName();
    }

    // -------------------------------------------------------------------------

    @Extension
    public static class DescriptorImpl extends Descriptor<MetricDefinition> {

        private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

        @NonNull
        @Override
        public String getDisplayName() {
            return "Custom Metric";
        }

        @POST
        public ListBoxModel doFillEntityTypeItems(@AncestorInPath Item item) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            ListBoxModel m = new ListBoxModel();
            m.add("Session Level",  "SESSION_LEVEL");
            m.add("vPlan Level",    "VPLAN_LEVEL");
            m.add("Coverage Level", "COVERAGE_LEVEL");
            return m;
        }

        /**
         * Validates the Attribute Name field. In addition to the required-field check,
         * this also pings the vManager REST endpoint for the selected entity type so
         * that any server-side error (auth, 404, connection refused, TLS, etc.) is
         * surfaced inline under the field in red. Triggered both on edits to the
         * field itself and — via {@code checkDependsOn} in the Jelly — whenever
         * {@code entityType}, {@code serverUrl} or {@code credentialsId} change.
         */
        @POST
        public FormValidation doCheckAttributeName(
                @QueryParameter String value,
                @QueryParameter String entityType,
                @RelativePath("../..") @QueryParameter String serverUrl,
                @RelativePath("../..") @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            if (serverUrl != null && !serverUrl.isBlank()
                    && entityType != null && !entityType.isBlank()) {
                String url = buildListUrl(serverUrl, entityType);
                if (url != null) {
                    try {
                        StandardUsernamePasswordCredentials creds =
                                lookupCredentials(item, credentialsId, serverUrl);
                        fetchUrl(url, creds);
                    } catch (Exception e) {
                        return FormValidation.error(
                                "vManager server error: " + friendlyMessage(e));
                    }
                }
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Attribute name is required.");
            }
            return FormValidation.ok();
        }

        private static String friendlyMessage(Throwable t) {
            String m = t.getMessage();
            return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
        }

        @POST
        public ListBoxModel doFillChartTypeItems(@AncestorInPath Item item) {
            VManagerChartsUtil.checkDescriptorPermission(item);
            ListBoxModel m = new ListBoxModel();
            m.add("Line",    "line");
            m.add("Bar",     "bar");
            m.add("Scatter", "scatter");
            return m;
        }

        /**
         * Populates the Attribute combobox by calling the vManager REST API and listing
         * the human-friendly titles of every numeric attribute. The combobox provides
         * built-in type-ahead filtering, and the title that the user picks is what gets
         * stored in {@code attributeName} — so it matches back on reopen and is
         * automatically used as the chart legend label.
         *
         * <p>URL patterns:
         * <ul>
         *   <li>COVERAGE_LEVEL: {@code {serverUrl}/rest/$schema/response?action=list-metrics-tree-sub-entities&component=tracking-configuration&extended=true}
         *   <li>VPLAN_LEVEL:   {@code {serverUrl}/rest/$schema/response?action=list-vplan-tree-sub-entities&component=tracking-configuration&extended=true}
         *   <li>SESSION_LEVEL: not supported yet — empty list
         * </ul>
         */
        @POST
        public ComboBoxModel doFillAttributeNameItems(
                @QueryParameter String entityType,
                @RelativePath("../..") @QueryParameter String serverUrl,
                @RelativePath("../..") @QueryParameter String credentialsId,
                @AncestorInPath Item item) {

            VManagerChartsUtil.checkDescriptorPermission(item);
            ComboBoxModel m = new ComboBoxModel();

            if (serverUrl == null || serverUrl.isBlank()
                    || entityType == null || entityType.isBlank()) {
                return m;
            }

            String url = buildListUrl(serverUrl, entityType);
            if (url == null) {
                return m;
            }

            try {
                StandardUsernamePasswordCredentials creds =
                        lookupCredentials(item, credentialsId, serverUrl);
                LOGGER.log(Level.FINE, "Fetching vManager attributes: GET {0} (creds={1})",
                        new Object[]{url, creds == null ? "<none>" : creds.getId()});
                String body = fetchUrl(url, creds);
                List<AttributeInfo> attrs = parseAttributes(body);
                attrs.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                LOGGER.log(Level.FINE, "vManager attributes: parsed {0} numeric attribute(s) from {1}",
                        new Object[]{attrs.size(), url});
                for (AttributeInfo attr : attrs) {
                    m.add(attr.title + " (" + attr.id + ")");
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Could not fetch attribute list from vManager: " + url, e);
            }
            return m;
        }

        // ── URL builder ─────────────────────────────────────────────────────

        private String buildListUrl(String baseUrl, String entityType) {
            String base = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            switch (entityType) {
                case "COVERAGE_LEVEL":
                    return base + "/rest/$schema/response?action=list-metrics-tree-sub-entities"
                            + "&component=tracking-configuration&extended=true";
                case "VPLAN_LEVEL":
                    return base + "/rest/$schema/response?action=list-vplan-tree-sub-entities"
                            + "&component=tracking-configuration&extended=true";
                case "SESSION_LEVEL":
                    return base + "/rest/$schema/response?action=list"
                            + "&component=sessions&extended=true";
                default:
                    return null;
            }
        }

        // ── Credentials lookup ───────────────────────────────────────────────

        private StandardUsernamePasswordCredentials lookupCredentials(
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

        // ── HTTP fetch ───────────────────────────────────────────────────────

        private String fetchUrl(String urlStr, StandardUsernamePasswordCredentials creds)
                throws Exception {
            return VManagerHttpClient.getJson(urlStr, creds);
        }

        // ── JSON parsing ─────────────────────────────────────────────────────

        /**
         * Parses the vManager schema response. Supports two shapes:
         *
         * <p><b>Metrics / vPlan</b>:
         * <pre>{ "items": { "oneOf": [ { "properties": { key: {type,id,title} } }, ... ] } }</pre>
         *
         * <p><b>Sessions</b>:
         * <pre>{ "items": { "properties": { key: {type,id,title} } } }</pre>
         *
         * Only properties with {@code "type": "number"} are returned, deduplicated by id.
         */
        private List<AttributeInfo> parseAttributes(String body) {
            Map<String, AttributeInfo> seen = new LinkedHashMap<>();
            if (body == null || body.isBlank()) {
                return new ArrayList<>(seen.values());
            }
            try {
                JSONObject root = JSONObject.fromObject(body.trim());

                Object itemsRaw = root.get("items");
                if (!(itemsRaw instanceof JSONObject)) {
                    return new ArrayList<>(seen.values());
                }
                JSONObject items = (JSONObject) itemsRaw;

                Object oneOfRaw = items.get("oneOf");
                if (oneOfRaw instanceof JSONArray) {
                    JSONArray oneOf = (JSONArray) oneOfRaw;
                    for (int i = 0; i < oneOf.size(); i++) {
                        Object elemRaw = oneOf.get(i);
                        if (elemRaw instanceof JSONObject) {
                            collectFromSchema((JSONObject) elemRaw, seen);
                        }
                    }
                } else {
                    // Session shape: items has properties directly
                    collectFromSchema(items, seen);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to parse vManager attribute list JSON", e);
            }
            return new ArrayList<>(seen.values());
        }

        private void collectFromSchema(JSONObject schema, Map<String, AttributeInfo> seen) {
            Object propsRaw = schema.get("properties");
            if (!(propsRaw instanceof JSONObject)) {
                return;
            }
            JSONObject properties = (JSONObject) propsRaw;

            @SuppressWarnings("unchecked")
            java.util.Set<Map.Entry<String, Object>> propEntries = properties.entrySet();
            for (Map.Entry<String, Object> entry : propEntries) {
                Object propRaw = entry.getValue();
                if (!(propRaw instanceof JSONObject)) {
                    continue;
                }
                JSONObject prop = (JSONObject) propRaw;

                Object type = prop.get("type");
                if (!"number".equals(type) && !"integer".equals(type)) {
                    continue;
                }

                // The attribute id is the property key (e.g. "functional_combined_other"),
                // NOT the inner "id" field (which is a server-internal symbolic name).
                String id    = entry.getKey();
                String title = toStringOrNull(prop.containsKey("title") ? prop.get("title") : null);

                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (!seen.containsKey(id)) {
                    seen.put(id, new AttributeInfo(id,
                            (title != null && !title.isEmpty()) ? title : id));
                }
            }
        }

        /** Converts a JSON value to a non-"null" string, or returns {@code null}. */
        private static String toStringOrNull(Object val) {
            if (val == null) return null;
            String s = val.toString();
            return "null".equals(s) ? null : s;
        }

        // ── Data holder ──────────────────────────────────────────────────────

        private static final class AttributeInfo {
            final String id;
            final String title;
            AttributeInfo(String id, String title) {
                this.id    = id;
                this.title = title;
            }
        }
    }
}
