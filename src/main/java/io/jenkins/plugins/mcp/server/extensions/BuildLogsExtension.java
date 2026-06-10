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
import hudson.console.AnnotatedLargeText;
import hudson.console.LineTransformationOutputStream;
import hudson.console.PlainTextConsoleOutputStream;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.extensions.util.SlidingWindow;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
                            + " as well as a boolean value indicating whether there is more content to retrieve"
                            + " For sequential pagination, pass the returned 'nextCursor' back as 'cursor' to resume"
                            + " from the same spot without re-scanning. 'totalLines' is exact for end-relative reads"
                            + " and -1 for forward reads (not computed)."
                            + " If 'nextCursor' is set but 'hasMoreContent' is false, you've read everything written"
                            + " so far but the build is still going; keep the cursor and call again later to pick up"
                            + " whatever was appended in the meantime.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
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
                    Integer limit,
            @ToolParam(
                            description =
                                    "Opaque cursor previously returned as 'nextCursor'. When set, reading resumes from that position; 'skip' is ignored and 'startLine', 'endLine' and 'totalLines' are reported as -1 (the cursor doesn't carry line numbers). The cursor is tied to the (job, buildNumber) it was issued for; passing it for a different build is rejected as invalid",
                            required = false)
                    String cursor) {
        if (limit == null || limit == 0) {
            limit = 100;
        }
        if (skip == null) {
            skip = 0L;
        }

        final int limitF = limit;
        final long skipF = skip;
        final String cursorF = cursor;
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    try {
                        return getLogLines(build, cursorF, skipF, limitF);
                    } catch (IllegalArgumentException e) {
                        // Let bad-input errors (invalid or wrong-build cursor) bubble up so the caller
                        // sees them; only unexpected failures get swallowed below.
                        throw e;
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
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
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

        try (SearchingOutputStream sos = new SearchingOutputStream(
                pattern, useRegex, ignoreCase, maxMatches, contextLines, true, run.getCharset())) {
            PlainTextConsoleOutputStream plain = new PlainTextConsoleOutputStream(sos);
            try {
                drain(run.getLogText(), 0L, plain);
                plain.forceEol();
                sos.forceEol();
            } catch (StopReading ignored) {
                // budget hit, every open match has its trailing context: nothing useful left to scan
            }
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

    private BuildLogResponse getLogLines(Run<?, ?> run, String cursor, long skip, int limit) throws IOException {
        log.trace(
                "getLogLines for run {}/{} called with cursor {}, skip {}, limit {}",
                run.getParent().getName(),
                run.getDisplayName(),
                cursor,
                skip,
                limit);
        int maxLimit = SystemProperties.getInteger(BuildLogsExtension.class.getName() + ".limit.max", 10000);
        if (Math.abs((long) limit) > maxLimit) {
            log.warn("Limit {} is too large, using the default max limit {}", limit, maxLimit);
        }
        int absLimit = (int) Math.min(Math.abs((long) limit), maxLimit);
        limit = limit < 0 ? -absLimit : absLimit;

        Charset charset = run.getCharset();

        // Cursor: jump to the byte offset and ignore skip. We don't know the line numbers at an
        // arbitrary offset, so startLine/endLine/totalLines all come back as -1.
        if (cursor != null && !cursor.isEmpty()) {
            long byteOffset = decodeCursor(cursor, run.getNumber());
            return readForward(run, charset, byteOffset, 0L, absLimit, false, 0L);
        }

        // Forward read: we can stop as soon as we have enough lines, so we don't bother counting the
        // total. totalLines comes back as -1.
        if ((skip >= 0 && limit > 0) || (skip > 0 && limit < 0)) {
            long resolvedSkip;
            int resolvedLimit;
            if (limit < 0) {
                // skip > 0 && limit < 0: window ends at `skip`, so [skip - |limit|, skip)
                resolvedLimit = absLimit;
                resolvedSkip = Math.max(0, skip - resolvedLimit);
            } else {
                resolvedSkip = skip;
                resolvedLimit = absLimit;
            }
            return readForward(run, charset, 0L, resolvedSkip, resolvedLimit, true, resolvedSkip);
        }

        // Tail read (negative skip, or skip == 0 with negative limit): we need the total to resolve
        // the window, so we make one full pass. totalLines is exact in this branch.
        return readTail(run, charset, skip, limit, maxLimit);
    }

    /**
     * Cursor format: base64url of {@code "<buildNumber>:<byteOffset>"}. The build number is in there so
     * a cursor accidentally replayed against a different build is rejected up front rather than handing
     * back unrelated bytes from another build's log.
     */
    private static String encodeCursor(int buildNumber, long byteOffset) {
        String raw = buildNumber + ":" + byteOffset;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    private static long decodeCursor(String cursor, int expectedBuildNumber) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
        int sep = raw.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        int buildNumber;
        long byteOffset;
        try {
            buildNumber = Integer.parseInt(raw, 0, sep, 10);
            byteOffset = Long.parseLong(raw, sep + 1, raw.length(), 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
        if (byteOffset < 0) {
            throw new IllegalArgumentException("Invalid cursor: negative byte offset");
        }
        if (buildNumber != expectedBuildNumber) {
            throw new IllegalArgumentException("Cursor was issued for build #" + buildNumber + ", but build #"
                    + expectedBuildNumber + " was requested");
        }
        return byteOffset;
    }

    /**
     * Forward / cursor read. Streams from {@code startByte}, skips {@code skip} lines, captures up to
     * {@code limit}, and stops the moment we see one more line beyond the window (that's enough to
     * know there's more). Saves us from reading the whole log just to serve a "head" request.
     */
    private BuildLogResponse readForward(
            Run<?, ?> run,
            Charset charset,
            long startByte,
            long skip,
            int limit,
            boolean knowLineNumbers,
            long baseLine)
            throws IOException {
        AnnotatedLargeText<?> logText = run.getLogText();
        long end = logText.length();
        if (startByte >= end) {
            // Cursor is already at the end. If the build is still going, hand back the same offset so
            // the caller can poll for what gets appended next; if it's done, there's nothing left.
            String nextCursor = run.isLogUpdated() ? encodeCursor(run.getNumber(), startByte) : null;
            return new BuildLogResponse(List.of(), false, -1, -1, -1, nextCursor);
        }
        long start = System.currentTimeMillis();
        ForwardLineCollector collector = new ForwardLineCollector(charset, skip, limit);
        try {
            drain(logText, startByte, collector);
            collector.forceEol();
        } catch (StopReading ignored) {
            // got `limit` lines and saw at least one more; that's all we needed
        }
        List<String> lines = collector.lines;
        boolean more = collector.hasMoreContent;
        long nextByteOffset = more
                ? startByte + collector.captureEndOffset
                : (run.isLogUpdated() ? startByte + collector.rawBytesConsumed : -1);
        String nextCursor = nextByteOffset >= 0 ? encodeCursor(run.getNumber(), nextByteOffset) : null;
        long startLine = !knowLineNumbers || lines.isEmpty() ? -1 : baseLine + 1;
        long endLine = !knowLineNumbers || lines.isEmpty() ? -1 : baseLine + lines.size();
        if (log.isDebugEnabled()) {
            log.debug(
                    "forward read from byte {} skip {} limit {} -> {} lines, more={}, time: {} ms",
                    startByte,
                    skip,
                    limit,
                    lines.size(),
                    more,
                    System.currentTimeMillis() - start);
        }
        return new BuildLogResponse(lines, more, startLine, endLine, -1, nextCursor);
    }

    /**
     * Tail read. One full pass that counts total lines and keeps only the trailing slice that could
     * fall inside the requested window in a bounded ring buffer. Replaces the previous count-then-
     * extract approach (two full passes) with a single pass.
     */
    private BuildLogResponse readTail(Run<?, ?> run, Charset charset, long skip, int limit, int maxLimit)
            throws IOException {
        long theoretical;
        if (skip == 0) {
            theoretical = Math.abs((long) limit); // skip == 0 && limit < 0
        } else if (limit > 0) {
            theoretical = -skip; // skip < 0 && limit > 0
        } else {
            theoretical = (-skip) + Math.abs((long) limit); // skip < 0 && limit < 0
        }
        if (theoretical > maxLimit) {
            log.warn(
                    "Requested end-relative window of {} lines exceeds the max of {}; older lines are not retained",
                    theoretical,
                    maxLimit);
        }
        int capacity = (int) Math.max(1, Math.min(theoretical, maxLimit));

        AnnotatedLargeText<?> logText = run.getLogText();
        long start = System.currentTimeMillis();
        TailLineCollector collector = new TailLineCollector(charset, capacity);
        drain(logText, 0L, collector);
        collector.forceEol();
        long total = collector.totalLines;
        long endOfSnapshot = collector.rawBytesConsumed;

        long resolvedSkip;
        int resolvedLimit = Math.abs(limit);
        if (skip == 0) {
            resolvedSkip = Math.max(0, total - resolvedLimit);
        } else if (limit > 0) {
            resolvedSkip = Math.max(0, total + skip);
        } else {
            resolvedSkip = Math.max(0, total + skip - resolvedLimit);
        }
        long endExclusive = Math.min(resolvedSkip + resolvedLimit, total);

        List<String> lines = new ArrayList<>();
        long lastEndOffset = -1;
        for (TailLine entry : collector.window.getRecords()) {
            if (entry.index() >= resolvedSkip && entry.index() < endExclusive) {
                lines.add(collector.decode(entry.raw()));
                lastEndOffset = entry.endOffset();
            }
        }
        boolean more = endExclusive < total;
        // If we're caught up but the build is still going, return a cursor pointing at the end of the
        // snapshot so the caller can come back later and pick up whatever was written in between.
        long nextByteOffset = more ? lastEndOffset : (run.isLogUpdated() ? endOfSnapshot : -1);
        String nextCursor = nextByteOffset >= 0 ? encodeCursor(run.getNumber(), nextByteOffset) : null;
        long startLine = lines.isEmpty() ? -1 : resolvedSkip + 1;
        long endLine = lines.isEmpty() ? -1 : endExclusive;
        if (log.isDebugEnabled()) {
            log.debug(
                    "tail read skip {} limit {} total {} -> {} lines, more={}, time: {} ms",
                    skip,
                    limit,
                    total,
                    lines.size(),
                    more,
                    System.currentTimeMillis() - start);
        }
        return new BuildLogResponse(lines, more, startLine, endLine, total, nextCursor);
    }

    /**
     * Streams a single non-blocking snapshot of the log to {@code out}, from raw byte {@code startByte}
     * up to the current length. Unlike {@link Run#writeWholeLogTo}, this won't sit and wait for an
     * in-progress build to finish.
     *
     * <p>We use {@link AnnotatedLargeText#writeRawLogTo} so {@code out} sees the raw bytes (console
     * notes and all) and the offsets line up with the ones we accept back as a cursor — callers handle
     * stripping notes themselves via {@link PlainTextConsoleOutputStream}. The output is buffered
     * because {@code writeRawLogTo} likes to write in small chunks.
     */
    private static void drain(AnnotatedLargeText<?> logText, long startByte, OutputStream out) throws IOException {
        long end = logText.length();
        long pos = startByte;
        BufferedOutputStream buffered = new BufferedOutputStream(out, 8192);
        while (pos < end) {
            long newPos = logText.writeRawLogTo(pos, buffered);
            if (newPos <= pos) {
                break;
            }
            pos = newPos;
        }
        buffered.flush();
    }

    static class SearchingOutputStream extends LineTransformationOutputStream {
        private final String pattern;
        private final boolean useRegex;
        private final boolean ignoreCase;
        private final int maxMatches;
        private final long contextLines;
        private final boolean earlyTerminate;
        private final Charset charset;
        private final SlidingWindow<String> slidingWindow;
        private final List<SearchMatch> openMatches = new ArrayList<>();
        private final List<SearchMatch> closedMatches = new ArrayList<>();
        private boolean hasMoreMatches = false;
        private boolean terminated = false;
        private long lineNumber = 0;
        private Pattern compiledPattern;

        public SearchingOutputStream(
                String pattern, boolean useRegex, boolean ignoreCase, int maxMatches, int contextLines)
                throws IOException {
            this(pattern, useRegex, ignoreCase, maxMatches, contextLines, false, StandardCharsets.UTF_8);
        }

        public SearchingOutputStream(
                String pattern,
                boolean useRegex,
                boolean ignoreCase,
                int maxMatches,
                int contextLines,
                boolean earlyTerminate,
                Charset charset)
                throws IOException {
            this.pattern = pattern;
            this.useRegex = useRegex;
            this.ignoreCase = ignoreCase;
            this.maxMatches = maxMatches;
            this.contextLines = contextLines;
            this.earlyTerminate = earlyTerminate;
            this.charset = charset;
            this.slidingWindow = new SlidingWindow<>(contextLines);

            if (useRegex) {
                int flags = ignoreCase ? Pattern.CASE_INSENSITIVE : 0;
                this.compiledPattern = Pattern.compile(pattern, flags);
            }
        }

        @Override
        protected void eol(byte[] b, int len) {
            // close() flushes a pending partial line through eol(); ignore it once we've already bailed.
            if (terminated) {
                return;
            }
            lineNumber++;
            String line = new String(b, 0, len, charset).trim();

            // Once the budget's full we can't take any new matches; we just need to finish trailing
            // context for the ones still open. Skip the match check in that window unless we still
            // need to flip `hasMoreMatches`.
            boolean budgetFull = openMatches.size() + closedMatches.size() >= maxMatches;
            boolean matched;
            if (budgetFull && hasMoreMatches) {
                matched = false; // already know the answer, no point re-testing
            } else if (budgetFull) {
                matched = matchLine(line);
                hasMoreMatches = matched;
            } else {
                matched = matchLine(line);
                if (matched) {
                    addNewMatch(line);
                }
            }

            updateOpenMatches(line);
            slidingWindow.add(line);

            // Budget hit, all open matches' context is filled, and we've seen at least one extra
            // match: nothing left here that can change the result, so bail out of the scan.
            if (earlyTerminate && hasMoreMatches && openMatches.isEmpty()) {
                terminated = true;
                throw StopReading.INSTANCE;
            }
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

    /**
     * Thrown by a collector to short-circuit the rest of the log read once it has what it needs. No
     * stack trace ({@code writableStackTrace=false}) and a shared singleton instance, since the trace
     * gets discarded by the catch block anyway and we want this to be cheap on the hot path.
     */
    private static final class StopReading extends RuntimeException {
        static final StopReading INSTANCE = new StopReading();

        private StopReading() {
            super(null, null, false, false);
        }
    }

    /**
     * Reads raw (annotated) log bytes one line at a time. Tracks the raw byte offset at every line
     * boundary so we can hand back a resumable cursor, and strips console notes on demand by routing
     * a copy of the line through {@link PlainTextConsoleOutputStream#eol()} into a reusable buffer.
     */
    private abstract static class PlainTextLineCollector extends PlainTextConsoleOutputStream {
        private final Charset charset;
        private final ByteArrayOutputStream lineBuffer;
        long rawBytesConsumed;
        long lineCount;

        protected PlainTextLineCollector(Charset charset) {
            this(charset, new ByteArrayOutputStream(256));
        }

        private PlainTextLineCollector(Charset charset, ByteArrayOutputStream lineBuffer) {
            super(lineBuffer);
            this.charset = charset;
            this.lineBuffer = lineBuffer;
        }

        @Override
        protected final void eol(byte[] in, int sz) throws IOException {
            long lineStart = rawBytesConsumed;
            rawBytesConsumed += sz;
            onLine(lineCount++, in, sz, lineStart, rawBytesConsumed);
        }

        /** Strips console notes from the given raw line and returns the plain, EOL-trimmed text. */
        protected final String decode(byte[] in, int sz) throws IOException {
            lineBuffer.reset();
            super.eol(in, sz);
            return trimEOL(lineBuffer.toString(charset));
        }

        protected final String decode(byte[] in) throws IOException {
            return decode(in, in.length);
        }

        /**
         * @param index 0-based line index
         * @param in raw line bytes (including any console notes and the EOL)
         * @param sz number of valid bytes in {@code in}
         * @param lineStart raw byte offset of the first byte of this line (relative to the read start)
         * @param lineEnd raw byte offset just past this line (relative to the read start)
         */
        protected abstract void onLine(long index, byte[] in, int sz, long lineStart, long lineEnd) throws IOException;
    }

    /** Captures the forward window {@code [skip, skip + limit)} and stops as soon as one more line confirms there's more to come. */
    private static final class ForwardLineCollector extends PlainTextLineCollector {
        private final long skip;
        private final int limit;
        private final List<String> lines = new ArrayList<>();
        private boolean hasMoreContent;
        private boolean terminated;
        private long captureEndOffset = -1;

        ForwardLineCollector(Charset charset, long skip, int limit) {
            super(charset);
            this.skip = skip;
            this.limit = limit;
        }

        @Override
        protected void onLine(long index, byte[] in, int sz, long lineStart, long lineEnd) throws IOException {
            // close() can drive a final flush after we've already bailed out; ignore it.
            if (terminated) {
                return;
            }
            if (index < skip) {
                return;
            }
            if (lines.size() < limit) {
                lines.add(decode(in, sz));
                captureEndOffset = lineEnd;
                return;
            }
            hasMoreContent = true;
            terminated = true;
            throw StopReading.INSTANCE;
        }
    }

    /** Counts all lines and keeps the last {@code capacity} of them (raw) in a ring buffer. */
    private static final class TailLineCollector extends PlainTextLineCollector {
        private final SlidingWindow<TailLine> window;
        private long totalLines;

        TailLineCollector(Charset charset, int capacity) {
            super(charset);
            this.window = new SlidingWindow<>(Math.max(1, capacity));
        }

        @Override
        protected void onLine(long index, byte[] in, int sz, long lineStart, long lineEnd) {
            window.add(new TailLine(index, Arrays.copyOf(in, sz), lineEnd));
            totalLines = index + 1;
        }
    }

    private record TailLine(long index, byte[] raw, long endOffset) {}

    public record BuildLogResponse(
            List<String> lines,
            boolean hasMoreContent,
            long startLine,
            long endLine,
            long totalLines,
            String nextCursor) {}

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
