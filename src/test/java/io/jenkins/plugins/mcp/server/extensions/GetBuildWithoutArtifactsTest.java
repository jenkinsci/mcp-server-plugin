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

public class GetBuildWithoutArtifactsTest {

    @McpClientTest
    void testGetBuildExcludesArtifacts(JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        // Create a job that produces artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "build-without-artifacts-job");
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
            // Test getBuild - should NOT include artifacts
            var getBuildRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuild")
                    .arguments(Map.of("jobFullName", "build-without-artifacts-job"))
                    .build();

            var getBuildResponse = client.callTool(getBuildRequest);
            assertThat(getBuildResponse.isError()).isFalse();
            assertThat(getBuildResponse.content()).hasSize(1);

            assertThat(getBuildResponse.content())
                    .first()
                    .isInstanceOfSatisfying(McpSchema.TextContent.class, getBuildTextContent -> {
                        ObjectMapper objectMapper = new ObjectMapper();
                        try {
                            JsonNode buildJsonNode = objectMapper.readTree(getBuildTextContent.text());

                            // Verify that artifacts field is NOT present in getBuild response
                            assertThat(buildJsonNode.has("artifacts")).isFalse();

                            // Verify that other build information is still present
                            assertThat(buildJsonNode.has("number")).isTrue();
                            assertThat(buildJsonNode.has("result")).isTrue();
                            assertThat(buildJsonNode.has("displayName")).isTrue();

                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    });

            // Test getBuildArtifacts - should include artifacts
            var getBuildArtifactsRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuildArtifacts")
                    .arguments(Map.of("jobFullName", "build-without-artifacts-job"))
                    .build();

            var getBuildArtifactsResponse = client.callTool(getBuildArtifactsRequest);
            assertThat(getBuildArtifactsResponse.isError()).isFalse();
            assertThat(getBuildArtifactsResponse.content())
                    .hasSize(2); // Two artifacts: artifact1.txt and artifact2.txt

            // Verify that artifacts are present in getBuildArtifacts response
            // Each artifact is returned as a separate content item
            ObjectMapper objectMapper = new ObjectMapper();
            Set<String> foundArtifacts = new HashSet<>();

            for (var content : getBuildArtifactsResponse.content()) {
                assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                    try {
                        JsonNode artifactNode = objectMapper.readTree(textContent.text());
                        assertThat(artifactNode.isObject()).isTrue();
                        String relativePath = artifactNode.get("relativePath").asText();
                        foundArtifacts.add(relativePath);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            assertThat(foundArtifacts).containsExactlyInAnyOrder("artifact1.txt", "artifact2.txt");
        }
    }

    @McpClientTest
    void testGetBuildWithoutArtifactsStillWorksForJobsWithoutArtifacts(
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins) throws Exception {
        // Create a job that does NOT produce artifacts
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "no-artifacts-job");
        project.setDefinition(new CpsFlowDefinition("node { echo 'Hello World' }", true));

        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);
        await().atMost(10, SECONDS).until(() -> project.getLastBuild().isBuilding() == false);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var getBuildRequest = McpSchema.CallToolRequest.builder()
                    .name("getBuild")
                    .arguments(Map.of("jobFullName", "no-artifacts-job"))
                    .build();

            var getBuildResponse = client.callTool(getBuildRequest);
            assertThat(getBuildResponse.isError()).isFalse();
            assertThat(getBuildResponse.content()).hasSize(1);

            assertThat(getBuildResponse.content())
                    .first()
                    .isInstanceOfSatisfying(McpSchema.TextContent.class, getBuildTextContent -> {
                        ObjectMapper objectMapper = new ObjectMapper();
                        try {
                            JsonNode buildJsonNode = objectMapper.readTree(getBuildTextContent.text());

                            // Verify that artifacts field is NOT present
                            assertThat(buildJsonNode.has("artifacts")).isFalse();

                            // Verify that other build information is still present
                            assertThat(buildJsonNode.has("number")).isTrue();
                            assertThat(buildJsonNode.has("result")).isTrue();
                            assertThat(buildJsonNode.has("displayName")).isTrue();

                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
