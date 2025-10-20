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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;

public class BuildArtifactsExtensionTest {

    @McpClientTest
    void testGetBuildArtifacts(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that produces artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "artifact-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'test.txt', text: 'Hello World'\n"
                        + "    archiveArtifacts artifacts: 'test.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
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
                    // After fix: the response contains a JSON array with a single artifact
                    assertThat(jsonNode.isArray()).isTrue();
                    assertThat(jsonNode.size()).isEqualTo(1);

                    // Check that we have the test.txt artifact in the array
                    JsonNode artifactNode = jsonNode.get(0);
                    assertThat(artifactNode.get("relativePath").asText()).isEqualTo("test.txt");
                    assertThat(artifactNode.get("fileName").asText()).isEqualTo("test.txt");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifact(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that produces artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "artifact-content-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'content.txt', text: 'This is test content for artifact reading'\n"
                        + "    archiveArtifacts artifacts: 'content.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName", "artifact-content-job",
                            "artifactPath", "content.txt"))
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

    @McpClientTest
    void testGetBuildArtifactWithPagination(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        // Create a job that produces a larger artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "large-artifact-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'large.txt', text: new String(new char[1000]).replace('\\0', 'A')\n"
                        + "    archiveArtifacts artifacts: 'large.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Read first 100 bytes
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName",
                            "large-artifact-job",
                            "artifactPath",
                            "large.txt",
                            "offset",
                            0,
                            "limit",
                            100))
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

    @McpClientTest
    void testGetBuildArtifactsEmptyForNonExistentJob(
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifacts")
                    .arguments(Map.of("jobFullName", "non-existent-job"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            // After fix: even empty results return a single content item with an empty JSON array
            assertThat(response.content()).hasSize(1);
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.isArray()).isTrue();
                    assertThat(jsonNode.size()).isEqualTo(0); // Empty array for non-existent job
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactsMultiple(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        // Create a job that produces multiple artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "multi-artifact-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'artifact1.txt', text: 'Content 1'\n"
                        + "    writeFile file: 'artifact2.txt', text: 'Content 2'\n"
                        + "    archiveArtifacts artifacts: '*.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifacts")
                    .arguments(Map.of("jobFullName", "multi-artifact-job"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            // After fix: getBuildArtifacts should return a single content item containing a JSON array
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    // Validate that the response is a proper JSON array
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.isArray()).isTrue();
                    assertThat(jsonNode.size()).isEqualTo(2); // Should have 2 artifacts

                    // Verify that both artifacts are present in the array
                    Set<String> foundArtifacts = new HashSet<>();
                    for (JsonNode artifactNode : jsonNode) {
                        assertThat(artifactNode.isObject()).isTrue();
                        assertThat(artifactNode.has("relativePath")).isTrue();
                        assertThat(artifactNode.has("fileName")).isTrue();
                        String relativePath = artifactNode.get("relativePath").asText();
                        foundArtifacts.add(relativePath);
                    }
                    assertThat(foundArtifacts).containsExactlyInAnyOrder("artifact1.txt", "artifact2.txt");
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactNotFound(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        // Create a job with an artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "artifact-not-found-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'exists.txt', text: 'Content'\n"
                        + "    archiveArtifacts artifacts: 'exists.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Try to get a non-existent artifact
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName", "artifact-not-found-job",
                            "artifactPath", "does-not-exist.txt"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.get("content").asText()).contains("Artifact not found");
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isEqualTo(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactNonExistentBuild(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName", "non-existent-job",
                            "artifactPath", "some-artifact.txt"))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.get("content").asText()).contains("Build not found");
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isEqualTo(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactWithOffsetBeyondFileSize(
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job with a small artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "offset-beyond-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'small.txt', text: 'Small'\n"
                        + "    archiveArtifacts artifacts: 'small.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Try to read with offset beyond file size
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of("jobFullName", "offset-beyond-job", "artifactPath", "small.txt", "offset", 10000))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    assertThat(jsonNode.get("content").asText()).isEmpty();
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isGreaterThan(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactWithExcessiveLimit(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        // Create a job with an artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "excessive-limit-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'test.txt', text: 'Test content'\n"
                        + "    archiveArtifacts artifacts: 'test.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Try to read with limit > 1MB (should be capped)
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName",
                            "excessive-limit-job",
                            "artifactPath",
                            "test.txt",
                            "limit",
                            2000000)) // 2MB, should be capped to 1MB
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    // Should still get the content, just with capped limit
                    assertThat(jsonNode.get("content").asText()).contains("Test content");
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isGreaterThan(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testGetBuildArtifactWithNegativeOffsetAndLimit(
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job with an artifact
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "negative-params-job");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n" + "    writeFile file: 'test.txt', text: 'Test content'\n"
                        + "    archiveArtifacts artifacts: 'test.txt'\n"
                        + "}",
                true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Try to read with negative offset and limit (should be normalized to 0 and default)
            var callToolRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifact")
                    .arguments(Map.of(
                            "jobFullName",
                            "negative-params-job",
                            "artifactPath",
                            "test.txt",
                            "offset",
                            -100,
                            "limit",
                            -50))
                    .build();

            var response = client.callTool(callToolRequest);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode jsonNode = objectMapper.readTree(textContent.text());
                    // Should still get the content with normalized parameters
                    assertThat(jsonNode.get("content").asText()).contains("Test content");
                    assertThat(jsonNode.get("hasMoreContent").asBoolean()).isFalse();
                    assertThat(jsonNode.get("totalSize").asLong()).isGreaterThan(0);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
