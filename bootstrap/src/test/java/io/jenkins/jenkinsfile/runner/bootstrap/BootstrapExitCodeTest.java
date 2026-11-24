package io.jenkins.jenkinsfile.runner.bootstrap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the centralized exit-code mapping installed by {@link Bootstrap#buildCommandLine()}
 * via picocli's {@code IExecutionExceptionHandler} and {@code IParameterExceptionHandler}.
 *
 * <p>A fake {@link Callable} subcommand throws configured exceptions; no Jenkins instance is
 * started and {@code System.exit} is never called.</p>
 */
public class BootstrapExitCodeTest {

    private static final String FAILING_SUBCOMMAND = "failing";

    private PrintStream originalOut;
    private PrintStream originalErr;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @Before
    public void redirectStreams() {
        originalOut = System.out;
        originalErr = System.err;
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @After
    public void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @CommandLine.Command(name = FAILING_SUBCOMMAND)
    public static class FailingCommand implements Callable<Integer> {

        static volatile RuntimeException toThrow;

        @Override
        public Integer call() {
            throw toThrow;
        }
    }

    private int executeFailing(RuntimeException exception) {
        FailingCommand.toThrow = exception;
        CommandLine commandLine = Bootstrap.buildCommandLine()
                .addSubcommand(FAILING_SUBCOMMAND, new FailingCommand());
        return commandLine.execute(FAILING_SUBCOMMAND);
    }

    @Test
    public void invalidInputExceptionMapsTo65() {
        int exitCode = executeFailing(new InvalidInputException("bad input"));
        assertEquals(ExitCode.INVALID_INPUT, exitCode);
    }

    @Test
    public void jenkinsStartupExceptionMapsTo75() {
        int exitCode = executeFailing(new JenkinsStartupException("plugin exploded"));
        assertEquals(ExitCode.JENKINS_STARTUP_ERROR, exitCode);
    }

    @Test
    public void unexpectedRuntimeExceptionMapsTo70() {
        int exitCode = executeFailing(new RuntimeException("boom"));
        assertEquals(ExitCode.UNEXPECTED_ERROR, exitCode);
    }

    @Test
    public void categorizedExceptionWrappedInRuntimeExceptionIsStillClassified() {
        int exitCode = executeFailing(new RuntimeException(new JenkinsStartupException("download failed")));
        assertEquals(ExitCode.JENKINS_STARTUP_ERROR, exitCode);
    }

    @Test
    public void unknownOptionIsAnInvalidInputError() {
        CommandLine commandLine = Bootstrap.buildCommandLine();
        int exitCode = commandLine.execute("--this-option-does-not-exist");
        assertEquals(ExitCode.INVALID_INPUT, exitCode);
    }

    @Test
    public void missingJenkinsfileIsAnInvalidInputError() {
        CommandLine commandLine = Bootstrap.buildCommandLine();
        // Validation runs before any Jenkins/WAR resolution; the missing Jenkinsfile is reported first
        int exitCode = commandLine.execute("run", "--file", "/path/that/does/not/exist/Jenkinsfile");
        assertEquals(ExitCode.INVALID_INPUT, exitCode);
    }
}
