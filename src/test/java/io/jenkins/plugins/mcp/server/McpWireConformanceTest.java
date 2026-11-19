/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, senor14.
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

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_STREAMABLE;

import io.github.senor14.mcptestkit.McpAssertions;
import io.github.senor14.mcptestkit.client.HttpMcpTestClient;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Wire-level protocol conformance for the streamable endpoint.
 *
 * <p>The streamable endpoint's existing tests exercise the server through the official SDK
 * client, which asserts what the SDK <em>parsed</em>. This test speaks raw JSON-RPC over HTTP
 * instead, so it checks the bytes a non-SDK client actually receives — including error paths
 * the suite does not currently assert (unknown method, unknown tool).</p>
 */
@WithJenkins
class McpWireConformanceTest {

    @Test
    void streamableEndpointConformsAtTheWireLevel(JenkinsRule jenkins) throws Exception {
        URI endpoint = URI.create(jenkins.getURL() + MCP_SERVER_STREAMABLE);
        try (HttpMcpTestClient client = HttpMcpTestClient.connect(endpoint, Map.of(), Duration.ofSeconds(300))) {
            McpAssertions.assertThat(client)
                    .initializesSuccessfully()
                    .declaresToolsCapability()
                    .hasTools()
                    .toolNamesAreUnique()
                    .toolsHaveDescriptions()
                    .toolSchemasAreValid()
                    .toolOutputSchemasAreValid()
                    .unknownMethodYieldsMethodNotFound()
                    .unknownToolHandledGracefully();
        }
    }
}
