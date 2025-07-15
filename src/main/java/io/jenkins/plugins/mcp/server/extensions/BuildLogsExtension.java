package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;

@Extension
public class BuildLogsExtension implements McpServerExtension {

    @Tool(
            description =
                    "Retrieves some log lines with pagination for a specific build or the last build of a Jenkins job,"
                            + " as well as a boolean value indicating whether there is more content to retrieve")
    public BuildLogResponse getBuildLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(
                            description = "The build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(
                            description =
                                    "The skip (optional, if not provided, returns the first line). Negative values function as 'from the end', with -1 meaning starting with the last line",
                            required = false)
                    Long skip,
            @ToolParam(
                            description =
                                    "The number of lines to return (optional, if not provided, returns 100 lines)",
                            required = false)
                    Integer limit) {
        if (limit == null || limit == 0) {
            limit = 100;
        }
        if (skip == null) {
            skip = 0l;
        }
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) return null;

        try (BufferedReader reader = getReader(buildNumber, job)) {

            if (reader == null) {
                return null;
            }

            List<String> allLines = reader.lines().toList();
            long actualOffset = skip;
            if (skip < 0) actualOffset = Math.max(0, allLines.size() + skip);
            int endIndex = (int) Math.min(actualOffset + limit, allLines.size());
            var lines = allLines.subList((int) actualOffset, endIndex);
            boolean hasMoreContent = endIndex < allLines.size();
            return new BuildLogResponse(hasMoreContent, lines);

        } catch (IOException e) {
            return null;
        }
    }

    record BuildLogResponse(boolean hasMoreContent, List<String> lines) {}

    private static BufferedReader getReader(Integer buildNumber, Job job) throws IOException {
        Run build;
        if (buildNumber == null || buildNumber <= 0) {
            build = job.getLastBuild();
        } else {
            build = job.getBuildByNumber(buildNumber);
        }
        if (build != null && build.getLogReader() != null) {
            return new BufferedReader(build.getLogReader());
        } else {
            return null;
        }
    }
}
