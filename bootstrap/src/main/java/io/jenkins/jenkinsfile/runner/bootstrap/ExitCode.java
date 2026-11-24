package io.jenkins.jenkinsfile.runner.bootstrap;

import java.util.Map;

/**
 * Centralized exit code table for Jenkinsfile Runner.
 *
 * <p>Exit codes are split into two non-overlapping ranges:</p>
 * <ul>
 *     <li><b>0-4</b> &mdash; build results. The values are the {@code hudson.model.Result} ordinals
 *     ({@code SUCCESS=0, UNSTABLE=1, FAILURE=2, NOT_BUILT=3, ABORTED=4}) and are kept stable for
 *     backward compatibility. Callers and the README already rely on them.</li>
 *     <li><b>65-75</b> &mdash; startup/invocation errors. Values are inspired by
 *     {@code sysexits.h} and deliberately avoid 1-4 (which collide with build results) as well
 *     as 255 (which is what a {@code -1} return value becomes at the process level).</li>
 * </ul>
 *
 * <p>This class does not depend on {@code jenkins-core}; build results are passed in by name
 * (see {@link #mapResultName(String)}), so the bootstrap module never references
 * {@code hudson.model.Result}.</p>
 *
 * <p>Signal handling is not customized: when the container terminates the JVM, the exit code is
 * determined by the runtime.</p>
 */
public final class ExitCode {

    // ---- Build results (0-4), stable and must not change ----

    /** Build result {@code SUCCESS}. */
    public static final int BUILD_SUCCESS = 0;
    /** Build result {@code UNSTABLE}. */
    public static final int BUILD_UNSTABLE = 1;
    /** Build result {@code FAILURE}. */
    public static final int BUILD_FAILURE = 2;
    /** Build result {@code NOT_BUILT}. */
    public static final int BUILD_NOT_BUILT = 3;
    /** Build result {@code ABORTED}. */
    public static final int BUILD_ABORTED = 4;

    // ---- Startup/invocation errors (65-75) ----

    /**
     * Command line or input file is invalid (EX_DATA).
     * Includes bad arguments, missing/invalid Jenkinsfile or plugins file, unsupported
     * Jenkins version, mutually exclusive options and lint failures.
     */
    public static final int INVALID_INPUT = 65;

    /** Any other unexpected error (EX_SOFTWARE). Also used when a build ends without a result. */
    public static final int UNEXPECTED_ERROR = 70;

    /** A build result name was received that does not match any known {@code hudson.model.Result}. */
    public static final int UNKNOWN_RESULT_NAME = 71;

    /**
     * Jenkins core or a plugin could not be downloaded, exploded or loaded (EX_TEMPFAIL).
     * Also used when the {@code --fail-on-plugin-error} option is enabled and one or more
     * plugins failed to load.
     */
    public static final int JENKINS_STARTUP_ERROR = 75;

    private static final Map<String, Integer> RESULT_ORDINALS = Map.of(
            "SUCCESS", BUILD_SUCCESS,
            "UNSTABLE", BUILD_UNSTABLE,
            "FAILURE", BUILD_FAILURE,
            "NOT_BUILT", BUILD_NOT_BUILT,
            "ABORTED", BUILD_ABORTED);

    private ExitCode() {
    }

    /**
     * Maps a {@code hudson.model.Result} name (passed as a string, to avoid a jenkins-core
     * dependency in the bootstrap module) to its process exit code.
     *
     * @param resultName the {@code Result.name()} value, or {@code null} when the build has
     *                   no result (e.g. it did not reach a terminal state)
     * @return the original ordinal (0-4) for the five known results;
     *         {@link #UNEXPECTED_ERROR} for {@code null};
     *         {@link #UNKNOWN_RESULT_NAME} for any other name. Never throws.
     */
    public static int mapResultName(String resultName) {
        if (resultName == null) {
            return UNEXPECTED_ERROR;
        }
        Integer ordinal = RESULT_ORDINALS.get(resultName);
        return ordinal != null ? ordinal : UNKNOWN_RESULT_NAME;
    }
}
