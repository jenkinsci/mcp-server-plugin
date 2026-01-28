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

package io.jenkins.plugins.mcp.server.junit;

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_STREAMABLE;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test client for stateless MCP server mode.
 * Uses the MCP SDK's HttpClientStreamableHttpTransport to communicate with the stateless server.
 * The stateless server uses the same HTTP/JSON-RPC protocol as the streamable server,
 * just without session management.
 */
public class StatelessMcpTestClient implements Closeable {

    private final McpSyncClient client;

    public StatelessMcpTestClient(JenkinsRule jenkins) throws IOException {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(MCP_SERVER_STREAMABLE)
                .build();

        this.client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();

        client.initialize();
    }

    /**
     * Gets server capabilities after initialization.
     */
    public McpSchema.ServerCapabilities getServerCapabilities() {
        return client.getServerCapabilities();
    }

    /**
     * Calls the tools/list method on the stateless MCP server.
     */
    public McpSchema.ListToolsResult listTools() {
        return client.listTools();
    }

    /**
     * Calls a tool on the stateless MCP server.
     */
    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        return client.callTool(request);
    }

    /**
     * Calls a tool on the stateless MCP server with convenience parameters.
     */
    public McpSchema.CallToolResult callTool(String toolName, java.util.Map<String, Object> arguments) {
        return this.callTool(new McpSchema.CallToolRequest(toolName, arguments));
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
