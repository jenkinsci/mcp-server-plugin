package io.jenkins.plugins.mcp.server.extensions;

import com.fasterxml.jackson.annotation.JsonInclude;
import hudson.model.Run;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
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
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.variant.OptionalExtension;

@OptionalExtension(requirePlugins = "junit")
public class TestResultExtension implements McpServerExtension {

    /**
     * Failure messages in a summary are cut to this many characters. A single assertion message can
     * otherwise be hundreds of kilobytes on its own.
     */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    @Tool(
            description =
                    "Retrieves the test results of a Jenkins build (a given build number, or the last build). "
                            + "Returns a compact summary by default: the pass/fail/skip counts plus one entry per failing test. "
                            + "Pass summary=false, or onlyFailingTests=true, when you need stdout, stderr and error stack traces.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
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
                                    "Return only the failing tests instead of every test. This only affects the full "
                                            + "(non-summary) response, because a summary always lists just the failing tests. "
                                            + "Passing true without an explicit summary resolves summary to false, so you get "
                                            + "the complete junit records - stdout, stderr and stack traces - for the failing tests.",
                            required = false)
                    Boolean onlyFailingTests,
            @Nullable
                    @ToolParam(
                            description =
                                    "Return a compact summary instead of the full junit records. Defaults to true: you get "
                                            + "buildNumber, totalCount/failCount/skipCount/passCount, and one entry per failing "
                                            + "test with its class name, test name, failure message (cut to 512 characters; the "
                                            + "head of the stack trace is used when the test reported no message) and age (number "
                                            + "of consecutive builds it has been failing). Use this to triage a build. Set it to "
                                            + "false to get the complete junit data including stdout, stderr and error stack "
                                            + "traces - that can be several megabytes for a build with many failures, so only do "
                                            + "it for a build whose detail you already know you need. If you omit this parameter "
                                            + "and pass onlyFailingTests=true, summary resolves to false, because asking for the "
                                            + "failing tests alone is treated as asking for their full detail.",
                            required = false)
                    Boolean summary) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            var testResultAction = run.get().getAction(TestResultAction.class);
            if (testResultAction != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("TestResultAction", testResultAction);
                var result = testResultAction.getResult();
                if (result != null) {
                    if (resolveSummary(summary, onlyFailingTests)) {
                        response.put(
                                "TestResultSummary",
                                toTestResultSummary(run.get().getNumber(), result));
                    } else if (Boolean.TRUE.equals(onlyFailingTests)) {
                        response.put("failingTests", result.getFailedTests());
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
            description =
                    "Retrieves the flaky failures of a Jenkins build if any were found - tests that failed and then "
                            + "passed on a retry. Returns a compact summary by default: class name, test name and the message "
                            + "and type of each flaky attempt. Pass summary=false when you need each attempt's stack trace, "
                            + "stdout and stderr.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getFlakyFailures(
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
                                    "Return a compact summary instead of the full junit records. Defaults to true: you get "
                                            + "buildNumber, the number of flaky tests and flaky attempts, and one entry per test "
                                            + "with flaky failures carrying its class name, test name and, per flaky attempt, the "
                                            + "failure message (cut to 512 characters; the head of the stack trace is used when the "
                                            + "attempt reported no message) and the exception type. Set it to false to get the "
                                            + "complete flaky-failure data including each attempt's stack trace, stdout and stderr - "
                                            + "that can be several megabytes, so only do it for a build whose detail you already "
                                            + "know you need.",
                            required = false)
                    Boolean summary) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            var testResultAction = run.get().getAction(TestResultAction.class);
            if (testResultAction != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("TestResultAction", testResultAction);
                var result = testResultAction.getResult();
                if (result != null) {
                    if (resolveSummary(summary, null)) {
                        response.put(
                                "FlakyFailuresSummary",
                                toFlakyFailuresSummary(run.get().getNumber(), result));
                    } else {
                        var flakyFailures = flakyCases(result)
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
                }
                return response;
            }
        }
        return Map.of();
    }

    /**
     * Decides whether to answer with a summary. An explicit {@code summary} always wins. Otherwise
     * {@code onlyFailingTests=true} reads as "give me the detail of the failures" - it is the
     * documented way to drill down after a summary - so it turns the summary off.
     */
    static boolean resolveSummary(@Nullable Boolean summary, @Nullable Boolean onlyFailingTests) {
        return summary != null ? summary : !Boolean.TRUE.equals(onlyFailingTests);
    }

    /**
     * The message a summary shows for one failure, cut to {@link #MAX_ERROR_MESSAGE_LENGTH}. Tests
     * can fail without a message at all (a bare {@code <error type="..."/>}), and an entry with no
     * message is useless to read, so fall back to the head of the stack trace - which carries the
     * exception and its first few frames. Only summaries do this; the full modes keep every byte.
     */
    private static String failureSummaryMessage(String errorDetails, String errorStackTrace) {
        String message = StringUtils.isBlank(errorDetails) ? errorStackTrace : errorDetails;
        return StringUtils.abbreviate(message, MAX_ERROR_MESSAGE_LENGTH);
    }

    private static TestResultSummary toTestResultSummary(int buildNumber, TestResult result) {
        var failedTests = result.getFailedTests().stream()
                .map(caseResult -> new FailedTestSummary(
                        caseResult.getClassName(),
                        caseResult.getName(),
                        failureSummaryMessage(caseResult.getErrorDetails(), caseResult.getErrorStackTrace()),
                        caseResult.getAge()))
                .toList();
        return new TestResultSummary(
                buildNumber,
                result.getTotalCount(),
                result.getFailCount(),
                result.getSkipCount(),
                result.getPassCount(),
                (int) flakyCases(result).count(),
                failedTests);
    }

    private static FlakyFailuresSummary toFlakyFailuresSummary(int buildNumber, TestResult result) {
        var flakyTests = flakyCases(result)
                .map(caseResult -> new FlakyTestSummary(
                        caseResult.getClassName(),
                        caseResult.getName(),
                        caseResult.getFlakyFailures().stream()
                                .map(flakyFailure -> new FlakyAttemptSummary(
                                        failureSummaryMessage(flakyFailure.message(), flakyFailure.stackTrace()),
                                        flakyFailure.type()))
                                .toList()))
                .toList();
        int flakyFailureCount = flakyTests.stream()
                .mapToInt(flakyTest -> flakyTest.flakyFailures().size())
                .sum();
        return new FlakyFailuresSummary(buildNumber, flakyTests.size(), flakyFailureCount, flakyTests);
    }

    private static Stream<hudson.tasks.junit.CaseResult> flakyCases(TestResult result) {
        return result.getSuites().stream()
                .map(SuiteResult::getCases)
                .flatMap(Collection::stream)
                .filter(caseResult -> !caseResult.getFlakyFailures().isEmpty());
    }

    @Getter
    @Setter
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
        private Map<String, String> properties;
        private List<FlakyFailure> flakyFailures;

        public CaseResult(
                float duration,
                String className,
                String testName,
                String skippedMessage,
                boolean skipped,
                String errorStackTrace,
                String errorDetails,
                int failedSince,
                String stdout,
                String stderr,
                Map<String, String> properties,
                List<FlakyFailure> flakyFailures) {
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
    }

    @Getter
    @Setter
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
    }

    /** Compact answer for {@code getTestResults}: the counts, plus one entry per failing test. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResultSummary(
            int buildNumber,
            int totalCount,
            int failCount,
            int skipCount,
            int passCount,
            int flakyTestCount,
            List<FailedTestSummary> failedTests) {}

    /**
     * One failing test in a summary. {@code age} is the number of consecutive builds it has been
     * failing, so 1 means it broke in this build.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FailedTestSummary(String className, String testName, String errorDetails, int age) {}

    /** Compact answer for {@code getFlakyFailures}. */
    public record FlakyFailuresSummary(
            int buildNumber, int flakyTestCount, int flakyFailureCount, List<FlakyTestSummary> flakyTests) {}

    public record FlakyTestSummary(String className, String testName, List<FlakyAttemptSummary> flakyFailures) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FlakyAttemptSummary(String message, String type) {}
}
