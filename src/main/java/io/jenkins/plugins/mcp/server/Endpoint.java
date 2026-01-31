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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.util.HttpServletFilter;
import jenkins.util.SystemProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 *
 */
@Restricted(NoExternalUse.class)
@Extension
@Slf4j
public class Endpoint extends CrumbExclusion implements RootAction, HttpServletFilter {

    public static final String MCP_SERVER = "mcp-server";

    public static final String SSE_ENDPOINT = "/sse";

    public static final String MCP_SERVER_SSE = MCP_SERVER + SSE_ENDPOINT;
    public static final String STREAMABLE_ENDPOINT = "/mcp";

    public static final String MCP_SERVER_STREAMABLE = MCP_SERVER + STREAMABLE_ENDPOINT;

    public static final String STATELESS_ENDPOINT = "/stateless";

    public static final String MCP_SERVER_STATELESS = MCP_SERVER + STATELESS_ENDPOINT;

    /**
     * The endpoint path for handling client messages
     */
    private static final String MESSAGE_ENDPOINT = "/message";

    public static final String MCP_SERVER_MESSAGE = MCP_SERVER + MESSAGE_ENDPOINT;
    public static final String USER_ID = Endpoint.class.getName() + ".userId";
    public static final String HTTP_SERVLET_REQUEST = Endpoint.class.getName() + ".httpServletRequest";

    private static final String MCP_CONTEXT_KEY = Endpoint.class.getName() + ".mcpContext";

    /**
     * The interval in seconds for sending keep-alive messages to the client.
     * Default is 0 seconds (so disabled per default), can be overridden by setting the system property
     * it's not static final on purpose to allow dynamic configuration via script console.
     */
    private static int keepAliveInterval =
            SystemProperties.getInteger(Endpoint.class.getName() + ".keepAliveInterval", 0);

    /**
     * Whether to require the Origin header in requests. Default is false, can be overridden by setting the system
     * property {@code io.jenkins.plugins.mcp.server.Endpoint.requireOriginHeader=true}.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean REQUIRE_ORIGIN_HEADER =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".requireOriginHeader", false);

    /**
     *
     * Whether to require the Origin header to match the Jenkins root URL. Default is true, can be overridden by
     * setting the system property {@code io.jenkins.plugins.mcp.server.Endpoint.requireOriginMatch=false}.
     * The header will be validated only if present.
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean REQUIRE_ORIGIN_MATCH =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".requireOriginMatch", true);

    /**
     * Whether to disable the stateless MCP endpoint. Default is false (enabled).
     * The stateless endpoint is available at /mcp-server/stateless
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean DISABLE_MCP_STATELESS =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".disableMcpStateless", false);

    /**
     * Whether to disable the SSE MCP endpoint. Default is false (enabled).
     * The SSE endpoint is available at /mcp-server/sse
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean DISABLE_MCP_SSE =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".disableMcpSse", false);

    /**
     * Whether to disable the streamable HTTP MCP endpoint. Default is false (enabled).
     * The streamable endpoint is available at /mcp-server/mcp
     */
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    public static boolean DISABLE_MCP_STREAMABLE =
            SystemProperties.getBoolean(Endpoint.class.getName() + ".disableMcpStreamable", false);

    /**
     * JSON object mapper for serialization/deserialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    HttpServletSseServerTransportProvider httpServletSseServerTransportProvider;
    HttpServletStreamableServerTransportProvider httpServletStreamableServerTransportProvider;
    HttpServletStatelessServerTransport httpServletStatelessServerTransport;

    private boolean initialized = false;

    public static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
        return httpServletRequest
                .getRequestURI()
                .substring(httpServletRequest.getContextPath().length());
    }

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!initialized) {
            init();
        }

        String requestedResource = getRequestedResourcePath(request);

        // Handle stateless endpoint
        if (isStatelessRequest(request)) {
            if (DISABLE_MCP_STATELESS) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Stateless endpoint is disabled");
                return true;
            }
            handleStatelessMessage(request, response);
            return true;
        }

        // Handle SSE message endpoint
        if (requestedResource.startsWith("/" + MCP_SERVER_MESSAGE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            if (DISABLE_MCP_SSE) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "SSE endpoint is disabled");
                return true;
            }
            handleMessage(request, response, httpServletSseServerTransportProvider);
            return true;
        }

        // Reject POST to SSE endpoint
        if (requestedResource.startsWith("/" + MCP_SERVER_SSE)
                && request.getMethod().equalsIgnoreCase("POST")) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return true;
        }

        // Handle streamable endpoint
        if (isStreamableRequest(request)) {
            if (DISABLE_MCP_STREAMABLE) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Streamable endpoint is disabled");
                return true;
            }
            handleMessage(request, response, httpServletStreamableServerTransportProvider);
            return true;
        }

        return false;
    }

    protected synchronized void init() throws ServletException {

        if (initialized) {
            return;
        }
        PluginWrapper wrapper = Jenkins.get().getPluginManager().whichPlugin(Endpoint.class);
        // should not be null here in production. Only for testing purposes
        String pluginName;
        String pluginVersion;
        if (wrapper == null) {
            pluginName = "Jenkins MCP Server Plugin";
            pluginVersion = "Unknown Version";
        } else {
            pluginName = "Jenkins " + wrapper.getDisplayName();
            pluginVersion = wrapper.getVersion();
        }

        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder()
                .tools(true)
                .prompts(true)
                .resources(true, true)
                .build();
        var extensions = McpServerExtension.all();

        var prompts = extensions.stream()
                .map(McpServerExtension::getSyncPrompts)
                .flatMap(List::stream)
                .toList();
        var resources = extensions.stream()
                .map(McpServerExtension::getSyncResources)
                .flatMap(List::stream)
                .toList();

        var rootUrl = jenkins.model.JenkinsLocationConfiguration.get().getUrl();
        if (rootUrl == null) {
            rootUrl = "";
        }

        // Initialize session-based transports if SSE or Streamable enabled
        if (!DISABLE_MCP_SSE || !DISABLE_MCP_STREAMABLE) {
            initSessionBased(serverCapabilities, extensions, prompts, resources, rootUrl, pluginName, pluginVersion);
        }

        // Initialize stateless transport if enabled
        if (!DISABLE_MCP_STATELESS) {
            initStateless(serverCapabilities, extensions, prompts, resources, pluginName, pluginVersion);
        }

        initialized = true;
    }

    private void initSessionBased(
            McpSchema.ServerCapabilities serverCapabilities,
            List<McpServerExtension> extensions,
            List<McpServerFeatures.SyncPromptSpecification> prompts,
            List<McpServerFeatures.SyncResourceSpecification> resources,
            String rootUrl,
            String pluginName,
            String pluginVersion) {

        var tools = extensions.stream()
                .map(McpServerExtension::getSyncTools)
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

        httpServletSseServerTransportProvider = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .baseUrl(rootUrl)
                .sseEndpoint(SSE_ENDPOINT)
                .messageEndpoint("/" + MCP_SERVER_MESSAGE)
                .contextExtractor(createExtractor())
                .keepAliveInterval(keepAliveInterval > 0 ? Duration.ofSeconds(keepAliveInterval) : null)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletSseServerTransportProvider)
                .serverInfo(pluginName, pluginVersion)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();

        httpServletStreamableServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                .contextExtractor(createExtractor())
                .keepAliveInterval(keepAliveInterval > 0 ? Duration.ofSeconds(keepAliveInterval) : null)
                .build();

        io.modelcontextprotocol.server.McpServer.sync(httpServletStreamableServerTransportProvider)
                .serverInfo(pluginName, pluginVersion)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();
    }

    private void initStateless(
            McpSchema.ServerCapabilities serverCapabilities,
            List<McpServerExtension> extensions,
            List<McpServerFeatures.SyncPromptSpecification> prompts,
            List<McpServerFeatures.SyncResourceSpecification> resources,
            String pluginName,
            String pluginVersion) {

        // Convert session-based tool specs to stateless tool specs
        var statelessTools = convertToStatelessTools(extensions);
        var statelessPrompts = convertToStatelessPrompts(prompts);
        var statelessResources = convertToStatelessResources(resources);

        httpServletStatelessServerTransport = HttpServletStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .messageEndpoint(STATELESS_ENDPOINT)
                .contextExtractor(createExtractor())
                .build();

        McpServer.sync(httpServletStatelessServerTransport)
                .serverInfo(pluginName, pluginVersion)
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .jsonSchemaValidator(new DefaultJsonSchemaValidator(objectMapper))
                .capabilities(serverCapabilities)
                .tools(statelessTools)
                .prompts(statelessPrompts)
                .resources(statelessResources)
                .build();
    }

    private List<McpStatelessServerFeatures.SyncToolSpecification> convertToStatelessTools(
            List<McpServerExtension> extensions) {
        // Collect tools from getSyncTools() - these need conversion
        var sessionTools = extensions.stream()
                .map(McpServerExtension::getSyncTools)
                .flatMap(List::stream)
                .map(this::convertToolToStateless)
                .toList();

        // Collect annotation-based tools - use McpToolWrapper.asStatelessSyncToolSpecification()
        var annotationTools = extensions.stream()
                .flatMap(extension -> Arrays.stream(extension.getClass().getMethods())
                        .filter(method -> method.isAnnotationPresent(Tool.class))
                        .map(method ->
                                new McpToolWrapper(objectMapper, extension, method).asStatelessSyncToolSpecification()))
                .toList();

        List<McpStatelessServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();
        allTools.addAll(sessionTools);
        allTools.addAll(annotationTools);
        return allTools;
    }

    private McpStatelessServerFeatures.SyncToolSpecification convertToolToStateless(
            McpServerFeatures.SyncToolSpecification sessionTool) {
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(sessionTool.tool())
                .callHandler((context, request) -> sessionTool.callHandler().apply(null, request))
                .build();
    }

    private Map<String, McpStatelessServerFeatures.SyncPromptSpecification> convertToStatelessPrompts(
            List<McpServerFeatures.SyncPromptSpecification> sessionPrompts) {
        Map<String, McpStatelessServerFeatures.SyncPromptSpecification> result = new HashMap<>();
        for (var prompt : sessionPrompts) {
            result.put(
                    prompt.prompt().name(),
                    new McpStatelessServerFeatures.SyncPromptSpecification(
                            prompt.prompt(),
                            (context, request) -> prompt.promptHandler().apply(null, request)));
        }
        return result;
    }

    private Map<String, McpStatelessServerFeatures.SyncResourceSpecification> convertToStatelessResources(
            List<McpServerFeatures.SyncResourceSpecification> sessionResources) {
        Map<String, McpStatelessServerFeatures.SyncResourceSpecification> result = new HashMap<>();
        for (var resource : sessionResources) {
            result.put(
                    resource.resource().uri(),
                    new McpStatelessServerFeatures.SyncResourceSpecification(
                            resource.resource(),
                            (context, request) -> resource.readHandler().apply(null, request)));
        }
        return result;
    }

    @Override
    public boolean handle(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        if (!initialized) {
            init();
        }

        // Handle stateless endpoint
        if (isStatelessRequest(req)) {
            if (DISABLE_MCP_STATELESS) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Stateless endpoint is disabled");
                return true;
            }
            if (isBrowserRequest(req)) {
                serveBrowserPage(resp);
            } else {
                handleStatelessMessage(req, resp);
            }
            return true;
        }

        // Handle SSE endpoint
        if (isSSERequest(req)) {
            if (DISABLE_MCP_SSE) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "SSE endpoint is disabled");
                return true;
            }
            handleSSE(req, resp);
            return true;
        }

        // Handle streamable endpoint
        if (isStreamableRequest(req)) {
            if (DISABLE_MCP_STREAMABLE) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Streamable endpoint is disabled");
                return true;
            }
            if (isBrowserRequest(req)) {
                serveBrowserPage(resp);
            } else {
                handleMessage(req, resp, httpServletStreamableServerTransportProvider);
            }
            return true;
        }

        return false;
    }

    private void serveBrowserPage(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter()
                .write(
                        "<html><head><title>Model Context Protocol Endpoint</title></head>"
                                + "<body><h2>This endpoint is designed for an AI agent using the Model Context Protocol.</h2></body></html>");
        resp.getWriter().flush();
    }

    private boolean isBrowserRequest(HttpServletRequest req) {
        if (req.getMethod().equalsIgnoreCase("GET")) {
            String acceptHeader = req.getHeader("Accept");
            return acceptHeader != null && acceptHeader.contains("text/html");
        } else {
            return false;
        }
    }

    private static McpTransportContextExtractor<HttpServletRequest> createExtractor() {
        return (httpServletRequest) -> (McpTransportContext) httpServletRequest.getAttribute(MCP_CONTEXT_KEY);
    }

    private boolean validOriginHeader(HttpServletRequest request, HttpServletResponse response) {
        String originHeaderValue = request.getHeader("Origin");
        if (REQUIRE_ORIGIN_HEADER && StringUtils.isEmpty(originHeaderValue)) {
            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Missing Origin header");
                return false;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if (REQUIRE_ORIGIN_MATCH && !StringUtils.isEmpty(originHeaderValue)) {
            var jenkinsRootUrl =
                    jenkins.model.JenkinsLocationConfiguration.get().getUrl();
            if (StringUtils.isEmpty(jenkinsRootUrl)) {
                // If Jenkins root URL is not configured, we cannot validate the Origin header
                return true;
            }

            String o = getRootUrlFromRequest(request);
            String removeSuffix1 = "/";
            if (o.endsWith(removeSuffix1)) {
                o = o.substring(0, o.length() - removeSuffix1.length());
            }
            String removeSuffix2 = request.getContextPath();
            if (o.endsWith(removeSuffix2)) {
                o = o.substring(0, o.length() - removeSuffix2.length());
            }
            final String expectedOrigin = o;

            if (!originHeaderValue.equals(expectedOrigin)) {
                log.debug("Rejecting origin: {}; expected was from request: {}", originHeaderValue, expectedOrigin);
                try {

                    response.sendError(
                            HttpServletResponse.SC_FORBIDDEN,
                            "Unexpected request origin (check your reverse proxy settings)");
                    return false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return true;
    }

    /**
     * Horrible copy/paste from {@link Jenkins} but this method in Jenkins is so dependent of Stapler#currentRequest
     * that it's not possible to call it from here.
     */
    private @NonNull String getRootUrlFromRequest(HttpServletRequest req) {

        StringBuilder buf = new StringBuilder();
        String scheme = getXForwardedHeader(req, "X-Forwarded-Proto", req.getScheme());
        buf.append(scheme).append("://");
        String host = getXForwardedHeader(req, "X-Forwarded-Host", req.getServerName());
        int index = host.lastIndexOf(':');
        int port = req.getServerPort();
        if (index == -1) {
            // Almost everyone else except Nginx put the host and port in separate headers
            buf.append(host);
        } else {
            if (host.startsWith("[") && host.endsWith("]")) {
                // support IPv6 address
                buf.append(host);
            } else {
                // Nginx uses the same spec as for the Host header, i.e. hostname:port
                buf.append(host, 0, index);
                if (index + 1 < host.length()) {
                    try {
                        port = Integer.parseInt(host.substring(index + 1));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
                // but if a user has configured Nginx with an X-Forwarded-Port, that will win out.
            }
        }
        String forwardedPort = getXForwardedHeader(req, "X-Forwarded-Port", null);
        if (forwardedPort != null) {
            try {
                port = Integer.parseInt(forwardedPort);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        if (port != ("https".equals(scheme) ? 443 : 80)) {
            buf.append(':').append(port);
        }
        buf.append(req.getContextPath()).append('/');
        return buf.toString();
    }

    private static String getXForwardedHeader(HttpServletRequest req, String header, String defaultValue) {
        String value = req.getHeader(header);
        if (value != null) {
            int index = value.indexOf(',');
            return index == -1 ? value.trim() : value.substring(0, index).trim();
        }
        return defaultValue;
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

    private boolean isSSERequest(HttpServletRequest request) {
        String requestedResource = getRequestedResourcePath(request);
        return requestedResource.startsWith("/" + MCP_SERVER_SSE)
                && request.getMethod().equalsIgnoreCase("GET");
    }

    private boolean isStreamableRequest(HttpServletRequest request) {
        String requestedResource = getRequestedResourcePath(request);
        return requestedResource.startsWith("/" + MCP_SERVER_STREAMABLE)
                && (request.getMethod().equalsIgnoreCase("GET")
                        || (request.getMethod().equalsIgnoreCase("POST")));
    }

    private boolean isStatelessRequest(HttpServletRequest request) {
        String requestedResource = getRequestedResourcePath(request);
        return requestedResource.startsWith("/" + MCP_SERVER_STATELESS)
                && (request.getMethod().equalsIgnoreCase("GET")
                        || (request.getMethod().equalsIgnoreCase("POST")));
    }

    private void handleSSE(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        httpServletSseServerTransportProvider.service(request, response);
    }

    private void handleMessage(HttpServletRequest request, HttpServletResponse response, HttpServlet httpServlet)
            throws IOException, ServletException {
        if (!validOriginHeader(request, response)) {
            return;
        }
        prepareMcpContext(request);
        httpServlet.service(request, response);
    }

    private void handleStatelessMessage(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        if (!validOriginHeader(request, response)) {
            return;
        }
        prepareMcpContext(request);
        httpServletStatelessServerTransport.service(request, response);
    }

    private static void prepareMcpContext(HttpServletRequest request) {
        Map<String, Object> contextMap = new HashMap<>();
        var currentUser = User.current();
        String userId = null;
        if (currentUser != null) {
            userId = currentUser.getId();
        }
        if (userId != null) {
            contextMap.put(USER_ID, userId);
        }
        contextMap.put(HTTP_SERVLET_REQUEST, request);
        request.setAttribute(MCP_CONTEXT_KEY, McpTransportContext.create(contextMap));
    }
}
