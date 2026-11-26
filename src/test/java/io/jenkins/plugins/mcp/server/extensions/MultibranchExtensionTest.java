/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, the Jenkins MCP Server plugin contributors.
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

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.Map;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.scm.impl.mock.MockSCMController;
import jenkins.scm.impl.mock.MockSCMSource;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class MultibranchExtensionTest {

    private static final String BUILDER_USER = "builder";

    @McpClientTest
    void testMcpToolCallScanMultibranchPipelineSuccess(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        var mbp = jenkins.createProject(WorkflowMultiBranchProject.class, "demo-mbp");
        setupBranchSource(mbp);
        secure(jenkins);

        try (var client = clientAsBuilder(jenkins, jenkinsMcpClientBuilder)) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("scanMultibranchPipeline", Map.of("jobFullName", "demo-mbp"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).containsEntry("scheduled", true);
                assertThat(contentMap).containsEntry("jobFullName", "demo-mbp");
                assertThat(contentMap).containsEntry("message", "Branch indexing scheduled successfully");
                assertThat(contentMap.get("scanUrl").toString()).contains("demo-mbp");
                assertThat(contentMap.get("queueId")).isNotNull();
            });
        }
    }

    @McpClientTest
    void testMcpToolCallScanMultibranchPipelineInFolder(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        var folder = jenkins.createFolder("test-folder");
        var mbp = folder.createProject(WorkflowMultiBranchProject.class, "nested-mbp");
        setupBranchSource(mbp);
        secure(jenkins);

        try (var client = clientAsBuilder(jenkins, jenkinsMcpClientBuilder)) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "scanMultibranchPipeline", Map.of("jobFullName", "test-folder/nested-mbp"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).containsEntry("scheduled", true);
                assertThat(contentMap).containsEntry("jobFullName", "test-folder/nested-mbp");
                assertThat(contentMap.get("scanUrl").toString()).contains("test-folder");
                assertThat(contentMap.get("scanUrl").toString()).contains("nested-mbp");
            });
        }
    }

    @McpClientTest
    void testMcpToolCallScanProjectNotFound(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        secure(jenkins);

        try (var client = clientAsBuilder(jenkins, jenkinsMcpClientBuilder)) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("scanMultibranchPipeline", Map.of("jobFullName", "nonexistent-job"));

            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("Project not found: nonexistent-job");
            });
        }
    }

    @McpClientTest
    void testMcpToolCallScanNotMultibranch(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        jenkins.createProject(WorkflowJob.class, "standard-pipeline");
        secure(jenkins);

        try (var client = clientAsBuilder(jenkins, jenkinsMcpClientBuilder)) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "scanMultibranchPipeline", Map.of("jobFullName", "standard-pipeline"));

            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("is not a Multibranch Pipeline or Organization Folder");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void testScanPermission(boolean allowBuild, JenkinsRule jenkins) throws Exception {
        var mbp = jenkins.createProject(WorkflowMultiBranchProject.class, "perm-mbp");
        setupBranchSource(mbp);
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        var authorization = new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to(BUILDER_USER);
        if (allowBuild) {
            authorization.grant(Item.BUILD).everywhere().to(BUILDER_USER);
        }
        jenkins.jenkins.setAuthorizationStrategy(authorization);

        try (var ignored = ACL.as2(User.getById(BUILDER_USER, true).impersonate2())) {
            var extension = ExtensionList.lookupSingleton(MultibranchExtension.class);
            if (allowBuild) {
                var result = extension.scanMultibranchPipeline("perm-mbp");
                assertThat(result).isNotNull();
                assertThat(result.scheduled()).isTrue();
            } else {
                assertThatThrownBy(() -> extension.scanMultibranchPipeline("perm-mbp"))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ, Item.BUILD)
                .everywhere()
                .to(BUILDER_USER));
        jenkins.jenkins.save();
    }

    private static McpSyncClient clientAsBuilder(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) {
        return builder.jenkins(jenkins)
                .requestCustomizer((httpRequestBuilder, method, endpoint, body, context) ->
                        httpRequestBuilder.setHeader("Authorization", basicAuth(BUILDER_USER)))
                .build();
    }

    private static void setupBranchSource(WorkflowMultiBranchProject mbp) throws Exception {
        MockSCMController c = MockSCMController.create();
        c.createRepository("repo");
        c.createBranch("repo", "main");
        mbp.getSourcesList().add(new BranchSource(new MockSCMSource(c, "repo")));
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
    }
}
