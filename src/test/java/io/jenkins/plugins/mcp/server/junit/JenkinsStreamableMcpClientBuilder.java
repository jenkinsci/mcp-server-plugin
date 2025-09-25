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

package io.jenkins.plugins.mcp.server.junit;

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_STREAMABLE;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import lombok.SneakyThrows;

public class JenkinsStreamableMcpClientBuilder extends JenkinsMcpClientBuilder.AbstractJenkinsMcpClientBuilder {
    @Override
    @SneakyThrows
    public McpSyncClient build() {
        var url = jenkins.getURL();
        var baseUrl = url.toString();

        HttpClientStreamableHttpTransport.Builder builder =
                HttpClientStreamableHttpTransport.builder(baseUrl).endpoint(MCP_SERVER_STREAMABLE);
        if (requestCustomizer != null) {
            builder.customizeRequest(requestCustomizer);
        }
        var transport = builder.build();

        var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .capabilities(McpSchema.ClientCapabilities.builder().build())
                .build();
        client.initialize();
        return client;
    }

    @Override
    public String toString() {
        return "Streamable MCP client";
    }
}
