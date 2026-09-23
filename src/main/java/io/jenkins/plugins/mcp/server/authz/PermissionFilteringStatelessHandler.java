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

package io.jenkins.plugins.mcp.server.authz;

import hudson.security.Permission;
import io.jenkins.plugins.mcp.server.Endpoint;
import io.jenkins.plugins.mcp.server.tool.ToolListFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * Filters {@code tools/list} responses per user for the stateless transport. The handler already gets
 * the per-request {@link McpTransportContext}, so the caller is read straight from it.
 */
public class PermissionFilteringStatelessHandler implements McpStatelessServerHandler {

    private static final String TOOLS_LIST = "tools/list";

    private final McpStatelessServerHandler delegate;
    private final Map<String, List<Permission>> toolPermissions;

    public PermissionFilteringStatelessHandler(
            McpStatelessServerHandler delegate, Map<String, List<Permission>> toolPermissions) {
        this.delegate = delegate;
        this.toolPermissions = toolPermissions;
    }

    @Override
    public Mono<McpSchema.JSONRPCResponse> handleRequest(
            McpTransportContext transportContext, McpSchema.JSONRPCRequest request) {
        Mono<McpSchema.JSONRPCResponse> response = delegate.handleRequest(transportContext, request);
        if (!TOOLS_LIST.equals(request.method())) {
            return response;
        }
        Authentication auth = authenticationOf(transportContext);
        return response.map(r -> ToolListFilter.filter(r, auth, toolPermissions));
    }

    @Override
    public Mono<Void> handleNotification(
            McpTransportContext transportContext, McpSchema.JSONRPCNotification notification) {
        return delegate.handleNotification(transportContext, notification);
    }

    static Authentication authenticationOf(McpTransportContext context) {
        Object auth = context == null ? null : context.get(Endpoint.AUTHENTICATION);
        return auth instanceof Authentication a ? a : null;
    }
}
