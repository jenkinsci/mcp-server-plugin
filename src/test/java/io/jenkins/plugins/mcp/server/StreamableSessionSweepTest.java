package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.model.Item;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Checks that a session whose client went away without sending DELETE is dropped, and that one still in
 * use is not. See issue #278.
 *
 * <p>The client here authenticates as {@link #USER} with basic auth, the way a real MCP client does. That
 * is also what exempts the POSTs from the CSRF crumb, so this test needs no crumb opt-in.
 */
@WithJenkins
public class StreamableSessionSweepTest {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

    private static final String USER = "mcp-user";

    /** Short enough to keep the test quick, long enough not to race the initialize round trip. */
    private static final int SWEEP_INTERVAL_SECONDS = 2;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private int previousSweepInterval;

    @BeforeEach
    void shortenSweepInterval() {
        // The field, not the system property: Endpoint reads that in a static initializer, which has
        // already run by the time a test starts.
        previousSweepInterval = Endpoint.SESSION_SWEEP_INTERVAL;
        Endpoint.SESSION_SWEEP_INTERVAL = SWEEP_INTERVAL_SECONDS;
    }

    @AfterEach
    void restoreSweepInterval() {
        Endpoint.SESSION_SWEEP_INTERVAL = previousSweepInterval;
    }

    @Test
    void idleSessionIsReclaimed(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        URI endpoint = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_STREAMABLE);
        String sessionId = openSession(endpoint);
        assertThat(liveSessions(jenkins)).isEqualTo(1);

        // Watch the metrics endpoint, not the session: anything sent with the session id marks it
        // active and would postpone the sweep we are waiting for.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> liveSessions(jenkins) == 0L);

        assertThat(post(endpoint, sessionId, TOOLS_LIST).statusCode()).isEqualTo(404);
    }

    @Test
    void sessionStillInUseSurvivesTheSweep(JenkinsRule jenkins) throws Exception {
        secure(jenkins);
        URI endpoint = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_STREAMABLE);
        String sessionId = openSession(endpoint);

        // Keep talking for longer than two sweeps; every request should still be served.
        for (int i = 0; i < 2 * SWEEP_INTERVAL_SECONDS + 2; i++) {
            Thread.sleep(1000);
            assertThat(post(endpoint, sessionId, TOOLS_LIST).statusCode()).isEqualTo(200);
        }
        assertThat(liveSessions(jenkins)).isEqualTo(1);
    }

    /** Runs initialize plus the initialized notification, and returns the new session id. */
    private String openSession(URI endpoint) throws Exception {
        HttpResponse<String> initialize = post(
                endpoint,
                null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                        + PROTOCOL_VERSION
                        + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
        assertThat(initialize.statusCode()).isEqualTo(200);
        String sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();

        HttpResponse<String> initialized =
                post(endpoint, sessionId, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(initialized.statusCode()).isEqualTo(202);
        return sessionId;
    }

    /** Reads streamableSessionsActive from /mcp-server/metrics, which never touches the transport. */
    private long liveSessions(JenkinsRule jenkins) throws Exception {
        URI metrics = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_METRICS);
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(metrics)
                        .GET()
                        .header("Authorization", basicAuth(USER))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext json = JsonPath.parse(response.body());
        return json.read("$.streamableSessionsActive", Long.class);
    }

    private HttpResponse<String> post(URI endpoint, String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Authorization", basicAuth(USER));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + user).getBytes());
    }
}
