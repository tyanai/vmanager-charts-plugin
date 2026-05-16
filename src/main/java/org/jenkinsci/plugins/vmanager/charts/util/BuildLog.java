package org.jenkinsci.plugins.vmanager.charts.util;

import hudson.model.TaskListener;

/**
 * Build-console output gating utility.
 *
 * <p>Most of the {@code [vManager Charts]} build-console lines (REST URLs,
 * payloads, response statuses, per-session listings, summary printfs, etc.)
 * are only useful when diagnosing an issue. By default the plugin should be
 * quiet: only WARNING / error lines should appear in a job's console output.
 *
 * <p>This helper exposes a thread-local "verbose" flag that
 * {@link org.jenkinsci.plugins.vmanager.charts.CustomMetricsRunListener}
 * sets at the start of each build (from
 * {@link org.jenkinsci.plugins.vmanager.charts.VManagerChartsJobProperty#isVerboseLogging()})
 * and clears in a {@code finally} block. Any code reachable from
 * {@code onCompleted} can call {@link #trace(TaskListener, String)} to emit
 * a line that is suppressed when verbose is off.
 *
 * <p>WARNING and error lines should be printed directly via
 * {@code listener.getLogger().println(...)} — they always appear regardless
 * of the verbose flag.
 */
public final class BuildLog {

    private static final ThreadLocal<Boolean> VERBOSE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private BuildLog() { /* no instances */ }

    /** Sets the verbose flag for the current thread. */
    public static void setVerbose(boolean verbose) {
        VERBOSE.set(verbose);
    }

    /** Clears the verbose flag for the current thread (call in finally). */
    public static void clear() {
        VERBOSE.remove();
    }

    /** Whether verbose console output is currently enabled. */
    public static boolean isVerbose() {
        return Boolean.TRUE.equals(VERBOSE.get());
    }

    /**
     * Prints {@code msg} to the build's console only when the verbose flag
     * is enabled and the listener is non-null. Use this for chatty
     * diagnostic lines (REST URLs, payloads, per-row summaries, etc.).
     */
    public static void trace(TaskListener listener, String msg) {
        if (listener != null && isVerbose()) {
            listener.getLogger().println(msg);
        }
    }
}
