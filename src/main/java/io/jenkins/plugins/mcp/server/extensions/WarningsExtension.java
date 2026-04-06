package io.jenkins.plugins.mcp.server.extensions;

import edu.hm.hafner.analysis.Issue;
import hudson.model.Run;
import io.jenkins.plugins.analysis.core.model.ResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.jenkinsci.plugins.variant.OptionalExtension;

@OptionalExtension(requirePlugins = "warnings-ng")
public class WarningsExtension implements McpServerExtension {

    @Tool(
            description = "Retrieves the warnings from static analysis tools associated with a Jenkins build",
            annotations = @Tool.Annotations(destructiveHint = false))
    public Map<String, Object> getWarnings(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description =
                                    "Build number (optional, if not provided, returns the test results for last build)",
                            required = false)
                    Integer buildNumber,
            @Nullable
                    @ToolParam(
                            description =
                                    "ID of the check action (optional, if not provided, all warnings are returned)",
                            required = false)
                    String checkId) {
        Optional<Run> run = JenkinsUtil.getBuildByNumberOrLast(jobFullName, buildNumber);
        if (run.isPresent()) {
            List<ResultAction> warningActions = run.get().getActions(ResultAction.class);
            Map<String, Object> response = new HashMap<>();
            for (ResultAction warningAction : warningActions) {
                if (checkId != null && !warningAction.getId().equals(checkId)) {
                    continue;
                }
                var result = warningAction.getResult();
                if (result != null) {
                    response.put(
                            warningAction.getId(),
                            result.getIssues().stream().map(IssueJson::new).collect(Collectors.toList()));
                }
            }
            return response;
        }
        return Map.of();
    }

    @Getter
    @Setter
    private static class IssueJson {
        private String category;
        private String message;
        private String type;
        private String severity;
        private String fileName;

        IssueJson(Issue issue) {
            this.category = issue.getCategory();
            this.message = issue.getMessage();
            this.type = issue.getType();
            this.severity = issue.getSeverity().toString();
            this.fileName = issue.getFileName();
        }
    }
}
