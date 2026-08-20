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

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Window-arithmetic edge cases in {@code getBuildLog}. Lowering {@code limit.max} from its default of
 * 10000 makes the ceiling reachable with a small fixture.
 */
@WithJenkins
class BuildLogWindowBoundsTest {

    private static final String LIMIT_MAX = "io.jenkins.plugins.mcp.server.extensions.BuildLogsExtension.limit.max";

    private WorkflowJob createJob(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition("for (int i = 1; i <= 30; i++) { echo 'L-' + i }", true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        return project;
    }

    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "-5")
    void negativeCeilingDoesNotInvertAForwardRead(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createJob(jenkins, "bounds-negative-max");

        try (var client = builder.jenkins(jenkins).build()) {
            // Integer.decode accepts "-5", so the default is not restored. Unfloored, a negative
            // ceiling inverts limit's sign and reroutes this forward read into the tail path.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), 0L, 100);
            List<String> lines = linesOf(result);

            assertThat(lines).isNotEmpty();
            assertThat(lines.get(0))
                    .as("a forward read must start at the beginning, not the end")
                    .isEqualTo("Started");
            assertThat(((Number) result.get("startLine")).longValue())
                    .as("startLine must describe a forward window")
                    .isEqualTo(1L);
        }
    }

    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "10")
    void clampedTailWindowStaysRecoverable(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createJob(jenkins, "bounds-clamped-tail");

        try (var client = builder.jenkins(jenkins).build()) {
            // Requested window falls entirely inside the evicted region. Unclamped, this returned zero
            // lines with hasMoreContent=true and nextCursor=null -- a dead end.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), -24L, 10);
            List<String> lines = linesOf(result);

            assertThat(lines)
                    .as("a clamped window must still return the lines it retained")
                    .isNotEmpty();

            long startLine = ((Number) result.get("startLine")).longValue();
            long endLine = ((Number) result.get("endLine")).longValue();
            assertThat(endLine - startLine + 1)
                    .as("reported line range must match the number of lines returned")
                    .isEqualTo(lines.size());

            boolean more = (Boolean) result.get("hasMoreContent");
            if (more) {
                assertThat((String) result.get("nextCursor"))
                        .as("hasMoreContent=true without a cursor leaves the caller stuck")
                        .isNotNull();
            }
        }
    }

    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "10")
    void windowBeyondTheRetainedTailIsStillTheRequestedWindow(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createJob(jenkins, "bounds-exact-window");
        List<String> full = project.getLastBuild().getLog(Integer.MAX_VALUE);
        int total = full.size();

        try (var client = builder.jenkins(jenkins).build()) {
            // The ring buffer only retained the last 10 lines, but the caller asked for the 10 lines
            // starting 24 from the end. Sliding the window forward to what happened to be retained
            // would answer a different question with no indication in the response.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), -24L, 10);

            assertThat(linesOf(result))
                    .as("the requested window, not the retained one")
                    .containsExactlyElementsOf(full.subList(total - 24, total - 14));
            assertThat(((Number) result.get("startLine")).longValue()).isEqualTo(total - 23L);
            assertThat(((Number) result.get("endLine")).longValue()).isEqualTo(total - 14L);
            assertThat(((Number) result.get("totalLines")).longValue())
                    .as("an end-relative read still reports the exact total")
                    .isEqualTo(total);
            assertThat((Boolean) result.get("hasMoreContent")).isTrue();
            assertThat((String) result.get("nextCursor")).isNotNull();
        }
    }

    // Pinned even though this is the default: the ceiling is a JVM-global system property, and the
    // other methods here move it, so an unpinned test reads whichever value happens to be installed.
    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "10000")
    void extremeNegativeSkipDoesNotOverflowIntoADeadEnd(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createJob(jenkins, "bounds-overflow");
        long total = project.getLastBuild().getLog(Integer.MAX_VALUE).size();

        try (var client = builder.jenkins(jenkins).build()) {
            // skip is an unvalidated Long from JSON. Both bounds have to be negative to reach the
            // underflow: `total + skip - limit` wraps to a huge positive, so the window lands past the
            // end of the log, past the retained-tail guard, and `resolvedSkip + limit` then overflows
            // back to negative -- leaving hasMoreContent=true with no lines and no cursor to retry with.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), Long.MIN_VALUE, -100);

            assertThat(linesOf(result))
                    .as("a lookback longer than any log means 'from the start'")
                    .hasSize((int) total);
            assertThat(((Number) result.get("startLine")).longValue()).isEqualTo(1L);
            assertThat(((Number) result.get("endLine")).longValue()).isEqualTo(total);
            assertThat((Boolean) result.get("hasMoreContent")).isFalse();
        }
    }

    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "10")
    void clampedTailReportsAccurateLineNumbers(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createJob(jenkins, "bounds-clamped-numbers");

        try (var client = builder.jenkins(jenkins).build()) {
            long total = project.getLastBuild().getLog(Integer.MAX_VALUE).size();

            // Plain tail read within the ceiling: the numbers must line up exactly.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), 0L, -10);
            List<String> lines = linesOf(result);

            assertThat(lines).hasSize(10);
            assertThat(((Number) result.get("totalLines")).longValue()).isEqualTo(total);
            assertThat(((Number) result.get("endLine")).longValue()).isEqualTo(total);
            assertThat(((Number) result.get("startLine")).longValue()).isEqualTo(total - 9);
        }
    }

    @McpClientTest
    @SetSystemProperty(key = LIMIT_MAX, value = "10")
    void nodeScopedWindowBeyondTheRetainedTailIsStillTheRequestedWindow(
            JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createNodePayloadJob(jenkins, "bounds-node-window");
        String nodeId = payloadNodeId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            // Same second-pass fallback as above, but over a *node* source, whose length is frozen at
            // construction and whose liveness was sampled before the snapshot -- the opposite of a
            // run's log on both counts. The whole-build cases here never exercise that combination.
            Map<String, Object> result = getBuildLog(client, project.getFullName(), nodeId, -24L, 10);

            assertThat(linesOf(result))
                    .as("the requested window, not the retained one")
                    .containsExactly(
                            "PAYLOAD-LINE-7",
                            "PAYLOAD-LINE-8",
                            "PAYLOAD-LINE-9",
                            "PAYLOAD-LINE-10",
                            "PAYLOAD-LINE-11",
                            "PAYLOAD-LINE-12",
                            "PAYLOAD-LINE-13",
                            "PAYLOAD-LINE-14",
                            "PAYLOAD-LINE-15",
                            "PAYLOAD-LINE-16");
            assertThat(((Number) result.get("startLine")).longValue()).isEqualTo(7L);
            assertThat(((Number) result.get("endLine")).longValue()).isEqualTo(16L);
            assertThat(((Number) result.get("totalLines")).longValue())
                    .as("the count from the first pass survives the second")
                    .isEqualTo(NODE_PAYLOAD_LINES);
            assertThat((Boolean) result.get("hasMoreContent")).isTrue();
            assertThat((String) result.get("nextCursor"))
                    .as("hasMoreContent=true without a cursor leaves the caller stuck")
                    .isNotNull();
        }
    }

    // -----------------------------------------------------------------------

    private static final int NODE_PAYLOAD_LINES = 30;

    /** One {@code echo} of a multi-line string, so a single flow node owns all {@value #NODE_PAYLOAD_LINES} lines. */
    private WorkflowJob createNodePayloadJob(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(
                "String payload = 'PAYLOAD-LINE-1'\n"
                        + "for (int i = 2; i <= " + NODE_PAYLOAD_LINES
                        + "; i++) { payload = payload + '\\n' + 'PAYLOAD-LINE-' + i }\n"
                        + "echo payload",
                true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        return project;
    }

    private static String payloadNodeId(WorkflowRun build) throws Exception {
        var execution =
                ((FlowExecutionOwner.Executable) build).asFlowExecutionOwner().getOrNull();
        for (FlowNode node : new FlowGraphWalker(execution)) {
            LogAction logAction = node.getAction(LogAction.class);
            if (logAction != null && logAction.getLogText().length() > 0) {
                var buf = new java.io.ByteArrayOutputStream();
                logAction.getLogText().writeLogTo(0, buf);
                if (buf.toString(build.getCharset()).contains("PAYLOAD-LINE-" + NODE_PAYLOAD_LINES)) {
                    return node.getId();
                }
            }
        }
        throw new AssertionError("no flow node owns the payload log");
    }

    private static Map<String, Object> getBuildLog(
            McpSyncClient client, String jobFullName, String nodeId, Long skip, Integer limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        params.put("nodeId", nodeId);
        if (skip != null) {
            params.put("skip", skip);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        var response = client.callTool(new McpSchema.CallToolRequest("getBuildLog", params));
        assertThat(response.isError())
                .as(
                        "unexpected error: %s",
                        ((McpSchema.TextContent) response.content().get(0)).text())
                .isFalse();
        return JsonPath.using(Configuration.defaultConfiguration())
                .parse(((McpSchema.TextContent) response.content().get(0)).text())
                .read("$.result", Map.class);
    }

    private static Map<String, Object> getBuildLog(McpSyncClient client, String jobFullName, Long skip, Integer limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        if (skip != null) {
            params.put("skip", skip);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        var response = client.callTool(new McpSchema.CallToolRequest("getBuildLog", params));
        assertThat(response.isError())
                .as(
                        "unexpected error: %s",
                        ((McpSchema.TextContent) response.content().get(0)).text())
                .isFalse();
        return JsonPath.using(Configuration.defaultConfiguration())
                .parse(((McpSchema.TextContent) response.content().get(0)).text())
                .read("$.result", Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(Map<String, Object> result) {
        Object lines = result.get("lines");
        return lines == null ? List.of() : new ArrayList<>((List<String>) lines);
    }
}
