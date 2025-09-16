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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class BuildLogExtensionTest {
    ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("buildLogTestParameters")
    void testMcpToolCallGetBuildLog(
            Long skip,
            Integer limit,
            Integer expectedContentSize,
            boolean hasMoreContent,
            List<String> expectedLines,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            if (limit != null) {
                params.put("limit", limit);
            }
            if (skip != null) {
                params.put("skip", skip);
            }
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            response.content().forEach(content -> {
                assertThat(content.type()).isEqualTo("text");
                assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                    JsonNode jsonNode;
                    try {
                        jsonNode = objectMapper.readTree(textContent.text());
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }

                    assertThat(jsonNode.has("hasMoreContent")).isTrue();
                    assertThat(hasMoreContent).matches(expected -> expected == hasMoreContent);

                    JsonNode linesArray = jsonNode.get("lines");
                    assertThat(linesArray.size()).isEqualTo(expectedContentSize);
                    for (JsonNode lineNode : linesArray) {
                        assertThat(lineNode.asText().trim()).isNotEmpty();
                    }
                    if (!expectedLines.isEmpty()) {
                        List lines = objectMapper.convertValue(linesArray, List.class);
                        assertThat(lines).isEqualTo(expectedLines);
                    }
                });
            });
        }
    }

    @McpClientTest
    void testMcpToolCallWithIncorrectBuildNumber(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            params.put("buildNumber", 100);

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            response.content().forEach(content -> {
                assertThat(content.type()).isEqualTo("text");
                assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                    assertThat(textContent.text()).isEqualTo("Result is null");
                });
            });
        }
    }

    static Stream<Arguments> buildLogTestParameters() {
        WorkflowRun workflowRun;
        // all lines Started;[Pipeline] Start of Pipeline;[Pipeline] End of Pipeline;Finished: SUCCESS
        // "Started;[Pipeline] Start of Pipeline;[Pipeline] End of Pipeline;Finished: SUCCESS"
        Stream<Arguments> baseArgs = Stream.of(
                Arguments.of(
                        null,
                        100,
                        4,
                        false,
                        List.of(
                                "Started",
                                "[Pipeline] Start of Pipeline",
                                "[Pipeline] End of Pipeline",
                                "Finished: SUCCESS")),
                Arguments.of(
                        1L,
                        100,
                        3,
                        false,
                        List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(
                        null,
                        3,
                        3,
                        true,
                        List.of("Started", "[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(-1L, 100, 1, false, List.of("Finished: SUCCESS")),
                Arguments.of(
                        -4L,
                        3,
                        3,
                        false,
                        List.of("Started", "[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(-2L, 3, 2, false, List.of("[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(
                        -3L,
                        100,
                        3,
                        false,
                        List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(-2L, 2, 2, true, List.of("[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(-1L, 10, 1, false, List.of("Finished: SUCCESS")),
                Arguments.of(-1L, -2, 2, true, List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(2L, -2, 2, true, List.of("Started", "[Pipeline] Start of Pipeline")));

        // 扩展成 2 倍：每组参数 + 两个不同的 McpClientProvider
        return TestUtils.appendMcpClientArgs(baseArgs);
    }
}
