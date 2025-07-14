package io.jenkins.plugins.mcp.server.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Job;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;

@Extension
public class BuildLogsExtension implements McpServerExtension {
    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(
            description =
                    "Retrieves some log lines with pagination for a specific build or the last build of a Jenkins job,"
                            + " as well as a boolean value indicating whether there is more content to retrieve")
    public ObjectNode getBuildLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(description = "The build number (optional, if not provided, returns the last build)")
                    String buildNumber,
            @ToolParam(description = "The number of lines to return (optional, if not provided, returns 100 lines)")
                    int length,
            @ToolParam(
                            description =
                                    "The offset (optional, if not provided, returns the first line). Negative values function as 'from the end', with -1 meaning starting with the last line")
                    long offset) {
        if (length == 0) length = 100;
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) return null;
        List<String> lines = null;

        try (BufferedReader reader = new BufferedReader(
                (buildNumber == null || buildNumber.isEmpty())
                        ? job.getLastBuild().getLogReader()
                        : job.getBuildByNumber(Integer.parseInt(buildNumber)).getLogReader())) {

            List<String> allLines = reader.lines().toList();
            long actualOffset = offset;
            if (offset < 0) actualOffset = Math.max(0, allLines.size() + offset);
            int endIndex = (int) Math.min(actualOffset + length, allLines.size());
            lines = allLines.subList((int) actualOffset, endIndex);
            boolean hasMoreContent = endIndex < allLines.size();

            ObjectNode jsonResponse = mapper.createObjectNode();
            jsonResponse.put("hasMoreContent", hasMoreContent);
            jsonResponse.set("lines", mapper.valueToTree(lines));
            return jsonResponse;
        } catch (IOException e) {
            return null;
        }
    }
}
