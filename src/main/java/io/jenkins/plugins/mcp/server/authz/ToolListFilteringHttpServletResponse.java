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
import io.jenkins.plugins.mcp.server.tool.ToolListFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;

/**
 * Response wrapper for the streamable transport, which writes its {@code tools/list} response straight
 * to the HTTP stream as SSE frames (so the SSE session-factory trick can't reach it). It rewrites the
 * {@code data:} frame carrying the result, dropping tools the caller may not see; the rest passes through.
 */
public class ToolListFilteringHttpServletResponse extends HttpServletResponseWrapper {

    private final Authentication auth;
    private final Map<String, List<Permission>> toolPermissions;
    private final JsonMapper mapper;
    private PrintWriter writer;

    public ToolListFilteringHttpServletResponse(
            HttpServletResponse response,
            Authentication auth,
            Map<String, List<Permission>> toolPermissions,
            JsonMapper mapper) {
        super(response);
        this.auth = auth;
        this.toolPermissions = toolPermissions;
        this.mapper = mapper;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            writer = new PrintWriter(new SseFilterWriter(super.getWriter(), auth, toolPermissions, mapper));
        }
        return writer;
    }

    /**
     * Rewrites each complete line that is a {@code data:} frame carrying a {@code tools/list} result.
     * An SSE {@code data:} value is a single line (JSON escapes newlines), so buffering one line at a
     * time is enough and the stream is never held up.
     */
    private static final class SseFilterWriter extends Writer {

        private static final String DATA_PREFIX = "data:";

        private final PrintWriter delegate;
        private final Authentication auth;
        private final Map<String, List<Permission>> toolPermissions;
        private final JsonMapper mapper;
        private final StringBuilder line = new StringBuilder();

        SseFilterWriter(
                PrintWriter delegate,
                Authentication auth,
                Map<String, List<Permission>> toolPermissions,
                JsonMapper mapper) {
            this.delegate = delegate;
            this.auth = auth;
            this.toolPermissions = toolPermissions;
            this.mapper = mapper;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            for (int i = off; i < off + len; i++) {
                char c = cbuf[i];
                line.append(c);
                if (c == '\n') {
                    writeLine(line.toString());
                    line.setLength(0);
                }
            }
        }

        private void writeLine(String text) {
            if (!text.startsWith(DATA_PREFIX)) {
                delegate.write(text);
                return;
            }
            String newline = text.endsWith("\n") ? "\n" : "";
            String body = text.substring(DATA_PREFIX.length(), text.length() - newline.length());
            String leadingSpace = body.startsWith(" ") ? " " : "";
            String json = body.substring(leadingSpace.length());
            String filtered = ToolListFilter.filterEventData(mapper, json, auth, toolPermissions);
            delegate.write(DATA_PREFIX + leadingSpace + filtered + newline);
        }

        @Override
        public void flush() throws IOException {
            // A full SSE frame ends in a newline, so anything left at flush time is partial non-data
            // content (e.g. a plain JSON error) and safe to pass straight through.
            if (line.length() > 0 && !maybeDataPrefix(line)) {
                delegate.write(line.toString());
                line.setLength(0);
            }
            delegate.flush();
            if (delegate.checkError()) {
                throw new IOException("client disconnected");
            }
        }

        @Override
        public void close() throws IOException {
            if (line.length() > 0) {
                delegate.write(line.toString());
                line.setLength(0);
            }
            delegate.flush();
        }

        private static boolean maybeDataPrefix(CharSequence buffer) {
            int n = Math.min(buffer.length(), DATA_PREFIX.length());
            for (int i = 0; i < n; i++) {
                if (buffer.charAt(i) != DATA_PREFIX.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
