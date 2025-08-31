/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Derek Taubert.
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
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

@McpClientTest
public class BuildArtifactsExtensionTest {

    @Test
    void testGetBuildArtifacts(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that produces artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "artifact-job");
        project.setDefinition(new CpsFlowDefinition(
                "writeFile file: 'test.txt', text: 'Hello World'\n" +
                "archiveArtifacts artifacts: 'test.txt'", true));
        
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifacts")
                    .arguments(Map.of("jobFullName", "artifact-job"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                assertThat(jsonNode.isArray()).isTrue();
                assertThat(jsonNode.size()).isGreaterThan(0);
                
                    // Check that we have the test.txt artifact
                    JsonNode firstArtifact = jsonNode.get(0);
                    assertThat(firstArtifact.get("relativePath").asText()).isEqualTo("test.txt");
                    assertThat(firstArtifact.get("fileName").asText()).isEqualTo("test.txt");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void testGetBuildArtifact(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that produces artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "artifact-content-job");
        project.setDefinition(new CpsFlowDefinition(
                "writeFile file: 'content.txt', text: 'This is test content for artifact reading'\n" +
                "archiveArtifacts artifacts: 'content.txt'", true));
        
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName", "artifact-content-job",
                            "artifactPath", "content.txt"
                    ))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.get("content").asText()).contains("This is test content for artifact reading");
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isGreaterThan(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void testGetBuildArtifactWithPagination(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that produces a larger artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "large-artifact-job");
        project.setDefinition(new CpsFlowDefinition(
                "writeFile file: 'large.txt', text: 'A'.multiply(1000)\n" +
                "archiveArtifacts artifacts: 'large.txt'", true));
        
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.build()) {
            // Read first 100 bytes
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName", "large-artifact-job",
                            "artifactPath", "large.txt",
                            "offset", 0,
                            "limit", 100
                    ))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.get("content").asText()).hasSize(100);
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isTrue();
                    assertThat(jsonNode.get("totalSize").asLong()).isEqualTo(1000);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void testGetBuildArtifactsEmptyForNonExistentJob(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        try (var client = jenkinsMcpClientBuilder.build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifacts")
                    .arguments(Map.of("jobFullName", "non-existent-job"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.isArray()).isTrue();
                    assertThat(jsonNode.size()).isEqualTo(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
