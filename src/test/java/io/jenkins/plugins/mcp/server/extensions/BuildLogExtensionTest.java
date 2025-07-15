package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_SSE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
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
            int length, long offset, int expectedContentSize, boolean hasMoreContent, JenkinsRule jenkins)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
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

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuildLog", Map.of("jobFullName", project.getFullName(), "length", length, "offset", offset));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            response.content().forEach(content -> {
                assertThat(content.type()).isEqualTo("text");
                assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                    JsonNode jsonNode = null;
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

    static Stream<Arguments> buildLogTestParameters() {
        return Stream.of(
                // length, offset, expectedContentSize
                Arguments.of(100, 0, 4, false), // Original test case
                Arguments.of(100, 1, 3, false), // With offset
                Arguments.of(3, 0, 3, true), // With limit
                Arguments.of(100, -1, 1, false), // With negative offset
                Arguments.of(3, -4, 3, true) // With negative offset and limit
                );
    }
}
