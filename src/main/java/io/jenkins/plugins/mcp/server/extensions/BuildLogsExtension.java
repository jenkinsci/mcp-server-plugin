package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil.getBuildByNumberOrLast;

import hudson.Extension;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
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
            skip = 0L;
        }

        final int limitF = limit;
        final long skipF = skip;
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    try (BufferedReader reader = new BufferedReader(build.getLogReader())) {
                        List<String> allLines = reader.lines().toList();
                        long actualOffset = skipF;
                        if (skipF < 0) actualOffset = Math.max(0, allLines.size() + skipF);
                        int endIndex = (int) Math.min(actualOffset + limitF, allLines.size());
                        var lines = allLines.subList((int) actualOffset, endIndex);
                        boolean hasMoreContent = endIndex < allLines.size();
                        return new BuildLogResponse(hasMoreContent, lines);

                    } catch (IOException e) {
                        log.error("Error reading log for job {} build {}", jobFullName, buildNumber, e);
                        return null;
                    }
                })
                .orElse(null);
    }

    record BuildLogResponse(boolean hasMoreContent, List<String> lines) {}
}
