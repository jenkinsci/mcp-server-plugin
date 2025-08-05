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

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_SSE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URL;
import java.util.Map;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class EndPointTest {
    @McpClientTest
    void testMcpToolCallSimpleJson(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
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
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("sayHello", Map.of("name", "foo"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");

                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    var contetMap = objectMapper.readValue(textContent.text(), Map.class);
                    assertThat(contetMap).extractingByKey("message").isEqualTo("Hello, foo!");

                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @McpClientTest
    void testMcpToolCallIntResult(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        var url = jenkins.getURL();
        var baseUrl = url.toString();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
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

    @McpClientTest
    void testMcpToolCallWithException(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
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
    void testSSEUrlSupportGetOnly(JenkinsRule jenkins) throws Exception {
        var url = jenkins.getURL();
        var baseUrl = url.toString();
        var sseUrl = baseUrl + MCP_SERVER_SSE;
        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            var request = new WebRequest(new URL(sseUrl), HttpMethod.POST);
            var response = webClient.loadWebResponse(request);
            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        }
    }
}
