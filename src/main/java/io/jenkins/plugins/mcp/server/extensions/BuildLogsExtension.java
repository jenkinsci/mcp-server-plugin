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

package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.extensions.util.JenkinsUtil.getBuildByNumberOrLast;

import hudson.Extension;
import hudson.console.ConsoleNote;
import hudson.console.PlainTextConsoleOutputStream;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.input.ReversedLinesFileReader;

@Extension
@Slf4j
public class BuildLogsExtension implements McpServerExtension {

    @Tool(
            description =
                    "Retrieves some log lines with pagination for a specific build or the last build of a Jenkins job,"
                            + " as well as a boolean value indicating whether there is more content to retrieve")
    public BuildLogResponse getBuildLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(
                            description = "The build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(
                            description =
                                    "The skip (optional, if not provided, returns from the first line). Negative values function as 'from the end', with -1 meaning starting with the last line",
                            required = false)
                    Long skip,
            @ToolParam(
                            description =
                                    "The number of lines to return (optional, if not provided, returns 100 lines), positive values return lines from the start, negative values return lines from the end",
                            required = false)
                    Integer limit) {
        if (limit == null || limit == 0) {
            limit = 100;
        }
        if (skip == null) {
            skip = 0L;
        }

        final int limitF = limit;
        final long skipF = skip;
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    try {
                        return getLogLines(build, skipF, limitF);
                    } catch (Exception e) {
                        log.error("Error reading log for job {} build {}", jobFullName, buildNumber, e);
                        return null;
                    }
                })
                .orElse(null);
    }

    private BuildLogResponse getLogLines(Run<?, ?> run, long skip, int limit) throws Exception {

        int maxLimit = SystemProperties.getInteger(BuildLogsExtension.class.getName() + ".limit.max", 10000);

        limit = Math.min(Math.abs(limit), maxLimit);

        if (skip < 0) {
            // TODO can get gz file?
            File logFile = run.getLogFile();
            boolean removedElement = false;
            try (ReversedLinesFileReader input =
                    ReversedLinesFileReader.builder().setFile(logFile).get()) {
                // we need this one if limit is positive
                Optional<LimitedQueue<String>> linesBucket =
                        limit > 0 ? Optional.of(new LimitedQueue<>(limit)) : Optional.empty();
                int current = -1;
                // here we advance the pointer so we may keep track of the line as it needs to be returned
                String currentLine = input.readLine();
                while (currentLine != null) {
                    if (linesBucket.isPresent()) {
                        removedElement = linesBucket.get().add(currentLine);
                    }
                    if (current <= skip) {
                        break;
                    }
                    currentLine = input.readLine();
                    current--;
                }
                if (currentLine == null
                        && (linesBucket.isEmpty() || linesBucket.get().isEmpty())) {
                    // no more lines and nothing to return last time to avoid NPE
                    return new BuildLogResponse(false, Collections.emptyList());
                }
                List<String> lines = limit > 0
                        ? new ArrayList<>(linesBucket.get().deque.stream().toList())
                        : new ArrayList<>(input.readLines(Math.abs(limit) - 1));
                // could be more simple to test if more line to populate hasMoreContent but if already reading too much
                // the call throw NPE (and catch NPE for this would be ugly)
                // so we try one line more and test number of lines returned
                // boolean hasMoreContent = input.readLine() != null;
                boolean hasMoreContent = limit > 0 ? removedElement : lines.size() > limit;
                Collections.reverse(lines);
                if (limit < 0) {
                    lines.add(currentLine);
                }
                lines = ConsoleNote.removeNotes(lines);
                return new BuildLogResponse(hasMoreContent, lines);
            }

        } else {
            // more simple run.getLogText().writeLogTo(0, new SkipLogOutputStream(System.out, skip, limit));
            try (ByteArrayOutputStream os = new ByteArrayOutputStream();
                    SkipLogOutputStream out = new SkipLogOutputStream(os, skip, limit)) {
                run.writeWholeLogTo(out);
                // is the right charset here?
                return new BuildLogResponse(
                        out.hasMoreContent,
                        os.toString(StandardCharsets.UTF_8).lines().toList());
            }
        }
    }

    private static class SkipLogOutputStream extends PlainTextConsoleOutputStream {
        private final long skip;
        private final int limit;
        private long current;
        private boolean hasMoreContent;

        public SkipLogOutputStream(OutputStream out, long skip, int limit) throws IOException {
            super(out);
            this.skip = skip;
            this.limit = limit;
        }

        @Override
        protected void eol(byte[] in, int sz) throws IOException {
            if (this.current >= this.skip && this.limit > (current - skip)) {
                super.eol(in, sz);
            } else {
                // skip the line but update hasMoreContent
                if (this.current - skip >= this.limit) {
                    hasMoreContent = true;
                }
            }
            current++;
        }
    }

    public record BuildLogResponse(boolean hasMoreContent, List<String> lines) {}

    private static class LimitedQueue<E> {
        private final int maxSize;
        private final Deque<E> deque;

        public LimitedQueue(int maxSize) {
            this.maxSize = maxSize;
            this.deque = new ArrayDeque<>(maxSize);
        }

        public boolean add(E e) {
            boolean removed = false;
            if (deque.size() == maxSize) {
                removed = true;
                deque.removeFirst();
            }
            deque.addLast(e);
            return removed;
        }

        public E remove() {
            return deque.removeFirst();
        }

        public int size() {
            return deque.size();
        }

        public boolean isEmpty() {
            return deque.isEmpty();
        }

        @Override
        public String toString() {
            return deque.toString();
        }
    }
}
