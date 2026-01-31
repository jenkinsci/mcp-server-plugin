/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Olivier Lamy.
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

import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the stateless MCP server endpoint.
 * The stateless endpoint is always available at /mcp-server/stateless alongside
 * the SSE and streamable endpoints.
 */
@WithJenkins
public class StatelessEndpointTest {

    @Test
    void testStatelessMcpEndpointWorksWithToolsList(JenkinsRule jenkins) throws Exception {
        try (StatelessMcpTestClient client = new StatelessMcpTestClient(jenkins)) {
            // Get server capabilities
            McpSchema.ServerCapabilities capabilities = client.getServerCapabilities();
            assertThat(capabilities).isNotNull();
            assertThat(capabilities.tools()).isNotNull();

            // List tools
            McpSchema.ListToolsResult result = client.listTools();
            assertThat(result).isNotNull();
            assertThat(result.tools()).isNotEmpty();

            // Verify some expected tools are present
            assertThat(result.tools()).extracting(McpSchema.Tool::name).contains("whoAmI", "getJobs", "sayHello");
        }
    }

    @Test
    void testStatelessMcpEndpointCallTool(JenkinsRule jenkins) throws Exception {
        try (StatelessMcpTestClient client = new StatelessMcpTestClient(jenkins)) {
            // Call whoAmI tool
            McpSchema.CallToolResult response = client.callTool("whoAmI", Map.of());
            assertThat(response).isNotNull();
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).isNotEmpty();
        }
    }

    @Test
    void testStatelessSayHelloTool(JenkinsRule jenkins) throws Exception {
        try (StatelessMcpTestClient client = new StatelessMcpTestClient(jenkins)) {
            McpSchema.CallToolResult response = client.callTool("sayHello", Map.of("name", "World"));
            assertThat(response).isNotNull();
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).isNotEmpty();

            // Verify the response contains the expected greeting
            assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
            McpSchema.TextContent textContent =
                    (McpSchema.TextContent) response.content().get(0);
            assertThat(textContent.text()).contains("Hello, World!");
        }
    }
}
