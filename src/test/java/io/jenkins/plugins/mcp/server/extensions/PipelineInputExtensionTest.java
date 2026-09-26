package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.junit.TestUtils.MIN_1;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.model.Result;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.tool.ToolResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PipelineInputExtensionTest {

    private static final String PIPELINE_WITH_PARAMS =
            "pipeline { agent any; stages { stage('S') { steps {"
                    + " input(id: 'deploy', message: 'Deploy?',"
                    + " parameters: [string(name: 'env', defaultValue: 'staging', description: 'Target env')])"
                    + " } } } }";

    private static final String PIPELINE_APPROVAL_ONLY =
            "pipeline { agent any; stages { stage('S') { steps {"
                    + " input(id: 'gate', message: 'Approve?')"
                    + " } } } }";

    @McpClientTest
    void testGetPendingInputs_withBuildNumber(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "input-build-scoped");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_WITH_PARAMS, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "getPendingInputs", Map.of("jobFullName", "input-build-scoped", "buildNumber", run.getNumber())));

            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                List<Map<String, Object>> inputs = doc.read("$.result");
                assertThat(inputs).hasSize(1);
                // pipeline-input-step capitalizes the configured id (InputStep#setId)
                assertThat(inputs.get(0)).containsEntry("inputId", "Deploy")
                        .containsEntry("message", "Deploy?")
                        .containsKey("parameters");
            });
        }

        abortRun(run);
    }

    @McpClientTest
    void testGetPendingInputs_withoutBuildNumber_findsRunningBuild(
            JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "input-job-scoped");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_APPROVAL_ONLY, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "getPendingInputs", Map.of("jobFullName", "input-job-scoped")));

            assertThat(response.isError()).isFalse();
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                List<Map<String, Object>> inputs = doc.read("$.result");
                assertThat(inputs).hasSize(1);
                // pipeline-input-step capitalizes the configured id (InputStep#setId)
                assertThat(inputs.get(0))
                        .containsEntry("inputId", "Gate")
                        .containsEntry("buildNumber", run.getNumber());
            });
        }

        abortRun(run);
    }

    @McpClientTest
    void testGetPendingInputs_noPendingInput_returnsEmptyList(
            JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "no-pending-input");
        project.setDefinition(new CpsFlowDefinition("pipeline { agent any; stages { stage('S') { steps { echo 'hi' } } } }", true));
        project.scheduleBuild2(0).get();

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "getPendingInputs", Map.of("jobFullName", "no-pending-input")));

            assertThat(response.isError()).isFalse();
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                // an empty collection result omits the "result" field entirely; see McpToolWrapper#toMcpResult
                String message = doc.read("$.message");
                assertThat(message).isEqualTo(ToolResponse.NO_DATA_MSG);
            });
        }
    }

    @McpClientTest
    void testSubmitPipelineInput_withParameters_resumesBuild(
            JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "submit-with-params");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_WITH_PARAMS, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "submitPipelineInput",
                    Map.of(
                            "jobFullName", "submit-with-params",
                            "buildNumber", run.getNumber(),
                            "inputId", "deploy",
                            "parameters", Map.of("env", "production"))));

            assertThat(response.isError()).isFalse();
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                String result = doc.read("$.result");
                assertThat(result).contains("deploy").contains(String.valueOf(run.getNumber()));
            });
        }

        await().atMost(MIN_1, MILLISECONDS).until(() -> !run.isBuilding());
        assertThat(run.getResult()).isEqualTo(Result.SUCCESS);
    }

    @McpClientTest
    void testSubmitPipelineInput_approvalOnly_resumesBuild(
            JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "submit-approval");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_APPROVAL_ONLY, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "submitPipelineInput",
                    Map.of(
                            "jobFullName", "submit-approval",
                            "buildNumber", run.getNumber(),
                            "inputId", "gate")));

            assertThat(response.isError()).isFalse();
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                String result = doc.read("$.result");
                assertThat(result).contains("gate").contains(String.valueOf(run.getNumber()));
            });
        }

        await().atMost(MIN_1, MILLISECONDS).until(() -> !run.isBuilding());
        assertThat(run.getResult()).isEqualTo(Result.SUCCESS);
    }

    @McpClientTest
    void testAbortPipelineInput_abortsBuild(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "abort-input");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_APPROVAL_ONLY, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "abortPipelineInput",
                    Map.of(
                            "jobFullName", "abort-input",
                            "buildNumber", run.getNumber(),
                            "inputId", "gate")));

            assertThat(response.isError()).isFalse();
            assertThat(response.content().get(0)).isInstanceOfSatisfying(McpSchema.TextContent.class, tc -> {
                DocumentContext doc = JsonPath.using(Configuration.defaultConfiguration()).parse(tc.text());
                String result = doc.read("$.result");
                assertThat(result).contains("gate").contains(String.valueOf(run.getNumber()));
            });
        }

        await().atMost(MIN_1, MILLISECONDS).until(() -> !run.isBuilding());
        assertThat(run.getResult()).isEqualTo(Result.ABORTED);
    }

    @McpClientTest
    void testGetPendingInputs_jobNotFound_returnsError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "getPendingInputs", Map.of("jobFullName", "nonexistent-job")));
            assertThat(response.isError()).isTrue();
        }
    }

    @McpClientTest
    void testSubmitPipelineInput_wrongInputId_returnsError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "wrong-input-id");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_APPROVAL_ONLY, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "submitPipelineInput",
                    Map.of(
                            "jobFullName", "wrong-input-id",
                            "buildNumber", run.getNumber(),
                            "inputId", "this-id-does-not-exist")));
            assertThat(response.isError()).isTrue();
        }

        abortRun(run);
    }

    @McpClientTest
    void testAbortPipelineInput_alreadySettled_returnsError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "already-settled");
        project.setDefinition(new CpsFlowDefinition(PIPELINE_APPROVAL_ONLY, true));
        WorkflowRun run = project.scheduleBuild2(0).waitForStart();
        waitForPendingInput(run);

        // abort it first so the step is settled
        abortRun(run);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "abortPipelineInput",
                    Map.of(
                            "jobFullName", "already-settled",
                            "buildNumber", run.getNumber(),
                            "inputId", "gate")));
            assertThat(response.isError()).isTrue();
        }
    }

    private static void waitForPendingInput(WorkflowRun run) {
        await().atMost(MIN_1, MILLISECONDS).until(() -> {
            try {
                InputAction action = run.getAction(InputAction.class);
                return action != null && !action.getExecutions().isEmpty();
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static void abortRun(WorkflowRun run) throws Exception {
        try {
            InputAction action = run.getAction(InputAction.class);
            if (action != null && !action.getExecutions().isEmpty()) {
                action.getExecutions().get(0).doAbort();
            }
        } catch (TimeoutException e) {
            run.doStop();
        }
        await().atMost(MIN_1, MILLISECONDS).until(() -> !run.isBuilding());
    }
}
