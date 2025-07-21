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

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_SSE;
import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class UpdateBuildTest {
    public static Stream<Arguments> buildLogTestParameters() {
        List<Integer> buildNumbers = Arrays.asList(1, null);
        List<String> descriptions = Arrays.asList("Updated Build Description", null);
        List<String> displayNames = Arrays.asList("Updated Build DisplayName", null);
        var argsList = buildNumbers.stream()
                .flatMap(number -> descriptions.stream()
                        .flatMap(desc -> displayNames.stream().map(name -> Arrays.asList(number, desc, name))))
                .filter(args -> (args.get(1) != null || args.get(2) != null))
                .toList();
        return IntStream.range(0, argsList.size())
                .mapToObj(index -> Arguments.of(
                        argsList.get(index).get(0),
                        argsList.get(index).get(1),
                        argsList.get(index).get(2),
                        index));
    }

    @ParameterizedTest
    @MethodSource("buildLogTestParameters")
    void testMcpToolCallUpdateBuild(
            Integer builderNumber, String description, String displayName, int idx, JenkinsRule jenkins)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "update-build-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport = HttpClientSseClientTransport.builder(baseUrl)
                .sseEndpoint(MCP_SERVER_SSE)
                .build();

        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(500))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build()) {
            client.initialize();

            Map<String, Object> requests = new HashMap<>();
            if (builderNumber != null) {
                requests.put("builderNumber", builderNumber);
            }
            if (description != null) {
                requests.put("description", description + idx);
            }
            if (displayName != null) {
                requests.put("displayName", displayName + idx);
            }
            requests.put("jobFullName", project.getFullName());
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("updateBuild", requests);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains("true");
            });

            // Verify that the build was actually updated
            var updatedBuild = project.getBuildByNumber(build.getNumber());
            assertThat(updatedBuild).isNotNull();
            if (displayName != null) {
                assertThat(updatedBuild.getDisplayName()).isEqualTo(displayName + idx);
            }
            if (description != null) {
                assertThat(updatedBuild.getDescription()).isEqualTo(description + idx);
            }
        }
    }
}
