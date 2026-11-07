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

import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Queue;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.tool.JenkinsMcpContext;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.variant.OptionalExtension;

@OptionalExtension(requirePlugins = {"branch-api"})
public class MultibranchExtension implements McpServerExtension {

    public record ScanMultibranchPipelineResult(
            boolean scheduled,
            String message,
            String jobFullName,
            String scanUrl,
            @Nullable Long queueId) {}

    @Tool(
            description =
                    "Trigger branch indexing / scan for a Multibranch Pipeline or Organization Folder to discover new branches and pull requests. Equivalent to 'Scan Multibranch Pipeline Now' in the Jenkins UI.",
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false))
    public ScanMultibranchPipelineResult scanMultibranchPipeline(
            @ToolParam(
                            description =
                                    "Full path or name of the Multibranch Pipeline or Organization Folder (e.g., 'folder/job-name')")
                    String jobFullName) {
        var jenkins = Jenkins.get();
        Item item = jenkins.getItemByFullName(jobFullName);
        if (item == null) {
            throw new IllegalArgumentException("Project not found: " + jobFullName);
        }

        item.checkPermission(Item.BUILD);

        if (!(item instanceof ComputedFolder<?> folder)) {
            throw new IllegalArgumentException(
                    "Project '" + jobFullName + "' is not a Multibranch Pipeline or Organization Folder.");
        }

        String scanUrl;
        if (folder.getComputation() != null) {
            scanUrl = jenkins.getRootUrl() + folder.getComputation().getUrl();
        } else {
            scanUrl = jenkins.getRootUrl() + folder.getUrl() + "indexing/";
        }

        if (!folder.isBuildable()) {
            return new ScanMultibranchPipelineResult(
                    false,
                    "Project '" + jobFullName
                            + "' is not buildable (it may be disabled or has no configured branch sources)",
                    jobFullName,
                    scanUrl,
                    null);
        }

        var queuedItem = jenkins.getQueue().getItem(folder);
        if (queuedItem != null) {
            return new ScanMultibranchPipelineResult(
                    false, "Branch indexing is already queued", jobFullName, scanUrl, queuedItem.getId());
        }

        if (folder.getComputation() != null && folder.getComputation().isBuilding()) {
            return new ScanMultibranchPipelineResult(
                    false, "Branch indexing is currently in progress", jobFullName, scanUrl, null);
        }

        List<Action> actions = new ArrayList<>();
        String remoteAddr = null;
        var context = JenkinsMcpContext.get();
        if (context != null && context.getHttpServletRequest() != null) {
            remoteAddr = context.getHttpServletRequest().getRemoteAddr();
        }
        if (remoteAddr != null) {
            actions.add(new CauseAction(new DefaultMcpServer.MCPCause(remoteAddr), new Cause.UserIdCause()));
        } else {
            actions.add(new CauseAction(new Cause.UserIdCause()));
        }

        Queue.Item scheduledItem = folder.scheduleBuild2(0, actions.toArray(new Action[0]));
        if (scheduledItem != null) {
            return new ScanMultibranchPipelineResult(
                    true, "Branch indexing scheduled successfully", jobFullName, scanUrl, scheduledItem.getId());
        }

        return new ScanMultibranchPipelineResult(
                false, "Could not schedule branch indexing", jobFullName, scanUrl, null);
    }
}
