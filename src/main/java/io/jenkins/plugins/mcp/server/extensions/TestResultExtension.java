package io.jenkins.plugins.mcp.server.extensions;

import hudson.model.Run;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jenkinsci.plugins.variant.OptionalExtension;

@OptionalExtension(requirePlugins = "junit")
public class TestResultExtension implements McpServerExtension {

    @Tool(
            description = "Retrieves the test results associated to a Jenkins build",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Map<String, Object> getTestResults(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description =
                                    "Build number (optional, if not provided, returns the test results for last build)",
                            required = false)
                    Integer buildNumber) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            var testResultAction = run.get().getAction(TestResultAction.class);
            if (testResultAction != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("TestResultAction", testResultAction);
                var result = testResultAction.getResult();
                if (result != null) {
                    response.put("TestResult", result);
                }
                return response;
            }
        }
        return Map.of();
    }
}
