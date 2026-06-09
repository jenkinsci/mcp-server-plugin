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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.jenkins.plugins.mcp.server.tool.ToolResponse;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minidev.json.JSONArray;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class BuildLogExtensionTest {
    ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("buildLogTestParameters")
    void testMcpToolCallGetBuildLog(
            Long skip,
            Integer limit,
            Integer expectedContentSize,
            boolean hasMoreContent,
            List<String> expectedLines,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            if (limit != null) {
                params.put("limit", limit);
            }
            if (skip != null) {
                params.put("skip", skip);
            }
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            response.content().forEach(content -> {
                assertThat(content.type()).isEqualTo("text");
                assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                    DocumentContext documentContext =
                            JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                    var contentMap = documentContext.read("$.result", Map.class);
                    assertThat((Boolean) contentMap.get("hasMoreContent"))
                            .matches(expected -> expected == hasMoreContent);

                    // Verify new fields exist
                    assertThat(contentMap.get("totalLines")).isNotNull();
                    assertThat(contentMap.get("startLine")).isNotNull();
                    assertThat(contentMap.get("endLine")).isNotNull();

                    JSONArray linesArray = (JSONArray) contentMap.get("lines");
                    assertThat(linesArray.size()).isEqualTo(expectedContentSize);
                    for (Object lineNode : linesArray) {
                        assertThat(((String) lineNode).trim()).isNotEmpty();
                    }
                    if (!expectedLines.isEmpty()) {
                        List lines = objectMapper.convertValue(linesArray, List.class);
                        assertThat(lines).isEqualTo(expectedLines);
                    }
                });
            });
        }
    }

    @McpClientTest
    void testMcpToolCallWithIncorrectBuildNumber(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            params.put("buildNumber", 100);

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            var contentMap = new ObjectMapper()
                    .readValue(((McpSchema.TextContent) response.content().get(0)).text(), Map.class);
            assertThat(contentMap.get("result")).isNull();
            assertThat((String) contentMap.get("message")).isEqualTo(ToolResponse.NO_DATA_MSG);
        }
    }

    static Stream<Arguments> buildLogTestParameters() {
        WorkflowRun workflowRun;
        // all lines Started;[Pipeline] Start of Pipeline;[Pipeline] End of Pipeline;Finished: SUCCESS
        // "Started;[Pipeline] Start of Pipeline;[Pipeline] End of Pipeline;Finished: SUCCESS"
        Stream<Arguments> baseArgs = Stream.of(
                Arguments.of(
                        null,
                        100,
                        4,
                        false,
                        List.of(
                                "Started",
                                "[Pipeline] Start of Pipeline",
                                "[Pipeline] End of Pipeline",
                                "Finished: SUCCESS")),
                Arguments.of(
                        1L,
                        100,
                        3,
                        false,
                        List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(
                        null,
                        3,
                        3,
                        true,
                        List.of("Started", "[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(-1L, 100, 1, false, List.of("Finished: SUCCESS")),
                Arguments.of(
                        -4L,
                        3,
                        3,
                        true,
                        List.of("Started", "[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(-2L, 3, 2, false, List.of("[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(
                        -3L,
                        100,
                        3,
                        false,
                        List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(-2L, 2, 2, false, List.of("[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(-1L, 10, 1, false, List.of("Finished: SUCCESS")),
                Arguments.of(-1L, -2, 2, true, List.of("[Pipeline] Start of Pipeline", "[Pipeline] End of Pipeline")),
                Arguments.of(2L, -2, 2, true, List.of("Started", "[Pipeline] Start of Pipeline")),
                // Test skip=0, limit<0: should return last -limit lines
                Arguments.of(0L, -2, 2, false, List.of("[Pipeline] End of Pipeline", "Finished: SUCCESS")),
                Arguments.of(0L, -1, 1, false, List.of("Finished: SUCCESS")));

        // 扩展成 2 倍：每组参数 + 两个不同的 McpClientProvider
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @McpClientTest
    void testSearchBuildLog(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "search-test-job");
        // Create a job with predictable log output
        project.setDefinition(new CpsFlowDefinition(
                "echo 'Starting build'\n"
                        + "echo 'Building project'\n"
                        + "echo 'ERROR: Something went wrong'\n"
                        + "echo 'WARNING: Check configuration'\n"
                        + "echo 'Build completed'",
                true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Test simple string search
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            params.put("pattern", "ERROR");
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("searchBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            var content = response.content().get(0);
            assertThat(content.type()).isEqualTo("text");
            assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);

                assertThat(contentMap.get("pattern")).isNotNull();
                assertThat((String) contentMap.get("pattern")).isEqualTo("ERROR");
                assertThat(contentMap.get("matchCount")).isNotNull();
                assertThat((Integer) contentMap.get("matchCount")).isGreaterThan(0);
                assertThat(contentMap.get("matches")).isNotNull();
            });
        }
    }

    @McpClientTest
    void testSearchBuildLogWithRegex(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "regex-test-job");
        project.setDefinition(
                new CpsFlowDefinition("echo 'Error 123'\n" + "echo 'Error 456'\n" + "echo 'Success'", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            params.put("pattern", "Error \\d+");
            params.put("useRegex", true);
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("searchBuildLog", params);

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);

            var content = response.content().get(0);
            assertThat(content.type()).isEqualTo("text");
            assertThat(content).isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);

                assertThat((Boolean) contentMap.get("useRegex")).isTrue();
                assertThat((Integer) contentMap.get("matchCount")).isGreaterThanOrEqualTo(2);
            });
        }
    }

    @McpClientTest
    void testGetBuildLogCursorPagination(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "cursor-job");
        project.setDefinition(new CpsFlowDefinition("for (int i = 1; i <= 25; i++) { echo \"LINE-\" + i }", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // A single large read returns the whole log; forward reads do not compute the total.
            Map<String, Object> full = getBuildLogResult(client, project.getFullName(), 0L, 1000, null);
            List<String> fullLines = toStringList(full.get("lines"));
            assertThat(fullLines.size()).isGreaterThanOrEqualTo(25);
            assertThat((Boolean) full.get("hasMoreContent")).isFalse();
            assertThat(((Number) full.get("totalLines")).longValue()).isEqualTo(-1L);

            // Page through the same log 5 lines at a time using the returned cursor.
            List<String> paged = new ArrayList<>();
            String cursor = null;
            boolean more = true;
            int guard = 0;
            while (more && guard++ < 1000) {
                Map<String, Object> page = getBuildLogResult(client, project.getFullName(), 0L, 5, cursor);
                paged.addAll(toStringList(page.get("lines")));
                more = (Boolean) page.get("hasMoreContent");
                cursor = (String) page.get("nextCursor");
                if (cursor == null) {
                    break;
                }
            }
            assertThat(paged).isEqualTo(fullLines);
        }
    }

    @McpClientTest
    void testCursorBoundToBuildNumber(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "cursor-bind-job");
        project.setDefinition(new CpsFlowDefinition("for (int i = 1; i <= 5; i++) { echo \"A-\" + i }", true));
        project.scheduleBuild2(0).get();
        // Second build with different log content
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS)
                .until(() ->
                        project.getLastBuild() != null && project.getLastBuild().getNumber() == 2);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Get a cursor from build #1
            Map<String, Object> params = new HashMap<>();
            params.put("jobFullName", project.getFullName());
            params.put("buildNumber", 1);
            params.put("limit", 2);
            Map<String, Object> page = readResult(client, "getBuildLog", params);
            String cursor = (String) page.get("nextCursor");
            assertThat(cursor).isNotNull();

            // Reusing it against build #2 must be rejected
            Map<String, Object> wrong = new HashMap<>();
            wrong.put("jobFullName", project.getFullName());
            wrong.put("buildNumber", 2);
            wrong.put("cursor", cursor);
            var response = client.callTool(new McpSchema.CallToolRequest("getBuildLog", wrong));
            assertThat(response.isError()).isTrue();
        }
    }

    @McpClientTest
    void testTailReadReportsExactTotalLines(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "tail-total-job");
        project.setDefinition(new CpsFlowDefinition("for (int i = 1; i <= 10; i++) { echo \"LINE-\" + i }", true));
        project.scheduleBuild2(0).get();
        await().atMost(10, SECONDS).until(() -> project.getLastBuild() != null);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            long total = project.getLastBuild().getLog(Integer.MAX_VALUE).size();

            // End-relative read: totalLines is exact, and only the last 3 lines are returned.
            Map<String, Object> tail = getBuildLogResult(client, project.getFullName(), 0L, -3, null);
            assertThat(toStringList(tail.get("lines"))).hasSize(3);
            assertThat(((Number) tail.get("totalLines")).longValue()).isEqualTo(total);
            assertThat(((Number) tail.get("endLine")).longValue()).isEqualTo(total);
        }
    }

    @McpClientTest
    void testReadingLogOfRunningBuildDoesNotBlock(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "running-job");
        // Emit a couple of lines, then keep the build in progress with a long sleep.
        project.setDefinition(new CpsFlowDefinition(
                "echo 'MARKER-ONE'\necho 'MARKER-TWO'\nsleep(time: 600, unit: 'SECONDS')\necho 'DONE'", true));
        project.scheduleBuild2(0);

        await().atMost(30, SECONDS).until(() -> {
            WorkflowRun b = project.getLastBuild();
            return b != null && b.isBuilding() && b.getLog(100).stream().anyMatch(l -> l.contains("MARKER-TWO"));
        });
        WorkflowRun build = project.getLastBuild();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Previously these calls used Run#writeWholeLogTo, which blocks until the build finishes.
            // They must now return a snapshot promptly while the build is still in progress.
            Map<String, Object> logResult = assertTimeoutPreemptively(
                    Duration.ofSeconds(30), () -> getBuildLogResult(client, project.getFullName(), 0L, 1000, null));
            assertThat(toStringList(logResult.get("lines"))).anyMatch(l -> l.contains("MARKER-TWO"));
            assertThat(build.isBuilding()).isTrue();

            Map<String, Object> searchResult = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                Map<String, Object> params = new HashMap<>();
                params.put("jobFullName", project.getFullName());
                params.put("pattern", "MARKER");
                var response = client.callTool(new McpSchema.CallToolRequest("searchBuildLog", params));
                assertThat(response.isError()).isFalse();
                return JsonPath.using(Configuration.defaultConfiguration())
                        .parse(((McpSchema.TextContent) response.content().get(0)).text())
                        .read("$.result", Map.class);
            });
            assertThat((Integer) searchResult.get("matchCount")).isGreaterThanOrEqualTo(2);
        } finally {
            build.doStop();
            await().atMost(30, SECONDS).until(() -> !build.isBuilding());
        }
    }

    private Map<String, Object> getBuildLogResult(
            McpSyncClient client, String jobFullName, Long skip, Integer limit, String cursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        if (skip != null) {
            params.put("skip", skip);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return readResult(client, "getBuildLog", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readResult(McpSyncClient client, String tool, Map<String, Object> params) {
        var response = client.callTool(new McpSchema.CallToolRequest(tool, params));
        assertThat(response.isError()).isFalse();
        assertThat(response.content()).hasSize(1);
        String text = ((McpSchema.TextContent) response.content().get(0)).text();
        DocumentContext documentContext =
                JsonPath.using(Configuration.defaultConfiguration()).parse(text);
        return documentContext.read("$.result", Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object linesNode) {
        return new ArrayList<>((List<String>) linesNode);
    }
}
