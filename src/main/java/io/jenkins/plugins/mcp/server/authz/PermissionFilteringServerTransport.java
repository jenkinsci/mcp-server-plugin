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
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Wraps an SSE session transport so each outgoing {@code tools/list} response is filtered per user
 * before it's sent. The caller comes from the reactive context the SSE transport sets for the request
 * ({@link McpTransportContext#KEY}).
 */
public class PermissionFilteringServerTransport implements McpServerTransport {

    private final McpServerTransport delegate;
    private final Map<String, List<Permission>> toolPermissions;

    public PermissionFilteringServerTransport(
            McpServerTransport delegate, Map<String, List<Permission>> toolPermissions) {
        this.delegate = delegate;
        this.toolPermissions = toolPermissions;
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCResponse response
                && response.result() instanceof McpSchema.ListToolsResult) {
            return Mono.deferContextual(contextView -> {
                Authentication auth = authenticationOf(contextView);
                return delegate.sendMessage(ToolListFilter.filter(response, auth, toolPermissions));
            });
        }
        return delegate.sendMessage(message);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
        return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private static Authentication authenticationOf(ContextView contextView) {
        McpTransportContext context = contextView.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
        Object auth = context == null ? null : context.get(Endpoint.AUTHENTICATION);
        return auth instanceof Authentication a ? a : null;
    }
}
