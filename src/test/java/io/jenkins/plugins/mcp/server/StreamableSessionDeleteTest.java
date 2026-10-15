package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class StreamableSessionDeleteTest {

    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void deleteTerminatesStreamableSession(JenkinsRule jenkins) throws Exception {
        URI endpoint = jenkins.getURL().toURI().resolve(Endpoint.MCP_SERVER_STREAMABLE);

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

        HttpResponse<String> delete = httpClient.send(
                HttpRequest.newBuilder(endpoint)
                        .DELETE()
                        .header("Mcp-Session-Id", sessionId)
                        .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(delete.statusCode()).isEqualTo(200);

        HttpResponse<String> afterDelete =
                post(endpoint, sessionId, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(afterDelete.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> post(URI endpoint, String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
