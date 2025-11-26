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
import java.util.List;
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
                                .filter(caseResult -> caseResult.getStatus() == hudson.tasks.junit.CaseResult.Status.FAILED)
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
                            .filter(caseResult -> !caseResult.getFlakyFailures().isEmpty())
                            .map(caseResult -> new CaseResult(
                                    caseResult.getDuration(),
                                    caseResult.getClassName(),
                                    caseResult.getName(),
                                    caseResult.getSkippedMessage(),
                                    caseResult.isSkipped(),
                                    caseResult.getErrorStackTrace(),
                                    caseResult.getErrorDetails(),
                                    caseResult.getFailedSince(),
                                    caseResult.getStdout(),
                                    caseResult.getStderr(),
                                    caseResult.getProperties(),
                                    caseResult.getFlakyFailures().stream()
                                            .map(flakyFailure -> new FlakyFailure(
                                                    flakyFailure.message(),
                                                    flakyFailure.type(),
                                                    flakyFailure.stackTrace(),
                                                    flakyFailure.stdout(),
                                                    flakyFailure.stderr()))
                                            .toList()))
                            .toList();
                    response.put("TestResultWithFlakyFailures", flakyFailures);
                }
                return response;
            }
        }
        return Map.of();
    }


    public static final class CaseResult {
        private float duration;
        private String className;
        private String testName;
        private String skippedMessage;
        private boolean skipped;
        private String errorStackTrace;
        private String errorDetails;
        private int failedSince;
        private String stdout;
        private String stderr;
        private Map<String,String> properties;
        private List<FlakyFailure> flakyFailures;

        public CaseResult(float duration, String className, String testName, String skippedMessage, boolean skipped, String errorStackTrace, String errorDetails, int failedSince, String stdout, String stderr, Map<String, String> properties, List<FlakyFailure> flakyFailures) {
            this.duration = duration;
            this.className = className;
            this.testName = testName;
            this.skippedMessage = skippedMessage;
            this.skipped = skipped;
            this.errorStackTrace = errorStackTrace;
            this.errorDetails = errorDetails;
            this.failedSince = failedSince;
            this.stdout = stdout;
            this.stderr = stderr;
            this.properties = properties;
            this.flakyFailures = flakyFailures;
        }

        public float getDuration() {
            return duration;
        }

        public String getClassName() {
            return className;
        }

        public String getTestName() {
            return testName;
        }

        public String getSkippedMessage() {
            return skippedMessage;
        }

        public boolean isSkipped() {
            return skipped;
        }

        public String getErrorStackTrace() {
            return errorStackTrace;
        }

        public String getErrorDetails() {
            return errorDetails;
        }

        public int getFailedSince() {
            return failedSince;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public List<FlakyFailure> getFlakyFailures() {
            return flakyFailures;
        }
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
