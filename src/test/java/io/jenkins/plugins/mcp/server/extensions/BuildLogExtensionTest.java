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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
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
            Integer limit,
            Long skip,
            Integer expectedContentSize,
            boolean hasMoreContent,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();

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
                });
            });
        }
    }

    @McpClientTest
    void testMcpToolCallWithIncorrectBuildNumber(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

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
        Stream<Arguments> baseArgs = Stream.of(
                Arguments.of(100, null, 4, false),
                Arguments.of(100, 1L, 3, false),
                Arguments.of(3, null, 3, true),
                Arguments.of(100, -1L, 1, false),
                Arguments.of(3, -4L, 3, true));

        // 扩展成 2 倍：每组参数 + 两个不同的 McpClientProvider
        return TestUtils.appendMcpClientArgs(baseArgs);
    }
}
