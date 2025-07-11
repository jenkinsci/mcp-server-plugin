/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2025, Gong Yi.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class DefaultMcpServer implements McpServerExtension {

    @Tool(description = "Get a specific build or the last build of a Jenkins job")
    public Run getBuild(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    String buildNumber) {
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job != null) {
            if (buildNumber == null || buildNumber.isEmpty()) {
                return job.getLastBuild();
            } else {
                return job.getBuildByNumber(Integer.parseInt(buildNumber));
            }
        }
        return null;
    }

    @Tool(description = "Get a Jenkins job by its full path")
    public Job getJob(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        return Jenkins.get().getItemByFullName(jobFullName, Job.class);
    }

    @Tool(description = "Trigger a build for a Jenkins job")
    public boolean triggerBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        var job = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);
        if (job != null) {
            job.scheduleBuild2(0);
            return true;
        }
        return false;
    }

    @Tool(
            description =
                    "Get a paginated list of Jenkins jobs, sorted by name. Returns up to 'limit' jobs starting from the 'skip' index. If no jobs are available in the requested range, returns an empty list.")
    public List<Job> getJobs(
            @ToolParam(
                            description =
                                    "The full path of the Jenkins folder (e.g., 'folder'), if not specified, it returns the items under root",
                            required = false)
                    String parentFllName,
            @ToolParam(
                            description = "The 0 based started index, if not specified, then start from the first (0)",
                            required = false)
                    Integer skip,
            @ToolParam(
                            description =
                                    "The maximum number of items to return. If not specified, returns 10 items. Cannot exceed 10 items.",
                            required = false)
                    Integer limit) {

        if (skip == null || skip < 0) {
            skip = 0;
        }
        if (limit == null || limit < 0 || limit > 10) {
            limit = 10;
        }
        ItemGroup parent = null;
        if (parentFllName == null || parentFllName.isEmpty()) {
            parent = Jenkins.get();
        } else {
            var fullNameItem = Jenkins.get().getItemByFullName(parentFllName, AbstractItem.class);
            if (fullNameItem instanceof ItemGroup) {
                parent = (ItemGroup) fullNameItem;
            }
        }
        if (parent != null) {
            return parent.getItemsStream()
                    .sorted(Comparator.comparing(Item::getName))
                    .skip(skip)
                    .limit(limit)
                    .toList();
        } else {
            return List.of();
        }
    }

    @Tool(description = "Retrieves the full log for a specific build of the last build of a Jenkins job")
    public List<String> getBuildLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "The build number (optional, if not provided, returns the last build)")
                    String buildNumber)
            throws IOException {
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job != null) {
            if (buildNumber == null || buildNumber.isEmpty()) {
                return job.getLastBuild().getLog(Integer.MAX_VALUE);
            } else {
                return job.getBuildByNumber(Integer.parseInt(buildNumber)).getLog(Integer.MAX_VALUE);
            }
        }
        return null;
    }
}
