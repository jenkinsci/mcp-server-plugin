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
import static io.jenkins.plugins.mcp.server.extensions.util.ParameterValueFactory.createParameterValue;

import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Action;
import hudson.model.AdministrativeMonitor;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.User;
import hudson.slaves.Cloud;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.tool.JenkinsMcpContext;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.queue.QueueItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.kohsuke.stapler.export.Exported;

@Extension
@Slf4j
public class DefaultMcpServer implements McpServerExtension {

    public static final String FULL_NAME = "fullName";

    @Tool(
            description = "Get a specific build or the last build of a Jenkins job",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Run getBuild(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber) {
        return getBuildByNumberOrLast(jobFullName, buildNumber).orElse(null);
    }

    @Tool(description = "Get a Jenkins job by its full path", annotations = @Tool.Annotations(destructiveHint = false))
    public Job getJob(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName) {
        return Jenkins.get().getItemByFullName(jobFullName, Job.class);
    }

    /**
     * A {@link Cause} that indicates a Jenkins build was triggered via MCP call.
     * <p>
     * This is useful for the end user to understand that the call was trigger through this plugin's code
     * and not some manual user intervention.</p>
     * <p>And among others, it allows plugins like
     * <a href="https://plugins.jenkins.io/buildtriggerbadge/">...</a> to offer custom badges</p>
     *
     * @see #triggerBuild(String, Map)
     */
    @Data
    @AllArgsConstructor
    public static class MCPCause extends Cause {
        private String addr;

        @Exported(visibility = 3)
        public String getAddr() {
            return this.addr;
        }

        @Override
        public String getShortDescription() {
            return "Triggered via MCP Client from " + addr;
        }
    }

    @Tool(description = "Trigger a build for a Jenkins job", treePruneSupported = true)
    // keep the default value for destructive (true)
    public QueueItem triggerBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "Build parameters (optional, e.g., {key1=value1,key2=value2})", required = false)
                    Map<String, Object> parameters) {
        var job = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);

        if (job != null) {
            job.checkPermission(Item.BUILD);
            var remoteAddr = JenkinsMcpContext.get().getHttpServletRequest().getRemoteAddr();
            CauseAction action = new CauseAction(new MCPCause(remoteAddr), new Cause.UserIdCause());
            List<Action> actions = new ArrayList<>();
            actions.add(action);

            if (job.isParameterized() && job instanceof Job j) {
                ParametersDefinitionProperty parametersDefinition =
                        (ParametersDefinitionProperty) j.getProperty(ParametersDefinitionProperty.class);
                var parameterValues = parametersDefinition.getParameterDefinitions().stream()
                        .map(param -> {
                            if (parameters != null && parameters.containsKey(param.getName())) {
                                var value = parameters.get(param.getName());
                                return createParameterValue(param, value);
                            } else {
                                return param.getDefaultParameterValue();
                            }
                        })
                        .filter(Objects::nonNull)
                        .toList();

                actions.add(new ParametersAction(parameterValues));
            }

            var scheduleResult = Jenkins.get().getQueue().schedule2(job, 0, actions);
            return scheduleResult.getItem();
        }
        return null;
    }

    @Tool(
            description =
                    "Get a paginated list of Jenkins jobs, sorted by name. Returns up to 'limit' jobs starting from the 'skip' index. If no jobs are available in the requested range, returns an empty list.",
            annotations = @Tool.Annotations(destructiveHint = false))
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

    @Tool(description = "Update build display name and/or description") // keep the default value for destructive (true)
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

    public record WhoAmIResponse(String fullName) {}

    public record GetReplayScriptsResult(String mainScript, Map<String, String> loadedScripts) {}

    @Tool(
            description =
                    "Get information about the currently authenticated user, including their full name or 'anonymous' if not authenticated",
            structuredOutput = true,
            annotations = @Tool.Annotations(destructiveHint = false))
    @SneakyThrows
    public WhoAmIResponse whoAmI() {
        return Optional.ofNullable(User.current())
                .map(user -> new WhoAmIResponse(user.getFullName()))
                .orElse(new WhoAmIResponse("anonymous"));
    }

    @Tool(
            description =
                    "Checks the health and readiness status of a Jenkins instance, including whether it's in quiet"
                            + " mode, has active administrative monitors, current queue size, root URL Status, and available executor capacity."
                            + " This tool provides a comprehensive overview of the controller's operational state to determine if"
                            + " it's stable and ready to build. Use this tool to assess Jenkins instance health rather than"
                            + " simple up/down status.",
            annotations = @Tool.Annotations(destructiveHint = false))
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
        if (Jenkins.get().hasAnyPermission(Jenkins.SYSTEM_READ)) {
            map.put(
                    "Defined clouds that can provide agents (any label)",
                    jenkins.clouds.stream()
                            .filter(cloud -> cloud.canProvision(new Cloud.CloudState(null, 1)))
                            .map(Cloud::getDisplayName)
                            .toList());
        }
        // getActiveAdministrativeMonitors is already protected, so no need to check the user
        map.put(
                "Active administrative monitors",
                jenkins.getActiveAdministrativeMonitors().stream()
                        .map(AdministrativeMonitor::getDisplayName)
                        .toList());

        // Explicit root URL health check
        if (jenkins.getRootUrl() == null || jenkins.getRootUrl().isEmpty()) {
            map.put(
                    "Root URL Status",
                    "ERROR: Jenkins root URL is not configured. Please configure the Jenkins URL under \"Manage Jenkins → Configure System → Jenkins Location\" so tools like getJobs can work properly.\n ");
        } else {
            map.put("Root URL Status", "OK");
        }
        return map;
    }

    @Tool(
            description =
                    "Get the queue item details by its ID. The caller can check the queue item's status, build details, and other relevant information.",
            treePruneSupported = true,
            annotations = @Tool.Annotations(destructiveHint = false))
    public QueueItem getQueueItem(@ToolParam(description = "The queue item id") long id) {
        return Jenkins.get().getQueue().getItem(id);
    }

    @Tool(
            description =
                    "Rebuild a Jenkins build: re-run with the same parameters (and for Pipeline jobs, the same script when possible). Returns the queue item for the new build.",
            treePruneSupported = true)
    public QueueItem rebuildBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, rebuilds the last build)",
                            required = false)
                    Integer buildNumber) {
        var optBuild = getBuildByNumberOrLast(jobFullName, buildNumber);
        if (optBuild.isEmpty()) {
            return null;
        }
        Run<?, ?> run = optBuild.get();
        Job<?, ?> job = run.getParent();
        job.checkPermission(Item.BUILD);

        ReplayAction replayAction = run.getAction(ReplayAction.class);
        if (replayAction != null && replayAction.isRebuildEnabled()) {
            Queue.Item item = replayAction.run2(
                    replayAction.getOriginalScript(), replayAction.getOriginalLoadedScripts());
            return item != null ? (QueueItem) item : null;
        }

        List<Action> actions = new ArrayList<>();
        var remoteAddr = JenkinsMcpContext.get().getHttpServletRequest().getRemoteAddr();
        actions.add(new CauseAction(new MCPCause(remoteAddr), new Cause.UserIdCause()));
        ParametersAction paramsAction = run.getAction(ParametersAction.class);
        if (paramsAction != null) {
            actions.add(paramsAction);
        }
        var jobParam = Jenkins.get().getItemByFullName(jobFullName, ParameterizedJobMixIn.ParameterizedJob.class);
        if (jobParam == null) {
            return null;
        }
        var scheduleResult = Jenkins.get().getQueue().schedule2(jobParam, 0, actions);
        return scheduleResult.getItem();
    }

    @Tool(
            description =
                    "Get the pipeline script(s) of a build for replay. Returns the main script and loaded scripts. Only available for Pipeline (replayable) builds.",
            structuredOutput = true,
            annotations = @Tool.Annotations(destructiveHint = false))
    public GetReplayScriptsResult getReplayScripts(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, uses the last build)",
                            required = false)
                    Integer buildNumber) {
        var optBuild = getBuildByNumberOrLast(jobFullName, buildNumber);
        if (optBuild.isEmpty()) {
            throw new IllegalArgumentException("Build not found for job " + jobFullName);
        }
        Run<?, ?> run = optBuild.get();
        ReplayAction replayAction = run.getAction(ReplayAction.class);
        if (replayAction == null) {
            throw new IllegalArgumentException(
                    "Not a replayable Pipeline build. Replay is only available for Pipeline jobs (workflow-cps).");
        }
        String mainScript = replayAction.getOriginalScript();
        Map<String, String> loadedScripts = replayAction.getOriginalLoadedScripts();
        return new GetReplayScriptsResult(mainScript, loadedScripts != null ? loadedScripts : Map.of());
    }

    @Tool(
            description =
                    "Replay a Pipeline build with optionally modified script(s). Runs the job again with the given main script and optional loaded scripts. Only available for Pipeline jobs.",
            treePruneSupported = true)
    public QueueItem replayBuild(
            @ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, uses the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(description = "Main pipeline script content") String mainScript,
            @Nullable
                    @ToolParam(
                            description =
                                    "Loaded scripts map (optional): script name to content. If not provided, original loaded scripts are used.",
                            required = false)
                    Map<String, String> loadedScripts) {
        var optBuild = getBuildByNumberOrLast(jobFullName, buildNumber);
        if (optBuild.isEmpty()) {
            return null;
        }
        Run<?, ?> run = optBuild.get();
        ReplayAction replayAction = run.getAction(ReplayAction.class);
        if (replayAction == null) {
            throw new IllegalArgumentException(
                    "Not a replayable Pipeline build. Replay is only available for Pipeline jobs (workflow-cps).");
        }
        if (!replayAction.isEnabled() || !replayAction.isReplayableSandboxTest()) {
            throw new IllegalStateException("Replay not allowed for this build (permission or script approval).");
        }
        Map<String, String> scriptsToUse =
                loadedScripts != null ? loadedScripts : replayAction.getOriginalLoadedScripts();
        if (scriptsToUse == null) {
            scriptsToUse = Map.of();
        }
        Queue.Item item = replayAction.run2(mainScript, scriptsToUse);
        return item != null ? (QueueItem) item : null;
    }
}
