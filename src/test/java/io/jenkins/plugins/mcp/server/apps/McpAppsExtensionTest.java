/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Omar Mahamid
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

package io.jenkins.plugins.mcp.server.apps;

import static io.jenkins.plugins.mcp.server.junit.TestUtils.findToolByName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class McpAppsExtensionTest {

    private static final McpSchema.ReadResourceRequest READ_BUILD_LOG_APP =
            McpSchema.ReadResourceRequest.builder(JenkinsApps.BUILD_LOG).build();

    @TestExtension("testAppCanOnlyCallReadOnlyTools")
    public static final McpApp PIPELINE_GRAPH_APP = new McpApp(
            "ui://test/pipeline-graph",
            "Pipeline graph",
            "The stages of a pipeline run",
            List.of("getBuild", "triggerBuild", "replayBuild"),
            JenkinsApps.PAGE);

    @McpClientTest
    void testReadOnlyToolsAdvertiseTheirPage(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var tools = client.listTools();

            Map.ofEntries(
                            Map.entry("getBuildLog", JenkinsApps.BUILD_LOG),
                            Map.entry("searchBuildLog", JenkinsApps.BUILD_LOG),
                            Map.entry("getBuild", JenkinsApps.BUILD),
                            Map.entry("getBuildChangeSets", JenkinsApps.BUILD),
                            Map.entry("getBuildScm", JenkinsApps.BUILD),
                            Map.entry("getReplayScripts", JenkinsApps.BUILD),
                            Map.entry("getJob", JenkinsApps.JOB),
                            Map.entry("getJobScm", JenkinsApps.JOB),
                            Map.entry("getJobs", JenkinsApps.JOBS),
                            Map.entry("findJobsWithScmUrl", JenkinsApps.JOBS),
                            Map.entry("getTestResults", JenkinsApps.TEST_RESULTS),
                            Map.entry("getFlakyFailures", JenkinsApps.TEST_RESULTS),
                            Map.entry("getQueueItem", JenkinsApps.QUEUE_ITEM),
                            Map.entry("getSystemLog", JenkinsApps.SYSTEM_LOG),
                            Map.entry("getLogRecorders", JenkinsApps.SYSTEM_LOG),
                            Map.entry("getStatus", JenkinsApps.STATUS),
                            Map.entry("whoAmI", JenkinsApps.STATUS))
                    .forEach((name, uri) -> {
                        var meta = findToolByName(tools, name).meta();
                        assertThat(meta).as(name).containsEntry("ui", Map.of("resourceUri", uri));
                        assertThat(meta).as(name).containsEntry("ui/resourceUri", uri);
                    });
        }
    }

    @McpClientTest
    void testToolsThatChangeJenkinsAreVisibleToTheModelOnly(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var tools = client.listTools();

            List.of("triggerBuild", "updateBuild", "rebuildBuild", "replayBuild")
                    .forEach(name -> assertThat(findToolByName(tools, name).meta())
                            .containsEntry("ui", Map.of("visibility", List.of("model"))));
        }
    }

    @McpClientTest
    void testPagesAreListedAsResources(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var pages = client.listResources().resources().stream()
                    .filter(resource -> resource.uri().startsWith("ui://"))
                    .toList();

            assertThat(pages)
                    .extracting(McpSchema.Resource::uri)
                    .containsExactlyInAnyOrder(
                            JenkinsApps.BUILD_LOG,
                            JenkinsApps.BUILD,
                            JenkinsApps.JOB,
                            JenkinsApps.JOBS,
                            JenkinsApps.TEST_RESULTS,
                            JenkinsApps.QUEUE_ITEM,
                            JenkinsApps.SYSTEM_LOG,
                            JenkinsApps.STATUS);
            assertThat(pages).allSatisfy(page -> assertThat(page.mimeType()).isEqualTo("text/html;profile=mcp-app"));
        }
    }

    @McpClientTest
    void testReadApp(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var page = (McpSchema.TextResourceContents)
                    client.readResource(READ_BUILD_LOG_APP).contents().get(0);

            assertThat(page.mimeType()).isEqualTo("text/html;profile=mcp-app");
            assertThat(page.meta()).isEqualTo(McpAppsExtension.RESOURCE_META);
            assertThat(page.text())
                    .contains("<meta name=\"jenkins:view\" content=\"build-log\">")
                    .contains("<meta name=\"jenkins:callable-tools\" content=\""
                            + String.join(" ", JenkinsApps.READ_ONLY_TOOLS) + "\">")
                    .contains("<meta name=\"jenkins:version\" content=\"" + Jenkins.VERSION + "\">")
                    .contains("<style id=\"jenkins-styles\">")
                    .contains(".jenkins-app-bar")
                    .contains("<template id=\"jenkins-logo\"><img src=\"data:image/svg+xml;base64,")
                    .contains("<template id=\"jenkins-symbol-status-red\">")
                    .doesNotContain("url(../images/")
                    .doesNotContain(McpAppPage.HEAD_SLOT);
        }
    }

    @McpClientTest
    void testAppCanOnlyCallReadOnlyTools(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var page = (McpSchema.TextResourceContents)
                    client.readResource(McpSchema.ReadResourceRequest.builder(PIPELINE_GRAPH_APP.uri())
                                    .build())
                            .contents()
                            .get(0);

            assertThat(page.text()).contains("<meta name=\"jenkins:callable-tools\" content=\"getBuild\">");
        }
    }

    @McpClientTest
    void testReadAppRequiresOverallRead(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        secure(jenkins);
        try (var stranger = clientAs(jenkins, jenkinsMcpClientBuilder, "stranger")) {
            assertThatThrownBy(() -> stranger.readResource(READ_BUILD_LOG_APP))
                    .isInstanceOf(McpError.class)
                    .hasMessageContaining("Overall/Read");
        }
        try (var reader = clientAs(jenkins, jenkinsMcpClientBuilder, "reader")) {
            assertThat(reader.readResource(READ_BUILD_LOG_APP).contents()).hasSize(1);
        }
    }

    @Test
    void testStatelessReadAppRequiresOverallRead(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        try (var stranger = new StatelessMcpTestClient(jenkins, basicAuth("stranger"))) {
            assertThat(stranger.readResource(READ_BUILD_LOG_APP).error().message())
                    .contains("Overall/Read");
        }
        try (var reader = new StatelessMcpTestClient(jenkins, basicAuth("reader"))) {
            assertThat(reader.readResource(READ_BUILD_LOG_APP).error()).isNull();
        }
    }

    private static void secure(JenkinsRule jenkins) {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));
    }

    private static McpSyncClient clientAs(JenkinsRule jenkins, JenkinsMcpClientBuilder builder, String user) {
        return builder.jenkins(jenkins)
                .requestCustomizer((request, method, endpoint, body, context) ->
                        request.setHeader("Authorization", basicAuth(user)))
                .build();
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }
}
