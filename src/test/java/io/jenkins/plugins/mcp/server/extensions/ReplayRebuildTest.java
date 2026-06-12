/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.extensions;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.tasks.Shell;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for rebuildBuild, getReplayScripts, and replayBuild MCP tools.
 */
@WithJenkins
class ReplayRebuildTest {

    @McpClientTest
    void testRebuildBuild_pipelineJob(
            JenkinsRule jenkins, io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "rebuild-pipeline");
        project.setDefinition(new CpsFlowDefinition("echo 'original run'", true));
        var firstBuild = project.scheduleBuild2(0).get();
        assertThat(firstBuild.getResult()).isEqualTo(Result.SUCCESS);

        int nextNumber = project.getNextBuildNumber();
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var request = new McpSchema.CallToolRequest(
                    "rebuildBuild",
                    Map.of("jobFullName", project.getFullName(), "buildNumber", firstBuild.getNumber()));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
        }

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
        await().atMost(30, SECONDS)
                .untilAsserted(
                        () -> assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
        assertThat(project.getLastBuild().getNumber()).isEqualTo(nextNumber);
        assertThat(project.getLastBuild().getLog()).contains("original run");
    }

    @McpClientTest
    void testRebuildBuild_parameterizedJob(
            JenkinsRule jenkins, io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "rebuild-param");
        project.addProperty(
                new ParametersDefinitionProperty(new StringParameterDefinition("GREETING", "default", "Greeting")));
        project.setDefinition(new CpsFlowDefinition("echo \"${params.GREETING}\"", true));

        var firstBuild = project.scheduleBuild2(
                        0, new ParametersAction(new StringParameterValue("GREETING", "first-run")))
                .get();
        assertThat(firstBuild.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(firstBuild.getLog()).contains("first-run");

        int nextNumber = project.getNextBuildNumber();
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var request = new McpSchema.CallToolRequest(
                    "rebuildBuild",
                    Map.of("jobFullName", project.getFullName(), "buildNumber", firstBuild.getNumber()));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
        }

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
        await().atMost(30, SECONDS)
                .untilAsserted(
                        () -> assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
        assertThat(project.getLastBuild().getNumber()).isEqualTo(nextNumber);
        assertThat(project.getLastBuild().getLog()).contains("first-run");
    }

    @McpClientTest
    void testGetReplayScripts_pipelineBuild(
            JenkinsRule jenkins, io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        String script = "echo 'hello replay'";
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "replay-scripts");
        project.setDefinition(new CpsFlowDefinition(script, true));
        project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var request =
                    new McpSchema.CallToolRequest("getReplayScripts", Map.of("jobFullName", project.getFullName()));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            var textContent = (McpSchema.TextContent) response.content().get(0);
            var result = JsonPath.using(Configuration.defaultConfiguration())
                    .parse(textContent.text())
                    .read("$.result", Map.class);
            assertThat(result).containsKey("mainScript");
            assertThat((String) result.get("mainScript")).contains("echo 'hello replay'");
            assertThat(result).containsKey("loadedScripts");
        }
    }

    /**
     * SECURITY-3759: a user with only Overall/Read + Item/Read (but not Item/ExtendedRead) must not be able
     * to read the Pipeline replay script, mirroring the GET /job/.../config.xml boundary. The tool must
     * return an empty script (no disclosure), consistent with the getJobScm guard added in SECURITY-3622.
     */
    @McpClientTest
    void testGetReplayScripts_deniedWithoutExtendedRead(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "replay-secured");
        project.setDefinition(new CpsFlowDefinition("echo 'secret pipeline source'", true));
        project.scheduleBuild2(0).get();

        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(Item.READ)
                .everywhere()
                .to("reader"));

        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(ReplayRebuildTest::authAsReader)
                .build()) {
            var request =
                    new McpSchema.CallToolRequest("getReplayScripts", Map.of("jobFullName", project.getFullName()));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            var textContent = (McpSchema.TextContent) response.content().get(0);
            var result = JsonPath.using(Configuration.defaultConfiguration())
                    .parse(textContent.text())
                    .read("$.result", Map.class);
            // No disclosure: mainScript is blank and the secret source is absent.
            assertThat((String) result.get("mainScript")).isEmpty();
            assertThat(textContent.text()).doesNotContain("secret pipeline source");
        }
    }

    /**
     * SECURITY-3759: a user granted Item/ExtendedRead (the permission GET /job/.../config.xml requires) can
     * still read the replay script.
     */
    @McpClientTest
    void testGetReplayScripts_allowedWithExtendedRead(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "replay-extread");
        project.setDefinition(new CpsFlowDefinition("echo 'visible pipeline source'", true));
        project.scheduleBuild2(0).get();

        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader")
                .grant(Item.READ, Item.EXTENDED_READ)
                .everywhere()
                .to("reader"));

        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(ReplayRebuildTest::authAsReader)
                .build()) {
            var request =
                    new McpSchema.CallToolRequest("getReplayScripts", Map.of("jobFullName", project.getFullName()));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            var textContent = (McpSchema.TextContent) response.content().get(0);
            var result = JsonPath.using(Configuration.defaultConfiguration())
                    .parse(textContent.text())
                    .read("$.result", Map.class);
            assertThat((String) result.get("mainScript")).contains("echo 'visible pipeline source'");
        }
    }

    private static void authAsReader(java.net.http.HttpRequest.Builder request) {
        String encodedAuth =
                Base64.getEncoder().encodeToString("reader:reader".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setHeader("Authorization", "Basic " + encodedAuth);
    }

    @McpClientTest
    void testGetReplayScripts_nonPipelineBuild(
            JenkinsRule jenkins, io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("replay-freestyle");
        project.getBuildersList().add(new Shell("echo ok"));
        project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var request =
                    new McpSchema.CallToolRequest("getReplayScripts", Map.of("jobFullName", project.getFullName()));
            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            var textContent = (McpSchema.TextContent) response.content().get(0);
            assertThat(textContent.text()).contains("Not a replayable Pipeline build");
        }
    }

    @McpClientTest
    void testReplayBuild_modifiedScript(
            JenkinsRule jenkins, io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "replay-modified");
        project.setDefinition(new CpsFlowDefinition("echo 'original'", true));
        project.scheduleBuild2(0).get();

        String modifiedScript = "echo 'replayed-with-change'";
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var request = new McpSchema.CallToolRequest(
                    "replayBuild", Map.of("jobFullName", project.getFullName(), "mainScript", modifiedScript));
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
        }

        await().atMost(30, SECONDS)
                .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
        await().atMost(30, SECONDS)
                .untilAsserted(
                        () -> assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
        assertThat(project.getLastBuild().getLog()).contains("replayed-with-change");
    }
}
