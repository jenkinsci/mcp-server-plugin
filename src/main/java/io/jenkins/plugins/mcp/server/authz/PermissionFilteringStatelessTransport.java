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
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Wraps the stateless transport just to slip a {@link PermissionFilteringStatelessHandler} in front of
 * the SDK handler; everything else is delegated. Give this to {@code McpServer.sync(...)} but keep
 * serving HTTP from the real transport.
 */
public class PermissionFilteringStatelessTransport implements McpStatelessServerTransport {

    private final McpStatelessServerTransport delegate;
    private final Map<String, List<Permission>> toolPermissions;

    public PermissionFilteringStatelessTransport(
            McpStatelessServerTransport delegate, Map<String, List<Permission>> toolPermissions) {
        this.delegate = delegate;
        this.toolPermissions = toolPermissions;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
        delegate.setMcpHandler(new PermissionFilteringStatelessHandler(mcpHandler, toolPermissions));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
