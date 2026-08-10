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

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.console.AnnotatedLargeText;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Covers the {@code nodeId} parameter on {@code getBuildLog} and {@code searchBuildLog}.
 *
 * <p>The payload comes from a <em>single</em> {@code echo} of a multi-line string on purpose: one
 * {@code echo} per line puts one line in each node, which would satisfy every pagination assertion
 * here with a single-page read.
 */
@WithJenkins
public class PipelineNodeLogTest {

    private static final int PAYLOAD_LINES = 30;

    /** {@value #PAYLOAD_LINES} lines from one {@code echo}, wrapped in a stage so the graph also has
     * block-boundary nodes that own no log. */
    private static String payloadPipeline() {
        return "stage('Payload') {\n"
                + "  String payload = 'PAYLOAD-LINE-1'\n"
                + "  for (int i = 2; i <= " + PAYLOAD_LINES
                + "; i++) { payload = payload + '\\n' + 'PAYLOAD-LINE-' + i }\n"
                + "  echo payload\n"
                + "}";
    }

    private WorkflowJob createPayloadJob(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(payloadPipeline(), true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        return project;
    }

    // -----------------------------------------------------------------------
    // Basic read
    // -----------------------------------------------------------------------

    @McpClientTest
    void nodeScopedReadReturnsOnlyThatNodesOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-read");
        String nodeId = payloadNodeId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            Map<String, Object> scoped = getBuildLog(client, project.getFullName(), nodeId, 0L, 1000, null);
            List<String> lines = linesOf(scoped);

            assertThat(lines).hasSize(PAYLOAD_LINES);
            assertThat(lines.get(0)).isEqualTo("PAYLOAD-LINE-1");
            assertThat(lines.get(PAYLOAD_LINES - 1)).isEqualTo("PAYLOAD-LINE-" + PAYLOAD_LINES);
            assertThat(lines).noneMatch(l -> l.contains("Finished: SUCCESS"));
            assertThat(lines).noneMatch(l -> l.contains("[Pipeline]"));

            List<String> whole = linesOf(getBuildLog(client, project.getFullName(), null, 0L, 1000, null));
            assertThat(whole.size()).isGreaterThan(lines.size());
            assertThat(whole).contains("Finished: SUCCESS");
        }
    }

    // -----------------------------------------------------------------------
    // Cursor pagination — genuinely multi-page now
    // -----------------------------------------------------------------------

    @McpClientTest
    void nodeScopedCursorPaginationCoversTheWholeNodeLog(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-cursor");
        String nodeId = payloadNodeId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            List<String> full = linesOf(getBuildLog(client, project.getFullName(), nodeId, 0L, 1000, null));
            assertThat(full).hasSize(PAYLOAD_LINES);

            // 7 does not divide 30, so the last page is partial.
            List<String> paged = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            while (pages++ < 100) {
                Map<String, Object> page = getBuildLog(client, project.getFullName(), nodeId, null, 7, cursor);
                paged.addAll(linesOf(page));
                cursor = (String) page.get("nextCursor");
                if (!Boolean.TRUE.equals(page.get("hasMoreContent")) || cursor == null) {
                    break;
                }
            }
            assertThat(paged).isEqualTo(full);
            assertThat(pages).isGreaterThan(1); // proves more than one page was actually needed
        }
    }

    // -----------------------------------------------------------------------
    // Tail read
    // -----------------------------------------------------------------------

    @McpClientTest
    void nodeScopedTailReadReportsExactTotal(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-tail");
        String nodeId = payloadNodeId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            // Negative limit takes the end-relative path, which knows the exact total.
            Map<String, Object> tail = getBuildLog(client, project.getFullName(), nodeId, 0L, -5, null);
            List<String> lines = linesOf(tail);

            assertThat(lines).hasSize(5);
            assertThat(lines.get(4)).isEqualTo("PAYLOAD-LINE-" + PAYLOAD_LINES);
            assertThat(lines.get(0)).isEqualTo("PAYLOAD-LINE-" + (PAYLOAD_LINES - 4));
            assertThat(((Number) tail.get("totalLines")).longValue()).isEqualTo(PAYLOAD_LINES);
            assertThat(((Number) tail.get("endLine")).longValue()).isEqualTo(PAYLOAD_LINES);
            assertThat(((Number) tail.get("startLine")).longValue()).isEqualTo(PAYLOAD_LINES - 4);
        }
    }

    // -----------------------------------------------------------------------
    // Omitting nodeId must not change existing behaviour
    // -----------------------------------------------------------------------

    @McpClientTest
    void omittingNodeIdMatchesWholeBuildRead(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-absent");

        try (var client = builder.jenkins(jenkins).build()) {
            List<String> viaOmitted = linesOf(getBuildLog(client, project.getFullName(), null, 0L, 1000, null));
            List<String> viaEmpty = linesOf(getBuildLog(client, project.getFullName(), "", 0L, 1000, null));

            assertThat(viaEmpty).isEqualTo(viaOmitted);
            assertThat(viaOmitted).contains("PAYLOAD-LINE-1", "Finished: SUCCESS");

            List<String> paged = new ArrayList<>();
            String cursor = null;
            int guard = 0;
            while (guard++ < 200) {
                Map<String, Object> page = getBuildLog(client, project.getFullName(), null, null, 4, cursor);
                paged.addAll(linesOf(page));
                cursor = (String) page.get("nextCursor");
                if (!Boolean.TRUE.equals(page.get("hasMoreContent")) || cursor == null) {
                    break;
                }
            }
            assertThat(paged).isEqualTo(viaOmitted);
        }
    }

    // -----------------------------------------------------------------------
    // Cursor scope enforcement
    // -----------------------------------------------------------------------

    @McpClientTest
    void cursorIsRejectedAcrossNodes(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-cursor-scope");
        WorkflowRun build = project.getLastBuild();
        String payloadNode = payloadNodeId(build);
        String otherNode = otherLoggableNodeId(build, payloadNode);

        try (var client = builder.jenkins(jenkins).build()) {
            String cursor = (String) getBuildLog(client, project.getFullName(), payloadNode, 0L, 3, null)
                    .get("nextCursor");
            assertThat(cursor).as("a 3-of-30 page must leave a resume cursor").isNotNull();

            var response = callRaw(client, "getBuildLog", params -> {
                params.put("jobFullName", project.getFullName());
                params.put("nodeId", otherNode);
                params.put("cursor", cursor);
            });
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).containsIgnoringCase("cursor was issued for");
        }
    }

    @McpClientTest
    void cursorIsRejectedAcrossJobs(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        // Identical pipelines means identical node IDs -- where a job-blind cursor serves wrong bytes.
        WorkflowJob jobA = createPayloadJob(jenkins, "node-scope-a");
        WorkflowJob jobB = createPayloadJob(jenkins, "node-scope-b");
        String nodeA = payloadNodeId(jobA.getLastBuild());
        String nodeB = payloadNodeId(jobB.getLastBuild());
        assertThat(nodeA).as("fixture assumes identical graphs").isEqualTo(nodeB);

        try (var client = builder.jenkins(jenkins).build()) {
            String cursor = (String)
                    getBuildLog(client, jobA.getFullName(), nodeA, 0L, 3, null).get("nextCursor");
            assertThat(cursor).isNotNull();

            var response = callRaw(client, "getBuildLog", params -> {
                params.put("jobFullName", jobB.getFullName());
                params.put("nodeId", nodeB);
                params.put("cursor", cursor);
            });
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).containsIgnoringCase("cursor was issued for");
        }
    }

    @McpClientTest
    void cursorIsRejectedEvenWhenTheTargetNodeOwnsNoLog(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-cursor-nolog");
        WorkflowRun build = project.getLastBuild();
        String payloadNode = payloadNodeId(build);
        String blockNode = nodeWithoutLogId(build);

        try (var client = builder.jenkins(jenkins).build()) {
            String cursor = (String) getBuildLog(client, project.getFullName(), payloadNode, 0L, 3, null)
                    .get("nextCursor");
            assertThat(cursor).isNotNull();

            // A log-less node short-circuits to an empty window. That must not become a hole in the
            // scope check: a foreign cursor accepted here reads as "you have caught up" instead of
            // "that cursor belongs to something else".
            var response = callRaw(client, "getBuildLog", params -> {
                params.put("jobFullName", project.getFullName());
                params.put("nodeId", blockNode);
                params.put("cursor", cursor);
            });
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).containsIgnoringCase("cursor was issued for");
        }
    }

    // -----------------------------------------------------------------------
    // Rejected input
    // -----------------------------------------------------------------------

    @McpClientTest
    void unknownNodeIdIsAnError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-unknown");

        try (var client = builder.jenkins(jenkins).build()) {
            var response = callRaw(client, "getBuildLog", params -> {
                params.put("jobFullName", project.getFullName());
                params.put("nodeId", "99999");
            });
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).contains("No Pipeline node '99999'");
        }
    }

    @McpClientTest
    void malformedNodeIdIsRejected(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-malformed");

        try (var client = builder.jenkins(jenkins).build()) {
            // A traversal-shaped ID must not reach the storage layer, which interpolates it into a
            // filename.
            for (String bad : new String[] {"../../../../config", "7;rm", "abc", "7 ", "-1"}) {
                var response = callRaw(client, "getBuildLog", params -> {
                    params.put("jobFullName", project.getFullName());
                    params.put("nodeId", bad);
                });
                assertThat(response.isError())
                        .as("nodeId %s must be rejected", bad)
                        .isTrue();
                assertThat(textOf(response)).contains("Invalid nodeId");
            }
        }
    }

    @McpClientTest
    void nodeIdOnNonPipelineBuildIsAnError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        var freeStyle = jenkins.createFreeStyleProject("node-freestyle");
        jenkins.buildAndAssertSuccess(freeStyle);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = callRaw(client, "getBuildLog", params -> {
                params.put("jobFullName", "node-freestyle");
                params.put("nodeId", "3");
            });
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).contains("is not a Pipeline run");

            assertThat(linesOf(getBuildLog(client, "node-freestyle", null, 0L, 100, null)))
                    .isNotEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Block-boundary node owns no log
    // -----------------------------------------------------------------------

    @McpClientTest
    void blockBoundaryNodeReturnsEmptyRatherThanError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-block");
        String blockId = nodeWithoutLogId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            Map<String, Object> result = getBuildLog(client, project.getFullName(), blockId, null, null, null);
            assertThat(linesOf(result)).isEmpty();
            // -1, like any other forward read: the total is never counted on this path. A log-less node
            // reads through the same empty source as everything else rather than a special case.
            assertThat(((Number) result.get("totalLines")).longValue()).isEqualTo(-1L);
            assertThat((Boolean) result.get("hasMoreContent")).isFalse();
            assertThat((String) result.get("nextCursor"))
                    .as("a finished node with no log has nothing left to poll for")
                    .isNull();

            Map<String, Object> search = search(client, project.getFullName(), blockId, "PAYLOAD");
            assertThat(((Number) search.get("matchCount")).intValue()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // searchBuildLog scoping
    // -----------------------------------------------------------------------

    @McpClientTest
    void searchIsScopedToTheNode(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-search");
        String nodeId = payloadNodeId(project.getLastBuild());

        try (var client = builder.jenkins(jenkins).build()) {
            // Present in the build log, absent from the echo node.
            Map<String, Object> wholeBuild = search(client, project.getFullName(), null, "Finished: SUCCESS");
            assertThat(((Number) wholeBuild.get("matchCount")).intValue()).isGreaterThan(0);

            Map<String, Object> scoped = search(client, project.getFullName(), nodeId, "Finished: SUCCESS");
            assertThat(((Number) scoped.get("matchCount")).intValue()).isZero();

            // Line numbers are relative to the node, not the build.
            Map<String, Object> hit = search(client, project.getFullName(), nodeId, "PAYLOAD-LINE-7");
            assertThat(((Number) hit.get("matchCount")).intValue()).isEqualTo(1);
            assertThat(((Number) hit.get("totalLines")).longValue()).isEqualTo(PAYLOAD_LINES);
        }
    }

    @McpClientTest
    void searchRejectsUnknownNodeRatherThanReturningEmpty(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createPayloadJob(jenkins, "node-search-unknown");

        try (var client = builder.jenkins(jenkins).build()) {
            var response = callRaw(client, "searchBuildLog", params -> {
                params.put("jobFullName", project.getFullName());
                params.put("pattern", "PAYLOAD");
                params.put("nodeId", "99999");
            });
            // Without the IllegalArgumentException carve-out this is an empty success.
            assertThat(response.isError()).isTrue();
            assertThat(textOf(response)).contains("No Pipeline node '99999'");
        }
    }

    // -----------------------------------------------------------------------
    // In-progress node must not block, and must still hand back a cursor
    // -----------------------------------------------------------------------

    @McpClientTest
    void readingARunningNodeDoesNotBlockAndKeepsPolling(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "node-running");
        project.setDefinition(new CpsFlowDefinition(
                "node {\n  echo 'RUNNING-MARKER'\n  sleep(time: 600, unit: 'SECONDS')\n  echo 'DONE'\n}", true));
        project.scheduleBuild2(0);

        // try/finally opens immediately: scheduleBuild2 is fire-and-forget, so a throw before the
        // assertions would leave a build parked in a 600s sleep.
        try {
            await().atMost(60, SECONDS).until(() -> {
                WorkflowRun b = project.getLastBuild();
                return b != null
                        && b.isBuilding()
                        && b.getLog(200).stream().anyMatch(l -> l.contains("RUNNING-MARKER"));
            });
            WorkflowRun build = project.getLastBuild();

            // The enclosing `node` block is still active. isRunning() got this wrong: not a current
            // head, so it looked complete and the resume cursor was suppressed.
            String activeBlockId = activeBlockNodeWithLogId(build);

            try (var client = builder.jenkins(jenkins).build()) {
                Map<String, Object> result = assertTimeoutPreemptively(
                        Duration.ofSeconds(30),
                        () -> getBuildLog(client, project.getFullName(), activeBlockId, 0L, 1000, null));

                assertThat(build.isBuilding())
                        .as("read must not have waited for the build")
                        .isTrue();
                assertThat(((Boolean) result.get("hasMoreContent"))).isFalse();
                assertThat((String) result.get("nextCursor"))
                        .as("a still-active node must return a cursor so the caller can poll for more")
                        .isNotNull();
            }
        } finally {
            WorkflowRun b = project.getLastBuild();
            if (b != null && b.isBuilding()) {
                b.doStop();
                await().atMost(60, SECONDS).until(() -> !b.isBuilding());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Graph helpers (in-process; getFlowNodes is covered by PipelineGraphExtensionTest)
    // -----------------------------------------------------------------------

    private static FlowExecution execution(WorkflowRun build) {
        FlowExecution exec =
                ((FlowExecutionOwner.Executable) build).asFlowExecutionOwner().getOrNull();
        assertThat(exec).isNotNull();
        return exec;
    }

    /**
     * Identified by line count, not content: {@code contains("PAYLOAD-LINE-1")} also matches {@code
     * PAYLOAD-LINE-10}, and {@link FlowGraphWalker} walks newest-first so the wrong node would win.
     */
    private static String payloadNodeId(WorkflowRun build) throws Exception {
        String best = null;
        int bestLines = 0;
        for (FlowNode node : new FlowGraphWalker(execution(build))) {
            LogAction la = node.getAction(LogAction.class);
            if (la == null) {
                continue;
            }
            int count = countLines(la.getLogText(), build);
            if (count > bestLines) {
                bestLines = count;
                best = node.getId();
            }
        }
        assertThat(best).as("no node with a log found").isNotNull();
        assertThat(bestLines)
                .as("fixture must put all %d payload lines in ONE node, else pagination is untested", PAYLOAD_LINES)
                .isEqualTo(PAYLOAD_LINES);
        return best;
    }

    private static String otherLoggableNodeId(WorkflowRun build, String excluding) {
        for (FlowNode node : new FlowGraphWalker(execution(build))) {
            if (node.getAction(LogAction.class) != null && !node.getId().equals(excluding)) {
                return node.getId();
            }
        }
        // A no-log node still accepts a nodeId, which is enough to prove the scope check.
        return nodeWithoutLogId(build);
    }

    private static String nodeWithoutLogId(WorkflowRun build) {
        for (FlowNode node : new FlowGraphWalker(execution(build))) {
            if (node.getAction(LogAction.class) == null) {
                return node.getId();
            }
        }
        throw new AssertionError("no node without a LogAction found");
    }

    /**
     * Active {@link BlockStartNode} with log output, asserted to have {@code isRunning() == false} --
     * the only combination that distinguishes the two liveness predicates. A mid-execution leaf step is
     * a current head, so a test bound to one passes with either predicate.
     */
    private static String activeBlockNodeWithLogId(WorkflowRun build) {
        for (FlowNode node : new FlowGraphWalker(execution(build))) {
            if (node instanceof BlockStartNode && node.isActive() && node.getAction(LogAction.class) != null) {
                assertThat(node.isRunning())
                        .as(
                                "node %s must be active-but-not-a-head to discriminate isActive from isRunning",
                                node.getId())
                        .isFalse();
                return node.getId();
            }
        }
        throw new AssertionError("no active BlockStartNode with a LogAction found");
    }

    private static int countLines(AnnotatedLargeText<?> text, WorkflowRun build) throws Exception {
        var buf = new ByteArrayOutputStream();
        long pos = 0;
        long end = text.length();
        while (pos < end) {
            long next = text.writeRawLogTo(pos, buf);
            if (next <= pos) {
                break;
            }
            pos = next;
        }
        String decoded = buf.toString(build.getCharset());
        if (decoded.isEmpty()) {
            return 0;
        }
        return decoded.split("\n", -1).length - (decoded.endsWith("\n") ? 1 : 0);
    }

    // -----------------------------------------------------------------------
    // MCP call helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> getBuildLog(
            McpSyncClient client, String jobFullName, String nodeId, Long skip, Integer limit, String cursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        if (nodeId != null) {
            params.put("nodeId", nodeId);
        }
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

    private static Map<String, Object> search(McpSyncClient client, String jobFullName, String nodeId, String pattern) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        params.put("pattern", pattern);
        if (nodeId != null) {
            params.put("nodeId", nodeId);
        }
        return readResult(client, "searchBuildLog", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readResult(McpSyncClient client, String tool, Map<String, Object> params) {
        var response = client.callTool(new McpSchema.CallToolRequest(tool, params));
        assertThat(response.isError())
                .as("unexpected error from %s: %s", tool, textOf(response))
                .isFalse();
        assertThat(response.content()).hasSize(1);
        DocumentContext ctx =
                JsonPath.using(Configuration.defaultConfiguration()).parse(textOf(response));
        return ctx.read("$.result", Map.class);
    }

    private static McpSchema.CallToolResult callRaw(
            McpSyncClient client, String tool, java.util.function.Consumer<Map<String, Object>> fill) {
        Map<String, Object> params = new HashMap<>();
        fill.accept(params);
        return client.callTool(new McpSchema.CallToolRequest(tool, params));
    }

    private static String textOf(McpSchema.CallToolResult response) {
        return ((McpSchema.TextContent) response.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(Map<String, Object> result) {
        Object lines = result.get("lines");
        return lines == null ? List.of() : new ArrayList<>((List<String>) lines);
    }
}
