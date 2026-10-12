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

import static io.jenkins.plugins.mcp.server.extensions.DefaultMcpServer.FULL_NAME;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.model.User;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.StatelessMcpTestClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * A {@link PluginServletFilter} registered after Jenkins's {@code HttpServletFilter} wrapper must be able to
 * authenticate an MCP request before the endpoint reads the current user.
 *
 * <p>That wrapper is inserted at the front of the chain and used to serve {@code /mcp-server/**} itself. Handling the
 * request there returned before filters added later (OpenShift Login bearer tokens, Blue Ocean JWT) ran, so tool
 * calls executed as anonymous.
 */
@WithJenkins
class PluginServletFilterAuthenticationTest {

    private static final String USERNAME = "bearer-user";
    private static final String TOKEN = "test-bearer-token";

    @McpClientTest
    void toolCallRunsAsUserAuthenticatedByLaterPluginServletFilter(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        BearerAuthenticationFilter filter = installBearerFilter(jenkins);
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) ->
                        builder.setHeader("Authorization", "Bearer " + TOKEN))
                .build()) {
            assertWhoAmI(client.callTool(new McpSchema.CallToolRequest("whoAmI", Map.of())));
        } finally {
            PluginServletFilter.removeFilter(filter);
        }
    }

    @Test
    void statelessToolCallRunsAsUserAuthenticatedByLaterPluginServletFilter(JenkinsRule jenkins) throws Exception {
        BearerAuthenticationFilter filter = installBearerFilter(jenkins);
        try (var client = new StatelessMcpTestClient(jenkins, "Bearer " + TOKEN)) {
            assertWhoAmI(client.callTool("whoAmI", Map.of()));
        } finally {
            PluginServletFilter.removeFilter(filter);
        }
    }

    private static BearerAuthenticationFilter installBearerFilter(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        FullControlOnceLoggedInAuthorizationStrategy authStrategy = new FullControlOnceLoggedInAuthorizationStrategy();
        authStrategy.setAllowAnonymousRead(false);
        jenkins.jenkins.setAuthorizationStrategy(authStrategy);
        User.getById(USERNAME, true);

        BearerAuthenticationFilter filter = new BearerAuthenticationFilter(USERNAME, TOKEN);
        PluginServletFilter.addFilter(filter);
        assertThat(filterIndex(jenkins, filter))
                .as("bearer filter is registered after the HttpServletFilter wrapper that used to serve MCP")
                .isGreaterThan(httpServletFilterIndex(jenkins));
        return filter;
    }

    private static void assertWhoAmI(McpSchema.CallToolResult response) {
        assertThat(response.isError()).isFalse();
        assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
            DocumentContext documentContext =
                    JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
            assertThat(documentContext.read("$.result", Map.class)).containsEntry(FULL_NAME, USERNAME);
        });
    }

    private static int httpServletFilterIndex(JenkinsRule jenkins) throws Exception {
        List<Filter> filters = pluginServletFilters(jenkins);
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).getClass().getName().contains("HttpServletFilter")) {
                return i;
            }
        }
        throw new AssertionError("HttpServletFilter wrapper is not registered in PluginServletFilter");
    }

    private static int filterIndex(JenkinsRule jenkins, Filter filter) throws Exception {
        int index = pluginServletFilters(jenkins).indexOf(filter);
        assertThat(index).as("bearer filter registered").isGreaterThanOrEqualTo(0);
        return index;
    }

    @SuppressWarnings("unchecked")
    private static List<Filter> pluginServletFilters(JenkinsRule jenkins) throws Exception {
        Object container = jenkins.jenkins.getServletContext().getAttribute(PluginServletFilter.class.getName());
        assertThat(container).isNotNull();
        Field listField = PluginServletFilter.class.getDeclaredField("list");
        listField.setAccessible(true);
        return (List<Filter>) listField.get(container);
    }

    /**
     * Stands in for an auth plugin filter, such as OpenShift Login's {@code OpenShiftPermissionFilter}, that is added
     * to {@link PluginServletFilter} after the MCP endpoint has already been registered.
     */
    private static final class BearerAuthenticationFilter implements Filter {
        private final String username;
        private final String token;

        private BearerAuthenticationFilter(String username, String token) {
            this.username = username;
            this.token = token;
        }

        @Override
        public void init(FilterConfig filterConfig) {}

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request instanceof HttpServletRequest httpRequest
                    && ("Bearer " + token).equals(httpRequest.getHeader("Authorization"))) {
                SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(
                                username, token, List.of(new SimpleGrantedAuthority("authenticated"))));
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {}
    }
}
