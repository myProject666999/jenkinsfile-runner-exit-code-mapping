package io.jenkins.jenkinsfile.runner.bootstrap;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;

/**
 * Table-driven tests for {@link ExitCode#mapResultName(String)} and the constant values.
 * No Jenkins instance is started and no {@code System.exit} is invoked.
 */
@RunWith(Enclosed.class)
public class ExitCodeTest {

    @RunWith(Parameterized.class)
    public static class MapResultNameTest {

        @Parameterized.Parameters(name = "mapResultName({0}) = {1}")
        public static Collection<Object[]> data() {
            return Arrays.asList(new Object[][]{
                    // The five known hudson.model.Result names keep their ordinal values (0-4)
                    {"SUCCESS", ExitCode.BUILD_SUCCESS},
                    {"UNSTABLE", ExitCode.BUILD_UNSTABLE},
                    {"FAILURE", ExitCode.BUILD_FAILURE},
                    {"NOT_BUILT", ExitCode.BUILD_NOT_BUILT},
                    {"ABORTED", ExitCode.BUILD_ABORTED},
                    // Unknown names get a dedicated code instead of throwing
                    {"SOME_FUTURE_RESULT", ExitCode.UNKNOWN_RESULT_NAME},
                    {"", ExitCode.UNKNOWN_RESULT_NAME},
                    // A null result (build ended without a result) is treated as an unexpected error
                    {null, ExitCode.UNEXPECTED_ERROR},
            });
        }

        private final String input;
        private final int expected;

        public MapResultNameTest(String input, int expected) {
            this.input = input;
            this.expected = expected;
        }

        @Test
        public void mapsResultName() {
            assertEquals(expected, ExitCode.mapResultName(input));
        }
    }

    public static class ConstantTest {

        @Test
        public void buildResultOrdinalsRemainStable() {
            assertEquals(0, ExitCode.BUILD_SUCCESS);
            assertEquals(1, ExitCode.BUILD_UNSTABLE);
            assertEquals(2, ExitCode.BUILD_FAILURE);
            assertEquals(3, ExitCode.BUILD_NOT_BUILT);
            assertEquals(4, ExitCode.BUILD_ABORTED);
        }

        @Test
        public void startupErrorCodesDoNotOverlapWithBuildResultsOr255() {
            assertEquals(65, ExitCode.INVALID_INPUT);
            assertEquals(70, ExitCode.UNEXPECTED_ERROR);
            assertEquals(71, ExitCode.UNKNOWN_RESULT_NAME);
            assertEquals(75, ExitCode.JENKINS_STARTUP_ERROR);
        }
    }
}
