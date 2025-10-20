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

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.scm.GitScmUtil;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import jenkins.triggers.SCMTriggerItem;

@Extension
public class JobScmExtension implements McpServerExtension {

    public static boolean isGitPluginInstalled() {
        var gitPlugin = Jenkins.get().getPluginManager().getPlugin("git");
        return gitPlugin != null && gitPlugin.isActive();
    }

    @Tool(
            description = "Retrieves scm configurations of a Jenkins job",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List getJobScm(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job instanceof SCMTriggerItem scmItem) {
            if (job.hasPermission(Item.EXTENDED_READ)) {
                return scmItem.getSCMs().stream()
                        .map(scm -> {
                            Object result = null;
                            if (scm.getType().equals("hudson.plugins.git.GitSCM")) {
                                result = GitScmUtil.extractGitScmInfo(scm);
                            }
                            return result;
                        })
                        .filter(Objects::nonNull)
                        .toList();
            }
        }
        return List.of();
    }

    @Tool(
            description = "Retrieves scm configurations of a Jenkins build",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List getBuildScm(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, updates the last build)",
                            required = false)
                    Integer buildNumber) {
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    if (isGitPluginInstalled()) {
                        return List.of(GitScmUtil.extractGitScmInfo(build));
                    }
                    return List.of();
                })
                .orElse(List.of());
    }

    @Tool(
            description = "Retrieves change log sets of a Jenkins build",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List getBuildChangeSets(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, updates the last build)",
                            required = false)
                    Integer buildNumber) {

        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .filter(RunWithSCM.class::isInstance)
                .map(RunWithSCM.class::cast)
                .map(RunWithSCM::getChangeSets)
                .orElse(List.of());
    }
}
