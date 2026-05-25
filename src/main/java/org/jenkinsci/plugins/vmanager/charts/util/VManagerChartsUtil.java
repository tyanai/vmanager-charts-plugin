package org.jenkinsci.plugins.vmanager.charts.util;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.Item;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.vmanager.charts.VManagerChartsJobProperty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility helpers for the vManager Charts plugin.
 *
 * <p>Centralises:</p>
 * <ul>
 *     <li>Building the conventional session-input filename
 *         ({@code ${BUILD_NUMBER}.${BUILD_ID}.sessions.input}).</li>
 *     <li>Resolving the actual file location given the user's
 *         {@link VManagerChartsJobProperty#getSessionSource() session source}
 *         choice and (optionally) custom path. The result is a {@link FilePath}
 *         so that reads work transparently on a remote agent (Linux or Windows).</li>
 *     <li>Reading the file contents into a list of session names (one per
 *         non-blank, non-{@code #}-comment line).</li>
 * </ul>
 *
 * <p>The class is {@code final} with a private constructor — it is a pure
 * collection of static helpers and is not intended to be instantiated.</p>
 */
public final class VManagerChartsUtil {

    /** Suffix used for the conventional per-build session-input file. */
    public static final String SESSIONS_INPUT_SUFFIX = ".sessions.input";

    /**
     * Suffix of the per-build file dropped by the vManager Jenkins Plugin
     * (launch mode) listing the IDs of the sessions it just kicked off.
     * Used as a fallback when {@link #SESSIONS_INPUT_SUFFIX} is missing.
     */
    public static final String SESSION_LAUNCH_OUTPUT_SUFFIX = ".session_launch.output";

    private VManagerChartsUtil() {
        // utility class
    }

    /**
     * Enforce that the caller has permission to invoke a descriptor's
     * {@code doCheckXxx} / {@code doFillXxxItems} method.
     *
     * <p>For {@link Item}-scoped descriptors (the model classes in this
     * plugin live under a job's config page), {@link Item#CONFIGURE} is
     * the appropriate permission — the same one Jenkins requires to even
     * open the page on which these endpoints are surfaced. When the
     * descriptor is invoked outside an item context (e.g. the global
     * {@code descriptorByName} URL), we fall back to
     * {@link Jenkins#ADMINISTER}.</p>
     *
     * <p>This is paired with {@code @POST} on each endpoint to ensure the
     * call is CSRF-protected. The method throws
     * {@link org.springframework.security.access.AccessDeniedException}
     * when the caller lacks the required permission.</p>
     */
    public static void checkDescriptorPermission(Item item) {
        if (item != null) {
            item.checkPermission(Item.CONFIGURE);
        } else {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        }
    }

    /**
     * @return the conventional file name for this run, e.g. {@code 42.42.sessions.input}.
     */
    public static String defaultSessionsFileName(Run<?, ?> run) {
        return run.getNumber() + "." + run.getId() + SESSIONS_INPUT_SUFFIX;
    }

    /**
     * @return the conventional fallback file name for this run, e.g.
     *         {@code 42.42.session_launch.output} — a list of session ids
     *         produced by the vManager Jenkins Plugin in launch mode.
     */
    public static String defaultSessionLaunchOutputFileName(Run<?, ?> run) {
        return run.getNumber() + "." + run.getId() + SESSION_LAUNCH_OUTPUT_SUFFIX;
    }

    /**
     * Resolves the {@link FilePath} of the fallback {@code .session_launch.output}
     * file. We place it next to the resolved sessions-input file (so a
     * custom user path's directory is honoured); if no input file is known
     * we fall back to the workspace root.
     *
     * @return the {@link FilePath} that <em>should</em> contain the session
     *         ids (the file may or may not actually exist), or {@code null}
     *         when neither a sessions-input file nor a workspace is available.
     */
    public static FilePath resolveSessionLaunchOutputFile(Run<?, ?> run,
                                                          FilePath sessionsInputFile,
                                                          FilePath workspace) {
        String defaultName = defaultSessionLaunchOutputFileName(run);
        if (sessionsInputFile != null) {
            FilePath parent = sessionsInputFile.getParent();
            if (parent != null) {
                return parent.child(defaultName);
            }
        }
        return workspace == null ? null : workspace.child(defaultName);
    }

    /**
     * Best-effort lookup of the directory where the vManager Jenkins Plugin
     * (or a user-supplied pipeline step) is expected to drop the
     * {@code .sessions.input} / {@code .session_launch.output} files.
     *
     * <p>For freestyle ({@code AbstractBuild}) jobs this is the build's
     * agent workspace.</p>
     *
     * <p>For Pipeline jobs there is no single workspace for the whole build,
     * and walking the flow graph to find {@code node { }} workspaces is
     * unreliable (the node block may have ended by the time we run, agents
     * may be offline, etc.). Instead, for pipeline runs we return the build's
     * <strong>controller-side</strong> directory ({@link Run#getRootDir()},
     * i.e. the directory that holds the build log) wrapped as a local
     * {@link FilePath}. Pipeline users are expected to copy / archive the
     * input files there explicitly (e.g. via {@code archiveArtifacts} or
     * {@code writeFile} on the controller).</p>
     */
    public static FilePath getCurrentWorkspace(Run<?, ?> run) {
        if (run instanceof AbstractBuild) {
            return ((AbstractBuild<?, ?>) run).getWorkspace();
        }
        // Pipeline (and any other non-AbstractBuild) — use the controller-side
        // build directory next to the log file.
        return new FilePath(run.getRootDir());
    }

    /**
     * Resolves the {@link FilePath} of the sessions input file for this build,
     * applying the rules described on
     * {@link VManagerChartsJobProperty#getSessionSource()}:
     * <ul>
     *   <li>{@code PLUGIN} (default) — file is in the build workspace and is
     *       named per {@link #defaultSessionsFileName(Run)}.</li>
     *   <li>{@code FILE} with empty path — same as {@code PLUGIN}.</li>
     *   <li>{@code FILE} with a non-empty path — that path is used. Absolute
     *       paths are resolved on the same node/channel as the workspace.
     *       Relative paths are resolved against the workspace.</li>
     * </ul>
     *
     * @return the {@link FilePath} that <em>should</em> contain the session
     *         names (the file may or may not actually exist), or {@code null}
     *         if no workspace is available and no absolute path was given.
     */
    public static FilePath resolveSessionsInputFile(Run<?, ?> run,
                                                    FilePath workspace,
                                                    String sessionSource,
                                                    String userPath) {
        String defaultName = defaultSessionsFileName(run);
        String mode = sessionSource == null || sessionSource.isBlank()
                ? "PLUGIN" : sessionSource.trim().toUpperCase();

        // PLUGIN, or FILE with no user path → workspace/{default name}
        if ("PLUGIN".equals(mode)
                || userPath == null || userPath.trim().isEmpty()) {
            return workspace == null ? null : workspace.child(defaultName);
        }

        String p = userPath.trim();
        if (workspace != null) {
            if (isAbsolutePath(p)) {
                // Absolute on the same node as the workspace.
                return new FilePath(workspace.getChannel(), p);
            }
            return workspace.child(p);
        }
        // No workspace (e.g. pipeline) — fall back to local interpretation.
        return new FilePath(new java.io.File(p));
    }

    /**
     * Reads session names from the resolved file. Empty lines and lines
     * starting with {@code #} are skipped.
     *
     * @return an immutable list of session names (possibly empty); never {@code null}.
     */
    public static List<String> readSessionNames(FilePath file)
            throws IOException, InterruptedException {
        if (file == null || !file.exists()) {
            return Collections.emptyList();
        }
        String content = file.readToString();
        List<String> names = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            names.add(line);
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Convenience: resolve and read in one call.
     */
    public static List<String> readSessionNames(Run<?, ?> run,
                                                FilePath workspace,
                                                String sessionSource,
                                                String userPath)
            throws IOException, InterruptedException {
        return readSessionNames(
                resolveSessionsInputFile(run, workspace, sessionSource, userPath));
    }

    /**
     * Loose absolute-path detection that works for both POSIX
     * ({@code /foo/bar}) and Windows ({@code C:\foo} or {@code \\server\share}).
     */
    private static boolean isAbsolutePath(String p) {
        if (p == null || p.isEmpty()) return false;
        char c0 = p.charAt(0);
        if (c0 == '/' || c0 == '\\') return true;
        return p.length() >= 2 && p.charAt(1) == ':'
                && Character.isLetter(c0);
    }
}
