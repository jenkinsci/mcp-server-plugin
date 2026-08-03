package io.jenkins.plugins.mcp.server.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.User;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class AgentExtensionTest {

    static Stream<Arguments> getAgentParameters() {
        Stream<Arguments> baseArgs = Stream.of(Arguments.of(false, ""), Arguments.of(true, "Maintenance"));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @ParameterizedTest
    @MethodSource("getAgentParameters")
    void testGetAgent(
            boolean takeOffline,
            String offlineReason,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        Node node = jenkins.createOnlineSlave();
        node.setLabelString("test linux");
        enableSecurity(jenkins);
        if (takeOffline) {
            User admin = User.getById("admin", true);
            node.toComputer().setTemporaryOfflineCause(new OfflineCause.UserCause(admin, offlineReason));
        }
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) -> {
                    String username = "admin";
                    String password = "admin";
                    String authString = username + ":" + password;
                    String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                    builder.setHeader("Authorization", "Basic " + encodedAuth);
                })
                .build()) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getAgent", Map.of("name", node.getNodeName()), null);
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).extractingByKey("displayName").isEqualTo(node.getDisplayName());
                assertThat(contentMap).extractingByKey("idle").isEqualTo(true);
                assertThat(contentMap).extractingByKey("temporarilyOffline").isEqualTo(takeOffline);
                assertThat(contentMap).extractingByKey("offline").isEqualTo(takeOffline);
                assertThat(contentMap).extractingByKey("offlineCauseReason").isEqualTo(offlineReason);
            });
        }
    }

    static Stream<Arguments> takeOfflineParameters() {
        Stream<Arguments> baseArgs = Stream.of(
                Arguments.of("admin", true), Arguments.of("connecter", false), Arguments.of("disconnecter", true));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @ParameterizedTest
    @MethodSource("takeOfflineParameters")
    void testTakeAgentOffline(
            String user, boolean canTakeOffline, JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        Node node = jenkins.createOnlineSlave();
        node.setLabelString("test linux");
        enableSecurity(jenkins);
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) -> {
                    String authString = user + ":" + user;
                    String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                    builder.setHeader("Authorization", "Basic " + encodedAuth);
                })
                .build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "takeAgentOffline", Map.of("name", node.getNodeName(), "reason", "Maintenance"), null);
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(Boolean.toString(canTakeOffline));
            });
            // Verify that the node is now offline with the correct reason
            assertThat(node.toComputer().isOffline()).isEqualTo(canTakeOffline);
            if (canTakeOffline) {
                assertThat(node.toComputer().getOfflineCauseReason()).isEqualTo("Maintenance");
            }
        }
    }

    static Stream<Arguments> takeOnlineParameters() {
        Stream<Arguments> baseArgs = Stream.of(
                Arguments.of("admin", true),
                Arguments.of("connecter", true),
                Arguments.of("disconnecter", true),
                Arguments.of("reader", false));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @ParameterizedTest
    @MethodSource("takeOnlineParameters")
    void testTakeAgentOnline(
            String user, boolean canTakeOnline, JenkinsMcpClientBuilder jenkinsMcpClientBuilder, JenkinsRule jenkins)
            throws Exception {
        Node node = jenkins.createOnlineSlave();
        node.setLabelString("test linux");
        node.toComputer()
                .setTemporaryOfflineCause(new OfflineCause.UserCause(User.getById("admin", true), "Maintenance"));
        enableSecurity(jenkins);
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) -> {
                    String authString = user + ":" + user;
                    String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                    builder.setHeader("Authorization", "Basic " + encodedAuth);
                })
                .build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "takeAgentOnline", Map.of("name", node.getNodeName(), "reason", "Maintenance"), null);
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(Boolean.toString(canTakeOnline));
            });
            // Verify that the node is now offline with the correct reason
            assertThat(node.toComputer().isOnline()).isEqualTo(canTakeOnline);
            if (!canTakeOnline) {
                assertThat(node.toComputer().getOfflineCauseReason()).isEqualTo("Maintenance");
            }
        }
    }

    @McpClientTest
    void testListAgentNames(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        jenkins.createOnlineSlave();
        jenkins.createOnlineSlave();
        jenkins.createOnlineSlave();
        enableSecurity(jenkins);
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) -> {
                    String username = "admin";
                    String password = "admin";
                    String authString = username + ":" + password;
                    String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                    builder.setHeader("Authorization", "Basic " + encodedAuth);
                })
                .build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("listAgentNames", Map.of(), null);
            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                    .parse(((McpSchema.TextContent) response.content().get(0)).text());
            var contentList = documentContext.read("$.result", List.class);
            assertThat(contentList).hasSize(3);
        }
    }

    private void enableSecurity(JenkinsRule jenkins) throws Exception {
        JenkinsRule.DummySecurityRealm securityRealm = jenkins.createDummySecurityRealm();
        jenkins.jenkins.setSecurityRealm(securityRealm);
        var authStrategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin");
        authStrategy.grant(Jenkins.READ).everywhere().toEveryone();
        authStrategy.grant(Computer.CONNECT).everywhere().to("connecter");
        authStrategy.grant(Computer.DISCONNECT).everywhere().to("disconnecter");
        jenkins.jenkins.setAuthorizationStrategy(authStrategy);
        jenkins.jenkins.save();
    }
}
