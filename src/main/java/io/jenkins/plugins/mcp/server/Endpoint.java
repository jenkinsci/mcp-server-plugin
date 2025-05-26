/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2025, Gong Yi.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.PluginServletFilter;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.modelcontextprotocol.spec.McpSchema.METHOD_INITIALIZE;

/**
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
@Restricted(NoExternalUse.class)
@Extension
public class Endpoint extends CrumbExclusion implements RootAction, McpServerTransportProvider {
	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";
	public static final String MCP_SERVER = "mcp-server";

	public static final String SSE_ENDPOINT = "/sse";

	public static final String MCP_SERVER_SSE = MCP_SERVER + SSE_ENDPOINT;
	/**
	 * Event type for regular messages
	 */
	public static final String MESSAGE_EVENT_TYPE = "message";
	/**
	 * Event type for endpoint information
	 */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";
	/**
	 * The endpoint path for handling client messages
	 */
	private static final String MESSAGE_ENDPOINT = "/message";
	public static final String MCP_SERVER_MESSAGE = MCP_SERVER + MESSAGE_ENDPOINT;
	private static final Logger logger = LoggerFactory.getLogger(Endpoint.class);
	/**
	 * JSON object mapper for serialization/deserialization
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();
	/**
	 * Map of active client sessions, keyed by session ID
	 */
	private final Map<String, SessionObject> sessions = new ConcurrentHashMap<>();
	private final Map<String, User> userSessions = new ConcurrentHashMap<>();

	/**
	 * Flag indicating if the transport is in the process of shutting down
	 */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);
	/**
	 * Session factory for creating new sessions
	 */
	private McpServerSession.Factory sessionFactory;

	public Endpoint() throws ServletException {

		init();
	}

	public static String getRequestedResourcePath(HttpServletRequest httpServletRequest) {
		return httpServletRequest.getRequestURI().substring(httpServletRequest.getContextPath().length());
	}

	@Override
	public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
		String requestedResource = getRequestedResourcePath(request);
		if (requestedResource.startsWith("/" + MCP_SERVER_MESSAGE) && request.getMethod().equalsIgnoreCase("POST")) {
			handleMessage(request, response);
			return true; // Do not allow this request on to Stapler
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

		var tools = extensions.<McpServerExtension>stream()
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
						.map(method ->
								new McpToolWrapper(objectMapper, extension, method).asSyncToolSpecification()
						)

				)
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


		PluginServletFilter.addFilter(new Filter() {
			@Override
			public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
				if (isSSERequest(servletRequest, servletResponse)) {
					handleSSE((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse);
				} else {
					filterChain.doFilter(servletRequest, servletResponse);
				}
			}

			/**
			 * Cleans up resources when the servlet is being destroyed.
			 * <p>
			 * This method ensures a graceful shutdown by closing all client connections before
			 * calling the parent's destroy method.
			 */
			@Override
			public void destroy() {
				closeGracefully().block();

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

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	/**
	 * Broadcasts a notification to all connected clients.
	 *
	 * @param method The method name for the notification
	 * @param params The parameters for the notification
	 * @return A Mono that completes when the broadcast attempt is finished
	 */
	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values())
				.flatMap(sessionObject -> sessionObject.session.sendNotification(method, params)
						.doOnError(
								e -> logger.error("Failed to send message to session {}: {}", sessionObject.session.getId(), e.getMessage()))
						.onErrorComplete())
				.then();
	}

	boolean isSSERequest(ServletRequest servletRequest, ServletResponse servletResponse) {
		if (servletRequest instanceof HttpServletRequest request && servletResponse instanceof HttpServletResponse) {
			String requestedResource = getRequestedResourcePath(request);
			return requestedResource.startsWith("/" + MCP_SERVER_SSE) && request.getMethod().equalsIgnoreCase("GET");
		}
		return false;
	}


	/**
	 * Handles GET requests to establish SSE connections.
	 * <p>
	 * This method sets up a new SSE connection when a client connects to the SSE
	 * endpoint. It configures the response headers for SSE, creates a new session, and
	 * sends the initial endpoint information to the client.
	 *
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws IOException If an I/O error occurs
	 */

	protected void handleSSE(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		String sessionId = UUID.randomUUID().toString();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);

		PrintWriter writer = response.getWriter();

		// Create a new session transport
		HttpServletMcpSessionTransport sessionTransport = new HttpServletMcpSessionTransport(sessionId, asyncContext,
				writer

		);

		// Create a new session using the session factory
		McpServerSession session = sessionFactory.create(sessionTransport);
		var currentUser=User.current();
		String userId=null;
		if (currentUser!= null) {
			userId=currentUser.getId();
        }
		this.sessions.put(sessionId,new SessionObject( session ,userId));
		var rootUrl = jenkins.model.JenkinsLocationConfiguration.get().getUrl();
		if (rootUrl == null) {
			rootUrl = "/";
		}
		if (!rootUrl.endsWith("/")) {
			rootUrl = rootUrl + "/";
		}

		// Send initial endpoint event
		this.sendEvent(writer, ENDPOINT_EVENT_TYPE, rootUrl + MCP_SERVER_MESSAGE + "?sessionId=" + sessionId);
	}

	/**
	 * Handles POST requests for client messages.
	 * <p>
	 * This method processes incoming messages from clients, routes them through the
	 * session handler, and sends back the appropriate response. It handles error cases
	 * and formats error responses according to the MCP specification.
	 *
	 * @param request  The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws IOException If an I/O error occurs
	 */

	protected void handleMessage(HttpServletRequest request, HttpServletResponse response) throws IOException {

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String requestURI = request.getRequestURI();
		if (!requestURI.endsWith(MESSAGE_ENDPOINT)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		// Get the session ID from the request parameter
		String sessionId = request.getParameter("sessionId");
		if (sessionId == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session ID missing in message endpoint"));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		// Get the session from the sessions map
		var sessionObject = sessions.get(sessionId);
		if (sessionObject == null) {
			response.setContentType(APPLICATION_JSON);
			response.setCharacterEncoding(UTF_8);
			response.setStatus(HttpServletResponse.SC_NOT_FOUND);
			String jsonError = objectMapper.writeValueAsString(new McpError("Session not found: " + sessionId));
			PrintWriter writer = response.getWriter();
			writer.write(jsonError);
			writer.flush();
			return;
		}

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			if (message instanceof McpSchema.JSONRPCRequest jsonrpcRequest ){
				if ( sessionObject.userId!=null && !jsonrpcRequest.method().equals(METHOD_INITIALIZE) && jsonrpcRequest.params() instanceof Map params) {
					Map arguments= (Map) params.get("arguments");
					if (arguments!=null){
						arguments.put("userId", sessionObject.userId);
					}
				}
			}


			// Process the message through the session's handle method
			sessionObject.session.handle(message).block(); // Block for Servlet compatibility

			response.setStatus(HttpServletResponse.SC_OK);
		} catch (Exception e) {
			logger.error("Error processing message: {}", e.getMessage());
			try {
				McpError mcpError = new McpError(e.getMessage());
				response.setContentType(APPLICATION_JSON);
				response.setCharacterEncoding(UTF_8);
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				String jsonError = objectMapper.writeValueAsString(mcpError);
				PrintWriter writer = response.getWriter();
				writer.write(jsonError);
				writer.flush();
			} catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing message");
			}
		}
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * <p>
	 * This method marks the transport as closing and closes all active client sessions.
	 * New connection attempts will be rejected during shutdown.
	 *
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Flux.fromIterable(sessions.values()).flatMap(sessionObject-> sessionObject.session.closeGracefully()).then();
	}

	/**
	 * Sends an SSE event to a client.
	 *
	 * @param writer    The writer to send the event through
	 * @param eventType The type of event (message or endpoint)
	 * @param data      The event data
	 * @throws IOException If an error occurs while writing the event
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();
		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}


	/**
	 * Implementation of McpServerTransport for HttpServlet SSE sessions. This class
	 * handles the transport-level communication for a specific client session.
	 */
	private class HttpServletMcpSessionTransport implements McpServerTransport {

		private final String sessionId;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		/**
		 * Creates a new session transport with the specified ID and SSE writer.
		 *
		 * @param sessionId    The unique identifier for this session
		 * @param asyncContext The async context for the session
		 * @param writer       The writer for sending server events to the client
		 */
		HttpServletMcpSessionTransport(String sessionId, AsyncContext asyncContext, PrintWriter writer) {
			this.sessionId = sessionId;
			this.asyncContext = asyncContext;
			this.writer = writer;
			logger.debug("Session transport {} initialized with SSE writer", sessionId);
		}



		/**
		 * Sends a JSON-RPC message to the client through the SSE connection.
		 *
		 * @param message The JSON-RPC message to send
		 * @return A Mono that completes when the message has been sent
		 */
		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.fromRunnable(() -> {
				try {
					String jsonText = objectMapper.writeValueAsString(message);
					sendEvent(writer, MESSAGE_EVENT_TYPE, jsonText);
					logger.debug("Message sent to session {}", sessionId);
				} catch (Exception e) {
					logger.error("Failed to send message to session {}: {}", sessionId, e.getMessage());
					sessions.remove(sessionId);
					asyncContext.complete();
				}
			});
		}

		/**
		 * Converts data from one type to another using the configured ObjectMapper.
		 *
		 * @param data    The source data object to convert
		 * @param typeRef The target type reference
		 * @param <T>     The target type
		 * @return The converted object of type T
		 */
		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		/**
		 * Initiates a graceful shutdown of the transport.
		 *
		 * @return A Mono that completes when the shutdown is complete
		 */
		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				logger.debug("Closing session transport: {}", sessionId);
				try {
					sessions.remove(sessionId);
					asyncContext.complete();
					logger.debug("Successfully completed async context for session {}", sessionId);
				} catch (Exception e) {
					logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
				}
			});
		}

		/**
		 * Closes the transport immediately.
		 */
		@Override
		public void close() {
			try {
				sessions.remove(sessionId);
				asyncContext.complete();
				logger.debug("Successfully completed async context for session {}", sessionId);
			} catch (Exception e) {
				logger.warn("Failed to complete async context for session {}: {}", sessionId, e.getMessage());
			}
		}

	}


	record  SessionObject( McpServerSession session, String userId) {}
}
