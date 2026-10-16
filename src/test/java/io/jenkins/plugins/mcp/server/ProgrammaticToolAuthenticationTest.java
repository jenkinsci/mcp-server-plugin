/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, the Jenkins MCP Server plugin contributors.
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

package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Programmatic {@link McpServerExtension#getSyncTools()} handlers must run as the caller captured on
 * the request and require {@link Jenkins#READ}. SSE message handling is offloaded to another thread,
 * so reading the thread-local security context from the handler is not enough.
 */
@WithJenkins
class ProgrammaticToolAuthenticationTest {

    static final String TOOL_NAME = "programmaticWhoAmI";
    static final String USERNAME = "alice";
    static final AtomicInteger invocations = new AtomicInteger();
    static final AtomicReference<String> observedUser = new AtomicReference<>();

    @BeforeEach
    void resetProbe() {
        invocations.set(0);
        observedUser.set(null);
    }

    @McpClientTest
    void programmaticToolRunsAsCapturedUser(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        secure(jenkins);
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) ->
                        builder.setHeader("Authorization", basicAuth(USERNAME)))
                .build()) {
            assertRanAs(client.callTool(new McpSchema.CallToolRequest(TOOL_NAME, Map.of())), USERNAME);
        }
    }

    @McpClientTest
    void anonymousProgrammaticToolIsDenied(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        secure(jenkins);
        // JenkinsRule's TestCrumbIssuer would reject this crumb-free anonymous POST before the tool
        // runs. This test is about Overall/Read, so drop the issuer and let the handler gate decide.
        jenkins.jenkins.setCrumbIssuer(null);
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolResult response = client.callTool(new McpSchema.CallToolRequest(TOOL_NAME, Map.of()));
            assertThat(response.isError()).isTrue();
            assertThat(invocations).hasValue(0);
        }
    }

    @Test
    void statelessProgrammaticToolRunsAsCapturedUser(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        try (var client = new StatelessMcpTestClient(jenkins, basicAuth(USERNAME))) {
            assertRanAs(client.callTool(TOOL_NAME, Map.of()), USERNAME);
        }
    }

    @Test
    void statelessAnonymousProgrammaticToolIsDenied(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        jenkins.jenkins.setCrumbIssuer(null);
        try (var client = new StatelessMcpTestClient(jenkins)) {
            McpSchema.CallToolResult response = client.callTool(TOOL_NAME, Map.of());
            assertThat(response.isError()).isTrue();
            assertThat(invocations).hasValue(0);
        }
    }

    @Test
    void statelessHandlerUsesCapturedAuthWhenThreadIsAnonymous(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        User alice = User.getById(USERNAME, true);
        var secured = Endpoint.secureStatelessTool(probeTool());
        McpTransportContext context = McpTransportContext.create(Map.of(Endpoint.AUTHENTICATION, alice.impersonate2()));
        try (ACLContext ignored = ACL.as2(Jenkins.ANONYMOUS2)) {
            McpSchema.CallToolResult result =
                    secured.callHandler().apply(context, new McpSchema.CallToolRequest(TOOL_NAME, Map.of()));
            assertThat(result.isError()).isFalse();
            assertThat(observedUser).hasValue(USERNAME);
            assertThat(invocations).hasValue(1);
        }
    }

    private static void assertRanAs(McpSchema.CallToolResult response, String username) {
        assertThat(response.isError()).isFalse();
        assertThat(response.content())
                .first()
                .isInstanceOfSatisfying(
                        McpSchema.TextContent.class,
                        text -> assertThat(text.text()).isEqualTo(username));
        assertThat(observedUser).hasValue(username);
        assertThat(invocations).hasValue(1);
    }

    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        FullControlOnceLoggedInAuthorizationStrategy strategy = new FullControlOnceLoggedInAuthorizationStrategy();
        strategy.setAllowAnonymousRead(false);
        jenkins.jenkins.setAuthorizationStrategy(strategy);
        User.getById(USERNAME, true);
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }

    static McpServerFeatures.SyncToolSpecification probeTool() {
        var tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .description("Reports the authentication restored for a programmatic tool")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    invocations.incrementAndGet();
                    String name = Jenkins.getAuthentication2().getName();
                    observedUser.set(name);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent(name)
                            .build();
                })
                .build();
    }

    @TestExtension
    public static class ProgrammaticProbe implements McpServerExtension {
        @Override
        public List<McpServerFeatures.SyncToolSpecification> getSyncTools() {
            return List.of(probeTool());
        }
    }
}
