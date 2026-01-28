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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Tracks and exposes MCP connection metrics.
 *
 * <p>This endpoint requires authentication (unlike the health endpoint) and provides
 * detailed statistics about MCP connections for monitoring and debugging.</p>
 *
 * <p>Endpoint: {@code /mcp-server/health/metrics}</p>
 *
 * <p>Response format:</p>
 * <pre>
 * {
 *   "sseConnectionsTotal": 42,
 *   "sseConnectionsActive": 3,
 *   "streamableRequestsTotal": 150,
 *   "connectionErrorsTotal": 2,
 *   "uptimeSeconds": 3600,
 *   "startTime": "2025-01-28T10:00:00Z"
 * }
 * </pre>
 */
@Restricted(NoExternalUse.class)
@Slf4j
public class McpConnectionMetrics {

    public static final String URL_NAME = Endpoint.MCP_SERVER + "/metrics";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Total number of SSE connections established since startup.
     */
    private static final AtomicLong sseConnectionsTotal = new AtomicLong(0);

    /**
     * Currently active SSE connections.
     */
    private static final AtomicLong sseConnectionsActive = new AtomicLong(0);

    /**
     * Total number of Streamable HTTP requests since startup.
     */
    private static final AtomicLong streamableRequestsTotal = new AtomicLong(0);

    /**
     * Total number of connection errors since startup.
     */
    private static final AtomicLong connectionErrorsTotal = new AtomicLong(0);

    /**
     * Server start time for uptime calculation.
     */
    private static final Instant startTime = Instant.now();

    /**
     * Handles GET requests to the metrics endpoint.
     * Requires authentication (checks Jenkins.READ permission).
     * This method is called directly from the HttpServletFilter.
     *
     * @param response the HTTP response to send
     * @throws IOException if writing the response fails
     */
    public static void handleMetricsRequest(HttpServletResponse response) throws IOException {
        // Check for Overall/Read permission
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            // Check if security is enabled and user has permission
            if (jenkins.isUseSecurity()) {
                try {
                    jenkins.checkPermission(Jenkins.READ);
                } catch (AccessDeniedException e) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authentication required");
                    return;
                }
            }
        }

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);

        ObjectNode responseJson = objectMapper.createObjectNode();
        responseJson.put("sseConnectionsTotal", sseConnectionsTotal.get());
        responseJson.put("sseConnectionsActive", sseConnectionsActive.get());
        responseJson.put("streamableRequestsTotal", streamableRequestsTotal.get());
        responseJson.put("connectionErrorsTotal", connectionErrorsTotal.get());

        Duration uptime = Duration.between(startTime, Instant.now());
        responseJson.put("uptimeSeconds", uptime.getSeconds());
        responseJson.put("startTime", startTime.toString());

        response.getWriter().write(objectMapper.writeValueAsString(responseJson));
        response.getWriter().flush();
    }

    /**
     * Records a new SSE connection starting.
     */
    public static void recordSseConnectionStart() {
        sseConnectionsTotal.incrementAndGet();
        sseConnectionsActive.incrementAndGet();
    }

    /**
     * Records an SSE connection ending.
     */
    public static void recordSseConnectionEnd() {
        sseConnectionsActive.decrementAndGet();
    }

    /**
     * Records a Streamable HTTP request.
     */
    public static void recordStreamableRequest() {
        streamableRequestsTotal.incrementAndGet();
    }

    /**
     * Records a connection error.
     */
    public static void recordConnectionError() {
        connectionErrorsTotal.incrementAndGet();
    }

    /**
     * Returns the current number of active SSE connections.
     *
     * @return active SSE connection count
     */
    public static long getActiveSseConnections() {
        return sseConnectionsActive.get();
    }

    /**
     * Returns the total number of SSE connections since startup.
     *
     * @return total SSE connection count
     */
    public static long getTotalSseConnections() {
        return sseConnectionsTotal.get();
    }

    /**
     * Returns the total number of Streamable HTTP requests since startup.
     *
     * @return total Streamable request count
     */
    public static long getTotalStreamableRequests() {
        return streamableRequestsTotal.get();
    }

    /**
     * Returns the total number of connection errors since startup.
     *
     * @return total connection error count
     */
    public static long getTotalConnectionErrors() {
        return connectionErrorsTotal.get();
    }

    /**
     * Resets all metrics. Primarily for testing purposes.
     */
    static void reset() {
        sseConnectionsTotal.set(0);
        sseConnectionsActive.set(0);
        streamableRequestsTotal.set(0);
        connectionErrorsTotal.set(0);
    }
}
