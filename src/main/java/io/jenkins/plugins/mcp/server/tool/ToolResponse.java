package io.jenkins.plugins.mcp.server.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolResponse {

    public static final String NO_DATA_MSG = "Search completed, but no results were found for the specified criteria.";

    public static final String DATA_MSG = "Data retrieved successfully.";

    private Status status;

    private String message;

    private Object result;

    public enum Status {
        COMPLETED,
        FAILED;
    }
}
