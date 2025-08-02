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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.*;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Streamable HTTP transport endpoint for MCP server.
 * This endpoint implements the new streamable transport protocol at /mcp.
 */
@Restricted(NoExternalUse.class)
@Extension
public class StreamableEndpoint extends CrumbExclusion implements McpStreamableServerTransportProvider {
    public static final String UTF_8 = "UTF-8";
    public static final String APPLICATION_JSON = "application/json";
    public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";
    public static final String MCP_SERVER = "mcp-server";
    public static final String MCP_ENDPOINT = "/mcp-stream";
    public static final String MCP_SERVER_MCP = MCP_SERVER + MCP_ENDPOINT;

    /**
     * Event type for regular messages
     */
    public static final String MESSAGE_EVENT_TYPE = "message";

    private static final Logger logger = LoggerFactory.getLogger(StreamableEndpoint.class);

    /**
     * JSON object mapper for serialization/deserialization
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Map of active client sessions, keyed by session ID
     */
    private final Map<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();

    /**
     * Flag indicating if the transport is in the process of shutting down
     */
    private final AtomicBoolean isClosing = new AtomicBoolean(false);

    /**
     * Session factory for creating new sessions
     */
    private McpStreamableServerSession.Factory sessionFactory;

    public StreamableEndpoint() throws ServletException {
        init();
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
        io.modelcontextprotocol.server.McpServer.sync(this)
                .capabilities(serverCapabilities)
                .tools(allTools)
                .prompts(prompts)
                .resources(resources)
                .build();

        try {
            PluginServletFilter.addFilter(new Filter() {
                @Override
                public void doFilter(
                        ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
                        throws IOException, ServletException {
                    if (isStreamableRequest(servletRequest, servletResponse)) {
                        // Handle streamable requests through the process method
                        if (servletRequest instanceof HttpServletRequest request && servletResponse instanceof HttpServletResponse response) {
                            if (!process(request, response, filterChain)) {
                                filterChain.doFilter(servletRequest, servletResponse);
                            }
                        } else {
                            filterChain.doFilter(servletRequest, servletResponse);
                        }
                    } else {
                        filterChain.doFilter(servletRequest, servletResponse);
                    }
                }

                @Override
                public void destroy() {
                    closeGracefully().block();
                }
            });
        } catch (ServletException e) {
            logger.error("Failed to register servlet filter", e);
            throw e;
        }
    }

    protected String getRequestedResourcePath(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            requestURI = requestURI.substring(contextPath.length());
        }
        return requestURI;
    }

    @Override
    public String protocolVersion() {
        // FIXME this should be done in sdk itself as returning "2024-11-05"
        // looks wrong for streamable
        // see https://github.com/modelcontextprotocol/java-sdk/pull/441
        return "2025-03-26";
    }

    @Override
    public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestedResource = getRequestedResourcePath(request);
        if (requestedResource.startsWith("/" + MCP_SERVER_MCP)
                && request.getMethod().equalsIgnoreCase("POST")) {
            handleStreamableMessage(request, response);
            return true;
        }
        if (requestedResource.startsWith("/" + MCP_SERVER_MCP)
                && request.getMethod().equalsIgnoreCase("GET")) {
            handleStreamableConnect(request, response);
            return true;
        }
        return false;
    }

    @Override
    public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

        return Mono.fromRunnable(() -> {
            sessions.values().parallelStream().forEach(session -> {
                try {
                    session.sendNotification(method, params).block();
                } catch (Exception e) {
                    logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
                }
            });
        });
    }

    boolean isStreamableRequest(ServletRequest servletRequest, ServletResponse servletResponse) {
        if (servletRequest instanceof HttpServletRequest request && servletResponse instanceof HttpServletResponse) {
            String requestedResource = getRequestedResourcePath(request);
            return requestedResource.startsWith("/" + MCP_SERVER_MCP);
        }
        return false;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            isClosing.set(true);
            logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

            sessions.values().parallelStream().forEach(session -> {
                try {
                    session.closeGracefully().block();
                } catch (Exception e) {
                    logger.error("Failed to close session {}: {}", session.getId(), e.getMessage());
                }
            });

            sessions.clear();
            logger.debug("Graceful shutdown completed");
        });
    }

    /**
     * Handles streamable SSE connection requests for message replay.
     */
    protected void handleStreamableConnect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isClosing.get()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
            return;
        }

        List<String> badRequestErrors = new ArrayList<>();

        String accept = request.getHeader("Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            badRequestErrors.add("text/event-stream required in Accept header");
        }

        String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
        if (sessionId == null || sessionId.isBlank()) {
            badRequestErrors.add("Session ID required in mcp-session-id header");
        }

        if (!badRequestErrors.isEmpty()) {
            String combinedMessage = String.join("; ", badRequestErrors);
            responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
            return;
        }

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        logger.debug("Handling streamable GET request for session: {}", sessionId);

        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding(UTF_8);
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            response.setHeader("Access-Control-Allow-Origin", "*");

            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(0);

            HttpServletStreamableMcpSessionTransport sessionTransport =
                    new HttpServletStreamableMcpSessionTransport(sessionId, asyncContext, response.getWriter());

            // Check if this is a replay request
            String lastEventId = request.getHeader("Last-Event-ID");
            if (lastEventId != null) {
                logger.debug("Handling replay request for session {} from event ID: {}", sessionId, lastEventId);
                try {
                    session.replay(lastEventId)
                            .toIterable()
                            .forEach(message -> {
                                try {
                                    sessionTransport.sendMessage(message).block();
                                } catch (Exception e) {
                                    logger.error("Failed to replay message: {}", e.getMessage());
                                    asyncContext.complete();
                                }
                            });
                } catch (Exception e) {
                    logger.error("Failed to replay messages: {}", e.getMessage());
                    asyncContext.complete();
                }
            } else {
                // Establish new listening stream
                logger.debug("Establishing new listening stream for session: {}", sessionId);
                var listeningStream = session.listeningStream(sessionTransport);

                // Set up cleanup when connection closes
                asyncContext.addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        logger.debug("SSE connection completed for session: {}", sessionId);
                        listeningStream.close();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        logger.debug("SSE connection timed out for session: {}", sessionId);
                        listeningStream.close();
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        logger.debug("SSE connection error for session: {}", sessionId);
                        listeningStream.close();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        // No action needed
                    }
                });
            }

            logger.info("Streamable MCP connection established for session {}", sessionId);

        } catch (Exception e) {
            logger.error("Failed to handle streamable GET request for session {}: {}", sessionId, e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Handles streamable message processing.
     */
    protected void handleStreamableMessage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isClosing.get()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
            return;
        }

        List<String> badRequestErrors = new ArrayList<>();

        String accept = request.getHeader("Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            badRequestErrors.add("text/event-stream required in Accept header");
        }
        if (accept == null || !accept.contains(APPLICATION_JSON)) {
            badRequestErrors.add("application/json required in Accept header");
        }

        try {
            BufferedReader reader = request.getReader();
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }

            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

            // Handle initialization request
            if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest
                    && jsonrpcRequest.method().equals(McpSchema.METHOD_INITIALIZE)) {
                handleInitializeRequest(jsonrpcRequest, request, response, badRequestErrors);
                return;
            }

            // Handle other messages with existing session
            String sessionId = request.getHeader(HttpHeaders.MCP_SESSION_ID);
            if (sessionId == null || sessionId.isBlank()) {
                badRequestErrors.add("Session ID required in mcp-session-id header");
            }

            if (!badRequestErrors.isEmpty()) {
                String combinedMessage = String.join("; ", badRequestErrors);
                responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
                return;
            }

            McpStreamableServerSession session = sessions.get(sessionId);
            if (session == null) {
                responseError(response, HttpServletResponse.SC_NOT_FOUND,
                        new McpError("Session not found: " + sessionId));
                return;
            }

            // Handle different message types
            if (message instanceof McpSchema.JSONRPCResponse jsonrpcResponse) {
                session.accept(jsonrpcResponse).block();
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
            } else if (message instanceof McpSchema.JSONRPCNotification jsonrpcNotification) {
                session.accept(jsonrpcNotification).block();
                response.setStatus(HttpServletResponse.SC_ACCEPTED);
            } else if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
                // For streaming responses, we need to return SSE
                handleStreamingRequest(jsonrpcRequest, sessionId, request, response);
            }

        } catch (Exception e) {
            logger.error("Error processing streamable MCP message", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /** Handles initialization requests for new sessions. */
    private void handleInitializeRequest(
            McpSchema.JSONRPCRequest jsonrpcRequest,
            HttpServletRequest request,
            HttpServletResponse response,
            List<String> badRequestErrors) throws IOException {

        if (!badRequestErrors.isEmpty()) {
            String combinedMessage = String.join("; ", badRequestErrors);
            responseError(response, HttpServletResponse.SC_BAD_REQUEST, new McpError(combinedMessage));
            return;
        }

        McpSchema.InitializeRequest initializeRequest = objectMapper.convertValue(
                jsonrpcRequest.params(), new TypeReference<>() {});

        McpStreamableServerSession.McpStreamableServerSessionInit init =
                sessionFactory.startSession(initializeRequest);
        sessions.put(init.session().getId(), init.session());

        try {
            McpSchema.InitializeResult initResult = init.initResult().block();

            response.setContentType(APPLICATION_JSON);
            response.setCharacterEncoding(UTF_8);
            response.setHeader(HttpHeaders.MCP_SESSION_ID, init.session().getId());
            response.setStatus(HttpServletResponse.SC_OK);

            String jsonResponse = objectMapper.writeValueAsString(new McpSchema.JSONRPCResponse(
                    McpSchema.JSONRPC_VERSION, jsonrpcRequest.id(), initResult, null));

            PrintWriter writer = response.getWriter();
            writer.write(jsonResponse);
            writer.flush();
        } catch (Exception e) {
            logger.error("Failed to initialize session: {}", e.getMessage());
            responseError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    new McpError("Failed to initialize session: " + e.getMessage()));
        }
    }

    /** Handles streaming requests that require SSE responses. */
    private void handleStreamingRequest(
            McpSchema.JSONRPCRequest jsonrpcRequest,
            String sessionId,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/event-stream");
        response.setCharacterEncoding(UTF_8);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);

        McpStreamableServerSession session = sessions.get(sessionId);
        if (session == null) {
            logger.error("Session not found for streaming request: {}", sessionId);
            asyncContext.complete();
            return;
        }

        HttpServletStreamableMcpSessionTransport sessionTransport =
                new HttpServletStreamableMcpSessionTransport(sessionId, asyncContext, response.getWriter());

        // Process the request and stream the response
        try {
            session.responseStream(jsonrpcRequest, sessionTransport)
                    .doOnError(error -> {
                        logger.error("Error processing streaming request: {}", error.getMessage());
                        sessionTransport.close();
                    })
                    .doFinally(signalType -> {
                        logger.debug("Streaming request processing completed for session: {} with signal: {}", sessionId, signalType);
                    })
                    .subscribe();

        } catch (Exception e) {
            logger.error("Failed to process streaming request for session {}: {}", sessionId, e.getMessage());
            sessionTransport.close();
        }

        logger.debug("Handling streaming request for session: {}", sessionId);
    }

    /** Sends an error response. */
    private void responseError(HttpServletResponse response, int statusCode, McpError error) {
        try {
            response.setContentType(APPLICATION_JSON);
            response.setCharacterEncoding(UTF_8);
            response.setStatus(statusCode);

            String errorJson = objectMapper.writeValueAsString(error);
            response.getWriter().write(errorJson);
            response.getWriter().flush();
        } catch (IOException e) {
            logger.error("Failed to send error response: {}", e.getMessage());
        }
    }

    /**
     * Sends an SSE event to a client with a specific ID.
     */
    private void sendEvent(PrintWriter writer, String eventType, String data, String id) throws IOException {
        if (id != null) {
            writer.write("id: " + id + "\n");
        }
        writer.write("event: " + eventType + "\n");
        writer.write("data: " + data + "\n\n");
        writer.flush();

        if (writer.checkError()) {
            throw new IOException("Client disconnected");
        }
    }

    /**
     * Inner class that implements the streamable session transport for HTTP servlet.
     */
    private class HttpServletStreamableMcpSessionTransport implements McpStreamableServerTransport {
        private final String sessionId;
        private final AsyncContext asyncContext;
        private final PrintWriter writer;
        private volatile boolean closed = false;

        public HttpServletStreamableMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
            this.sessionId = sessionId;
            this.asyncContext = asyncContext;
            this.writer = writer;
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return sendMessage(message, null);
        }

        @Override
        public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
            return Mono.fromRunnable(() -> {
                if (closed) {
                    logger.debug("Session {} was closed during message send attempt", sessionId);
                    return;
                }

                try {
                    String finalMessageId = messageId;
                    if (finalMessageId == null) {
                        if (message instanceof McpSchema.JSONRPCRequest request) {
                            finalMessageId = request.id() != null ? request.id().toString() : null;
                        } else if (message instanceof McpSchema.JSONRPCResponse response) {
                            finalMessageId = response.id() != null ? response.id().toString() : null;
                        }
                    }

                    String jsonText = objectMapper.writeValueAsString(message);
                    StreamableEndpoint.this.sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText, finalMessageId != null ? finalMessageId : sessionId);
                    logger.debug("Message sent to session {} with ID {}", sessionId, finalMessageId);
                } catch (Exception e) {
                    logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
                    close();
                }
            });
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(this::close);
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    asyncContext.complete();
                } catch (Exception e) {
                    logger.debug("Error completing async context for session {}: {}", sessionId, e.getMessage());
                }
            }
        }
    }
}
