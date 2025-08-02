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

package io.jenkins.plugins.mcp.server;

import static io.jenkins.plugins.mcp.server.StreamableEndpoint.MCP_SERVER_MCP;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class StreamableEndpointTest {

    @Test
    void testStreamableToolCallSimpleJson(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(500))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build()) {
            client.initialize();
            client.getServerCapabilities();
            var tools = client.listTools();
            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsOnly(
                            "sayHello",
                            "testInt",
                            "testWithError",
                            "getBuildLog",
                            "triggerBuild",
                            "updateBuild",
                            "getJobs",
                            "getBuild",
                            "getJob",
                            "getJobScm",
                            "getBuildScm",
                            "getBuildChangeSets");
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("sayHello", Map.of("name", "streamable"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");

                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    var contentMap = objectMapper.readValue(textContent.text(), Map.class);
                    assertThat(contentMap).extractingByKey("message").isEqualTo("Hello, streamable!");

                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void testStreamableToolCallIntResult(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(500))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build()) {
            client.initialize();
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("testInt", Map.of());

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");

                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    var result = objectMapper.readValue(textContent.text(), Integer.class);
                    assertThat(result).isEqualTo(10);

                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void testStreamableToolCallWithException(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(500))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build()) {
            client.initialize();
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("testWithError", Map.of());

            var response = client.callTool(request);
            assertThat(response.isError()).isTrue();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains("Error occurred during execution");
            });
        }
    }

    @Test
    void testStreamableEndpointUrlSupportPostOnly(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();
        var streamableUrl = baseUrl + MCP_SERVER_MCP;
        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {

            // Test that GET without proper headers returns error
            var getRequest = new WebRequest(new URL(streamableUrl), HttpMethod.GET);
            var getResponse = webClient.loadWebResponse(getRequest);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);

            // Test that PUT is not allowed
            var putRequest = new WebRequest(new URL(streamableUrl), HttpMethod.PUT);
            var putResponse = webClient.loadWebResponse(putRequest);
            assertThat(putResponse.getStatusCode()).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);

            // Test that DELETE is not allowed
            var deleteRequest = new WebRequest(new URL(streamableUrl), HttpMethod.DELETE);
            var deleteResponse = webClient.loadWebResponse(deleteRequest);
            assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }

    @Test
    void testStreamableEndpointRequiresProperHeaders(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();
        var streamableUrl = baseUrl + MCP_SERVER_MCP;
        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {

            // Test POST without proper Accept headers
            var postRequest = new WebRequest(new URL(streamableUrl), HttpMethod.POST);
            postRequest.setAdditionalHeader("Content-Type", "application/json");
            var postResponse = webClient.loadWebResponse(postRequest);
            assertThat(postResponse.getStatusCode()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);

            // Test GET without proper Accept headers
            var getRequest = new WebRequest(new URL(streamableUrl), HttpMethod.GET);
            var getResponse = webClient.loadWebResponse(getRequest);
            assertThat(getResponse.getStatusCode()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    @Test
    void testStreamableJenkinsToolCalls(JenkinsRule jenkins) throws Exception {

        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job-1");
        project.setDefinition(new CpsFlowDefinition("", true));

        project = jenkins.createProject(WorkflowJob.class, "demo-job-2");
        project.setDefinition(new CpsFlowDefinition("", true));

        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        try (var client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(500))
                .requestTimeout(Duration.ofSeconds(500))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build()) {
            client.initialize();

            // Test getJobs tool call
            McpSchema.CallToolRequest getJobsRequest = new McpSchema.CallToolRequest("getJobs", Map.of("limit", 5));
            var getJobsResponse = client.callTool(getJobsRequest);
            assertThat(getJobsResponse.isError()).isFalse();
            assertThat(getJobsResponse.content()).hasSize(2);
            assertThat(getJobsResponse.content().get(0).type()).isEqualTo("text");

            // Test that the response contains valid JSON (even if empty list)
            assertThat(getJobsResponse.content())
                    .first()
                    .isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                        assertThat(textContent.text()).startsWith("{").endsWith("}");
                    });
        }
    }

    @Test
    void testStreamableSessionManagement(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        var transport1 = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        var transport2 = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_MCP)
                .build();

        // Test that multiple clients can connect simultaneously
        try (var client1 = McpClient.sync(transport1)
                        .requestTimeout(Duration.ofSeconds(500))
                        .capabilities(McpSchema.ClientCapabilities.builder().build())
                        .build();
                var client2 = McpClient.sync(transport2)
                        .requestTimeout(Duration.ofSeconds(500))
                        .capabilities(McpSchema.ClientCapabilities.builder().build())
                        .build()) {

            client1.initialize();
            client2.initialize();

            // Both clients should be able to make tool calls independently
            McpSchema.CallToolRequest request1 = new McpSchema.CallToolRequest("sayHello", Map.of("name", "client1"));
            McpSchema.CallToolRequest request2 = new McpSchema.CallToolRequest("sayHello", Map.of("name", "client2"));

            var response1 = client1.callTool(request1);
            var response2 = client2.callTool(request2);

            assertThat(response1.isError()).isFalse();
            assertThat(response2.isError()).isFalse();

            // Verify responses are different and correct
            assertThat(response1.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("client1");
            });

            assertThat(response2.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.text()).contains("client2");
            });
        }
    }
}
