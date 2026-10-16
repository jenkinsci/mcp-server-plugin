package io.jenkins.plugins.mcp.server;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
// Set before Endpoint initializes. TestCrumbIssuerMcpExtension would load Endpoint in a class-level
// beforeEach, which freezes REQUIRE_ORIGIN_HEADER before the method-level origin properties apply.
@SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.allowUnauthenticatedMcp", value = "true")
public class OriginHeaderValidationTest {

    @McpClientTest
    @SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.requireOriginHeader", value = "true")
    void testMcpMandatoryOriginHeader(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        assertThrows(
                RuntimeException.class,
                () -> jenkinsMcpClientBuilder
                        .jenkins(jenkins)
                        // as it fail because of 403 we set a short timeout
                        // to avoid waiting the default 300 seconds
                        .requestTimeoutSeconds(10)
                        .build());
    }

    @McpClientTest
    @SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.requireOriginHeader", value = "true")
    @SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.requireOriginMatch", value = "true")
    void testMcpMandatoryNonValidOriginHeader(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        assertThrows(
                RuntimeException.class,
                () -> jenkinsMcpClientBuilder
                        .jenkins(jenkins)
                        // as it fail because of 403 we set a short timeout
                        // to avoid waiting the default 300 seconds
                        .requestTimeoutSeconds(10)
                        .requestCustomizer((builder, method, endpoint, body, context) ->
                                builder.header("Origin", "http://foo-bar-beer.com"))
                        .build());
    }

    @McpClientTest
    @SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.requireOriginHeader", value = "true")
    @SetSystemProperty(key = "io.jenkins.plugins.mcp.server.Endpoint.requireOriginMatch", value = "true")
    void testMcpMandatoryValidOriginHeader(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        String jenkinsUrl = StringUtils.removeEnd(jenkins.getURL().toString(), "/jenkins/");
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                // as it fail because of 403 we set a short timeout
                // to avoid waiting the default 300 seconds
                .requestTimeoutSeconds(10)
                .requestCustomizer((builder, method, endpoint, body, context) -> builder.header("Origin", jenkinsUrl))
                .build()) {
            var tools = client.listTools().tools();
            Assertions.assertThat(tools).isNotEmpty();
        }
    }
}
