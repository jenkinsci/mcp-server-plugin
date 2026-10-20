/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Yigal Gvili.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestCrumbIssuerMcpExtension;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
@ExtendWith(TestCrumbIssuerMcpExtension.class)
class PipelineExtensionTest {

    @McpClientTest
    void testMcpToolCallCreatePipeline(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "createPipeline", Map.of("jobName", "new-pipeline-job", "pipelineScript", "echo 'hello'"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");

            var job = jenkins.jenkins.getItemByFullName("new-pipeline-job");
            assertThat(job).isNotNull();
            assertThat(job).isInstanceOf(WorkflowJob.class);
            var definition = ((WorkflowJob) job).getDefinition();
            assertThat(definition).isInstanceOf(CpsFlowDefinition.class);
            assertThat(((CpsFlowDefinition) definition).getScript()).isEqualTo("echo 'hello'");
            assertThat(((CpsFlowDefinition) definition).isSandbox()).isTrue();
        }
    }

    @McpClientTest
    void testMcpToolCallCreatePipelineAlreadyExists(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        var existingJob = jenkins.createProject(WorkflowJob.class, "existing-job");
        existingJob.setDefinition(new CpsFlowDefinition("echo 'original'", true));
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "createPipeline", Map.of("jobName", "existing-job", "pipelineScript", "echo 'hello'"));

            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("already exists");
            });
            assertThat(((CpsFlowDefinition) existingJob.getDefinition()).getScript())
                    .isEqualTo("echo 'original'");
        }
    }

    @McpClientTest
    void testMcpToolCallCreatePipelineInFolder(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        jenkins.createFolder("my-folder");
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "createPipeline",
                    Map.of("jobName", "my-folder/new-pipeline-job", "pipelineScript", "echo 'in folder'"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");

            var job = jenkins.jenkins.getItemByFullName("my-folder/new-pipeline-job");
            assertThat(job).isNotNull();
            assertThat(job).isInstanceOf(WorkflowJob.class);
            var definition = ((WorkflowJob) job).getDefinition();
            assertThat(definition).isInstanceOf(CpsFlowDefinition.class);
            assertThat(((CpsFlowDefinition) definition).getScript()).isEqualTo("echo 'in folder'");
        }
    }

    @McpClientTest
    void testMcpToolCallCreatePipelineInInvalidFolder(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "createPipeline",
                    Map.of("jobName", "nonexistent-folder/new-pipeline-job", "pipelineScript", "echo 'hello'"));

            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("does not exist or cannot contain jobs");
            });
        }
    }

    @McpClientTest
    void testScriptAndUrlArePreserved(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        var outer = jenkins.createFolder("outer");
        outer.createProject(Folder.class, "inner");
        String script = "echo '<tag> & \"quoted\"'\necho 'second line'";
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "createPipeline", Map.of("jobName", "outer/inner/pipeline", "pipelineScript", script)));
            assertThat(response.isError()).isFalse();
            var job = jenkins.jenkins.getItemByFullName("outer/inner/pipeline", WorkflowJob.class);
            assertThat(job).isNotNull();
            assertThat(((CpsFlowDefinition) job.getDefinition()).getScript()).isEqualTo(script);
            assertThat(((CpsFlowDefinition) job.getDefinition()).isSandbox()).isTrue();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains(jenkins.jenkins.getRootUrl() + job.getUrl());
            job.doReload();
            assertThat(((CpsFlowDefinition) job.getDefinition()).getScript()).isEqualTo(script);
            jenkins.assertBuildStatusSuccess(job.scheduleBuild2(0));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testCreatePermission(boolean allowCreate, JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        var authorization = new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("creator");
        if (allowCreate) {
            authorization.grant(Item.CREATE).everywhere().to("creator");
        }
        jenkins.jenkins.setAuthorizationStrategy(authorization);
        try (var ignored = ACL.as2(User.getById("creator", true).impersonate2())) {
            var extension = ExtensionList.lookupSingleton(PipelineExtension.class);
            if (allowCreate) {
                extension.createPipeline("secured-pipeline", "echo 'hello'");
                assertThat(jenkins.jenkins.getItem("secured-pipeline")).isNotNull();
            } else {
                assertThatThrownBy(() -> extension.createPipeline("secured-pipeline", "echo 'hello'"))
                        .isInstanceOf(AccessDeniedException.class);
                assertThat(jenkins.jenkins.getItem("secured-pipeline")).isNull();
            }
        }
    }
}
