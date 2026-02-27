/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
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

package io.jenkins.plugins.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

/**
 * Lightweight health endpoint specifically for MCP Server status monitoring.
 *
 * <p>This endpoint provides MCP clients a quick way to check if the MCP server is available
 * and accepting connections, without the overhead of the full MCP protocol handshake.
 * It's accessible without authentication to enable health checking from load balancers
 * and monitoring systems.</p>
 *
 * <p>Unlike a generic Jenkins health endpoint, this endpoint returns MCP-specific information
 * such as active connection counts and server shutdown state, allowing MCP clients to make
 * informed decisions about connection management and reconnection.</p>
 *
 * <p>Endpoint: {@code /mcp-health}</p>
 *
 * <p>Response format:</p>
 * <pre>
 * {
 *   "mcpServerStatus": "ok" | "shutting_down",
 *   "activeConnections": 5,
 *   "shuttingDown": false,
 *   "timestamp": "2025-01-28T10:30:00Z"
 * }
 * </pre>
 */
@Restricted(NoExternalUse.class)
@Extension
@Slf4j
public class HealthEndpoint implements UnprotectedRootAction {

    public static final String URL_NAME = "mcp-health";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Tracks whether Jenkins is in the process of shutting down.
     * Set by {@link McpServerShutdownListener}.
     */
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Grace period in seconds for clients to detect shutdown before full termination.
     */
    public static final int SHUTDOWN_GRACE_PERIOD_SECONDS = 5;

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    /**
     * Handles GET requests via Stapler.
     *
     * @param req the Stapler request
     * @param rsp the Stapler response
     * @throws IOException if writing the response fails
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        handleHealthRequest(rsp);
    }

    /**
     * Handles GET requests to the MCP health endpoint.
     * Returns MCP-specific status information including active connection counts.
     * This method can be called directly or via Stapler.
     *
     * @param response the HTTP response to send
     * @throws IOException if writing the response fails
     */
    public static void handleHealthRequest(HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");

        boolean isShuttingDown = shuttingDown.get();

        if (isShuttingDown) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", String.valueOf(SHUTDOWN_GRACE_PERIOD_SECONDS));
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }

        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.put("mcpServerStatus", isShuttingDown ? "shutting_down" : "ok");
        responseJson.put("activeConnections", McpConnectionMetrics.getActiveSseConnections());
        responseJson.put("shuttingDown", isShuttingDown);
        responseJson.put("timestamp", Instant.now().toString());

        response.getWriter().write(objectMapper.writeValueAsString(responseJson));
        response.getWriter().flush();
    }

    /**
     * Sets the shutdown state of the MCP server.
     * Called by {@link McpServerShutdownListener} when Jenkins begins shutdown.
     *
     * @param shutdown true if Jenkins is shutting down
     */
    public static void setShuttingDown(boolean shutdown) {
        shuttingDown.set(shutdown);
        if (shutdown) {
            log.info("MCP Server health endpoint marked as shutting down");
        }
    }

    /**
     * Returns whether the MCP server is in shutdown state.
     *
     * @return true if shutting down
     */
    public static boolean isShuttingDown() {
        return shuttingDown.get();
    }
}
