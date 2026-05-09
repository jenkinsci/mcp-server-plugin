/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Gong Yi.
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

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URL;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class McpConnectionMetricsTest {

    @BeforeEach
    void setUp() {
        // Reset metrics before each test
        McpConnectionMetrics.reset();
    }

    @Test
    void testMetricsEndpointRequiresAuthentication(JenkinsRule jenkins) throws Exception {
        // Configure Jenkins to require authentication
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        var authStrategy = new FullControlOnceLoggedInAuthorizationStrategy();
        authStrategy.setAllowAnonymousRead(false);
        jenkins.jenkins.setAuthorizationStrategy(authStrategy);

        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);

            var url = jenkins.getURL();
            var metricsUrl = url.toString() + McpConnectionMetrics.URL_NAME;

            var request = new WebRequest(new URL(metricsUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            // Should require authentication
            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    @Test
    void testMetricsEndpointReturnsJsonWithAuthentication(JenkinsRule jenkins) throws Exception {
        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            var url = jenkins.getURL();
            var metricsUrl = url.toString() + McpConnectionMetrics.URL_NAME;

            var request = new WebRequest(new URL(metricsUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(response.getContentType()).contains("application/json");

            DocumentContext json = JsonPath.parse(response.getContentAsString());
            assertThat(json.read("$.sseConnectionsTotal", Long.class)).isNotNull();
            assertThat(json.read("$.sseConnectionsActive", Long.class)).isNotNull();
            assertThat(json.read("$.streamableRequestsTotal", Long.class)).isNotNull();
            assertThat(json.read("$.connectionErrorsTotal", Long.class)).isNotNull();
            assertThat(json.read("$.uptimeSeconds", Long.class)).isNotNull();
            assertThat(json.read("$.startTime", String.class)).isNotEmpty();
        }
    }

    @Test
    void testSseConnectionMetrics() {
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isZero();
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isZero();

        // Start a connection
        McpConnectionMetrics.recordSseConnectionStart();
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isOne();
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isOne();

        // Start another connection
        McpConnectionMetrics.recordSseConnectionStart();
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isEqualTo(2);
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isEqualTo(2);

        // End one connection
        McpConnectionMetrics.recordSseConnectionEnd();
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isEqualTo(2);
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isOne();

        // End another connection
        McpConnectionMetrics.recordSseConnectionEnd();
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isEqualTo(2);
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isZero();
    }

    @Test
    void testStreamableRequestMetrics() {
        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isZero();

        McpConnectionMetrics.recordStreamableRequest();
        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isOne();

        McpConnectionMetrics.recordStreamableRequest();
        McpConnectionMetrics.recordStreamableRequest();
        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isEqualTo(3);
    }

    @Test
    void testConnectionErrorMetrics() {
        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isZero();

        McpConnectionMetrics.recordConnectionError();
        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isOne();

        McpConnectionMetrics.recordConnectionError();
        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isEqualTo(2);
    }

    @Test
    void testReset() {
        // Record some metrics
        McpConnectionMetrics.recordSseConnectionStart();
        McpConnectionMetrics.recordStreamableRequest();
        McpConnectionMetrics.recordConnectionError();

        // Verify they're non-zero
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isPositive();
        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isPositive();
        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isPositive();

        // Reset
        McpConnectionMetrics.reset();

        // Verify they're zero
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isZero();
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isZero();
        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isZero();
        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isZero();
    }

    @Test
    void testPerUserSseConnectionMetrics() {
        McpConnectionMetrics.recordSseConnectionStart("alice");
        McpConnectionMetrics.recordSseConnectionStart("alice");
        McpConnectionMetrics.recordSseConnectionStart("bob");

        McpConnectionMetrics.UserMetrics alice = McpConnectionMetrics.userMetrics.get("alice");
        McpConnectionMetrics.UserMetrics bob = McpConnectionMetrics.userMetrics.get("bob");

        assertThat(alice).isNotNull();
        assertThat(alice.sseConnectionsTotal.get()).isEqualTo(2);
        assertThat(alice.sseConnectionsActive.get()).isEqualTo(2);

        assertThat(bob).isNotNull();
        assertThat(bob.sseConnectionsTotal.get()).isOne();
        assertThat(bob.sseConnectionsActive.get()).isOne();

        // Global counters should also reflect the combined total
        assertThat(McpConnectionMetrics.getTotalSseConnections()).isEqualTo(3);
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isEqualTo(3);

        McpConnectionMetrics.recordSseConnectionEnd("alice");
        assertThat(alice.sseConnectionsActive.get()).isOne();
        assertThat(McpConnectionMetrics.getActiveSseConnections()).isEqualTo(2);
    }

    @Test
    void testPerUserStreamableRequestMetrics() {
        McpConnectionMetrics.recordStreamableRequest("carol");
        McpConnectionMetrics.recordStreamableRequest("carol");
        McpConnectionMetrics.recordStreamableRequest("dave");

        McpConnectionMetrics.UserMetrics carol = McpConnectionMetrics.userMetrics.get("carol");
        McpConnectionMetrics.UserMetrics dave = McpConnectionMetrics.userMetrics.get("dave");

        assertThat(carol).isNotNull();
        assertThat(carol.streamableRequestsTotal.get()).isEqualTo(2);

        assertThat(dave).isNotNull();
        assertThat(dave.streamableRequestsTotal.get()).isOne();

        assertThat(McpConnectionMetrics.getTotalStreamableRequests()).isEqualTo(3);
    }

    @Test
    void testPerUserConnectionErrorMetrics() {
        McpConnectionMetrics.recordConnectionError("alice");
        McpConnectionMetrics.recordConnectionError("alice");
        McpConnectionMetrics.recordConnectionError("bob");

        McpConnectionMetrics.UserMetrics alice = McpConnectionMetrics.userMetrics.get("alice");
        McpConnectionMetrics.UserMetrics bob = McpConnectionMetrics.userMetrics.get("bob");

        assertThat(alice).isNotNull();
        assertThat(alice.connectionErrorsTotal.get()).isEqualTo(2);

        assertThat(bob).isNotNull();
        assertThat(bob.connectionErrorsTotal.get()).isOne();

        assertThat(McpConnectionMetrics.getTotalConnectionErrors()).isEqualTo(3);
    }

    @Test
    void testResetClearsUserMetrics() {
        McpConnectionMetrics.recordSseConnectionStart("alice");
        McpConnectionMetrics.recordStreamableRequest("bob");

        assertThat(McpConnectionMetrics.userMetrics).isNotEmpty();

        McpConnectionMetrics.reset();

        assertThat(McpConnectionMetrics.userMetrics).isEmpty();
    }

    @Test
    void testMetricsEndpointIncludesUsersSection(JenkinsRule jenkins) throws Exception {
        McpConnectionMetrics.recordSseConnectionStart("alice");
        McpConnectionMetrics.recordStreamableRequest("alice");
        McpConnectionMetrics.recordStreamableRequest("bob");

        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            var metricsUrl = jenkins.getURL().toString() + McpConnectionMetrics.URL_NAME;
            var request = new WebRequest(new URL(metricsUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_OK);

            DocumentContext json = JsonPath.parse(response.getContentAsString());
            assertThat(json.read("$.users", Object.class)).isNotNull();
            assertThat(json.read("$.users.alice.sseConnectionsTotal", Long.class))
                    .isEqualTo(1L);
            assertThat(json.read("$.users.alice.sseConnectionsActive", Long.class))
                    .isEqualTo(1L);
            assertThat(json.read("$.users.alice.streamableRequestsTotal", Long.class))
                    .isEqualTo(1L);
            assertThat(json.read("$.users.alice.connectionErrorsTotal", Long.class))
                    .isZero();
            assertThat(json.read("$.users.bob.streamableRequestsTotal", Long.class))
                    .isEqualTo(1L);
        }
    }
}
