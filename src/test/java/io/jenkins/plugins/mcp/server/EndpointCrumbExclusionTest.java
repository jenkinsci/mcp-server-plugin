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

import hudson.security.csrf.DefaultCrumbIssuer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Crumb exclusion must follow the canonical path {@code CrumbFilter} exposes via
 * {@link jakarta.servlet.http.HttpServletRequest#getPathInfo()}, and only for callers a cross-site
 * page cannot impersonate.
 */
@WithJenkins
class EndpointCrumbExclusionTest {

    private static final String ITEM_NAME = "x";

    @Test
    void dotDotPostDoesNotSkipTheCrumb(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true));

        // Authorization would exempt a genuine /mcp-server/ POST. The raw URI looks like one, but
        // Stapler resolves ".." to /createItem. That must still require a crumb.
        HttpResponse<String> response = post(
                jenkins,
                "mcp-server/mcp/../../createItem?name=" + ITEM_NAME,
                "<project/>",
                "Bearer crumb-bypass-probe");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("No valid crumb");
        assertThat(jenkins.jenkins.getItem(ITEM_NAME)).isNull();
    }

    @Test
    void anonymousMcpPostRequiresCrumbUnlessOptedIn(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true));
        boolean previous = Endpoint.ALLOW_UNAUTHENTICATED_MCP;
        Endpoint.ALLOW_UNAUTHENTICATED_MCP = false;
        try {
            HttpResponse<String> rejected = post(jenkins, "mcp-server/mcp", "{}", null);
            assertThat(rejected.statusCode()).isEqualTo(403);
            assertThat(rejected.body()).contains("No valid crumb");

            HttpResponse<String> authorized = post(jenkins, "mcp-server/mcp", "{}", "Bearer crumb-exempt");
            assertThat(authorized.statusCode()).isNotEqualTo(403);

            Endpoint.ALLOW_UNAUTHENTICATED_MCP = true;
            HttpResponse<String> optedIn = post(jenkins, "mcp-server/mcp", "{}", null);
            assertThat(optedIn.statusCode()).isNotEqualTo(403);
            assertThat(optedIn.body()).doesNotContain("No valid crumb");
        } finally {
            Endpoint.ALLOW_UNAUTHENTICATED_MCP = previous;
        }
    }

    private static HttpResponse<String> post(JenkinsRule jenkins, String path, String body, String authorization)
            throws Exception {
        // URI.create keeps ".." in the path. new URL(base, relative) would resolve it away.
        URI target = URI.create(jenkins.getURL().toString() + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
