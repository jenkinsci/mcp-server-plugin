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

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.servlet.UserContextHttpRequest;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 *
 */
@Restricted(NoExternalUse.class)
@Extension
public class Endpoint extends CrumbExclusion implements RootAction {

    public static final String MCP_SERVER = "mcp-server";

    public static final String SSE_ENDPOINT = "/sse";

    public static final String MCP_SERVER_SSE = MCP_SERVER + SSE_ENDPOINT;
    public static final String STREAMABLE_ENDPOINT = "/mcp";

    public static final String MCP_SERVER_STREAMABLE = MCP_SERVER + STREAMABLE_ENDPOINT;

    /**
     * The endpoint path for handling client messages
     */
    private static final String MESSAGE_ENDPOINT = "/message";

    public static final String MCP_SERVER_MESSAGE = MCP_SERVER + MESSAGE_ENDPOINT;
    public static final String USER_ID = "userId";
    /**
     * JSON object mapper for serialization/deserialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    HttpServletSseServerTransportProvider httpServletSseServerTransportProvider;
    HttpServletStreamableServerTransportProvider httpServletStreamableServerTransportProvider;

    public Endpoint() throws ServletException {

        init();
    }

    public static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest
                .getRequestURI()
                .substring(httpServletRequest.getContextPath().length());
    }

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestedResource = getRequestedResourcePath(request);
        if (requestedResource.startsWith("/" + MCP_SERVER_MESSAGE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            handleMessage(request, response);
            return true; // Do not allow this request on to Stapler
        }
        if (requestedResource.startsWith("/" + MCP_SERVER_SSE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return true;
        }
        if (isStreamableRequest(request)) {
            handleMcpRequest(request, response);
            return true;
        }
        return false;
    }

    protected void init() throws ServletException {

        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .prompts(true)
                .resources(true, true)
                .build();
        var extensions = McpServerExtension.all();

        var tools = extensions.stream()
                .map(McpServerExtension::getSyncTools)
                .flatMap(List::stream)
                .toList();
        var prompts = extensions.stream()
                .map(McpServerExtension::getSyncPrompts)
                .flatMap(List::stream)
                .toList();
        var resources = extensions.stream()
                .map(McpServerExtension::getSyncResources)
                .flatMap(List::stream)
                .toList();

        var annotationTools = extensions.stream()
                .flatMap(extension -> Arrays.stream(extension.getClass().getMethods())
                        .filter(method -> method.isAnnotationPresent(Tool.class))
                        .map(method -> new McpToolWrapper(objectMapper, extension, method).asSyncToolSpecification()))
                .toList();

        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
        allTools.addAll(tools);
        allTools.addAll(annotationTools);

        var rootUrl = jenkins.model.JenkinsLocationConfiguration.get().getUrl();
        if (rootUrl == null) {
            rootUrl = "";
        }
        httpServletSseServerTransportProvider = HttpServletSseServerTransportProvider.builder()
                .sseEndpoint(SSE_ENDPOINT)
                .baseUrl(rootUrl)
                .messageEndpoint(MCP_SERVER_MESSAGE)
                .objectMapper(objectMapper)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletSseServerTransportProvider)
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();

        httpServletStreamableServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
                .objectMapper(objectMapper)
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                .contextExtractor((serverRequest, context) -> {
                    var userId = serverRequest.getAttribute(USER_ID);
                    if (userId != null) {
                        context.put(USER_ID, userId);
                    }
                    return context;
                })
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletStreamableServerTransportProvider)
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();
        PluginServletFilter.addFilter((Filter) (servletRequest, servletResponse, filterChain) -> {
            if (isSSERequest(servletRequest)) {
                handleSSE(servletRequest, servletResponse);
            } else if (isStreamableRequest(servletRequest)) {
                handleMcpRequest(servletRequest, servletResponse);
            } else {
                filterChain.doFilter(servletRequest, servletResponse);
            }
        });
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return MCP_SERVER;
    }

    boolean isSSERequest(ServletRequest servletRequest) {
        if (servletRequest instanceof HttpServletRequest request) {
            String requestedResource = getRequestedResourcePath(request);
            return requestedResource.startsWith("/" + MCP_SERVER_SSE)
                    && request.getMethod().equalsIgnoreCase("GET");
        }
        return false;
    }

    boolean isStreamableRequest(ServletRequest servletRequest) {
        if (servletRequest instanceof HttpServletRequest request) {
            String requestedResource = getRequestedResourcePath(request);
            return requestedResource.startsWith("/" + MCP_SERVER_STREAMABLE)
                    && (request.getMethod().equalsIgnoreCase("GET")
                            || (request.getMethod().equalsIgnoreCase("POST")));
        }
        return false;
    }

    protected void handleSSE(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        httpServletSseServerTransportProvider.service(request, response);
    }

    protected void handleMessage(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        httpServletSseServerTransportProvider.service(new UserContextHttpRequest(objectMapper, request), response);
    }

    private void handleMcpRequest(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        var currentUser = User.current();
        String userId = null;
        if (currentUser != null) {
            userId = currentUser.getId();
        }
        if (userId != null) {
            request.setAttribute(USER_ID, userId);
        }
        httpServletStreamableServerTransportProvider.service(request, response);
    }
}
