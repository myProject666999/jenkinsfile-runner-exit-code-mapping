package io.jenkins.jenkinsfile.runner.bootstrap;

/**
 * Thrown when Jenkins core or a plugin cannot be downloaded, exploded or loaded.
 *
 * <p>Maps to {@link ExitCode#JENKINS_STARTUP_ERROR}. This is a runtime exception so it can
 * propagate through picocli's {@code Callable} contract; the registered execution exception
 * handler turns it into the corresponding exit code.</p>
 */
public class JenkinsStartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JenkinsStartupException(String message) {
        super(message);
    }

    public JenkinsStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
