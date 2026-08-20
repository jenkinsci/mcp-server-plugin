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

import static io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil.getBuildByNumberOrLast;

import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

/**
 * Exposes a Pipeline build's flow graph so callers can discover node IDs. Without this the {@code
 * nodeId} parameter on the log tools is unreachable through MCP: no {@code @Exported} property on
 * {@code WorkflowRun} walks into the flow graph.
 */
@OptionalExtension(requirePlugins = {"workflow-api", "workflow-job"})
@Slf4j
public class PipelineGraphExtension implements McpServerExtension {

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * @param id pass as {@code nodeId} to the log tools; decimal today, but treat as opaque
     * @param type e.g. {@code StepAtomNode}, {@code StepStartNode}, {@code StepEndNode}
     * @param enclosingIds enclosing block IDs, innermost first
     * @param hasLog whether this node owns log output; only these return anything from the log tools
     */
    public record FlowNodeInfo(
            String id,
            String displayName,
            String type,
            String status,
            List<String> parentIds,
            List<String> enclosingIds,
            String stageName,
            boolean hasLog,
            String errorMessage) {}

    private static final int DEFAULT_LIMIT = 100;

    /** Graphs reach thousands of nodes in practice, so an unbounded listing is never useful. */
    private static final int MAX_LIMIT = 1000;

    /**
     * @param matched nodes matching the filters; page through this many
     * @param totalInGraph nodes in the whole graph, filter-independent
     */
    public record FlowNodesResponse(
            List<FlowNodeInfo> nodes, long skip, int matched, int totalInGraph, boolean hasMore) {}

    @Tool(
            description = "Lists the nodes (steps and blocks) of a Pipeline build's flow graph in execution order."
                    + " Use this to discover the 'nodeId' values accepted by getBuildLog and searchBuildLog:"
                    + " a node is worth reading only when its 'hasLog' is true. Go by that flag rather than"
                    + " by node type -- many block boundary nodes delegate their output to the steps nested"
                    + " inside them, but some own output themselves."
                    + " 'stageName' gives the enclosing stage (a parallel branch reports its stage, not the"
                    + " branch name), so you can find the steps belonging to a particular stage and then"
                    + " fetch just those logs."
                    + " Real Pipelines can hold many thousands of nodes, so results are paginated:"
                    + " compare 'matched' with 'totalInGraph' to see how much your filters narrowed the graph,"
                    + " and prefer narrowing with 'stageName'/'onlyWithLogs' over paging through everything."
                    + " Errors if the build is not a Pipeline run.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public FlowNodesResponse getFlowNodes(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, uses the last build)",
                            required = false)
                    Integer buildNumber,
            @Nullable
                    @ToolParam(
                            description =
                                    "Only return nodes inside the stage with this exact name (optional, case-sensitive)",
                            required = false)
                    String stageName,
            @Nullable
                    @ToolParam(
                            description =
                                    "Only return nodes that own log output, i.e. those whose 'hasLog' would be true (optional, default false)",
                            required = false)
                    Boolean onlyWithLogs,
            @Nullable
                    @ToolParam(
                            description =
                                    "The 0-based index to start from (optional, defaults to 0 - the first matching node)",
                            required = false)
                    Long skip,
            @Nullable
                    @ToolParam(
                            description =
                                    "Maximum nodes to return (optional, defaults to 100, capped at 1000). Narrow with 'stageName' or 'onlyWithLogs' rather than requesting large pages",
                            required = false)
                    Integer limit) {
        boolean logsOnly = Boolean.TRUE.equals(onlyWithLogs);
        long resolvedSkip = skip == null || skip < 0 ? 0L : skip;
        int resolvedLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> listNodes(build, stageName, logsOnly, resolvedSkip, resolvedLimit))
                .orElse(null);
    }

    private FlowNodesResponse listNodes(Run<?, ?> run, String stageFilter, boolean logsOnly, long skip, int limit) {
        FlowExecution execution = flowExecutionOf(run);
        boolean filterByStage = stageFilter != null && !stageFilter.isEmpty();

        // FlowGraphWalker visits children before parents, so the child-label index is complete by the
        // time each node is evaluated -- no second pass needed. Building FlowNodeInfo is the expensive
        // part (getParents/getEnclosingBlocks), so it is deferred to the page window.
        Map<String, String> childBlockLabels = new HashMap<>();
        List<FlowNode> candidates = new ArrayList<>();
        int totalInGraph = 0;

        for (FlowNode node : new FlowGraphWalker(execution)) {
            totalInGraph++;
            if (node instanceof BlockStartNode) {
                String label = stageLabelOf(node);
                if (label != null) {
                    for (FlowNode parent : node.getParents()) {
                        childBlockLabels.putIfAbsent(parent.getId(), label);
                    }
                }
            }
            if (logsOnly && node.getAction(LogAction.class) == null) {
                continue;
            }
            candidates.add(node);
        }

        // Walk order is newest-first; callers reason about a build top to bottom.
        Collections.reverse(candidates);

        // The stage filter runs here rather than inside the walk. A BlockEndNode resolves its stage
        // through its start node, whose label is indexed only once the walk reaches the block nested
        // inside it -- later, walking newest-first. Filtering mid-walk therefore dropped end nodes that
        // the same call reports as belonging to that stage when no filter is given, since the page is
        // rendered after the walk with a complete index.
        List<FlowNode> matching = candidates;
        if (filterByStage) {
            matching = new ArrayList<>();
            for (FlowNode node : candidates) {
                if (stageFilter.equals(stageNameOf(node, childBlockLabels))) {
                    matching.add(node);
                }
            }
        }

        int matched = matching.size();
        int from = (int) Math.min(skip, matched);
        int to = (int) Math.min((long) from + limit, matched);
        List<FlowNodeInfo> page = new ArrayList<>(to - from);
        for (FlowNode node : matching.subList(from, to)) {
            // Under a stage filter every surviving node already resolved to stageFilter above, so
            // re-walking its enclosing blocks here would recompute an answer we have.
            String stage = filterByStage ? stageFilter : stageNameOf(node, childBlockLabels);
            page.add(toInfo(node, stage, node.getAction(LogAction.class) != null));
        }
        if (matched > MAX_LIMIT && !filterByStage && !logsOnly) {
            log.debug(
                    "{} has {} flow nodes; returning {}-{}. Callers should filter rather than page through all of them.",
                    run.getFullDisplayName(),
                    matched,
                    from,
                    to);
        }
        return new FlowNodesResponse(page, from, matched, totalInGraph, to < matched);
    }

    private static FlowExecution flowExecutionOf(Run<?, ?> run) {
        if (!(run instanceof FlowExecutionOwner.Executable executable)) {
            throw new IllegalArgumentException(
                    "Build " + run.getFullDisplayName() + " is not a Pipeline run, so it has no flow graph.");
        }
        // Mirrors the same resolution in PipelineLogUtil.resolveNodeLogSource. Kept separate on
        // purpose: that class keeps Pipeline types out of every public signature so a Pipeline-less
        // controller can reflect over it, so it cannot host a shared helper returning a FlowExecution.
        // Change one of these two and change the other.
        FlowExecutionOwner owner = executable.asFlowExecutionOwner();
        if (owner == null) {
            throw new IllegalStateException(
                    "Pipeline execution owner is unavailable for " + run.getFullDisplayName() + "; retry later.");
        }
        FlowExecution execution = owner.getOrNull();
        if (execution == null) {
            throw new IllegalStateException("Pipeline flow graph is not loaded for " + run.getFullDisplayName()
                    + "; it may still be resuming. Retry later.");
        }
        return execution;
    }

    private static FlowNodeInfo toInfo(FlowNode node, String stageName, boolean hasLog) {
        ErrorAction error = node.getError();
        String status;
        if (error != null) {
            status = STATUS_FAILED;
        } else if (node.isActive()) {
            status = STATUS_IN_PROGRESS;
        } else {
            status = STATUS_SUCCESS;
        }

        List<String> parentIds = node.getParents().stream().map(FlowNode::getId).toList();
        List<String> enclosingIds =
                node.getEnclosingBlocks().stream().map(FlowNode::getId).toList();

        return new FlowNodeInfo(
                node.getId(),
                node.getDisplayName(),
                node.getClass().getSimpleName(),
                status,
                parentIds,
                enclosingIds,
                stageName,
                hasLog,
                error != null ? errorMessage(error) : null);
    }

    /**
     * The block's stage name, or {@code null} if it does not name a stage.
     *
     * <p>{@link LabelAction} is not stage-specific: a {@code parallel} branch start carries one too,
     * holding the branch name. Accepting it would report {@code stageName: "linux"} for a step inside
     * {@code stage('Test') { parallel linux: ... }}, so a {@code stageName=Test} filter would drop that
     * stage's actual steps. A branch start is distinguished by also carrying a {@link ThreadNameAction},
     * which is the same test {@code pipeline-graph-analysis} uses to find stage chunks.
     *
     * <p>{@code getPersistentAction} avoids triggering action population on a live graph.
     */
    @Nullable
    private static String stageLabelOf(FlowNode node) {
        LabelAction label = node.getPersistentAction(LabelAction.class);
        if (label == null || node.getPersistentAction(ThreadNameAction.class) != null) {
            return null;
        }
        return label.getDisplayName();
    }

    /**
     * A {@code stage} compiles to a nested <em>pair</em> of blocks with the {@link LabelAction} on the
     * inner one, while the outer one owns the log. So the outer node needs the child lookup below, or it
     * reports no stage and a {@code stageName} filter drops the very node holding the output.
     */
    @Nullable
    private static String stageNameOf(FlowNode node, Map<String, String> childBlockLabels) {
        String own = stageLabelOf(node);
        if (own != null) {
            return own;
        }
        for (BlockStartNode enclosing : node.getEnclosingBlocks()) {
            String label = stageLabelOf(enclosing);
            if (label != null) {
                return label;
            }
        }
        String fromChild = childBlockLabels.get(node.getId());
        if (fromChild != null) {
            return fromChild;
        }
        if (node instanceof BlockEndNode<?> endNode) {
            // getStartNode() resolves through node storage, and on a damaged or partially loaded graph
            // it throws rather than returning null -- it wraps both the missing-node and the IOException
            // case in IllegalStateException. One unattributable node should not abort the whole listing.
            BlockStartNode start;
            try {
                start = endNode.getStartNode();
            } catch (IllegalStateException e) {
                log.debug("No start node for {}, so it gets no stage attribution", node.getId(), e);
                return null;
            }
            return stageNameOf(start, childBlockLabels);
        }
        return null;
    }

    /** {@code ErrorAction.getError()} is {@code @NonNull}, but its message may be absent. */
    private static String errorMessage(ErrorAction error) {
        Throwable cause = error.getError();
        String message = cause.getMessage();
        return message != null && !message.isEmpty()
                ? message
                : cause.getClass().getName();
    }
}
