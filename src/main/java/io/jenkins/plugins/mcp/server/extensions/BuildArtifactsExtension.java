/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Derek Taubert.
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
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jenkins.util.VirtualFile;
import lombok.extern.slf4j.Slf4j;

@Extension
@Slf4j
public class BuildArtifactsExtension implements McpServerExtension {

    @Tool(description = "Get the artifacts for a specific build or the last build of a Jenkins job")
    public List<Run.Artifact> getBuildArtifacts(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber) {
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(Run::getArtifacts)
                .orElse(List.of());
    }

    @Tool(
            description =
                    "Get the content of a specific build artifact with pagination support. Returns the artifact content as text with information about whether there is more content to retrieve.")
    public BuildArtifactResponse getBuildArtifact(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "Build number (optional, if not provided, returns the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(description = "Relative path of the artifact from the artifacts root") String artifactPath,
            @Nullable
                    @ToolParam(
                            description = "The byte offset to start reading from (optional, if not provided, starts from the beginning)",
                            required = false)
                    Long offset,
            @Nullable
                    @ToolParam(
                            description = "The maximum number of bytes to read (optional, if not provided, reads up to 64KB)",
                            required = false)
                    Integer limit) {
        
        if (offset == null || offset < 0) {
            offset = 0L;
        }
        if (limit == null || limit <= 0) {
            limit = 65536; // 64KB default
        }
        
        // Cap the limit to prevent excessive memory usage
        final int maxLimit = 1048576; // 1MB max
        if (limit > maxLimit) {
            log.warn("Limit {} is too large, using the default max limit {}", limit, maxLimit);
            limit = maxLimit;
        }

        final long offsetF = offset;
        final int limitF = limit;
        
        return getBuildByNumberOrLast(jobFullName, buildNumber)
                .map(build -> {
                    try {
                        return getArtifactContent(build, artifactPath, offsetF, limitF);
                    } catch (Exception e) {
                        log.error("Error reading artifact {} for job {} build {}", artifactPath, jobFullName, buildNumber, e);
                        return new BuildArtifactResponse(false, 0L, "Error reading artifact: " + e.getMessage());
                    }
                })
                .orElse(new BuildArtifactResponse(false, 0L, "Build not found"));
    }

    private BuildArtifactResponse getArtifactContent(Run<?, ?> run, String artifactPath, long offset, int limit) throws IOException {
        log.trace(
                "getArtifactContent for run {}/{} called with artifact {}, offset {}, limit {}",
                run.getParent().getName(),
                run.getDisplayName(),
                artifactPath,
                offset,
                limit);

        // Find the artifact
        Run.Artifact artifact = run.getArtifacts().stream()
                .filter(a -> a.relativePath.equals(artifactPath))
                .findFirst()
                .orElse(null);
        
        if (artifact == null) {
            return new BuildArtifactResponse(false, 0L, "Artifact not found: " + artifactPath);
        }

        // Get the artifact file through the artifact manager
        VirtualFile artifactFile = run.getArtifactManager().root().child(artifactPath);
        if (!artifactFile.exists()) {
            return new BuildArtifactResponse(false, 0L, "Artifact file does not exist: " + artifactPath);
        }

        long fileSize = artifactFile.length();
        if (offset >= fileSize) {
            return new BuildArtifactResponse(false, fileSize, "");
        }

        // Read the content with offset and limit
        try (InputStream is = artifactFile.open()) {
            // Skip to the offset
            long skipped = is.skip(offset);
            if (skipped != offset) {
                log.warn("Could not skip to offset {}, only skipped {}", offset, skipped);
            }

            // Read up to limit bytes
            byte[] buffer = new byte[limit];
            int bytesRead = is.read(buffer);
            
            if (bytesRead <= 0) {
                return new BuildArtifactResponse(false, fileSize, "");
            }

            // Convert to string (assuming text content)
            String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            
            // Check if there's more content
            boolean hasMoreContent = (offset + bytesRead) < fileSize;
            
            return new BuildArtifactResponse(hasMoreContent, fileSize, content);
        }
    }

    public record BuildArtifactResponse(boolean hasMoreContent, long totalSize, String content) {}
}
