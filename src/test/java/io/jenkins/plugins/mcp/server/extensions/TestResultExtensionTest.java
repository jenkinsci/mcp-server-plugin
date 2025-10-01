package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.FilePath;
import hudson.model.Result;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class TestResultExtensionTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @McpClientTest
    void testMcpToolCallGetTestResults(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        WorkflowJob j = jenkins.createProject(WorkflowJob.class, "singleStep");
        j.setDefinition(new CpsFlowDefinition(
                """
                        stage('first') {
                          node {
                            def results = junit(testResults: '*.xml')
                            assert results.totalCount == 6
                          }
                        }
                        """,
                true));
        FilePath ws = jenkins.jenkins.getWorkspaceFor(j);
        FilePath testFile = Objects.requireNonNull(ws).child("test-result.xml");
        testFile.copyFrom(TestResultExtensionTest.class.getResource("junit-report-20090516.xml"));

        Run run = jenkins.buildAndAssertStatus(Result.FAILURE, j);
        assertThat(run).isNotNull();
        assertThat(run.getAction(TestResultAction.class)).isNotNull();

        TestResult testResult = run.getAction(TestResultAction.class).getResult();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Call getJobScm tool
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getTestResults", Map.of("jobFullName", j.getFullName()));

            var response = client.callTool(request);

            // Assert response
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                ;
            });

            Map<String, Object> result = OBJECT_MAPPER.readValue(
                    ((McpSchema.TextContent) response.content().get(0)).text(), Map.class);

            Object testResultAction = result.get("TestResultAction");
            assertThat(testResultAction).isNotNull();

            Object testResultRaw = result.get("TestResult");
            assertThat(testResultRaw).isNotNull();
            Configuration conf = Configuration.defaultConfiguration();
            String rawJson = ((McpSchema.TextContent) response.content().get(0)).text();
            //            System.out.println(rawJson);
            DocumentContext documentContext = JsonPath.using(conf).parse(rawJson);
            assertThat(documentContext.read("$.TestResult.failCount", Integer.class))
                    .isEqualTo(testResult.getFailCount());
            assertThat(documentContext.read("$.TestResult.passCount", Integer.class))
                    .isEqualTo(testResult.getPassCount());

            //            documentContext = JsonPath.using(conf).parse(rawJson);
            //            conf = conf.addOptions(Option.ALWAYS_RETURN_LIST);
            List<Object> list = documentContext.read("$.TestResult..suites");
            assertThat(list).size().isEqualTo(testResult.getSuites().size());

            list = documentContext.read("$.TestResult..suites..cases..className");
            assertThat(list)
                    .size()
                    .isEqualTo(testResult.getSuites().stream()
                            .findFirst()
                            .get()
                            .getCases()
                            .size());
        }
    }
}
