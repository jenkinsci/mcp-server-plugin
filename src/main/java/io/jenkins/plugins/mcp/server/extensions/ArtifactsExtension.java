package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.Functions;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.util.VirtualFile;

/**
 * Lists and reads the archived artifacts of a build. Content is returned in byte-range chunks
 * (text as UTF-8, anything else as base64) because MCP has no streaming or ranged transfer of its
 * own: every tool result and {@code resources/read} reply is a single JSON message.
 */
@Extension
public class ArtifactsExtension implements McpServerExtension {

    static final int MAX_LISTED_ARTIFACTS = 1000;
    static final int DEFAULT_CHUNK_BYTES = 64 * 1024;
    static final int MAX_CHUNK_BYTES = 1024 * 1024;
    /** How much of the start of a file is inspected to decide between text and binary. */
    private static final int SNIFF_BYTES = 8 * 1024;

    @Tool(
            description = "Lists the archived artifacts of a specific build or the last build of a Jenkins job,"
                    + " with each artifact's relative path, size in bytes and download URL."
                    + " Use getBuildArtifact with the 'relativePath' to read one."
                    + " At most " + MAX_LISTED_ARTIFACTS
                    + " artifacts are listed; 'truncated' is true if there are more.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public ArtifactListResponse listBuildArtifacts(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "The build number (optional, if not provided, uses the last build)",
                            required = false)
                    Integer buildNumber) {
        var run = getRunWithArtifactAccess(jobFullName, buildNumber);
        var artifacts = run.getArtifactsUpTo(MAX_LISTED_ARTIFACTS + 1);
        boolean truncated = artifacts.size() > MAX_LISTED_ARTIFACTS;
        String baseUrl = artifactBaseUrl(run);
        var infos = artifacts.stream()
                .limit(MAX_LISTED_ARTIFACTS)
                .map(a -> new ArtifactInfo(a.relativePath, a.getFileName(), a.getFileSize(), baseUrl + a.getHref()))
                .toList();
        return new ArtifactListResponse(run.getNumber(), infos, truncated);
    }

    @Tool(
            description = "Reads the content of one archived artifact of a specific build or the last build of a"
                    + " Jenkins job, in byte-range chunks. Text files are returned as UTF-8 ('encoding': 'text'),"
                    + " anything else as base64 ('encoding': 'base64'). If 'hasMore' is true, call again with"
                    + " 'offset' set to the returned 'nextOffset' to read the next chunk."
                    + " 'totalSize' is the full size of the artifact in bytes.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public ArtifactContentResponse getBuildArtifact(
            @ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
            @Nullable
                    @ToolParam(
                            description = "The build number (optional, if not provided, uses the last build)",
                            required = false)
                    Integer buildNumber,
            @ToolParam(
                            description =
                                    "Path of the artifact relative to the build's artifact root, as returned by listBuildArtifacts (e.g., 'target/report.txt')")
                    String relativePath,
            @Nullable
                    @ToolParam(
                            description = "Byte offset to start reading from (optional, default: 0)",
                            required = false)
                    Long offset,
            @Nullable
                    @ToolParam(
                            description = "Maximum number of bytes to return (optional, default: " + DEFAULT_CHUNK_BYTES
                                    + ", max: " + MAX_CHUNK_BYTES + ")",
                            required = false)
                    Integer limit)
            throws IOException {
        validateRelativePath(relativePath);
        long start = offset == null ? 0 : offset;
        if (start < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + start);
        }
        int max = limit == null || limit <= 0 ? DEFAULT_CHUNK_BYTES : Math.min(limit, MAX_CHUNK_BYTES);

        var run = getRunWithArtifactAccess(jobFullName, buildNumber);
        VirtualFile file = resolveWithoutSymlinks(run.getArtifactManager().root(), relativePath);
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("Artifact not found in build #" + run.getNumber() + ": " + relativePath);
        }
        long totalSize = file.length();
        if (start > totalSize) {
            throw new IllegalArgumentException(
                    "offset " + start + " is beyond the end of the artifact (" + totalSize + " bytes)");
        }

        boolean text = looksLikeText(file);
        byte[] chunk;
        try (InputStream in = file.open()) {
            in.skipNBytes(start);
            chunk = in.readNBytes(max);
        }

        String content;
        int bytesRead;
        if (text) {
            boolean atEnd = start + chunk.length >= totalSize;
            var decoded = decodeUtf8(chunk, atEnd);
            content = decoded.text();
            bytesRead = decoded.bytesConsumed();
        } else {
            content = Base64.getEncoder().encodeToString(chunk);
            bytesRead = chunk.length;
        }
        long next = start + bytesRead;
        boolean hasMore = next < totalSize;
        return new ArtifactContentResponse(
                relativePath,
                text ? "text" : "base64",
                content,
                mimeType(file.getName(), text),
                start,
                bytesRead,
                totalSize,
                hasMore,
                hasMore ? next : null);
    }

    private static Run<?, ?> getRunWithArtifactAccess(String jobFullName, @Nullable Integer buildNumber) {
        // getItemByFullName returns null when the caller lacks Item.READ, so an unreadable job looks missing.
        var job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException("Job not found: " + jobFullName);
        }
        Run<?, ?> run =
                buildNumber == null || buildNumber <= 0 ? job.getLastBuild() : job.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new IllegalArgumentException(
                    buildNumber == null
                            ? "No builds found for job: " + jobFullName
                            : "Build not found: #" + buildNumber);
        }
        // Same rule as Run#doArtifact: Run.ARTIFACTS only applies when it has been switched on.
        if (Functions.isArtifactsPermissionEnabled()) {
            run.checkPermission(Run.ARTIFACTS);
        }
        return run;
    }

    /** Rejects absolute paths and any '.' or '..' segment so the path stays inside the artifact root. */
    static void validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be empty");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("relativePath must be relative: " + relativePath);
        }
        for (String segment : relativePath.split("[/\\\\]")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("relativePath must not contain '.' or '..': " + relativePath);
            }
        }
    }

    /**
     * Resolves {@code relativePath} under {@code root} one segment at a time, returning {@code null} if
     * any segment is a symlink, so an archived link can't be used to read files outside the artifact root.
     */
    @Nullable
    private static VirtualFile resolveWithoutSymlinks(VirtualFile root, String relativePath) throws IOException {
        VirtualFile current = root;
        for (String segment : relativePath.split("[/\\\\]")) {
            if (segment.isEmpty()) {
                continue;
            }
            current = current.child(segment);
            if (current.readLink() != null) {
                return null;
            }
        }
        return current;
    }

    /**
     * Decides text vs binary from the start of the file, so every chunk of one artifact uses the same
     * encoding: text means no NUL bytes and valid UTF-8 (a multi-byte sequence cut off by the sniff
     * window is fine).
     */
    private static boolean looksLikeText(VirtualFile file) throws IOException {
        byte[] head;
        try (InputStream in = file.open()) {
            head = in.readNBytes(SNIFF_BYTES);
        }
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        boolean wholeFile = head.length < SNIFF_BYTES;
        return !decoder.decode(ByteBuffer.wrap(head), CharBuffer.allocate(head.length), wholeFile)
                .isError();
    }

    /**
     * Decodes a chunk as UTF-8. Unless the chunk ends the file, a trailing incomplete multi-byte
     * sequence is left out (and not counted as consumed) so the next chunk starts on a character
     * boundary. Malformed bytes inside the chunk are replaced rather than failing the read.
     */
    private static DecodedChunk decodeUtf8(byte[] chunk, boolean atEnd) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer in = ByteBuffer.wrap(chunk);
        CharBuffer out = CharBuffer.allocate(chunk.length);
        decoder.decode(in, out, atEnd);
        if (atEnd) {
            decoder.flush(out);
        }
        out.flip();
        int consumed = in.position();
        if (consumed == 0 && chunk.length > 0) {
            // limit is smaller than a single character; return it whole rather than making no progress
            return new DecodedChunk(new String(chunk, StandardCharsets.UTF_8), chunk.length);
        }
        return new DecodedChunk(out.toString(), consumed);
    }

    private static String mimeType(String fileName, boolean text) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (guessed != null) {
            return guessed;
        }
        return text ? "text/plain" : "application/octet-stream";
    }

    private static String artifactBaseUrl(Run<?, ?> run) {
        String rootUrl = Jenkins.get().getRootUrl();
        return (rootUrl != null ? rootUrl : "") + run.getUrl() + "artifact/";
    }

    private record DecodedChunk(String text, int bytesConsumed) {}

    public record ArtifactInfo(String relativePath, String fileName, long size, String url) {}

    public record ArtifactListResponse(int buildNumber, List<ArtifactInfo> artifacts, boolean truncated) {}

    public record ArtifactContentResponse(
            String relativePath,
            String encoding,
            String content,
            String mimeType,
            long offset,
            int bytesRead,
            long totalSize,
            boolean hasMore,
            @Nullable Long nextOffset) {}
}
