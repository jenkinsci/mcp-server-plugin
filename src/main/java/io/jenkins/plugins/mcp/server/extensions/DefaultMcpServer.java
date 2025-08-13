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
import hudson.model.AbstractItem;
import hudson.model.AdministrativeMonitor;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.User;
import hudson.slaves.Cloud;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
public class DefaultMcpServer implements McpServerExtension {

    public static final String FULL_NAME = "fullName";

    @Tool(description = "Get a specific build or the last build of a Jenkins job")
    public Run getBuild(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber) {
        return getBuildByNumberOrLast(jobFullName, buildNumber).orElse(null);
    }

    @Tool(description = "Get a Jenkins job by its full path")
    public Job getJob(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        return Jenkins.get().getItemByFullName(jobFullName, Job.class);
    }

    @Tool(description = "Trigger a build for a Jenkins job")
    public boolean triggerBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build parameters (optional, e.g., {key1=value1,key2=value2})", required = false)
                    Map<String, Object> parameters) {
        var job = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);

        if (job != null) {
            if (job.isParameterized() && job instanceof Job j) {
                ParametersDefinitionProperty parametersDefinition =
                        (ParametersDefinitionProperty) j.getProperty(ParametersDefinitionProperty.class);
                var parameterValues = parametersDefinition.getParameterDefinitions().stream()
                        .map(param -> {
                            if (param instanceof SimpleParameterDefinition sd) {
                                if (parameters != null && parameters.containsKey(param.getName())) {
                                    var value = parameters.get(param.getName());
                                    return sd.createValue(String.valueOf(value));
                                } else {
                                    return sd.getDefaultParameterValue();
                                }
                            } else {
                                log.warn(
                                        "Unsupported parameter type: {}",
                                        param.getClass().getName());
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .toList();
                job.scheduleBuild2(0, new ParametersAction(parameterValues));
            } else {
                job.scheduleBuild2(0);
            }
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
                    String parentFullName,
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
        if (parentFullName == null || parentFullName.isEmpty()) {
            parent = Jenkins.get();
        } else {
            var fullNameItem = Jenkins.get().getItemByFullName(parentFullName, AbstractItem.class);
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

    @Tool(description = "Update build display name and/or description")
    @SneakyThrows
    public boolean updateBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, updates the last build)",
                            required = false)
                    Integer buildNumber,
            @Nullable @ToolParam(description = "New display name for the build", required = false) String displayName,
            @Nullable @ToolParam(description = "New description for the build", required = false) String description) {

        var optBuild = getBuildByNumberOrLast(jobFullName, buildNumber);
        boolean updated = false;
        if (optBuild.isPresent()) {
            var build = optBuild.get();
            if (displayName != null && !displayName.isEmpty()) {
                build.setDisplayName(displayName);
                updated = true;
            }
            if (description != null && !description.isEmpty()) {
                build.setDescription(description);
                updated = true;
            }
        }

        return updated;
    }

    @Tool(
            description =
                    "Get information about the currently authenticated user, including their full name or 'anonymous' if not authenticated")
    @SneakyThrows
    public Map<String, String> whoAmI() {
        return Optional.ofNullable(User.current())
                .map(user -> Map.of(FULL_NAME, user.getFullName()))
                .orElse(Map.of(FULL_NAME, "anonymous"));
    }

    @Tool(
            description =
                    "Checks the health and readiness status of a Jenkins instance, including whether it's in quiet"
                            + " mode, has active administrative monitors, current queue size, and available executor capacity."
                            + " This tool provides a comprehensive overview of the controller's operational state to determine if"
                            + " it's stable and ready to build. Use this tool to assess Jenkins instance health rather than"
                            + " simple up/down status.")
    public Map<String, Object> getStatus() {
        var map = new HashMap<String, Object>();
        var jenkins = Jenkins.get();
        var quietMode = jenkins.isQuietingDown();
        var queue = jenkins.getQueue();
        var availableExecutors = Arrays.stream(jenkins.getComputers())
                .filter(Computer::isOnline)
                .map(Computer::countExecutors)
                .reduce(0, Integer::sum);

        map.put("Quiet Mode", quietMode);
        if (quietMode) {
            map.put(
                    "Quiet Mode reason",
                    jenkins.getQuietDownReason() != null ? jenkins.getQuietDownReason() : "Unknown");
        }
        map.put("Full Queue Size", queue.getItems().length);
        map.put("Buildable Queue Size", queue.countBuildableItems());
        map.put("Available executors (any label)", availableExecutors);
        // Tell me which clouds are defined as they can be used to provision ephemeral agents
        map.put(
                "Defined clouds that can provide agents (any label)",
                jenkins.clouds.stream()
                        .filter(cloud -> cloud.canProvision(new Cloud.CloudState(null, 1)))
                        .map(Cloud::getDisplayName)
                        .toList());
        // getActiveAdministrativeMonitors is already protected, so no need to check the user
        map.put(
                "Active administrative monitors",
                jenkins.getActiveAdministrativeMonitors().stream()
                        .map(AdministrativeMonitor::getDisplayName)
                        .toList());
        return map;
    }
}
