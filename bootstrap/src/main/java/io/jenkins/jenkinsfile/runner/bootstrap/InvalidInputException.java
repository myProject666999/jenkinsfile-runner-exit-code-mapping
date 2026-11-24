package io.jenkins.jenkinsfile.runner.bootstrap;

/**
 * Thrown when the command line, an input file or a pre-flight validation is invalid.
 *
 * <p>Maps to {@link ExitCode#INVALID_INPUT}. This is a runtime exception so it can propagate
 * through picocli's {@code Callable} contract; the registered execution exception handler
 * turns it into the corresponding exit code instead of crashing with the generic code 1.</p>
 */
public class InvalidInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidInputException(String message) {
        super(message);
    }

    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
