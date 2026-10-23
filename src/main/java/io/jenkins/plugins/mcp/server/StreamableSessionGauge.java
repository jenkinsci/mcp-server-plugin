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

package io.jenkins.plugins.mcp.server;

import hudson.ExtensionList;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.OptionalLong;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Counts the streamable HTTP sessions the server is holding.
 *
 * <p>The MCP SDK keeps them in a private map and offers no way to count them, so we read the field.
 * Asked for upstream in
 * <a href="https://github.com/modelcontextprotocol/java-sdk/issues/1063">java-sdk#1063</a>; drop this
 * class once that lands. Keeping our own tally instead would not work, as the SDK drops idle sessions
 * without telling us.
 *
 * <p>Nothing here throws: if the field moves, the count is unavailable and the metric is left out.
 */
@Restricted(NoExternalUse.class)
@Slf4j
final class StreamableSessionGauge {

    private static final String SESSIONS_FIELD = "sessions";

    /** The SDK's session map, or null if we can't reach it. Looked up once: it either works or never will. */
    private static final Field SESSIONS = lookupSessionsField();

    private StreamableSessionGauge() {
        // utility class
    }

    private static Field lookupSessionsField() {
        try {
            Field field = HttpServletStreamableServerTransportProvider.class.getDeclaredField(SESSIONS_FIELD);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Cannot read streamable session count from the MCP SDK, metric will be omitted", e);
            return null;
        }
    }

    /** Live streamable sessions, or empty if the transport is off, not built yet, or the SDK has changed. */
    static OptionalLong activeSessions() {
        if (SESSIONS == null) {
            return OptionalLong.empty();
        }
        Endpoint endpoint = ExtensionList.lookupFirst(Endpoint.class);
        if (endpoint == null || endpoint.httpServletStreamableServerTransportProvider == null) {
            return OptionalLong.empty();
        }
        try {
            Object sessions = SESSIONS.get(endpoint.httpServletStreamableServerTransportProvider);
            if (sessions instanceof Map<?, ?> map) {
                return OptionalLong.of(map.size());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("Failed to read the streamable session count", e);
        }
        return OptionalLong.empty();
    }
}
