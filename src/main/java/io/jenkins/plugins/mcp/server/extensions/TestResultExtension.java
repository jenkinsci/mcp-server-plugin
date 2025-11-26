package io.jenkins.plugins.mcp.server.extensions;

import hudson.model.Run;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil;
import jakarta.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jenkinsci.plugins.variant.OptionalExtension;

@OptionalExtension(requirePlugins = "junit")
public class TestResultExtension implements McpServerExtension {

    @Tool(
            description = "Retrieves the test results associated to a Jenkins build",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Map<String, Object> getTestResults(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description =
                                    "Build number (optional, if not provided, returns the test results for last build)",
                            required = false)
                    Integer buildNumber,
            @Nullable
                    @ToolParam(
                            description =
                                    "To return only failing tests and not all test (to help reducing the size of returned data)",
                            required = false)
                    Boolean onlyFailingTests) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            var testResultAction = run.get().getAction(TestResultAction.class);
            if (testResultAction != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("TestResultAction", testResultAction);
                var result = testResultAction.getResult();
                if (result != null) {
                    if (Boolean.TRUE.equals(onlyFailingTests)) {
                        var failingTests = result.getTestResult().getSuites().stream()
                                .flatMap(suiteResult -> suiteResult.getCases().stream())
                                .filter(caseResult -> caseResult.getStatus() == CaseResult.Status.FAILED)
                                .toList();
                        response.put("failingTests", failingTests);
                    } else {
                        response.put("TestResult", result);
                    }
                }
                return response;
            }
        }
        return Map.of();
    }

    @Tool(
            description = "Retrieves the flaky failures associated to a Jenkins build if any found",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Map<String, Object> getFlakyFailures(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description =
                                    "Build number (optional, if not provided, returns the test results for last build)",
                            required = false)
                    Integer buildNumber) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            var testResultAction = run.get().getAction(TestResultAction.class);
            if (testResultAction != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("TestResultAction", testResultAction);
                var result = testResultAction.getResult();
                if (result != null) {
                    var flakyFailures = result.getTestResult().getSuites().stream()
                            .map(SuiteResult::getCases)
                            .flatMap(Collection::stream)
                            .map(CaseResult::getFlakyFailures)
                            .filter(failures -> !failures.isEmpty())
                            .flatMap(Collection::stream)
                            // well record doesn't look to be supported so map to a standard java bean
                            .map(f -> new FlakyFailure(f.message(), f.type(), f.stackTrace(), f.stdout(), f.stderr()))
                            .toList();
                    response.put("flakyFailures", flakyFailures);
                }
                return response;
            }
        }
        return Map.of();
    }

    public static final class FlakyFailure {
        private final String message;
        private final String type;
        private final String stackTrace;
        private final String stdout;
        private final String stderr;

        public FlakyFailure(String message, String type, String stackTrace, String stdout, String stderr) {
            this.message = message;
            this.type = type;
            this.stackTrace = stackTrace;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public String getMessage() {
            return message;
        }

        public String getType() {
            return type;
        }

        public String getStackTrace() {
            return stackTrace;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }
    }
}
