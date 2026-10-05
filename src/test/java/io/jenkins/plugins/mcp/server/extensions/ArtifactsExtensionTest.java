package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import hudson.ExtensionList;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.tasks.ArtifactArchiver;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

@WithJenkins
class ArtifactsExtensionTest {

    private static final byte[] BINARY = {0x00, 0x01, 0x02, (byte) 0xFF, 0x7F, 0x00};

    /** Build a job that archives a text file, a nested text file and a binary file. */
    private static FreeStyleBuild buildWithArtifacts(JenkinsRule j, String name) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject(name);
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws java.io.IOException, InterruptedException {
                var ws = build.getWorkspace();
                ws.child("report.txt").write("hello artifact\nline two\n", "UTF-8");
                ws.child("nested/dir/data.json").write("{\"k\":1}", "UTF-8");
                try (var out = ws.child("blob.bin").write()) {
                    out.write(BINARY);
                }
                return true;
            }
        });
        project.getPublishersList().add(new ArtifactArchiver("**"));
        return j.buildAndAssertSuccess(project);
    }

    private static Map<String, Object> resultOf(McpSchema.CallToolResult response) {
        assertThat(response.isError()).as(() -> response.content().toString()).isFalse();
        var text = ((McpSchema.TextContent) response.content().get(0)).text();
        return JsonPath.using(Configuration.defaultConfiguration()).parse(text).read("$.result", Map.class);
    }

    @McpClientTest
    void listBuildArtifacts_returnsAllArchivedFiles(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        var build = buildWithArtifacts(j, "list-job");

        try (var client = builder.jenkins(j).build()) {
            var result = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "listBuildArtifacts", Map.of("jobFullName", "list-job", "buildNumber", build.getNumber()))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
            assertThat(artifacts)
                    .extracting(a -> a.get("relativePath"))
                    .containsExactlyInAnyOrder("report.txt", "nested/dir/data.json", "blob.bin");
            assertThat(artifacts)
                    .filteredOn(a -> "blob.bin".equals(a.get("relativePath")))
                    .singleElement()
                    .satisfies(a -> {
                        assertThat(a).containsEntry("fileName", "blob.bin");
                        assertThat(((Number) a.get("size")).longValue()).isEqualTo(BINARY.length);
                        assertThat((String) a.get("url")).endsWith("artifact/blob.bin");
                    });
            assertThat(result).containsEntry("truncated", false);
        }
    }

    @McpClientTest
    void listBuildArtifacts_defaultsToLastBuild_andEmptyWhenNone(JenkinsRule j, JenkinsMcpClientBuilder builder)
            throws Exception {
        j.buildAndAssertSuccess(j.createFreeStyleProject("no-artifacts"));

        try (var client = builder.jenkins(j).build()) {
            var result = resultOf(client.callTool(
                    new McpSchema.CallToolRequest("listBuildArtifacts", Map.of("jobFullName", "no-artifacts"))));
            assertThat((List<?>) result.get("artifacts")).isEmpty();
        }
    }

    @McpClientTest
    void getBuildArtifact_readsTextContent(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        buildWithArtifacts(j, "text-job");

        try (var client = builder.jenkins(j).build()) {
            var result = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact", Map.of("jobFullName", "text-job", "relativePath", "report.txt"))));

            assertThat(result)
                    .containsEntry("encoding", "text")
                    .containsEntry("content", "hello artifact\nline two\n")
                    .containsEntry("hasMore", false);
            assertThat(((Number) result.get("offset")).longValue()).isZero();
            assertThat(((Number) result.get("totalSize")).longValue()).isEqualTo(24);
        }
    }

    @McpClientTest
    void getBuildArtifact_readsBinaryAsBase64(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        buildWithArtifacts(j, "binary-job");

        try (var client = builder.jenkins(j).build()) {
            var result = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact", Map.of("jobFullName", "binary-job", "relativePath", "blob.bin"))));

            assertThat(result).containsEntry("encoding", "base64");
            assertThat(Base64.getDecoder().decode((String) result.get("content")))
                    .isEqualTo(BINARY);
        }
    }

    @McpClientTest
    void getBuildArtifact_pagesWithOffsetAndLimit(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        buildWithArtifacts(j, "paged-job");

        try (var client = builder.jenkins(j).build()) {
            var first = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact",
                    Map.of("jobFullName", "paged-job", "relativePath", "report.txt", "limit", 10))));
            assertThat(first).containsEntry("content", "hello arti").containsEntry("hasMore", true);
            assertThat(((Number) first.get("nextOffset")).longValue()).isEqualTo(10);

            var second = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact",
                    Map.of("jobFullName", "paged-job", "relativePath", "report.txt", "offset", 10, "limit", 100))));
            assertThat(second).containsEntry("content", "fact\nline two\n").containsEntry("hasMore", false);
        }
    }

    @McpClientTest
    void getBuildArtifact_nestedPath(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        buildWithArtifacts(j, "nested-job");

        try (var client = builder.jenkins(j).build()) {
            var result = resultOf(client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact", Map.of("jobFullName", "nested-job", "relativePath", "nested/dir/data.json"))));
            assertThat(result).containsEntry("content", "{\"k\":1}");
        }
    }

    @McpClientTest
    void getBuildArtifact_rejectsTraversalAndMissing(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        buildWithArtifacts(j, "bad-path-job");

        try (var client = builder.jenkins(j).build()) {
            for (var path : List.of("../build.xml", "/etc/passwd", "nested/../../log", "does-not-exist.txt")) {
                var response = client.callTool(new McpSchema.CallToolRequest(
                        "getBuildArtifact", Map.of("jobFullName", "bad-path-job", "relativePath", path)));
                assertThat(response.isError()).as(path).isTrue();
            }
        }
    }

    @McpClientTest
    void getBuildArtifact_refusesArchivedSymlink(JenkinsRule j, JenkinsMcpClientBuilder builder) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("symlink-job");
        var outside = j.jenkins.getRootPath().child("outside-secret.txt");
        outside.write("top secret", "UTF-8");
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws java.io.IOException, InterruptedException {
                build.getWorkspace().child("link.txt").symlinkTo(outside.getRemote(), listener);
                return true;
            }
        });
        project.getPublishersList().add(new ArtifactArchiver("**"));
        var build = j.buildAndAssertSuccess(project);
        assertThat(java.nio.file.Files.isSymbolicLink(
                        build.getArtifactsDir().toPath().resolve("link.txt")))
                .as("archiver should keep the symlink, otherwise this test proves nothing")
                .isTrue();

        try (var client = builder.jenkins(j).build()) {
            var response = client.callTool(new McpSchema.CallToolRequest(
                    "getBuildArtifact", Map.of("jobFullName", "symlink-job", "relativePath", "link.txt")));
            assertThat(response.isError()).isTrue();
            assertThat(response.content().toString()).doesNotContain("top secret");
        }
    }

    private static void secure(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER, Run.ARTIFACTS) // Run.ARTIFACTS is not implied by ADMINISTER
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to("reader") // can see jobs; artifacts only while Run.ARTIFACTS is disabled
                .grant(Jenkins.READ)
                .everywhere()
                .to("outsider")); // cannot see any job
    }

    @Test
    void artifactsHiddenFromUserWithoutItemRead(JenkinsRule j) throws Exception {
        secure(j);
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            buildWithArtifacts(j, "hidden-job");
        }
        var extension = ExtensionList.lookupSingleton(ArtifactsExtension.class);

        try (ACLContext ignored = ACL.as2(User.getById("outsider", true).impersonate2())) {
            assertThrows(IllegalArgumentException.class, () -> extension.listBuildArtifacts("hidden-job", null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> extension.getBuildArtifact("hidden-job", null, "report.txt", null, null));
        }
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            // Run.ARTIFACTS is disabled by default, so Item.READ is enough (same as the web UI)
            assertThat(extension
                            .getBuildArtifact("hidden-job", null, "report.txt", null, null)
                            .content())
                    .startsWith("hello");
        }
    }

    @Test
    void artifactsRequireArtifactsPermissionWhenEnabled(JenkinsRule j) throws Exception {
        secure(j);
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            buildWithArtifacts(j, "secret-artifacts");
        }
        var extension = ExtensionList.lookupSingleton(ArtifactsExtension.class);

        System.setProperty("hudson.security.ArtifactsPermission", "true");
        Run.ARTIFACTS.setEnabled(true);
        try {
            try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
                assertThrows(AccessDeniedException.class, () -> extension.listBuildArtifacts("secret-artifacts", null));
                assertThrows(
                        AccessDeniedException.class,
                        () -> extension.getBuildArtifact("secret-artifacts", null, "report.txt", null, null));
            }
            try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
                assertThat(extension
                                .getBuildArtifact("secret-artifacts", null, "report.txt", null, null)
                                .content())
                        .startsWith("hello");
            }
        } finally {
            Run.ARTIFACTS.setEnabled(false);
            System.clearProperty("hudson.security.ArtifactsPermission");
        }
    }
}
