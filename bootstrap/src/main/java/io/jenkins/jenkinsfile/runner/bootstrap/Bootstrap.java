package io.jenkins.jenkinsfile.runner.bootstrap;


import io.jenkins.jenkinsfile.runner.bootstrap.commands.JenkinsLauncherOptions;
import io.jenkins.jenkinsfile.runner.bootstrap.commands.LintJenkinsfileCommand;
import io.jenkins.jenkinsfile.runner.bootstrap.commands.PipelineRunOptions;
import io.jenkins.jenkinsfile.runner.bootstrap.commands.RunCLICommand;
import io.jenkins.jenkinsfile.runner.bootstrap.commands.RunJenkinsfileCommand;
import io.jenkins.jenkinsfile.runner.bootstrap.commands.VersionCommand;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the Jenkinsfile Runner execution.
 *
 * @author Kohsuke Kawaguchi
 * @author Oleg Nenashev
 */
@Command(name = "jenkinsfile-runner", versionProvider = Util.VersionProviderImpl.class, sortOptions = false, mixinStandardHelpOptions = true,
        subcommands = {RunJenkinsfileCommand.class, RunCLICommand.class, AutoComplete.GenerateCompletion.class, VersionCommand.class, CommandLine.HelpCommand.class,
            LintJenkinsfileCommand.class})
public class Bootstrap implements Callable<Integer> {

    @CommandLine.Mixin
    public PipelineRunOptions pipelineRunOptions;

    @CommandLine.Mixin
    public JenkinsLauncherOptions launcherOptions;

    /**
     * @deprecated Replaced by {@link RunCLICommand}
     */
    @Deprecated
    @CommandLine.Option(names = "--cli", hidden = true,
            description = "Launch interactive CLI")
    public boolean cliMode;

    public static void main(String[] args) {
        // break for attaching profiler
        if (Boolean.getBoolean("start.pause")) {
            System.console().readLine();
        }

        int exitCode = buildCommandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * Builds the {@link CommandLine} with the centralized exit-code mapping installed.
     * Extracted from {@link #main(String[])} so tests can execute the same configuration
     * without invoking {@link System#exit(int)}.
     */
    public static CommandLine buildCommandLine() {
        return new CommandLine(new Bootstrap())
                .setParameterExceptionHandler(new ParameterExceptionHandler())
                .setExecutionExceptionHandler(new ExecutionExceptionHandler());
    }

    /**
     * Executes {@link RunJenkinsfileCommand} by default
     */
    @Override
    public Integer call() {
        // TODO: Remove it: Compatibility mode for Docker images
        if (cliMode || System.getenv("FORCE_JENKINS_CLI") != null) {
            System.out.println("WARNING: Using the deprecated CLI mode. Use the 'cli' subcommand instead of passing the argument or an environment variable");
            RunCLICommand command = new RunCLICommand();
            command.launcherOptions = launcherOptions;
            try {
                command.postConstruct();
                return command.runJenkinsfileRunnerApp();
            } catch (RuntimeException e) {
                // Categorized exceptions (InvalidInputException, JenkinsStartupException) propagate as-is
                throw e;
            } catch (Throwable e) {
                // Anything else is reported as an unexpected error by the execution exception handler
                throw new RuntimeException(e);
            }
        }

        RunJenkinsfileCommand command = new RunJenkinsfileCommand();
        command.launcherOptions = launcherOptions;
        command.pipelineRunOptions = pipelineRunOptions;
        return command.call();
    }

    /**
     * Maps picocli parameter/parse errors (unknown options, missing values, type conversion
     * failures, ...) to {@link ExitCode#INVALID_INPUT}. Mirrors the default picocli output
     * (error message followed by the usage help of the offending command).
     */
    static final class ParameterExceptionHandler implements CommandLine.IParameterExceptionHandler {
        @Override
        public int handleParseException(CommandLine.ParameterException ex, String[] args) {
            CommandLine commandLine = ex.getCommandLine();
            PrintWriter err = commandLine.getErr();
            err.println(ex.getMessage());
            CommandLine.UnmatchedArgumentException.printSuggestions(ex, err);
            commandLine.usage(err, commandLine.getColorScheme());
            err.flush();
            return ExitCode.INVALID_INPUT;
        }
    }

    /**
     * Maps exceptions thrown while executing a command onto the centralized exit code table:
     * {@link InvalidInputException} &rarr; {@link ExitCode#INVALID_INPUT},
     * {@link JenkinsStartupException} &rarr; {@link ExitCode#JENKINS_STARTUP_ERROR},
     * anything else &rarr; {@link ExitCode#UNEXPECTED_ERROR}.
     */
    static final class ExecutionExceptionHandler implements CommandLine.IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof InvalidInputException) {
                    commandLine.getErr().println(cause.getMessage());
                    commandLine.getErr().flush();
                    return ExitCode.INVALID_INPUT;
                }
                if (cause instanceof JenkinsStartupException) {
                    cause.printStackTrace(commandLine.getErr());
                    commandLine.getErr().flush();
                    return ExitCode.JENKINS_STARTUP_ERROR;
                }
                cause = cause.getCause();
            }
            ex.printStackTrace(commandLine.getErr());
            commandLine.getErr().flush();
            return ExitCode.UNEXPECTED_ERROR;
        }
    }
}
