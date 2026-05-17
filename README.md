# vManager Charts Plugin

A Jenkins plugin that adds an interactive **vManager Charts** view to any
Jenkins job, showing build trends and live, per-build numeric metrics fetched
straight from a Cadence Verisium Manager (vManager) server.

The built-in trend charts (build duration, success rate, test results) work
out-of-the-box for any job. The **Custom Metrics** feature additionally lets
you connect to a Verisium Manager server and chart Session, vPlan and
Coverage attributes per build.

> Plugin development and support is made by Cadence Design Systems.

## About

Cadence Verisium Manager exposes a REST API (vAPI) for querying regression,
session, coverage and vPlan data. This plugin uses that API to:

- attach a numeric value (per metric, per build) to every build of a job, and
- render those values together with Jenkins' own build-history data on a
  dedicated **vManager Charts** page in the job's left sidebar.

There is no required pre-existing integration with the
[Cadence vManager Plugin](https://plugins.jenkins.io/vmanager-plugin) — the
charts plugin can read session names from a workspace file. However, when the
vManager Plugin is also installed, this plugin can pick up session names
automatically.

## Features

- **Job-level integration** — adds a **vManager Charts** link to the job's
  left sidebar; rendering uses the
  [ECharts API plugin](https://plugins.jenkins.io/echarts-api/) for zoom,
  tooltips and PNG export.
- **Build-level charts** — a separate **vManager Charts** link is also added
  to every individual build's sidebar. These charts are populated per build
  (not aggregated across the job) and currently include the **Runs Duration
  Chart**, which renders a distribution of the build's runs by start time
  (X axis) and run duration (Y axis), so you can spot if there's any
  potential for faster turn-around time for the overall regression.
- **Built-in trend charts** (no Verisium Manager server required):
  - Build Duration (line)
  - Success/Failure Rate (stacked bar)
  - Regression Anomaly Detection Summary — pass/fail/skip from JUnit results
    (stacked bar, requires the JUnit plugin).
- **Custom Metrics charts** sourced from Verisium Manager vAPI:
  - **Session Level** — sums any numeric session attribute across the build's
    sessions (one POST to `/rest/sessions/list` per chart).
  - **vPlan Level** — sums any numeric vPlan attribute under a vPlan + optional
    hierarchy path (one POST to `/rest/vplan/get` per hierarchy).
  - **Coverage Level** — sums any numeric coverage attribute under an optional
    coverage hierarchy + verification scope (one POST to `/rest/metrics/get`
    per hierarchy).
- **Two configuration sources** — *GUI (wizard)* or **JSON file from
  workspace** loaded at build time. Pick the workspace path for fully
  data-driven, per-build chart definitions; see
  [Configuration source](#configuration-source-gui-vs-json-file).
- **Export configuration to JSON** — one-click export of the current GUI
  configuration to a JSON file you can check into source control,
  hand-edit and later drop into a build's workspace as the JSON config
  source. Credentials, server URL and session source are intentionally
  *not* exported.
- **Per-metric chart type**: `line`, `bar` or `scatter`.
- **Multiple charts per job**, each with its own title, max-builds window and
  list of metrics.
- **Same attribute, multiple times in a chart** — supported via the
  per-metric **Nickname** field; one REST fetch fans out to all duplicates.
- **Refinement files** for Coverage / vPlan attribute resolution.
- **Live form validation** — every server-dependant field pings vManager and
  shows red inline errors on the spot (auth/404/TLS/connection failures, etc.).
- **Verbose build logging toggle** — by default the per-build console is
  quiet (only WARNING / error lines from the plugin are printed). Tick
  **Verbose build logging** on the job to get every REST URL, request
  header, payload, response routing OID and per-session listing on the
  build's console while reproducing an issue.
- **System-log diagnostics at `FINE`** — the same trace lines are also
  emitted to Jenkins' system log via `java.util.logging` at `FINE` level
  under the package `org.jenkinsci.plugins.vmanager.charts`. They are
  hidden by default; enable them via a Log Recorder (see
  [System log diagnostics](#system-log-diagnostics)).
- **REST de-duplication** — attribute lists (per entity type) and the vPlan
  list are fetched at most once per configuration page load and reused
  across every combobox row, regardless of how many charts / metrics you
  have configured.
- **Pipeline-friendly** — the job property is configured via the standard
  Jenkins Configure UI; no DSL step is required.

## Plugin Dependencies

Required:

- [ECharts API plugin](https://plugins.jenkins.io/echarts-api/) (5.6.0+)
- [Credentials plugin](https://plugins.jenkins.io/credentials/)
- [Plain Credentials plugin](https://plugins.jenkins.io/plain-credentials/)
- [Ionicons API plugin](https://plugins.jenkins.io/ionicons-api/)

Optional:

- [JUnit plugin](https://plugins.jenkins.io/junit/) — required for the **Test
  Results** built-in chart.
- [Cadence vManager Plugin](https://plugins.jenkins.io/vmanager-plugin) —
  required only when the **Session source** is set to *Leverage vManager
  Jenkins Plugin Information*.

## Requirements

- Jenkins 2.479.3+
- Java 17+

## Installation

### Via Jenkins UI

1. **Manage Jenkins → Plugins → Advanced settings**.
2. Under **Deploy Plugin**, choose `vmanager-charts.hpi` and upload.
3. Restart Jenkins when prompted.

### Manual

1. Copy `vmanager-charts.hpi` to `$JENKINS_HOME/plugins/`.
2. Restart Jenkins.

## Configuration

All configuration lives in the job's **Configure** page, under the
**vManager Charts** section.

### Top-level options

| Field | Default | Description |
|-------|---------|-------------|
| **Enable vManager Charts** | `false` | Master on/off switch for this job. When unchecked the **vManager Charts** sidebar link is hidden and no per-build metric collection runs. |
| **vManager Server URL** | *(empty)* | **Required** when *Enable* is on. See [Verisium Manager connection](#verisium-manager-connection-when-show-custom-metrics-is-on) below. |
| **Credentials** | *(empty)* | **Required** when *Enable* is on. Standard Jenkins username/password credentials. |
| **vManager Session** | *Leverage vManager Jenkins Plugin Information* | See [Session source](#session-source). Always taken from the GUI — never from a JSON config file. |
| **Configuration source** | `GUI (configure charts below)` | Radio. Choose **GUI** to define charts via the wizard below, or **JSON file from workspace** to load the entire chart configuration from a workspace JSON file at build completion. See [Configuration source](#configuration-source-gui-vs-json-file). |
| **Verbose build logging** | `false` | When on, every `[vManager Charts]` diagnostic line (REST URLs, request headers, payloads, response routing OIDs, per-session listings, summary counters, etc.) is printed to the build's console. When off, only WARNING and error lines appear. Always taken from the GUI — never from a JSON config file. |

The checkboxes below are shown only when **Configuration source = GUI**:

| Field | Default | Description |
|-------|---------|-------------|
| **Build Level Charts** | `false` | Master switch for the *per-build* charts. When on, a **vManager Charts** link is also added to every build's sidebar. Expands the sub-options below. |
| &nbsp;&nbsp;&nbsp;&nbsp;**Runs Duration Chart** | `false` | Distribution of the build's runs by start time (X axis) and run duration (Y axis). |
| **Build Duration Chart** | `false` | Show the built-in line chart of build duration. |
| **Success/Failure Rate Chart** | `false` | Show the built-in stacked bar of build outcomes. |
| **Regression Anomaly Detection Summary** | `false` | Show the built-in stacked bar of pass/fail/skip from JUnit results. |
| **Maximum builds** | `50` | How many of the most-recent builds the **built-in** charts plot. `0` = no limit (slow on long histories — a yellow warning is shown). |
| **Show Custom Metrics** | `false` | Reveals the **Custom Charts** repeatable list below. |

### Verisium Manager connection

| Field | Default | Behaviour when empty |
|-------|---------|----------------------|
| **vManager Server URL** | *(empty)* | **Required.** Must start with `http://` or `https://`. Leaving it blank or malformed turns the field red and blocks Save without leaving the page. Example: `https://host:port/vmgr/vapi`. |
| **Credentials** | *(empty)* | **Required.** Standard Jenkins username/password credentials used as HTTP Basic auth against vManager. |
| **vManager Schema** | `latest` | Used when building the REST URLs (`/rest/$schema/...`). Leaving it blank falls back to `latest`. |

*Server URL, credentials, the session source and the verbose-logging flag
are always taken from the GUI, even when the **Configuration source** is
set to **JSON file from workspace**.*

#### Session source

Select where the per-build session names come from — these names drive every
custom-metric REST call.

| Option | Behaviour |
|--------|-----------|
| **Leverage vManager Jenkins Plugin Information** *(default)* | Read the sessions associated with this build by the [Cadence vManager Plugin](https://plugins.jenkins.io/vmanager-plugin). If you are using *collect* mode no plugin change is required; if you are using *launch* mode you must upgrade to the latest version of the vManager Plugin so it can create the `BUILD_ID.BUILD_VERSION.sessions.input` file in the workspace. |
| **Input file name** | Read session names from a text file (one session name per line). Path is taken from **Input file path** below. |

| Field | Default | Behaviour when empty |
|-------|---------|----------------------|
| **Input file path** *(only when source = Input file)* | *(empty)* | When blank, the plugin looks in the build's workspace for `BUILD_ID.BUILD_VERSION.sessions.input`. Set this only if you want a fixed path that is consistent across builds. |

If a build has **no sessions** (file missing, file empty, vManager Plugin
didn't record any), every custom metric for that build is recorded as `0`
and the build log will contain a `skipped (no sessions or no id) = 0`
line per metric, so the chart still has a data point.

### Custom Charts (repeatable)

Click **Add Custom Chart** for every chart you want on the **vManager
Charts** page. Each chart has:

| Field | Default | Notes |
|-------|---------|-------|
| **Chart Title** | *(empty, required)* | Rendered above the chart. |
| **Maximum builds** | `50` | How many of the most-recent builds *this* chart plots. `0` = no limit. |
| **vPlan Type** | `-- None --` | `DB (from server)` or `File (local path)`. Required when this chart contains any **vPlan Level** metrics; ignored otherwise. |
| **vPlan** | *(empty)* | When **vPlan Type** = `DB`, a combobox populated by `POST {serverUrl}/rest/vplan/list-vplans` showing every available `vplan_name`. When **vPlan Type** = `FILE`, type the absolute path to a `.vplan` file. The vPlan applies to **the whole chart** — every vPlan-level metric in the chart shares it. |

Then add one or more **Metrics** to the chart.

### Metrics (repeatable, inside a chart)

| Field | Default | Notes |
|-------|---------|-------|
| **Entity Type** | `Session Level` | One of: `Session Level`, `vPlan Level`, `Coverage Level`. Drives which REST endpoint and which sub-fields are relevant. |
| **Attribute Name** | *(empty, required)* | Combobox populated from vManager. vPlan / Coverage are populated by `GET /rest/$schema/response?action=list-vplan-tree-sub-entities&component=tracking-configuration&extended=true` (vPlan) or `...list-metrics-tree-sub-entities...` (Coverage) and shown as `Title (id)`. The displayed string is what's saved, so the combobox stays in sync on reopen. Numeric attributes only. |
| **Chart Type** | `line` | `line`, `bar` or `scatter`. Per-metric — different metrics in the same chart can render differently. |
| **Nickname** | *(empty)* | Optional human-friendly label. When set: (a) it becomes the legend label instead of the attribute title, and (b) it acts as the per-chart unique series identifier — letting you pick the **same attribute** more than once in a chart and tell the values apart (e.g. one with refinement file A, another with refinement file B). |
| **Coverage Hierarchy** *(Coverage Level only)* | *(empty)* | Hierarchy path inside the coverage model. Empty = no hierarchy filter (server-default scope). |
| **Verification Scope** *(Coverage Level only)* | *(empty)* | Verification scope filter forwarded to `/rest/metrics/get`. **See important grouping note below.** |
| **Refinement Files** *(Coverage and vPlan)* | *(empty list)* | Repeatable list of full filesystem paths to vManager refinement files. **See important grouping note below.** |
| **Hierarchy Path** *(vPlan Level only)* | *(empty)* | Hierarchy path inside the vPlan. Empty = whole vPlan. |
| **vPlan Refinement Files** *(vPlan Level only)* | *(empty list)* | Repeatable list of full filesystem paths to vPlan-specific refinement files. |

#### Important: per-hierarchy batching

To minimise vAPI traffic the plugin **batches metrics into a single REST
call when they share the same hierarchy path within a chart**:

- **Coverage Level** metrics are grouped by **Coverage Hierarchy** (treating
  blank as one bucket). Inside a group, **only the first metric's
  Verification Scope and Refinement Files are sent**; later metrics in the
  same group inherit them and any value typed into their own *Verification
  Scope* / *Refinement Files* fields is **ignored** for the REST request.
- **vPlan Level** metrics are grouped by **Hierarchy Path** (blank = one
  bucket). Inside a group, only the first metric's **Refinement Files** and
  **vPlan Refinement Files** are used; later metrics' lists are ignored.

In practice this means: if two metrics in the same chart share the same
hierarchy *but need different verification scopes or different refinement
files*, give each one a **different hierarchy** (or split them into
separate charts). If they truly share a hierarchy, only fill the scope and
refinement-file fields on the **first** metric; the others can leave them
blank.

The chart's **vPlan** (name + type) is configured **once per chart** and is
shared by every vPlan-level metric in that chart.

#### Empty-attribute behaviour

If a metric is saved with a blank **Attribute Name**, or its REST call
returns no value, the build records `0` for that metric and a warning is
written to the build log:

```
[vManager Charts] [<chart title>] '<metric>' (<level>) skipped (no sessions or no id) = 0
```

Charts will still render — the missing builds simply show `0`.

#### Validation rules (enforced inline on the form)

- **vManager Server URL** — must start with `http://` or `https://`.
  Empty or malformed: red outline, inline error, **Save is blocked** while
  you stay on the page.
- **Attribute Name** — required. The form pings vManager when you change
  the attribute, server URL or credentials, and surfaces server-side errors
  inline (auth, 404, TLS, connection refused, ...).
- **Nickname**:
  - Must be **unique within a chart** when set.
  - **Becomes mandatory** when the **same attribute** is picked more than
    once in the chart — every duplicate occurrence must carry its own
    nickname so the chart can tell them apart. The form turns the offending
    nickname inputs red and blocks Save until each duplicate has a
    nickname.
- **Maximum builds** — non-negative integer; `0` is allowed but produces a
  yellow warning that the chart will scan the entire job history.

## Usage

1. Open any job's **Configure** page.
2. Tick **Enabled** under **vManager Charts** (it is on by default once the
   property is added).
3. Decide which built-in charts you want; uncheck the ones you don't.
4. To add custom metrics:
   1. Tick **Show Custom Metrics**.
   2. Enter the **vManager Server URL** and pick **Credentials**.
   3. Choose your **Session source**.
   4. Click **Add Custom Chart**, fill **Chart Title** and **Maximum builds**.
   5. (Optional) Pick a **vPlan Type** and **vPlan** — required only if any
      metric on this chart will be **vPlan Level**.
   6. Click **Add Metric** for each series, fill in the fields above.
5. **Save**.
6. Run a build. After the build finishes, the metrics are fetched and
   stored as a `CustomMetricsBuildAction` on the build (a `RunListener`
   does the work, so no custom build step is needed).
7. Open the job page and click **vManager Charts** in the left sidebar.

The first time you open the page after enabling custom metrics, only the
builds run **after** that moment will have custom values — historical
builds simply show `0` for the new metrics.

### Build log

By default the per-build console only shows **WARNING** and **error** lines
from the plugin. Server-side failures are logged at `WARNING` and recorded
as `0` for the build so the chart still renders.

When reproducing an issue, tick **Verbose build logging** on the job
configuration to also print every chatty `[vManager Charts]` trace line
to the build's console (REST URLs, request headers, payloads, response
routing OIDs, per-session listings and the per-metric summary line, e.g.):

```
[vManager Charts] Configuration source: GUI (job configuration page).
[vManager Charts] sessions input file: .../42.42.sessions.input (1 session)
[vManager Charts]   session: vmgr.israel.linux64.x86_64.agile_mdv.051226
[vManager Charts] POST https://host:port/vmgr/vapi/rest/sessions/list
[vManager Charts]   payload: { ... }
[vManager Charts] [Coverage Closure] 'Expression Hit' (COVERAGE_LEVEL id='CoverageAttributes.EXPRESSION_HIT' hierarchy='top.uart' scope='cover' refinement=2) = 87.43
[vManager Charts] [Session KPIs] 'Passed' (SESSION_LEVEL id='SessionAttributes.PASSED_RUNS' sessions=3) = 412
```

Turn it back off once you're done to keep the console clean.

### System log diagnostics

The same diagnostic traces are also emitted through Jenkins'
`java.util.logging` system, under the logger
`org.jenkinsci.plugins.vmanager.charts`, at level `FINE`. They are hidden
by default — enable them via a Log Recorder:

1. **Manage Jenkins → System Log** (under *Status Information*).
2. **Add new log recorder** → name it e.g. `vManager Charts` → **Create**.
3. Under *Loggers*, click **Add** and enter:
   - **Logger**: `org.jenkinsci.plugins.vmanager.charts`
   - **Log level**: `FINE` (or `FINEST` for maximum detail)
4. **Save**, then open the recorder and click **Log records**.

The recorder is global (visible to all Jenkins users); the per-job
**Verbose build logging** checkbox above controls only the per-build
console and is opt-in per job.

## Troubleshooting

**The vManager Charts sidebar link doesn't appear.**
Make sure the **vManager Charts** job property is added on the job's
Configure page and **Enabled** is ticked.

**Custom charts are empty / all zeros.**
- Confirm the build log has `[vManager Charts]` lines. If not, the
  `RunListener` didn't run — check that the property is enabled and that
  **Show Custom Metrics** is on.
- Check the build log for `WARNING` lines from `[vManager Charts]` — they
  contain the underlying vAPI error.
- Verify **Session source**: with the *vManager Plugin* option, the build
  must have actually launched a vManager session. With the *Input file*
  option, verify the file exists in the workspace and that its name
  matches the configured path (or the default
  `${BUILD_NUMBER}.${BUILD_ID}.sessions.input`).

**Two metrics with the same attribute show the same value.**
Add a **Nickname** to each. The plugin will refuse to save without one
when the attribute is duplicated within a chart.

**Refinement files / verification scope on a metric look ignored.**
This is expected when another metric in the **same chart** uses the
**same hierarchy** — only the first metric's scope and refinement-file
list are sent (see the batching note above). Give each one a different
hierarchy, or move them into separate charts.

**vPlan combobox is empty.**
Either **vPlan Type** is not set to `DB`, or `POST
{serverUrl}/rest/vplan/list-vplans` failed (check the system log; the
field's red inline error will also tell you).

**Charts don't load at all.**
- Ensure the [ECharts API plugin](https://plugins.jenkins.io/echarts-api/)
  is installed and enabled.
- Open the browser console; missing JS dependencies show up there.

## Configuration source: GUI vs JSON file

Under **Charts configuration** the job offers two radio options:

- **GUI (configure charts below)** *(default)* — the chart selection and
  Custom Chart definitions come from the wizard on the same page (this is
  what the rest of this README describes).
- **JSON file from workspace** — at build completion the plugin loads a
  JSON file from the build's workspace and uses it to replace the entire
  chart configuration for that build. The path is taken from the
  **Config JSON File** field; when blank, the plugin looks for
  `vmanager-charts.config.json` in the build's workspace.

Even when **JSON file from workspace** is selected, the following continue
to come from the GUI (and **must** be filled in on the GUI):

- **vManager Server URL**
- **Credentials**
- **vManager Session** (source + input-file path)
- **Verbose build logging**

If the JSON file is missing, unreadable, or contains `"enabled": false`,
the build is recorded with a single warning line and no charts are
populated for that build.

### Export configuration to JSON

The **Charts configuration** section contains an **Export configuration to
JSON** button. Clicking it serializes the current GUI configuration to the
browser as `vmanager-charts.config.json`. The export intentionally **omits**:

- `credentialsId`
- `serverUrl`
- `sessionSource` / `sessionInputFile`
- `verboseLogging`
- `configSource` / `configFilePath`

…so the exported file is safe to commit to source control and to share
across jobs / Jenkins instances.

### JSON structure

The loader accepts the JSON shape produced by the exporter; any field may
be omitted (defaults are applied). Top-level keys:

```jsonc
{
  "enabled":                         true,
  "vManagerSchema":                  "latest",
  "maxBuilds":                       50,

  // Built-in charts — all default to false
  "showBuildLevelCharts":            true,
  "showRegressionOptimizationChart": true,
  "showBuildDuration":               true,
  "showSuccessRate":                 false,

  // Custom charts list
  "showCustomMetrics":               true,
  "customCharts": [
    {
      "title":     "Coverage Closure",
      "vPlanType": "DB",          // "", "DB" or "FILE"
      "vPlanPath": "my_vplan",   // vPlan name (DB) or absolute path (FILE)
      "maxBuilds": 50,
      "metrics": [
        {
          "entityType":         "COVERAGE_LEVEL",     // SESSION_LEVEL | VPLAN_LEVEL | COVERAGE_LEVEL
          "attributeName":      "Expression Hit (CoverageAttributes.EXPRESSION_HIT)",
          "chartType":          "line",               // line | bar | scatter
          "nickname":           "uart-cover",         // unique within chart; required when an attribute is repeated
          "hierarchyPath":      "",                  // VPLAN_LEVEL only
          "coverageHierarchy":  "top.uart",          // COVERAGE_LEVEL only
          "verificationScope":  "cover",             // COVERAGE_LEVEL only
          "refinementFiles":      [{"path": "/abs/refine.refine"}],
          "vplanRefinementFiles": [{"path": "/abs/vplan_refine.refine"}]
        }
      ]
    }
  ]
}
```

The following fields, even if present in a hand-edited file, are
**always ignored** on load (they come from the GUI): `credentialsId`,
`serverUrl`, `sessionSource`, `sessionInputFile`, `verboseLogging`,
`configSource`, `configFilePath`.

## Building from source

```powershell
mvn -DskipTests clean package
```

The output is `target/vmanager-charts.hpi`. Drop it into
`$JENKINS_HOME/plugins/` and restart Jenkins.

## License

This plugin follows the standard Jenkins project licensing.

## Support

For issues and feature requests, please use the project's issue tracker.

## Change Log

### 1.1

- Renamed **Test Results** built-in chart to **Regression Anomaly Detection
  Summary**.
- New **Build Level Charts** section with the **Runs Duration Chart**
  (per-build distribution of runs by start time and duration); accessed
  via the **vManager Charts** link added to every build's sidebar.
- New **Configuration source** radio: choose between *GUI* and *JSON file
  from workspace*. JSON file fully replaces the chart selection at build
  time; Server URL, credentials, session source and verbose-logging flag
  stay in the GUI.
- New **Export configuration to JSON** button that downloads the current
  GUI configuration as `vmanager-charts.config.json`, omitting
  credentials, server URL, session source and the verbose-logging flag.
- New **Verbose build logging** checkbox — by default the per-build
  console only shows WARNING / error lines from the plugin. Turn this on
  to see every REST URL, payload and per-session listing.
- System-log diagnostics demoted from `INFO` to `FINE`; enable via a Log
  Recorder on logger `org.jenkinsci.plugins.vmanager.charts` at `FINE`.
- All built-in chart checkboxes (Build Duration, Success/Failure Rate,
  Regression Anomaly Detection Summary) now default to **off** so a newly
  enabled job starts with a clean configuration.
- REST de-duplication: attribute lists per entity type and the vPlan list
  are fetched at most once per configuration page load and reused across
  every combobox row.

### 1.0 (initial release)

- Built-in Build Duration, Success Rate, Test Results trend charts.
- Custom Metrics framework with Session / vPlan / Coverage entity types.
- Per-chart vPlan (DB or local file) selection.
- Per-metric Nickname for duplicate attributes within a chart.
- Per-hierarchy batching for vPlan and Coverage REST calls.
- Live in-form validation that pings vManager and surfaces server errors
  inline; mandatory fields enforced with red outlines that block Save
  while keeping the user on the page.
