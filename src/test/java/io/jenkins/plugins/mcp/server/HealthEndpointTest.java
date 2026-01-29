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
import jakarta.servlet.http.HttpServletResponse;
import java.net.URL;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HealthEndpointTest {

    @BeforeEach
    void setUp() {
        // Reset shutdown state before each test
        HealthEndpoint.setShuttingDown(false);
    }

    @AfterEach
    void tearDown() {
        // Reset shutdown state after each test
        HealthEndpoint.setShuttingDown(false);
    }

    @Test
    void testHealthEndpointReturnsOkWhenHealthy(JenkinsRule jenkins) throws Exception {
        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            var url = jenkins.getURL();
            var healthUrl = url.toString() + HealthEndpoint.URL_NAME;

            var request = new WebRequest(new URL(healthUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(response.getContentType()).contains("application/json");

            DocumentContext json = JsonPath.parse(response.getContentAsString());
            assertThat(json.read("$.mcpServerStatus", String.class)).isEqualTo("ok");
            assertThat(json.read("$.activeConnections", Integer.class)).isNotNull();
            assertThat(json.read("$.shuttingDown", Boolean.class)).isFalse();
            assertThat(json.read("$.timestamp", String.class)).isNotEmpty();
        }
    }

    @Test
    void testHealthEndpointReturns503WhenShuttingDown(JenkinsRule jenkins) throws Exception {
        // Set shutdown state
        HealthEndpoint.setShuttingDown(true);

        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);

            var url = jenkins.getURL();
            var healthUrl = url.toString() + HealthEndpoint.URL_NAME;

            var request = new WebRequest(new URL(healthUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            assertThat(response.getResponseHeaderValue("Retry-After"))
                    .isEqualTo(String.valueOf(HealthEndpoint.SHUTDOWN_GRACE_PERIOD_SECONDS));

            DocumentContext json = JsonPath.parse(response.getContentAsString());
            assertThat(json.read("$.mcpServerStatus", String.class)).isEqualTo("shutting_down");
            assertThat(json.read("$.shuttingDown", Boolean.class)).isTrue();
        }
    }

    @Test
    void testHealthEndpointIsAccessibleWithoutAuthentication(JenkinsRule jenkins) throws Exception {
        // Configure Jenkins to require authentication
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());

        try (JenkinsRule.WebClient webClient = jenkins.createWebClient()) {
            // Don't provide any credentials
            webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);

            var url = jenkins.getURL();
            var healthUrl = url.toString() + HealthEndpoint.URL_NAME;

            var request = new WebRequest(new URL(healthUrl), HttpMethod.GET);
            WebResponse response = webClient.loadWebResponse(request);

            // Should still be accessible (UnprotectedRootAction)
            assertThat(response.getStatusCode()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Test
    void testSetShuttingDownUpdatesState() {
        assertThat(HealthEndpoint.isShuttingDown()).isFalse();

        HealthEndpoint.setShuttingDown(true);
        assertThat(HealthEndpoint.isShuttingDown()).isTrue();

        HealthEndpoint.setShuttingDown(false);
        assertThat(HealthEndpoint.isShuttingDown()).isFalse();
    }
}
