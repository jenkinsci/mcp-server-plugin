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
import hudson.console.LineTransformationOutputStream;
import hudson.console.PlainTextConsoleOutputStream;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.SlidingWindow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import jenkins.util.SystemProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
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

        try (SearchingOutputStream sos =
                new SearchingOutputStream(pattern, useRegex, ignoreCase, maxMatches, contextLines)) {
            run.writeWholeLogTo(new PlainTextConsoleOutputStream(sos));
            return new SearchLogResponse(
                    pattern,
                    useRegex,
                    ignoreCase,
                    sos.getMatches().size(),
                    sos.hasMoreMatches,
                    sos.lineNumber,
                    sos.getMatches());
        }
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

    static class SearchingOutputStream extends LineTransformationOutputStream {
        private final String pattern;
        private final boolean useRegex;
        private final boolean ignoreCase;
        private final int maxMatches;
        private final long contextLines;
        private final SlidingWindow<String> slidingWindow;
        private final List<SearchMatch> openMatches = new ArrayList<>();
        private final List<SearchMatch> closedMatches = new ArrayList<>();
        private boolean hasMoreMatches = false;
        private long lineNumber = 0;
        private Pattern compiledPattern;

        public SearchingOutputStream(
                String pattern, boolean useRegex, boolean ignoreCase, int maxMatches, int contextLines)
                throws IOException {
            this.pattern = pattern;
            this.useRegex = useRegex;
            this.ignoreCase = ignoreCase;
            this.maxMatches = maxMatches;
            this.contextLines = contextLines;
            this.slidingWindow = new SlidingWindow<>(contextLines);

            if (useRegex) {
                int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                this.compiledPattern = Pattern.compile(pattern, flags);
            }
        }

        @Override
        protected void eol(byte[] b, int len) {
            lineNumber++;
            String line = new String(b, 0, len, StandardCharsets.UTF_8).trim();

            boolean matched = matchLine(line);
            if (matched && (openMatches.size() + closedMatches.size() < maxMatches)) {
                addNewMatch(line);
            } else if (!hasMoreMatches && (openMatches.size() + closedMatches.size() >= maxMatches)) {
                hasMoreMatches = matched;
            }

            updateOpenMatches(line);
            slidingWindow.add(line);
        }

        private boolean matchLine(String line) {
            if (useRegex) {
                return compiledPattern.matcher(line).find();
            } else {
                return ignoreCase ? line.toLowerCase().contains(pattern.toLowerCase()) : line.contains(pattern);
            }
        }

        private void addNewMatch(String matchedLine) {
            List<String> contextLines = new ArrayList<>(slidingWindow.size());
            contextLines.addAll(slidingWindow.getRecords());
            long contextStartLine = lineNumber - contextLines.size();
            openMatches.add(new SearchMatch(lineNumber, matchedLine, contextLines, contextStartLine, lineNumber));
        }

        private void updateOpenMatches(String line) {
            Iterator<SearchMatch> iterator = openMatches.iterator();
            while (iterator.hasNext()) {
                SearchMatch match = iterator.next();

                match.addContextLine(line);
                match.setContextEndLine(lineNumber);

                if (lineNumber >= match.matchedLineNumber + contextLines) {
                    closedMatches.add(match);
                    iterator.remove();
                }
            }
        }

        public List<SearchMatch> getMatches() {
            List<SearchMatch> allMatches = new ArrayList<>(closedMatches);
            allMatches.addAll(openMatches);
            return allMatches;
        }

        public boolean hasMoreMatches() {
            return hasMoreMatches;
        }

        public long getTotalLines() {
            return lineNumber;
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
            long totalLines,
            List<SearchMatch> matches) {}

    @Getter
    @RequiredArgsConstructor
    public static class SearchMatch {
        private final long matchedLineNumber;
        private final String matchedLine;
        private final List<String> contextLines;
        private final long contextStartLine;

        @Setter
        private long contextEndLine;

        public SearchMatch(
                long matchedLineNumber,
                String matchedLine,
                List<String> contextLines,
                long contextStartLine,
                long contextEndLine) {
            this(matchedLineNumber, matchedLine, new ArrayList<>(contextLines), contextStartLine);
            this.contextEndLine = contextEndLine;
        }

        public void addContextLine(String line) {
            this.contextLines.add(line);
        }
    }
}
