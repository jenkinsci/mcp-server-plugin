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

import hudson.Extension;
import hudson.init.Terminator;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Listens for Jenkins shutdown events and notifies MCP clients.
 *
 * <p>This class hooks into Jenkins' termination lifecycle to update the health endpoint
 * status, allowing connected MCP clients to detect when Jenkins is shutting down and
 * prepare for reconnection.</p>
 *
 * <p>The shutdown sequence:</p>
 * <ol>
 *   <li>Jenkins initiates shutdown</li>
 *   <li>This terminator is called, setting the health endpoint to "shutting_down" state</li>
 *   <li>A grace period allows clients to detect the state change via the health endpoint</li>
 *   <li>Jenkins completes shutdown</li>
 * </ol>
 */
@Restricted(NoExternalUse.class)
@Extension
@Slf4j
public class McpServerShutdownListener {

    /**
     * Called when Jenkins is shutting down.
     *
     * <p>Sets the MCP server health endpoint to shutdown state and waits for a brief
     * grace period to allow clients to detect the shutdown.</p>
     *
     * @throws InterruptedException if the grace period sleep is interrupted
     */
    @Terminator
    public void onShutdown() throws InterruptedException {
        log.info("Jenkins shutdown detected, marking MCP server as shutting down");

        // Set the shutdown state so health endpoint returns 503
        HealthEndpoint.setShuttingDown(true);

        // Brief grace period to allow clients to detect shutdown via health endpoint
        // This gives active clients a chance to see the shutdown state before connections are closed
        int gracePeriodSeconds = HealthEndpoint.SHUTDOWN_GRACE_PERIOD_SECONDS;
        log.info("Waiting {} seconds for MCP clients to detect shutdown", gracePeriodSeconds);

        try {
            Thread.sleep(gracePeriodSeconds * 1000L);
        } catch (InterruptedException e) {
            log.debug("MCP shutdown grace period interrupted");
            Thread.currentThread().interrupt();
            throw e;
        }

        log.info("MCP server shutdown grace period complete");
    }
}
