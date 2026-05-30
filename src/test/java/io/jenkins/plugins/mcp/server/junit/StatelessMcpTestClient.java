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

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_STATELESS;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test client for stateless MCP server mode.
 * Uses direct HTTP POST with JSON-RPC messages since the MCP SDK does not provide
 * a stateless client transport. The stateless server handles individual JSON-RPC
 * requests without session initialization.
 */
public class StatelessMcpTestClient implements Closeable {

    private final HttpClient httpClient;
    private final String endpointUrl;
    private final JacksonMcpJsonMapper jsonMapper;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private McpSchema.ServerCapabilities serverCapabilities;

    public StatelessMcpTestClient(JenkinsRule jenkins) throws IOException {
        var url = jenkins.getURL();
        this.endpointUrl = url.toString() + MCP_SERVER_STATELESS;
        this.httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);

        // Send initialize request to get server capabilities
        var initRequest = new McpSchema.InitializeRequest(
                "2025-03-26",
                McpSchema.ClientCapabilities.builder().build(),
                new McpSchema.Implementation("StatelessMcpTestClient", "1.0.0"));

        McpSchema.JSONRPCResponse response = sendRequest(McpSchema.METHOD_INITIALIZE, initRequest);
        McpSchema.InitializeResult initResult =
                jsonMapper.convertValue(response.result(), McpSchema.InitializeResult.class);
        this.serverCapabilities = initResult.capabilities();

        // Send initialized notification
        sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
    }

    /**
     * Gets server capabilities after initialization.
     */
    public McpSchema.ServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    /**
     * Calls the tools/list method on the stateless MCP server.
     */
    public McpSchema.ListToolsResult listTools() {
        McpSchema.JSONRPCResponse response = sendRequest(McpSchema.METHOD_TOOLS_LIST, Map.of());
        return jsonMapper.convertValue(response.result(), McpSchema.ListToolsResult.class);
    }

    /**
     * Calls a tool on the stateless MCP server.
     */
    public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest request) {
        McpSchema.JSONRPCResponse response = sendRequest(McpSchema.METHOD_TOOLS_CALL, request);
        return jsonMapper.convertValue(response.result(), McpSchema.CallToolResult.class);
    }

    /**
     * Calls a tool on the stateless MCP server with convenience parameters.
     */
    public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        return this.callTool(new McpSchema.CallToolRequest(toolName, arguments));
    }

    private McpSchema.JSONRPCResponse sendRequest(String method, Object params) {
        try {
            var jsonRpcRequest = new McpSchema.JSONRPCRequest(
                    McpSchema.JSONRPC_VERSION, method, requestId.incrementAndGet(), params);

            String body = jsonMapper.writeValueAsString(jsonRpcRequest);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("HTTP error " + httpResponse.statusCode() + ": " + httpResponse.body());
            }

            return jsonMapper.readValue(httpResponse.body(), McpSchema.JSONRPCResponse.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to send JSON-RPC request: " + method, e);
        }
    }

    private void sendNotification(String method) {
        try {
            var notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method, null);

            String body = jsonMapper.writeValueAsString(notification);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to send JSON-RPC notification: " + method, e);
        }
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }
}
