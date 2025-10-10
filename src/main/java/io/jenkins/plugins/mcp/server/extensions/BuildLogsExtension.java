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
import hudson.console.PlainTextConsoleOutputStream;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
public class BuildLogsExtension implements McpServerExtension {

    @Tool(
            description =
                    "Retrieves some log lines with pagination for a specific build or the last build of a Jenkins job,"
                            + " as well as a boolean value indicating whether there is more content to retrieve",
            annotations = @Tool.Annotations(destructiveHint = false))
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

    @Tool(
            description =
                    "Search for log lines matching a pattern in a specific build or the last build of a Jenkins job. "
                            + "Returns matching lines with their line numbers and context.",
            annotations = @Tool.Annotations(destructiveHint = false))
    public SearchLogResponse searchBuildLog(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @ToolParam(
                            description = "The build number (optional, if not provided, searches the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(description = "The search pattern (regex supported)") String pattern,
            @ToolParam(
                            description =
                                    "Whether to use regex pattern matching (default: false, uses simple string contains)",
                            required = false)
                    Boolean useRegex,
            @ToolParam(description = "Whether the search should be case-insensitive (default: false)", required = false)
                    Boolean ignoreCase,
            @ToolParam(
                            description = "Maximum number of matches to return (optional, default: 100, max: 1000)",
                            required = false)
                    Integer maxMatches,
            @ToolParam(
                            description = "Number of context lines to show before and after each match (default: 0)",
                            required = false)
                    Integer contextLines) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Search pattern cannot be null or empty");
        }
        if (useRegex == null) {
            useRegex = false;
        }
        if (ignoreCase == null) {
            ignoreCase = false;
        }
        if (maxMatches == null) {
            maxMatches = 100;
        }
        if (contextLines == null) {
            contextLines = 0;
        }

        // Enforce limits
        maxMatches = Math.min(maxMatches, 1000);
        contextLines = Math.min(Math.max(contextLines, 0), 10);

        final boolean useRegexF = useRegex;
        final boolean ignoreCaseF = ignoreCase;
        final int maxMatchesF = maxMatches;
        final int contextLinesF = contextLines;

        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    try {
                        return searchLogLines(build, pattern, useRegexF, ignoreCaseF, maxMatchesF, contextLinesF);
                    } catch (Exception e) {
                        log.error("Error searching log for job {} build {}", jobFullName, buildNumber, e);
                        return null;
                    }
                })
                .orElse(null);
    }

    private SearchLogResponse searchLogLines(
            Run<?, ?> run, String pattern, boolean useRegex, boolean ignoreCase, int maxMatches, int contextLines)
            throws Exception {
        log.trace(
                "searchLogLines for run {}/{} called with pattern '{}', useRegex={}, ignoreCase={}",
                run.getParent().getName(),
                run.getDisplayName(),
                pattern,
                useRegex,
                ignoreCase);

        Pattern searchPattern = null;
        if (useRegex) {
            int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
            searchPattern = Pattern.compile(pattern, flags);
        }

        List<String> allLines;
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            run.writeWholeLogTo(new PlainTextConsoleOutputStream(os));
            String logContent = os.toString(StandardCharsets.UTF_8);
            allLines = logContent.lines().toList();
        }

        List<SearchMatch> matches = new ArrayList<>();
        String searchPattern2 = ignoreCase ? pattern.toLowerCase() : pattern;

        for (int i = 0; i < allLines.size() && matches.size() < maxMatches; i++) {
            String line = allLines.get(i);
            boolean matched = false;

            if (useRegex && searchPattern != null) {
                Matcher matcher = searchPattern.matcher(line);
                matched = matcher.find();
            } else {
                String lineToSearch = ignoreCase ? line.toLowerCase() : line;
                matched = lineToSearch.contains(searchPattern2);
            }

            if (matched) {
                // Calculate context range
                int startLine = Math.max(0, i - contextLines);
                int endLine = Math.min(allLines.size() - 1, i + contextLines);

                List<String> contextLinesList = new ArrayList<>();
                for (int j = startLine; j <= endLine; j++) {
                    contextLinesList.add(allLines.get(j));
                }

                matches.add(new SearchMatch(i + 1, line, contextLinesList, startLine + 1, endLine + 1));
            }
        }

        boolean hasMoreMatches = false;
        // Check if there are more matches beyond maxMatches
        if (matches.size() >= maxMatches) {
            for (int i = matches.size(); i < allLines.size(); i++) {
                String line = allLines.get(i);
                boolean matched = false;

                if (useRegex && searchPattern != null) {
                    Matcher matcher = searchPattern.matcher(line);
                    matched = matcher.find();
                } else {
                    String lineToSearch = ignoreCase ? line.toLowerCase() : line;
                    matched = lineToSearch.contains(searchPattern2);
                }

                if (matched) {
                    hasMoreMatches = true;
                    break;
                }
            }
        }

        return new SearchLogResponse(
                pattern, useRegex, ignoreCase, matches.size(), hasMoreMatches, allLines.size(), matches);
    }

    private BuildLogResponse getLogLines(Run<?, ?> run, long skip, int limit) throws Exception {
        log.trace(
                "getLogLines for run {}/{} called with skip {}, limit {}",
                run.getParent().getName(),
                run.getDisplayName(),
                skip,
                limit);
        int maxLimit = SystemProperties.getInteger(BuildLogsExtension.class.getName() + ".limit.max", 10000);
        boolean negativeLimit = limit < 0;
        if (Math.abs(limit) > maxLimit) {
            log.warn("Limit {} is too large, using the default max limit {}", limit, maxLimit);
        }
        limit = Math.min(Math.abs(limit), maxLimit);
        if (negativeLimit) {
            limit = -limit;
        }

        // first need number of lines
        long skipInit = skip;
        int limitInit = limit;
        int linesNumber;
        long start = System.currentTimeMillis();
        log.trace("counting lines for run {}", run.getDisplayName());
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
                LinesNumberOutputStream out = new LinesNumberOutputStream(os)) {
            run.writeWholeLogTo(out);
            linesNumber = out.lines;
            if (log.isDebugEnabled()) {
                log.debug("counted {} lines in {} ms", linesNumber, System.currentTimeMillis() - start);
            }
        }
        // now we can make the maths to skip, limit and start from for the capture read
        // special for skip > 0 and limit < 0, we simply recalculate the skip and positive the limit
        if (skip > 0 && limit < 0) {
            skip = Math.max(0, skip - Math.abs(limit));
            limit = Math.abs(limit);
        } else if (skip == 0 && negativeLimit) {
            // skip == 0 and limit < 0: return the last -limit lines
            // limit is negative here, so linesNumber + limit = linesNumber - abs(limit)
            skip = Math.max(0, linesNumber + limit);
            limit = Math.abs(limit);
        } else if (skip < 0 && limit > 0) {
            // recalculate skip from the end
            skip = Math.max(0, linesNumber + skip);
        } else if (skip < 0 && limit < 0) {
            // recalculate skip from the end and make limit positive
            skip = Math.max(0, linesNumber + skip - Math.abs(limit));
            limit = Math.abs(limit);
        }

        // Calculate actual start and end lines (1-based for user display)
        long actualStartLine = skip + 1;
        long actualEndLine = Math.min(skip + limit, linesNumber);

        start = System.currentTimeMillis();
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
                SkipLogOutputStream out = new SkipLogOutputStream(os, skip, limit)) {
            run.writeWholeLogTo(out);
            if (log.isDebugEnabled()) {
                log.debug(
                        "call with skip {}, limit {} for linesNumber {} with read with skip {}, limit {}, time to extract: {} ms",
                        skipInit,
                        limitInit,
                        linesNumber,
                        skip,
                        limit,
                        System.currentTimeMillis() - start);
            }
            // is the right charset here?
            return new BuildLogResponse(
                    out.hasMoreContent,
                    os.toString(StandardCharsets.UTF_8).lines().toList(),
                    linesNumber,
                    actualStartLine,
                    actualEndLine);
        }
    }

    private static class LinesNumberOutputStream extends PlainTextConsoleOutputStream {
        private int lines;

        public LinesNumberOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        protected void eol(byte[] in, int sz) throws IOException {
            lines++;
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

    public record BuildLogResponse(
            boolean hasMoreContent, List<String> lines, int totalLines, long startLine, long endLine) {}

    public record SearchLogResponse(
            String pattern,
            boolean useRegex,
            boolean ignoreCase,
            int matchCount,
            boolean hasMoreMatches,
            int totalLines,
            List<SearchMatch> matches) {}

    public record SearchMatch(
            long lineNumber,
            String matchedLine,
            List<String> contextLines,
            long contextStartLine,
            long contextEndLine) {}

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
