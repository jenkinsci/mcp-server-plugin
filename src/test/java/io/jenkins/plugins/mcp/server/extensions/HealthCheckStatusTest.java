/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, the Jenkins MCP Server plugin contributors.
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

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestCrumbIssuerMcpExtension;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import jenkins.health.HealthCheck;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
@ExtendWith(TestCrumbIssuerMcpExtension.class)
public class HealthCheckStatusTest {

    public static volatile boolean check1Healthy = true;
    public static volatile boolean check2Healthy = true;
    public static volatile boolean check2Throw = false;

    @TestExtension
    public static class CustomHealthCheck1 implements HealthCheck {
        @Override
        public String getName() {
            return "CustomCheck1";
        }

        @Override
        public boolean check() {
            return check1Healthy;
        }
    }

    @TestExtension
    public static class CustomHealthCheck2 implements HealthCheck {
        @Override
        public String getName() {
            return "CustomCheck2";
        }

        @Override
        public boolean check() {
            if (check2Throw) {
                throw new IllegalStateException("Simulated health check crash");
            }
            return check2Healthy;
        }
    }

    @McpClientTest
    void testGetStatusWithHealthyChecks(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        check1Healthy = true;
        check2Healthy = true;
        check2Throw = false;

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getStatus", Map.of());
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).containsEntry("Healthy", true);
                assertThat(contentMap).doesNotContainKey("Failing health checks");

                List<Map<String, Object>> healthChecks = documentContext.read("$.result['Health checks']");
                assertThat(healthChecks).isNotNull();
                assertThat(healthChecks)
                        .anySatisfy(check -> {
                            assertThat(check).containsEntry("name", "CustomCheck1");
                            assertThat(check).containsEntry("healthy", true);
                        })
                        .anySatisfy(check -> {
                            assertThat(check).containsEntry("name", "CustomCheck2");
                            assertThat(check).containsEntry("healthy", true);
                        });
            });
        }
    }

    @McpClientTest
    void testGetStatusWithFailingHealthCheck(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        check1Healthy = true;
        check2Healthy = false;
        check2Throw = false;

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getStatus", Map.of());
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).containsEntry("Healthy", false);

                List<String> failingChecks = documentContext.read("$.result['Failing health checks']");
                assertThat(failingChecks).containsExactly("CustomCheck2");

                List<Map<String, Object>> healthChecks = documentContext.read("$.result['Health checks']");
                assertThat(healthChecks).anySatisfy(check -> {
                    assertThat(check).containsEntry("name", "CustomCheck2");
                    assertThat(check).containsEntry("healthy", false);
                });
            });
        }
    }

    @McpClientTest
    void testGetStatusWithExceptionInHealthCheck(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        check1Healthy = true;
        check2Healthy = false;
        check2Throw = true;

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getStatus", Map.of());
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();

            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).containsEntry("Healthy", false);

                List<String> failingChecks = documentContext.read("$.result['Failing health checks']");
                assertThat(failingChecks).contains("CustomCheck2");

                List<Map<String, Object>> healthChecks = documentContext.read("$.result['Health checks']");
                assertThat(healthChecks).anySatisfy(check -> {
                    assertThat(check).containsEntry("name", "CustomCheck2");
                    assertThat(check).containsEntry("healthy", false);
                    assertThat(check).containsKey("error");
                });
            });
        }
    }
}
