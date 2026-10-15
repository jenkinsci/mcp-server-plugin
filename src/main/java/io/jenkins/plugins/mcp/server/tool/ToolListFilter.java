/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Olivier Lamy
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

package io.jenkins.plugins.mcp.server.tool;

import hudson.security.Permission;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Removes from a {@code tools/list} result any tool the caller may not see (see {@link ToolPermissions#isAllowed}). */
public final class ToolListFilter {

    private ToolListFilter() {}

    /** Filters a {@code tools/list} JSON-RPC response; anything else, or nothing removed, comes back as-is. */
    public static McpSchema.JSONRPCResponse filter(
            McpSchema.JSONRPCResponse response,
            @Nullable Authentication auth,
            Map<String, List<Permission>> toolPermissions) {
        if (response == null || !(response.result() instanceof McpSchema.ListToolsResult listToolsResult)) {
            return response;
        }
        McpSchema.ListToolsResult filtered = filter(listToolsResult, auth, toolPermissions);
        return filtered == listToolsResult
                ? response
                : new McpSchema.JSONRPCResponse(response.jsonrpc(), response.id(), filtered, response.error());
    }

    /** Filters a {@code tools/list} result, returning it unchanged when nothing is removed. */
    public static McpSchema.ListToolsResult filter(
            McpSchema.ListToolsResult result,
            @Nullable Authentication auth,
            Map<String, List<Permission>> toolPermissions) {
        List<McpSchema.Tool> visible = result.tools().stream()
                .filter(tool -> isVisible(tool.name(), auth, toolPermissions))
                .toList();
        return visible.size() == result.tools().size()
                ? result
                : new McpSchema.ListToolsResult(visible, result.nextCursor(), result.meta());
    }

    /**
     * Filters the JSON of an SSE {@code data:} frame that carries a {@code tools/list} result, for the
     * streamable transport (which writes straight to the HTTP stream). Returns the original text on
     * anything unexpected, so other frames are left alone.
     */
    public static String filterEventData(
            JsonMapper mapper,
            String dataJson,
            @Nullable Authentication auth,
            Map<String, List<Permission>> toolPermissions) {
        try {
            JsonNode root = mapper.readTree(dataJson);
            if (!(root.get("result") instanceof ObjectNode result)
                    || !(result.get("tools") instanceof ArrayNode tools)) {
                return dataJson;
            }
            ArrayNode kept = mapper.createArrayNode();
            for (JsonNode tool : tools) {
                if (isVisible(tool.path("name").asString(), auth, toolPermissions)) {
                    kept.add(tool);
                }
            }
            if (kept.size() == tools.size()) {
                return dataJson;
            }
            result.set("tools", kept);
            return mapper.writeValueAsString(root);
        } catch (RuntimeException e) {
            return dataJson;
        }
    }

    private static boolean isVisible(
            String toolName, @Nullable Authentication auth, Map<String, List<Permission>> toolPermissions) {
        return ToolPermissions.isAllowed(auth, toolPermissions.getOrDefault(toolName, List.of()));
    }
}
