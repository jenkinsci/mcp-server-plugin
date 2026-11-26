package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import hudson.model.Item;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * {@code activeConnections} of {@code /mcp-health} counts the clients that are connected right now, over either
 * transport. See issue #192.
 */
@WithJenkins
class HealthEndpointConnectionsTest {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String USER = "mcp-user";

    private final HttpClient httpClient =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @BeforeEach
    void reset() {
        McpConnectionMetrics.reset();
        // A Jenkins stopped by an earlier test leaves this set
        HealthEndpoint.setShuttingDown(false);
    }

    @Test
    void openSseConnectionIsCounted(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        URI sse = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_SSE);
        HttpResponse<InputStream> stream = httpClient.send(
                HttpRequest.newBuilder(sse)
                        .GET()
                        .header("Accept", "text/event-stream")
                        .header("Authorization", basicAuth(USER))
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(stream.statusCode()).isEqualTo(200);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            // The endpoint event is the first thing the transport writes on a new connection
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.startsWith("event:endpoint") && !line.startsWith("event: endpoint"));
            assertThat(line).isNotNull();

            assertThat(activeConnections(jenkins)).isEqualTo(1);
            assertThat(McpConnectionMetrics.getActiveSseConnections()).isEqualTo(1);
        }
    }

    @Test
    void streamableSessionIsCounted(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        URI endpoint = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_STREAMABLE);
        HttpResponse<String> initialize = httpClient.send(
                HttpRequest.newBuilder(endpoint)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                        + "\"protocolVersion\":\"" + PROTOCOL_VERSION + "\",\"capabilities\":{},"
                                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                        .header("Authorization", basicAuth(USER))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.headers().firstValue("Mcp-Session-Id")).isPresent();

        await().atMost(Duration.ofSeconds(10)).until(() -> activeConnections(jenkins) == 1L);
    }

    private long activeConnections(JenkinsRule jenkins) throws Exception {
        URI health = jenkins.getURL().toURI().resolve(HealthEndpoint.URL_NAME);
        HttpResponse<String> response =
                httpClient.send(HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonPath.parse(response.body()).read("$.activeConnections", Long.class);
    }

    private static void secure(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .to(USER));
        jenkins.jenkins.save();
    }

    private static String basicAuth(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes(StandardCharsets.UTF_8));
    }
}
