package io.jenkins.plugins.mcp.server.extensions;

import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

@OptionalExtension(requirePlugins = "pipeline-input-step")
public class PipelineInputExtension implements McpServerExtension {

    @Tool(
            description = "Get pending input steps for a Jenkins pipeline build. "
                    + "If no build number is provided, scans all running builds of the job.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public List<Map<String, Object>> getPendingInputs(
            @ToolParam(description = "Job full name (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, scans all running builds if omitted)",
                            required = false)
                    Integer buildNumber)
            throws InterruptedException, TimeoutException {
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("Job not found: " + jobFullName);
        }

        List<Run<?, ?>> runs;
        if (buildNumber != null) {
            var run = job.getBuildByNumber(buildNumber);
            if (run == null) {
                throw new IllegalArgumentException("Build not found: #" + buildNumber);
            }
            runs = List.of(run);
        } else {
            runs = job.getBuilds().stream()
                    .filter(r -> ((Run<?, ?>) r).isBuilding())
                    .map(r -> (Run<?, ?>) r)
                    .toList();
        }

        return collectPendingInputs(runs);
    }

    private List<Map<String, Object>> collectPendingInputs(List<Run<?, ?>> runs)
            throws InterruptedException, TimeoutException {
        var result = new ArrayList<Map<String, Object>>();
        for (var run : runs) {
            var inputAction = run.getAction(InputAction.class);
            if (inputAction == null) {
                continue;
            }
            for (var execution : inputAction.getExecutions()) {
                if (execution.isSettled()) {
                    continue;
                }
                var input = execution.getInput();
                var entry = new HashMap<String, Object>();
                entry.put("buildNumber", run.getNumber());
                entry.put("inputId", execution.getId());
                entry.put("message", input.getMessage());
                entry.put("ok", input.getOk() != null ? input.getOk() : "");
                entry.put("cancel", input.getCancel() != null ? input.getCancel() : "");
                entry.put("submitter", input.getSubmitter() != null ? input.getSubmitter() : "");
                var params = input.getParameters().stream()
                        .map(pd -> {
                            var p = new HashMap<String, Object>();
                            p.put("name", pd.getName());
                            p.put("type", pd.getDescriptor().getDisplayName());
                            p.put("description", pd.getDescription() != null ? pd.getDescription() : "");
                            return p;
                        })
                        .toList();
                entry.put("parameters", params);
                result.add(entry);
            }
        }
        return result;
    }

    @Tool(description = "Submit a response to a pending pipeline input step to allow the build to proceed",
            annotations = @Tool.Annotations(destructiveHint = true))
    public String submitPipelineInput(
            @ToolParam(description = "Job full name") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Input step ID (from getPendingInputs)") String inputId,
            @Nullable
                    @ToolParam(
                            description = "Input parameter values (optional, e.g., {key=value})",
                            required = false)
                    Map<String, Object> parameters)
            throws IOException, InterruptedException, TimeoutException {
        var execution = getExecution(jobFullName, buildNumber, inputId);
        execution.preSubmissionCheck();
        execution.proceed(parameters != null ? parameters : Map.of());
        return "Input step '" + inputId + "' submitted for build #" + buildNumber;
    }

    @Tool(description = "Abort a pending pipeline input step, causing the build to be aborted",
            annotations = @Tool.Annotations(destructiveHint = true))
    public String abortPipelineInput(
            @ToolParam(description = "Job full name") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Input step ID (from getPendingInputs)") String inputId)
            throws IOException, InterruptedException, TimeoutException {
        var execution = getExecution(jobFullName, buildNumber, inputId);
        execution.doAbort();
        return "Input step '" + inputId + "' aborted for build #" + buildNumber;
    }

    private InputStepExecution getExecution(String jobFullName, int buildNumber, String inputId)
            throws InterruptedException, TimeoutException {
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("Job not found: " + jobFullName);
        }
        var run = job.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new IllegalArgumentException("Build not found: #" + buildNumber);
        }
        var inputAction = run.getAction(InputAction.class);
        if (inputAction == null) {
            throw new IllegalStateException("No pending inputs for build #" + buildNumber);
        }
        var execution = inputAction.getExecution(inputId);
        if (execution == null) {
            throw new IllegalStateException("Input step not found: " + inputId);
        }
        return execution;
    }
}
