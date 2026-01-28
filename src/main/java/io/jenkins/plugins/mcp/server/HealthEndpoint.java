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
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Lightweight health endpoint for MCP Server connection monitoring.
 *
 * <p>This endpoint provides a quick way for clients to check if the MCP server is available
 * without the overhead of the full MCP protocol handshake. It's accessible without authentication
 * to enable maximum accessibility for health checking.</p>
 *
 * <p>The endpoint tracks shutdown state, allowing clients to detect when Jenkins is shutting down
 * and prepare for reconnection.</p>
 *
 * <p>Endpoint: {@code /mcp-server/health}</p>
 *
 * <p>Response format:</p>
 * <pre>
 * {
 *   "status": "ok" | "shutting_down",
 *   "timestamp": "2025-01-28T10:30:00Z",
 *   "jenkinsVersion": "2.533",
 *   "shuttingDown": false
 * }
 * </pre>
 */
@Restricted(NoExternalUse.class)
@Slf4j
public class HealthEndpoint {

    public static final String URL_NAME = Endpoint.MCP_SERVER + "/health";

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

    /**
     * Handles GET requests to the health endpoint.
     * This method is called directly from the HttpServletFilter to bypass authentication.
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
        responseJson.put("status", isShuttingDown ? "shutting_down" : "ok");
        responseJson.put("timestamp", Instant.now().toString());

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            responseJson.put("jenkinsVersion", Jenkins.VERSION);
        }

        responseJson.put("shuttingDown", isShuttingDown);

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
