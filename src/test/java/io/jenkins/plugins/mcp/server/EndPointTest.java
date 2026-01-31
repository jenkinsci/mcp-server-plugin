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

import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URL;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class EndPointTest {
    @McpClientTest
    void testListTools(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tools = client.listTools();
            assertThat(tools.tools())
                    .extracting(McpSchema.Tool::name)
                    .containsOnly(
                            "whoAmI",
                            "getBuildLog",
                            "searchBuildLog",
                            "triggerBuild",
                            "updateBuild",
                            "getJobs",
                            "getBuild",
                            "getJob",
                            "getJobScm",
                            "getBuildScm",
                            "findJobsWithScmUrl",
                            "getBuildChangeSets",
                            "getStatus",
                            "getTestResults",
                            "getFlakyFailures",
                            "getQueueItem");
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
