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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.ExtensionList;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.Map;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * The system-log tools are hidden from {@code tools/list} and refused on {@code tools/call} for users
 * without Overall/SystemRead, but available to permitted users and when security is off. Covers SSE and
 * streamable ({@link McpClientTest}), stateless, and a direct in-process call.
 */
class SystemLogExtensionTest {

    @McpClientTest
    void systemReadUserSeesAndCanCallLogTools(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        secure(jenkins);
        try (McpSyncClient client = clientAs(jenkins, builder, "admin")) {
            assertThat(TestUtils.findToolByName(client.listTools(), "getSystemLog"))
                    .isNotNull();
            assertThat(TestUtils.findToolByName(client.listTools(), "getLogRecorders"))
                    .isNotNull();

            var log = client.callTool(new McpSchema.CallToolRequest("getSystemLog", Map.of("limit", 5)));
            assertThat(log.isError()).isFalse();

            var recorders = client.callTool(new McpSchema.CallToolRequest("getLogRecorders", Map.of()));
            assertThat(recorders.isError()).isFalse();
        }
    }

    @McpClientTest
    void userWithoutSystemReadCannotSeeOrCallLogTools(JenkinsRule jenkins, JenkinsMcpClientBuilder builder)
            throws Exception {
        secure(jenkins);
        try (McpSyncClient client = clientAs(jenkins, builder, "reader")) {
            // the permission-gated tools are hidden from the list...
            assertThat(TestUtils.findToolByName(client.listTools(), "getSystemLog"))
                    .isNull();
            assertThat(TestUtils.findToolByName(client.listTools(), "getLogRecorders"))
                    .isNull();
            // ...while a tool with no permission requirement stays visible
            assertThat(TestUtils.findToolByName(client.listTools(), "whoAmI")).isNotNull();
            // and calling the hidden tool anyway is denied
            var log = client.callTool(new McpSchema.CallToolRequest("getSystemLog", Map.of()));
            assertThat(log.isError()).isTrue();
        }
    }

    @McpClientTest
    void unsecuredJenkinsExposesLogTools(JenkinsRule jenkins, JenkinsMcpClientBuilder builder) throws Exception {
        // no security configured: everything is visible and callable
        try (McpSyncClient client = builder.jenkins(jenkins).build()) {
            assertThat(TestUtils.findToolByName(client.listTools(), "getSystemLog"))
                    .isNotNull();
            var log = client.callTool(new McpSchema.CallToolRequest("getSystemLog", Map.of()));
            assertThat(log.isError()).isFalse();
        }
    }

    @Test
    @WithJenkins
    void statelessTransportRespectsPermissions(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        try (StatelessMcpTestClient admin = new StatelessMcpTestClient(jenkins, basicAuth("admin"))) {
            assertThat(TestUtils.findToolByName(admin.listTools(), "getSystemLog"))
                    .isNotNull();
            assertThat(admin.callTool("getSystemLog", Map.of()).isError()).isFalse();
        }
        try (StatelessMcpTestClient reader = new StatelessMcpTestClient(jenkins, basicAuth("reader"))) {
            assertThat(TestUtils.findToolByName(reader.listTools(), "getSystemLog"))
                    .isNull();
            assertThat(reader.callTool("getSystemLog", Map.of()).isError()).isTrue();
        }
    }

    @Test
    @WithJenkins
    void callingTheToolDirectlyWithoutSystemReadIsDenied(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        SystemLogExtension extension = ExtensionList.lookupSingleton(SystemLogExtension.class);

        // A user without SystemRead is refused even when the tool method is invoked directly,
        // bypassing the MCP tools/call gate entirely.
        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThatThrownBy(() -> extension.getSystemLog(10, null, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> extension.getLogRecorders()).isInstanceOf(AccessDeniedException.class);
        }

        // ADMINISTER implies SystemRead, so a direct call succeeds.
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertThat(extension.getSystemLog(10, null, null)).isNotNull();
            assertThat(extension.getLogRecorders()).isNotNull();
        }
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
    }

    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader"));
        jenkins.jenkins.save();
    }

    private static McpSyncClient clientAs(JenkinsRule jenkins, JenkinsMcpClientBuilder builder, String user) {
        return builder.jenkins(jenkins)
                .requestCustomizer((httpRequestBuilder, method, endpoint, body, context) ->
                        httpRequestBuilder.setHeader("Authorization", basicAuth(user)))
                .build();
    }
}
