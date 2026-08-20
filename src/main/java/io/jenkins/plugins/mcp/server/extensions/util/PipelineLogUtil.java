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

package io.jenkins.plugins.mcp.server.extensions.util;

import hudson.console.AnnotatedLargeText;
import hudson.model.Run;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * Resolves a single Pipeline node's log. All workflow-api types used for log reading are confined here:
 * the dependency is optional, so {@code BuildLogsExtension} must not name them in its own signatures or
 * a Pipeline-less controller would fail to load it and lose {@code getBuildLog} entirely.
 */
public final class PipelineLogUtil {

    /**
     * CpsFlowExecution IDs are always decimal. Enforced because the ID reaches
     * {@code SimpleXStreamFlowNodeStorage.getNodeFile}, which interpolates it into a filename without
     * sanitising, so {@code ../..} would escape the build directory.
     */
    private static final Pattern VALID_NODE_ID = Pattern.compile("[0-9]+");

    private PipelineLogUtil() {}

    /**
     * Returns the node's log. A node owning none — a block boundary delegating output to the steps
     * inside it, or a step that has not written yet — yields an empty source rather than {@code null},
     * so callers need no special case: an empty read is just a read that found nothing, and it still
     * goes through cursor validation and still reports liveness.
     *
     * @throws IllegalArgumentException for caller-fixable input (not a Pipeline run, malformed ID, no
     *     such node)
     * @throws IllegalStateException when the graph cannot currently be loaded, which is worth retrying
     */
    public static LogSource resolveNodeLogSource(Run<?, ?> run, String nodeId) {
        if (!(run instanceof FlowExecutionOwner.Executable executable)) {
            throw new IllegalArgumentException("Build " + run.getFullDisplayName()
                    + " is not a Pipeline run, so it has no nodes." + " Omit 'nodeId' to read the whole build log.");
        }
        if (nodeId == null || !VALID_NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid nodeId '" + nodeId + "': expected a decimal Pipeline node ID such as '7'."
                            + " Use getFlowNodes to list the available IDs.");
        }

        // Deliberately not shared with PipelineGraphExtension.flowExecutionOf, which resolves an
        // execution the same way and must keep these two messages in step by hand. A common helper
        // would have to live somewhere both can reach, and this class cannot be it: every public
        // signature here stays free of Pipeline types so that reflecting over the class on a
        // controller without workflow-api does not resolve them (BuildLogsWithoutPipelineTest
        // asserts exactly that). Duplication is the cheaper half of that trade.
        FlowExecutionOwner owner = executable.asFlowExecutionOwner();
        if (owner == null) {
            throw new IllegalStateException(
                    "Pipeline execution owner is unavailable for " + run.getFullDisplayName() + "; retry later.");
        }
        FlowExecution execution = owner.getOrNull();
        if (execution == null) {
            // Not an IllegalArgumentException: the request is well-formed, the graph just isn't loaded.
            // Must not collapse into a "no data" success or a client cannot tell retry from give-up.
            throw new IllegalStateException("Pipeline flow graph is not loaded for " + run.getFullDisplayName()
                    + "; it may still be resuming. Retry later.");
        }

        FlowNode node = findNode(execution, nodeId);
        if (node == null) {
            throw new IllegalArgumentException("No Pipeline node '" + nodeId + "' in " + run.getFullDisplayName()
                    + ". Use getFlowNodes to list the available IDs.");
        }

        // isActive(), not isRunning(): isRunning() means "is a current head", false for a block start
        // whose body is still executing. isActive() means "end not yet reached", which is what "the log
        // may still grow" requires -- and is what workflow-api's own LogStorageAction uses.
        //
        // Sampled *before* the snapshot below, and then held constant, which is the opposite of how a
        // Run's log is treated. A node's AnnotatedLargeText is sized once, up front:
        // FileLogStorage.StreamingStepLog keeps that length in a final field, so unlike a Run's
        // file-backed text it can never report growth and a post-read length re-check sees nothing.
        // Whether the node was still running when we took the snapshot is therefore the only signal
        // that the snapshot may be short -- and since a node that has ended cannot restart, that is a
        // settled fact rather than a reading that goes stale. Sampling before the snapshot means a step
        // that finishes in between costs the caller one extra empty poll instead of losing its tail.
        boolean activeWhenSnapshotted = node.isActive();

        LogAction logAction = node.getAction(LogAction.class);
        AnnotatedLargeText<?> text = logAction != null ? logAction.getLogText() : emptyLog(activeWhenSnapshotted);
        return new LogSource(text, () -> activeWhenSnapshotted, LogSource.scopeKey(run, nodeId));
    }

    /**
     * A node has no {@code LogAction} until something writes through it, so "no action" means "nothing
     * yet", not "nothing ever" — an in-progress step can acquire one at any moment. Standing in an empty
     * log keeps that node on the normal read path, where an active node still gets a resume cursor.
     */
    private static AnnotatedLargeText<?> emptyLog(boolean active) {
        return new AnnotatedLargeText<>(new ByteBuffer(), StandardCharsets.UTF_8, !active, null);
    }

    /** Translates the checked IOException from {@code getNode} into this class's failure vocabulary. */
    @Nullable
    private static FlowNode findNode(FlowExecution execution, String nodeId) {
        try {
            return execution.getNode(nodeId);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load Pipeline node '" + nodeId + "': " + e.getMessage(), e);
        }
    }
}
