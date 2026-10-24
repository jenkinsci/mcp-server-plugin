package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.User;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.util.List;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
public class AgentExtension implements McpServerExtension {

    @Tool(
            description = "Get a list of all agent names, excluding the built-in node (master)",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public List<String> listAgentNames() {
        return Jenkins.get().getNodes().stream().map(Node::getNodeName).toList();
    }

    @Tool(
            description = "Get a Jenkins agent by its name (the Computer object as the api does)",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public Computer getAgent(@ToolParam(description = "Agent name") String name) {
        return Jenkins.get().getComputer(name);
    }

    @Tool(description = "Marks a Jenkins agent temporarily offline or takes it back online")
    public boolean agentStatus(
            @ToolParam(description = "Agent name") String name,
            @ToolParam(
                            description =
                                    "Agent status, 'ONLINE' to take the agent online or 'OFFLINE' to take the agent offline")
                    AgentStatus status,
            @ToolParam(description = "Offline reason when taking the agent offline", required = false) String reason) {
        Computer computer = Jenkins.get().getComputer(name);
        if (computer == null) {
            return false;
        }
        if (status == AgentStatus.OFFLINE && !computer.hasPermission(Computer.DISCONNECT)) {
            return false;
        }
        if (status == AgentStatus.ONLINE && !computer.hasPermission(Computer.CONNECT)) {
            return false;
        }
        if (status == AgentStatus.OFFLINE) {
            OfflineCause.UserCause cause = new OfflineCause.UserCause(User.current(), Util.fixEmptyAndTrim(reason));
            computer.setTemporaryOfflineCause(cause);
            return true;
        }
        computer.setTemporaryOfflineCause(null);
        return true;
    }

    public enum AgentStatus {
        ONLINE,
        OFFLINE;
    }
}
