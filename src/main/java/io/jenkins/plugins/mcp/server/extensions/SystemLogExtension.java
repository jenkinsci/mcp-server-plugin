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

package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.Functions;
import hudson.logging.LogRecorder;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import jenkins.model.Jenkins;

/**
 * Tools to read the Jenkins system log, both gated by Overall/SystemRead - the same permission Jenkins
 * uses for its "Manage Jenkins &gt; System Log" screen.
 */
@Extension
public class SystemLogExtension implements McpServerExtension {

    private static final int DEFAULT_LIMIT = 50;

    @Tool(
            description =
                    "Read recent Jenkins log entries, newest first. Reads the global system log, or a named Log Recorder when 'recorder' is set. Requires the Overall/SystemRead permission.",
            permissions = {"hudson.model.Hudson.SystemRead"},
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public SystemLogResponse getSystemLog(
            @ToolParam(
                            description = "Maximum number of entries to return, newest first. Defaults to 50.",
                            required = false)
                    Integer limit,
            @ToolParam(
                            description =
                                    "Minimum log level to include, e.g. SEVERE, WARNING, INFO. Defaults to all levels.",
                            required = false)
                    String level,
            @ToolParam(
                            description =
                                    "Name of a configured Log Recorder to read instead of the global system log. Use getLogRecorders to list them.",
                            required = false)
                    String recorder) {
        // The @Tool(permissions) gate already blocks this, but never read the log without SystemRead -
        // it's the same check core does in Jenkins.getLog().
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);

        List<LogRecord> source = new ArrayList<>(
                recorder == null || recorder.isBlank()
                        ? Jenkins.logRecords
                        : findRecorder(recorder).getLogRecords());

        Level threshold = (level == null || level.isBlank()) ? null : Level.parse(level.trim());
        int max = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;

        SimpleFormatter formatter = new SimpleFormatter();
        List<LogEntry> entries = source.stream()
                .filter(logRecord -> threshold == null || logRecord.getLevel().intValue() >= threshold.intValue())
                .sorted(Comparator.comparingLong(LogRecord::getMillis).reversed())
                .limit(max)
                .map(logRecord -> toEntry(logRecord, formatter))
                .toList();

        return new SystemLogResponse(entries);
    }

    @Tool(
            description =
                    "List the names of configured Jenkins Log Recorders, for use with getSystemLog's 'recorder' parameter. Requires the Overall/SystemRead permission.",
            permissions = {"hudson.model.Hudson.SystemRead"},
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true))
    public List<String> getLogRecorders() {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        return Jenkins.get().getLog().getRecorders().stream()
                .map(LogRecorder::getName)
                .toList();
    }

    private static LogRecorder findRecorder(String name) {
        return Jenkins.get().getLog().getRecorders().stream()
                .filter(logRecorder -> name.equals(logRecorder.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such Log Recorder: " + name));
    }

    private static LogEntry toEntry(LogRecord logRecord, SimpleFormatter formatter) {
        String exception = logRecord.getThrown() != null ? Functions.printThrowable(logRecord.getThrown()) : null;
        return new LogEntry(
                DateTimeFormatter.ISO_INSTANT.format(logRecord.getInstant()),
                logRecord.getLevel().getName(),
                logRecord.getLoggerName(),
                formatter.formatMessage(logRecord),
                exception);
    }

    public record SystemLogResponse(List<LogEntry> entries) {}

    public record LogEntry(String timestamp, String level, String logger, String message, String exception) {}
}
