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

package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.junit.TestUtils.MIN_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class TreePruneMcpServerTest {

    @McpClientTest
    void testMcpToolCallGetBuildWithTreePrune(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuild", Map.of("jobFullName", project.getFullName(), "tree", "number"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).hasSize(2).extractingByKey("number").isEqualTo(build.getNumber());
            });
        }
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuild", Map.of("jobFullName", project.getFullName(), "tree", "number,result"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).hasSize(3).extractingByKey("number").isEqualTo(build.getNumber());
            });
        }
    }

    @McpClientTest
    void testMcpToolCallGetJobsWithAuth(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        for (int i = 0; i < 2; i++) {
            jenkins.createProject(WorkflowJob.class, "demo-job" + i);
        }

        var folder = jenkins.createFolder("test");

        for (int i = 0; i < 20; i++) {
            folder.createProject(WorkflowJob.class, "sub-demo-job" + i);
        }

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                        "getJobs", Map.of("parentFullName", "test", "limit", 10, "tree", "name"));

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                        .parse(((McpSchema.TextContent) response.content().get(0)).text());
                var contentList = documentContext.read("$.result", List.class);
                assertThat(contentList).hasSize(10);
                assertThat(contentList.get(0)).isInstanceOfSatisfying(Map.class, contentMap -> {
                    // var contentMap = objectMapper.readValue(textContent.text(), Map.class);
                    assertThat(contentMap).hasSize(2).containsEntry("name", "sub-demo-job0");
                });
            }
        }
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }
}
