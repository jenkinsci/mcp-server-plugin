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
import com.jayway.jsonpath.JsonPath;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Covers {@code getFlowNodes}, the discovery tool that makes {@code nodeId} usable. */
@WithJenkins
public class PipelineGraphExtensionTest {

    private static final String TWO_STAGE_PIPELINE =
            "stage('Build') {\n  echo 'BUILD-OUTPUT'\n}\nstage('Test') {\n  echo 'TEST-OUTPUT'\n}";

    private WorkflowJob createTwoStageJob(JenkinsRule jenkins, String name) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(TWO_STAGE_PIPELINE, true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        return project;
    }

    // -----------------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------------

    @McpClientTest
    void listsNodesInExecutionOrderWithStageNames(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-list");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> nodes = getFlowNodes(client, project.getFullName(), null, null);
            assertThat(nodes).isNotEmpty();

            for (Map<String, Object> node : nodes) {
                assertThat((String) node.get("id")).isNotBlank();
                assertThat((String) node.get("type")).isNotBlank();
                assertThat((String) node.get("status")).isIn("SUCCESS", "FAILED", "IN_PROGRESS");
                assertThat(node).containsKey("hasLog");
            }

            List<String> stageSequence = nodes.stream()
                    .map(n -> (String) n.get("stageName"))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            assertThat(stageSequence).containsExactly("Build", "Test");

            // Jenkins names the echo step "Print Message", not "echo".
            assertThat(nodes)
                    .anySatisfy(n -> {
                        assertThat((String) n.get("displayName")).isEqualTo("Print Message");
                        assertThat((String) n.get("stageName")).isEqualTo("Build");
                        assertThat((Boolean) n.get("hasLog")).isTrue();
                    })
                    .anySatisfy(n -> {
                        assertThat((String) n.get("displayName")).isEqualTo("Print Message");
                        assertThat((String) n.get("stageName")).isEqualTo("Test");
                        assertThat((Boolean) n.get("hasLog")).isTrue();
                    });

            // The node holding a stage's log is the outer "Stage : Start" block, whose LabelAction is
            // on its child. Unresolved, a stageName filter drops exactly that node.
            assertThat(nodes)
                    .filteredOn(n -> Boolean.TRUE.equals(n.get("hasLog")))
                    .allSatisfy(n -> assertThat((String) n.get("stageName"))
                            .as("log-bearing node %s (%s) must belong to a stage", n.get("id"), n.get("displayName"))
                            .isNotNull());

            List<Integer> ids = nodes.stream()
                    .map(n -> Integer.parseInt((String) n.get("id")))
                    .toList();
            assertThat(ids).isSorted();
        }
    }

    // -----------------------------------------------------------------------
    // The round trip that closes the discovery gap
    // -----------------------------------------------------------------------

    @McpClientTest
    void discoveredIdsWorkAsNodeIdInGetBuildLog(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-roundtrip");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> loggable = getFlowNodes(client, project.getFullName(), null, true);
            assertThat(loggable).as("at least one node must own a log").isNotEmpty();

            // The contract that makes nodeId reachable: every hasLog=true ID must be accepted.
            List<String> allText = new ArrayList<>();
            for (Map<String, Object> node : loggable) {
                assertThat((Boolean) node.get("hasLog")).isTrue();
                Map<String, Object> logResult = getBuildLog(client, project.getFullName(), (String) node.get("id"));
                allText.addAll(linesOf(logResult));
            }
            assertThat(allText).contains("BUILD-OUTPUT", "TEST-OUTPUT");
        }
    }

    @McpClientTest
    void nodesReportedWithoutLogsReturnEmptyLogs(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-nolog");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> all = getFlowNodes(client, project.getFullName(), null, null);
            List<Map<String, Object>> withoutLogs = all.stream()
                    .filter(n -> !Boolean.TRUE.equals(n.get("hasLog")))
                    .toList();
            assertThat(withoutLogs)
                    .as("a staged pipeline has block boundary nodes")
                    .isNotEmpty();

            for (Map<String, Object> node : withoutLogs) {
                Map<String, Object> logResult = getBuildLog(client, project.getFullName(), (String) node.get("id"));
                assertThat(linesOf(logResult))
                        .as("node %s reported hasLog=false", node.get("id"))
                        .isEmpty();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Filters
    // -----------------------------------------------------------------------

    @McpClientTest
    void stageFilterNarrowsToThatStage(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-stagefilter");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> testStage = getFlowNodes(client, project.getFullName(), "Test", null);
            assertThat(testStage).isNotEmpty();
            assertThat(testStage)
                    .allSatisfy(n -> assertThat((String) n.get("stageName")).isEqualTo("Test"));

            List<Map<String, Object>> all = getFlowNodes(client, project.getFullName(), null, null);
            assertThat(testStage.size()).isLessThan(all.size());

            // Both directions. Asserting only that every returned node says "Test" passes happily while
            // the filter drops nodes -- and it did: a stage's end node resolves its stage through its
            // start node, whose label was not yet indexed at the point the walk evaluated it, so the
            // filtered listing omitted a node the unfiltered listing attributes to the same stage.
            List<String> expected = all.stream()
                    .filter(n -> "Test".equals(n.get("stageName")))
                    .map(n -> (String) n.get("id"))
                    .toList();
            assertThat(testStage.stream().map(n -> (String) n.get("id")).toList())
                    .as("the stage filter must return every node the unfiltered listing puts in that stage")
                    .containsExactlyElementsOf(expected);

            assertThat(getFlowNodes(client, project.getFullName(), "test", null))
                    .isEmpty();
            assertThat(getFlowNodes(client, project.getFullName(), "Nonexistent", null))
                    .isEmpty();
        }
    }

    @McpClientTest
    void onlyWithLogsFilterMatchesTheHasLogFlag(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-logfilter");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> all = getFlowNodes(client, project.getFullName(), null, null);
            List<Map<String, Object>> filtered = getFlowNodes(client, project.getFullName(), null, true);

            long expected = all.stream()
                    .filter(n -> Boolean.TRUE.equals(n.get("hasLog")))
                    .count();
            assertThat(filtered).hasSize((int) expected);
            assertThat(filtered)
                    .allSatisfy(n -> assertThat((Boolean) n.get("hasLog")).isTrue());
            assertThat(filtered.size()).isLessThan(all.size());
        }
    }

    @McpClientTest
    void bothFiltersCombine(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createTwoStageJob(jenkins, "graph-bothfilters");

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> nodes = getFlowNodes(client, project.getFullName(), "Build", true);
            assertThat(nodes).isNotEmpty();
            assertThat(nodes).allSatisfy(n -> {
                assertThat((String) n.get("stageName")).isEqualTo("Build");
                assertThat((Boolean) n.get("hasLog")).isTrue();
            });

            List<String> text = new ArrayList<>();
            for (Map<String, Object> node : nodes) {
                text.addAll(linesOf(getBuildLog(client, project.getFullName(), (String) node.get("id"))));
            }
            assertThat(text).contains("BUILD-OUTPUT");
            assertThat(text).doesNotContain("TEST-OUTPUT");
        }
    }

    // -----------------------------------------------------------------------
    // Parallel branches are not stages
    // -----------------------------------------------------------------------

    @McpClientTest
    void parallelBranchNamesAreNotReportedAsStages(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "graph-parallel");
        project.setDefinition(new CpsFlowDefinition("""
                stage('Test') {
                  parallel(
                    linux: { echo 'LINUX-OUTPUT' },
                    windows: { echo 'WINDOWS-OUTPUT' }
                  )
                }
                """, true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> all = getFlowNodes(client, project.getFullName(), null, null);

            // A branch start carries a LabelAction too. Taking it as the stage reports "linux"/"windows".
            assertThat(all)
                    .as("no node may report a branch name as its stage")
                    .allSatisfy(n -> assertThat((String) n.get("stageName")).isNotIn("linux", "windows"));

            // ...and the steps inside the branches must still be reachable through the stage filter.
            List<Map<String, Object>> testStage = getFlowNodes(client, project.getFullName(), "Test", true);
            List<String> text = new ArrayList<>();
            for (Map<String, Object> node : testStage) {
                text.addAll(linesOf(getBuildLog(client, project.getFullName(), (String) node.get("id"))));
            }
            assertThat(text).contains("LINUX-OUTPUT", "WINDOWS-OUTPUT");
        }
    }

    // -----------------------------------------------------------------------
    // Failure reporting
    // -----------------------------------------------------------------------

    @McpClientTest
    void failedNodeReportsFailedStatusAndMessage(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "graph-failure");
        project.setDefinition(new CpsFlowDefinition("stage('Boom') {\n  error 'DELIBERATE-FAILURE'\n}", true));
        project.scheduleBuild2(0).get();
        await().atMost(60, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        WorkflowRun build = project.getLastBuild();
        assertThat(build.getResult().isWorseThan(hudson.model.Result.SUCCESS)).isTrue();

        try (var client = builder.jenkins(jenkins).build()) {
            List<Map<String, Object>> nodes = getFlowNodes(client, project.getFullName(), null, null);
            assertThat(nodes).anySatisfy(n -> {
                assertThat((String) n.get("status")).isEqualTo("FAILED");
                assertThat((String) n.get("errorMessage")).contains("DELIBERATE-FAILURE");
            });
        }
    }

    // -----------------------------------------------------------------------
    // Pagination and scale
    //
    // Other fixtures here have ~13 nodes; real Pipelines reach thousands. This one is large enough
    // that an unbounded response is visibly wrong.
    // -----------------------------------------------------------------------

    /** Enough nodes to exceed a page and to catch accidental O(n^2) behaviour. */
    private WorkflowJob createManyNodeJob(JenkinsRule jenkins, String name, int steps) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, name);
        project.setDefinition(new CpsFlowDefinition(
                "stage('Many') {\n  for (int i = 1; i <= " + steps + "; i++) { echo 'STEP-' + i }\n}", true));
        project.scheduleBuild2(0).get();
        await().atMost(300, SECONDS)
                .until(() -> project.getLastBuild() != null
                        && !project.getLastBuild().isBuilding());
        return project;
    }

    @McpClientTest
    void defaultPageIsBoundedAndReportsTotals(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createManyNodeJob(jenkins, "graph-many", 150);

        try (var client = builder.jenkins(jenkins).build()) {
            Map<String, Object> first = page(client, project.getFullName(), null, null, null, null);

            assertThat(nodesOf(first)).hasSize(100);
            assertThat((Boolean) first.get("hasMore")).isTrue();
            assertThat(((Number) first.get("matched")).intValue()).isGreaterThan(100);
            assertThat(((Number) first.get("totalInGraph")).intValue())
                    .isEqualTo(((Number) first.get("matched")).intValue());
            assertThat(((Number) first.get("skip")).longValue()).isZero();
        }
    }

    @McpClientTest
    void pagingCoversEveryNodeExactlyOnceInOrder(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        WorkflowJob project = createManyNodeJob(jenkins, "graph-paging", 150);

        try (var client = builder.jenkins(jenkins).build()) {
            int matched = ((Number) page(client, project.getFullName(), null, null, null, 1)
                            .get("matched"))
                    .intValue();

            List<String> pagedIds = new ArrayList<>();
            long skip = 0;
            int guard = 0;
            while (guard++ < 100) {
                Map<String, Object> p = page(client, project.getFullName(), null, null, skip, 40);
                List<Map<String, Object>> nodes = nodesOf(p);
                nodes.forEach(n -> pagedIds.add((String) n.get("id")));
                assertThat(((Number) p.get("skip")).longValue()).isEqualTo(skip);
                if (!Boolean.TRUE.equals(p.get("hasMore"))) {
                    break;
                }
                skip += nodes.size();
            }

            // No gaps or repeats, still ordered across page joins.
            assertThat(pagedIds).hasSize(matched).doesNotHaveDuplicates();
            List<Integer> asInts = pagedIds.stream().map(Integer::parseInt).toList();
            assertThat(asInts).isSorted();
        }
    }

    @McpClientTest
    void limitIsCappedAndSkipBeyondEndIsEmpty(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createManyNodeJob(jenkins, "graph-bounds", 150);

        try (var client = builder.jenkins(jenkins).build()) {
            Map<String, Object> huge = page(client, project.getFullName(), null, null, null, 999999);
            int matched = ((Number) huge.get("matched")).intValue();
            assertThat(nodesOf(huge)).hasSize(Math.min(matched, 1000));

            Map<String, Object> past = page(client, project.getFullName(), null, null, (long) matched + 500, 10);
            assertThat(nodesOf(past)).isEmpty();
            assertThat((Boolean) past.get("hasMore")).isFalse();
            assertThat(((Number) past.get("matched")).intValue()).isEqualTo(matched);

            assertThat(nodesOf(page(client, project.getFullName(), null, null, -5L, 0)))
                    .hasSize(Math.min(matched, 100));
        }
    }

    @McpClientTest
    void filtersNarrowMatchedBelowTotalInGraph(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createManyNodeJob(jenkins, "graph-narrow", 150);

        try (var client = builder.jenkins(jenkins).build()) {
            Map<String, Object> unfiltered = page(client, project.getFullName(), null, null, null, 1);
            Map<String, Object> logsOnly = page(client, project.getFullName(), null, true, null, 1);

            int total = ((Number) unfiltered.get("totalInGraph")).intValue();
            int matchedAll = ((Number) unfiltered.get("matched")).intValue();
            int matchedLogs = ((Number) logsOnly.get("matched")).intValue();

            // totalInGraph is filter-independent; matched reflects the filter.
            assertThat(((Number) logsOnly.get("totalInGraph")).intValue()).isEqualTo(total);
            assertThat(matchedAll).isEqualTo(total);
            assertThat(matchedLogs).isLessThan(total).isGreaterThan(0);
        }
    }

    @McpClientTest
    void largeGraphListingCompletesPromptly(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        WorkflowJob project = createManyNodeJob(jenkins, "graph-timing", 150);

        try (var client = builder.jenkins(jenkins).build()) {
            // Guards against reintroducing a per-node graph re-walk.
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                Map<String, Object> p = page(client, project.getFullName(), null, null, null, 100);
                assertThat(nodesOf(p)).isNotEmpty();
            });

            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                Map<String, Object> p = page(client, project.getFullName(), "Many", true, null, 100);
                assertThat(nodesOf(p)).isNotEmpty();
            });
        }
    }

    // -----------------------------------------------------------------------
    // Non-Pipeline build
    // -----------------------------------------------------------------------

    @McpClientTest
    void nonPipelineBuildIsAnError(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        var freeStyle = jenkins.createFreeStyleProject("graph-freestyle");
        jenkins.buildAndAssertSuccess(freeStyle);

        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(
                    new McpSchema.CallToolRequest("getFlowNodes", Map.of("jobFullName", "graph-freestyle")));
            assertThat(response.isError()).isTrue();
            assertThat(((McpSchema.TextContent) response.content().get(0)).text())
                    .contains("is not a Pipeline run");
        }
    }

    @McpClientTest
    void unknownJobYieldsNoData(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) {
        try (var client = builder.jenkins(jenkins).build()) {
            var response = client.callTool(
                    new McpSchema.CallToolRequest("getFlowNodes", Map.of("jobFullName", "does-not-exist")));
            // Consistent with the other tools: a missing job is "no data", not an error.
            assertThat(response.isError()).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Node list from a default-page call. */
    private static List<Map<String, Object>> getFlowNodes(
            McpSyncClient client, String jobFullName, String stageName, Boolean onlyWithLogs) {
        return nodesOf(page(client, jobFullName, stageName, onlyWithLogs, null, null));
    }

    /** Full response, so tests can assert the pagination envelope. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> page(
            McpSyncClient client,
            String jobFullName,
            String stageName,
            Boolean onlyWithLogs,
            Long skip,
            Integer limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        if (stageName != null) {
            params.put("stageName", stageName);
        }
        if (onlyWithLogs != null) {
            params.put("onlyWithLogs", onlyWithLogs);
        }
        if (skip != null) {
            params.put("skip", skip);
        }
        if (limit != null) {
            params.put("limit", limit);
        }
        var response = client.callTool(new McpSchema.CallToolRequest("getFlowNodes", params));
        assertThat(response.isError())
                .as("unexpected error: %s", text(response))
                .isFalse();
        return JsonPath.using(Configuration.defaultConfiguration())
                .parse(text(response))
                .read("$.result", Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodesOf(Map<String, Object> pageResult) {
        Object nodes = pageResult.get("nodes");
        return nodes == null ? List.of() : new ArrayList<>((List<Map<String, Object>>) nodes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getBuildLog(McpSyncClient client, String jobFullName, String nodeId) {
        Map<String, Object> params = new HashMap<>();
        params.put("jobFullName", jobFullName);
        params.put("nodeId", nodeId);
        params.put("limit", 1000);
        var response = client.callTool(new McpSchema.CallToolRequest("getBuildLog", params));
        assertThat(response.isError())
                .as("nodeId %s from getFlowNodes was rejected by getBuildLog: %s", nodeId, text(response))
                .isFalse();
        return JsonPath.using(Configuration.defaultConfiguration())
                .parse(text(response))
                .read("$.result", Map.class);
    }

    private static String text(McpSchema.CallToolResult response) {
        return ((McpSchema.TextContent) response.content().get(0)).text();
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(Map<String, Object> result) {
        Object lines = result.get("lines");
        return lines == null ? List.of() : new ArrayList<>((List<String>) lines);
    }
}
