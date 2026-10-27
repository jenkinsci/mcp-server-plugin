package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestCrumbIssuerMcpExtension;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@ExtendWith(TestCrumbIssuerMcpExtension.class)
public class TestResultExtensionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * The {@code assert} is deliberately wrong for every fixture set used here: a passing assert
     * leaves the build UNSTABLE rather than FAILURE.
     */
    private static final String PIPELINE = """
            stage('first') {
              node {
                def results = junit(testResults: '*.xml')
                assert results.totalCount == 6
              }
            }
            """;

    private static Run<?, ?> buildWithTestReports(JenkinsRule jenkins, String... reportResources) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "singleStep");
        job.setDefinition(new CpsFlowDefinition(PIPELINE, true));

        FilePath workspace = Objects.requireNonNull(jenkins.jenkins.getWorkspaceFor(job));
        for (String reportResource : reportResources) {
            workspace
                    .child("test-result" + UUID.randomUUID() + ".xml")
                    .copyFrom(TestResultExtensionTest.class.getResource(reportResource));
        }

        Run<?, ?> run = jenkins.buildAndAssertStatus(Result.FAILURE, job);
        assertThat(run).isNotNull();
        assertThat(run.getAction(TestResultAction.class)).isNotNull();
        return run;
    }

    /** Calls a tool and returns the raw text content of the single response item. */
    private static String callTool(McpSyncClient client, String tool, Map<String, Object> arguments) {
        var response = client.callTool(new McpSchema.CallToolRequest(tool, arguments));

        assertThat(response.isError()).isFalse();
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).type()).isEqualTo("text");

        return ((McpSchema.TextContent) response.content().get(0)).text();
    }

    private static DocumentContext parse(String json) {
        return JsonPath.using(Configuration.defaultConfiguration()).parse(json);
    }

    @Test
    void testResolveSummary() {
        // no explicit summary: compact, unless the caller asked for the failing tests on their own
        assertThat(TestResultExtension.resolveSummary(null, null)).isTrue();
        assertThat(TestResultExtension.resolveSummary(null, false)).isTrue();
        assertThat(TestResultExtension.resolveSummary(null, true)).isFalse();
        // an explicit summary always wins
        assertThat(TestResultExtension.resolveSummary(true, null)).isTrue();
        assertThat(TestResultExtension.resolveSummary(true, true)).isTrue();
        assertThat(TestResultExtension.resolveSummary(false, null)).isFalse();
        assertThat(TestResultExtension.resolveSummary(false, true)).isFalse();
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsSummaryByDefault(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "junit-report-20090516.xml");
        TestResult testResult = run.getAction(TestResultAction.class).getResult();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName())));

            var result = documentContext.read("$.result", Map.class);
            assertThat(result).containsKey("TestResultAction").doesNotContainKey("TestResult");

            String summary = "$.result.TestResultSummary.";
            assertThat(documentContext.read(summary + "buildNumber", Integer.class))
                    .isEqualTo(run.getNumber());
            assertThat(documentContext.read(summary + "totalCount", Integer.class))
                    .isEqualTo(testResult.getTotalCount());
            assertThat(documentContext.read(summary + "failCount", Integer.class))
                    .isEqualTo(testResult.getFailCount());
            assertThat(documentContext.read(summary + "skipCount", Integer.class))
                    .isEqualTo(testResult.getSkipCount());
            assertThat(documentContext.read(summary + "passCount", Integer.class))
                    .isEqualTo(testResult.getPassCount());
            assertThat(documentContext.read(summary + "flakyTestCount", Integer.class))
                    .isZero();

            assertThat((List<Object>) documentContext.read(summary + "failedTests"))
                    .hasSize(testResult.getFailedTests().size())
                    .hasSize(3);
            assertThat((List<Object>) documentContext.read(summary + "failedTests[*].className"))
                    .allSatisfy(className -> assertThat(className).isNotNull());
            assertThat((List<Object>) documentContext.read(summary + "failedTests[*].testName"))
                    .allSatisfy(testName -> assertThat(testName).isNotNull());

            // what issue #283 is about: none of the heavy fields come along
            assertThat((List<Object>) documentContext.read("$..stdout")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..stderr")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..errorStackTrace"))
                    .isEmpty();
            assertThat((List<Object>) documentContext.read("$..suites")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..properties")).isEmpty();
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsSummaryIsMuchSmallerThanFull(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "junit-report-20090516.xml");
        String jobFullName = run.getParent().getFullName();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            int summaryLength = callTool(client, "getTestResults", Map.of("jobFullName", jobFullName))
                    .length();
            int fullLength = callTool(client, "getTestResults", Map.of("jobFullName", jobFullName, "summary", false))
                    .length();

            // a loose bound, so a junit plugin upgrade cannot make this flaky
            assertThat(summaryLength).isLessThan(fullLength / 4);
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsSummaryFallsBackToStackTraceWhenNoMessage(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        // the first failing case of this report is a bare <error type="java.lang.NullPointerException">
        // with no message attribute at all
        Run<?, ?> run = buildWithTestReports(jenkins, "junit-report-20090516.xml");

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName())));

            List<Object> messages = documentContext.read(
                    "$.result.TestResultSummary.failedTests[?(@.testName == 'testGetVendorFirmKeyForVendorRep')].errorDetails");
            assertThat(messages)
                    .singleElement()
                    .satisfies(message -> assertThat((String) message).startsWith("java.lang.NullPointerException"));
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsSummaryCutsLongErrorDetails(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "TEST-org.test.LongMessageTest.xml");

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName())));

            List<String> messages = documentContext.read("$.result.TestResultSummary.failedTests[*].errorDetails");
            assertThat(messages)
                    .hasSize(2)
                    .allSatisfy(message -> assertThat(message).hasSizeLessThanOrEqualTo(512));

            // the long single-line message is cut, the multi-line one fits and keeps its newlines
            assertThat(messages)
                    .anySatisfy(message -> assertThat(message).hasSize(512).endsWith("..."))
                    .anySatisfy(message -> assertThat(message)
                            .contains("expected: 3")
                            .contains("but was: 4")
                            .contains("\n"));
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsExplicitSummaryWithOnlyFailingTests(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "junit-report-20090516.xml");

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName(), "onlyFailingTests", true, "summary", true)));

            // an explicit summary wins; onlyFailingTests has nothing left to do
            var result = documentContext.read("$.result", Map.class);
            assertThat(result).containsKey("TestResultSummary").doesNotContainKey("failingTests");
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsFullMode(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "junit-report-20090516.xml");
        TestResult testResult = run.getAction(TestResultAction.class).getResult();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName(), "summary", false)));

            var result = documentContext.read("$.result", Map.class);

            Object testResultAction = result.get("TestResultAction");
            assertThat(testResultAction).isNotNull();

            Object testResultRaw = result.get("TestResult");
            assertThat(testResultRaw).isNotNull();
            documentContext = parse(OBJECT_MAPPER.writeValueAsString(testResultRaw));
            assertThat(documentContext.read("$.failCount", Integer.class)).isEqualTo(testResult.getFailCount());
            assertThat(documentContext.read("$.passCount", Integer.class)).isEqualTo(testResult.getPassCount());

            assertThat((List<Object>) documentContext.read("$..suites[*].nodeId"))
                    .isEmpty();
            assertThat((List<Object>) documentContext.read("$..suites[*].enclosingBlocks"))
                    .isEmpty();
            assertThat((List<Object>) documentContext.read("$..suites[*].enclosingBlockNames"))
                    .isEmpty();

            assertThat((List<Object>) documentContext.read("$..cases[*].errorDetails"))
                    .anySatisfy(v -> assertThat(v).isNotNull());

            List<Object> list = documentContext.read("$..suites");
            assertThat(list).size().isEqualTo(testResult.getSuites().size());

            list = documentContext.read("$..suites..cases..className");
            assertThat(list)
                    .size()
                    .isEqualTo(testResult.getSuites().stream()
                            .findFirst()
                            .get()
                            .getCases()
                            .size());
        }
    }

    @McpClientTest
    void testMcpToolCallGetTestResultsFailingOnly(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        Run<?, ?> run = buildWithTestReports(jenkins, "TEST-org.test.DefaultTest.xml", "junit-report-20090516.xml");
        TestResult testResult = run.getAction(TestResultAction.class).getResult();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getTestResults",
                    Map.of("jobFullName", run.getParent().getFullName(), "onlyFailingTests", true)));

            var result = documentContext.read("$.result", Map.class);

            Object testResultAction = result.get("TestResultAction");
            assertThat(testResultAction).isNotNull();

            Object testResultRaw = result.get("failingTests");
            assertThat(testResultRaw).isNotNull();

            // onlyFailingTests on its own is the documented way to drill down, so it must not summarise
            assertThat(result).doesNotContainKey("TestResultSummary");
            assertThat((List<Object>) documentContext.read("$..failingTests[*].errorStackTrace"))
                    .anySatisfy(v -> assertThat(v).isNotNull());

            List<Object> list = documentContext.read("$..failingTests..className");
            assertThat(list).size().isEqualTo(testResult.getFailedTests().size());
            assertThat(list).size().isEqualTo(3); // There are 3 failing tests in the two reports
        }
    }

    @McpClientTest
    void testMcpToolCallGetFlakyFailuresSummaryByDefault(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {

        Run<?, ?> run = buildWithTestReports(
                jenkins,
                "TEST-org.test.DefaultTest.xml",
                "junit-report-20090516.xml",
                "TEST-io.olamy.FlakyTest.xml",
                "TEST-org.foo.FlakyTest.xml");

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getFlakyFailures",
                    Map.of("jobFullName", run.getParent().getFullName())));

            var result = documentContext.read("$.result", Map.class);
            assertThat(result).containsKey("TestResultAction").doesNotContainKey("TestResultWithFlakyFailures");

            String summary = "$.result.FlakyFailuresSummary.";
            assertThat(documentContext.read(summary + "buildNumber", Integer.class))
                    .isEqualTo(run.getNumber());
            assertThat(documentContext.read(summary + "flakyTestCount", Integer.class))
                    .isEqualTo(2); // one flaky test in each of the two flaky reports
            assertThat(documentContext.read(summary + "flakyFailureCount", Integer.class))
                    .isEqualTo(4); // two flaky attempts each

            assertThat((List<Object>) documentContext.read("$..flakyFailures..message"))
                    .hasSize(4);
            assertThat((List<Object>) documentContext.read("$..flakyTests[*].className"))
                    .containsExactlyInAnyOrder("io.olamy.FlakyTest", "org.foo.FlakyTest");

            assertThat((List<Object>) documentContext.read("$..stackTrace")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..stdout")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..stderr")).isEmpty();
            assertThat((List<Object>) documentContext.read("$..properties")).isEmpty();
        }
    }

    @McpClientTest
    void testMcpToolCallGetFlakyFailuresFullMode(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        Run<?, ?> run = buildWithTestReports(
                jenkins,
                "TEST-org.test.DefaultTest.xml",
                "junit-report-20090516.xml",
                "TEST-io.olamy.FlakyTest.xml",
                "TEST-org.foo.FlakyTest.xml");
        TestResult testResult = run.getAction(TestResultAction.class).getResult();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            DocumentContext documentContext = parse(callTool(
                    client,
                    "getFlakyFailures",
                    Map.of("jobFullName", run.getParent().getFullName(), "summary", false)));

            var result = documentContext.read("$.result", Map.class);

            Object testResultAction = result.get("TestResultAction");
            assertThat(testResultAction).isNotNull();

            Object testResultRaw = result.get("TestResultWithFlakyFailures");
            assertThat(testResultRaw).isNotNull();

            List<Object> list = documentContext.read("$..flakyFailures..message");
            assertThat(list)
                    .size()
                    .isEqualTo(testResult.getSuites().stream()
                            .map(SuiteResult::getCases)
                            .flatMap(Collection::stream)
                            .map(CaseResult::getFlakyFailures)
                            .filter(failures -> !failures.isEmpty())
                            .mapToLong(Collection::size)
                            .sum());
            assertThat(list).size().isEqualTo(4); // There are 4 flaky failures in the four reports

            assertThat((List<Object>) documentContext.read("$..suites[*].nodeId"))
                    .isEmpty();
            assertThat((List<Object>) documentContext.read("$..suites[*].enclosingBlocks"))
                    .isEmpty();
            assertThat((List<Object>) documentContext.read("$..suites[*].enclosingBlockNames"))
                    .isEmpty();
        }
    }
}
