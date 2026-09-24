/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Nikolay Versus.
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
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Verifies that tools with a {@code defaultTree} return a compact projection when the client
 * does not pass the {@code tree} parameter, while an explicit {@code tree} still takes
 * precedence over the default.
 */
@WithJenkins
class DefaultTreeMcpServerTest {

    @McpClientTest
    void testGetBuildWithoutTreeReturnsCompactDefault(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getBuild", Map.of("jobFullName", project.getFullName()));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                // Compact default projection: the requested fields are present...
                assertThat(contentMap).extractingByKey("number").isEqualTo(build.getNumber());
                assertThat(contentMap).containsKeys("url", "result", "building", "timestamp", "duration");
                // ...and the heavy ones (actions, changeSets, artifacts, ...) are not.
                assertThat(contentMap).doesNotContainKeys("actions", "changeSets", "artifacts", "culprits");
            });
        }
    }

    @McpClientTest
    void testExplicitTreeOverridesDefault(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuild", Map.of("jobFullName", project.getFullName(), "tree", "number"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                // Explicit tree wins over the default: only _class + number.
                assertThat(contentMap).hasSize(2).extractingByKey("number").isEqualTo(build.getNumber());
            });
        }
    }

    @McpClientTest
    void testStarTreeReturnsFullObject(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuild", Map.of("jobFullName", project.getFullName(), "tree", "*"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                // "*" bypasses the compact default: the full exported model is back.
                assertThat(contentMap).containsKeys("number", "result", "actions", "changeSets");
            });
        }
    }

    @McpClientTest
    void testGetJobsWithoutTreeReturnsCompactDefault(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getJobs", Map.of());

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                List<Map<String, Object>> jobs = documentContext.read("$.result", List.class);
                assertThat(jobs).hasSize(1);
                var job = jobs.get(0);
                assertThat(job).extractingByKey("name").isEqualTo("demo-job");
                assertThat(job).containsKeys("fullName", "url", "color", "lastBuild");
                // The bulky default export must not leak in without an explicit tree.
                assertThat(job).doesNotContainKeys("builds", "healthReport", "actions", "property");
            });
        }
    }
}
