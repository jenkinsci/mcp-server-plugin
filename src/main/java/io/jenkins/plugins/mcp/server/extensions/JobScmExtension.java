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
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.scm.SCM;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.scm.GitScmUtil;
import jakarta.annotation.Nullable;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import jenkins.triggers.SCMTriggerItem;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

@Extension
public class JobScmExtension implements McpServerExtension {
    private static final Logger LOGGER = Logger.getLogger(JobScmExtension.class.getName());

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

    @Tool(
            description = "Get a paginated list of Jenkins jobs that use the specified git SCM URL",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List<Job> findJobsWithScmUrl(
            @ToolParam(description = "SCM URL to search for (e.g., 'git@github.com:jenkinsci/mcp-server-plugin.git')")
                    String scmUrl,
            @ToolParam(description = "SCM Branch (e.g., 'feature/my-feature')", required = false) String branch,
            @ToolParam(
                            description = "The 0 based started index, if not specified, then start from the first (0)",
                            required = false)
                    Integer skip,
            @ToolParam(
                            description =
                                    "The maximum number of items to return. If not specified, returns 10 items. Cannot exceed 10 items.",
                            required = false)
                    Integer limit)
            throws URISyntaxException {

        if (skip == null || skip < 0) {
            skip = 0;
        }

        if (limit == null || limit < 0 || limit > 10) {
            limit = 10;
        }

        URIish uri = new URIish(scmUrl);

        List<Job> result = Jenkins.get().getAllItems().stream()
                .filter(project -> {
                    SCMTriggerItem scmTriggerItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(project);
                    if (scmTriggerItem == null) {
                        return false;
                    }

                    for (SCM scm : scmTriggerItem.getSCMs()) {
                        if (scm instanceof GitSCM) {
                            if (matchesGitSCM(project, (GitSCM) scm, uri, branch)) {
                                if (LOGGER.isLoggable(Level.FINE)) {
                                    LOGGER.log(
                                            Level.FINE,
                                            "Project: {0} matches SCM URL: {1} and branch: {2}",
                                            new Object[] {project.getFullDisplayName(), scmUrl, branch});
                                }
                                return true;
                            }
                        } else {
                            if (LOGGER.isLoggable(Level.FINER)) {
                                LOGGER.log(
                                        Level.FINER,
                                        "Skipping unhandled SCM type: {0} for project: {1}",
                                        new Object[] {scm.getType(), project.getFullDisplayName()});
                            }
                        }
                    }

                    return false;
                })
                .filter(item -> item instanceof Job)
                .map(item -> (Job) item)
                .skip(skip)
                .limit(limit)
                .toList();

        return result;
    }

    private boolean matchesGitSCM(Item project, GitSCM git, URIish uri, String branch) {
        for (RemoteConfig repository : git.getRepositories()) {
            boolean repositoryMatches = false;
            for (URIish remoteURL : repository.getURIs()) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Comparing SCM URL {0} with {1} for {2}", new Object[] {
                        uri, remoteURL, project.getFullDisplayName()
                    });
                }
                if (GitStatus.looselyMatches(uri, remoteURL)) {
                    if (LOGGER.isLoggable(Level.FINER)) {
                        LOGGER.log(Level.FINER, "SCM URL {0} matches {1} for {2}", new Object[] {
                            uri, remoteURL, project.getFullDisplayName()
                        });
                    }
                    repositoryMatches = true;
                    break;
                }
            }

            if (!repositoryMatches) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "No matching SCM URL found in repository {0} for {1}", new Object[] {
                        repository.getName(), project.getFullDisplayName()
                    });
                }
                continue;
            }

            boolean branchFound = false;
            if (branch == null || branch.isEmpty()) {
                branchFound = true;
            } else {
                for (BranchSpec branchSpec : git.getBranches()) {
                    // If a parameterized branch spec is used return it as a match
                    if (branchSpec.getName().contains("$")) {
                        if (LOGGER.isLoggable(Level.FINE)) {
                            LOGGER.log(Level.FINE, "Branch Spec is parametrized for {0}", project.getFullDisplayName());
                        }
                        branchFound = true;
                    } else {
                        if (branchSpec.matchesRepositoryBranch(repository.getName(), branch)) {
                            if (LOGGER.isLoggable(Level.FINE)) {
                                LOGGER.log(
                                        Level.FINE,
                                        "Branch Spec {0} matches modified branch {1} for {2}",
                                        new Object[] {branchSpec, branch, project.getFullDisplayName()});
                            }
                            branchFound = true;
                            break;
                        }
                    }
                }
            }

            if (branchFound) {
                return true;
            }
        }

        return false;
    }
}
