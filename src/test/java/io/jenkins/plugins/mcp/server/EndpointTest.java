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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static io.jenkins.plugins.mcp.server.Endpoint.MCP_SERVER_SSE;
import static org.assertj.core.api.Assertions.assertThat;

@WithJenkins
class EndpointTest {
	@Test
	void testMcpToolCallGetBuild(JenkinsRule jenkins) throws Exception {
		WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
		project.setDefinition(new CpsFlowDefinition("", true));
		var build = project.scheduleBuild2(0).get();

		var url = jenkins.getURL();
		var baseUrl = url.toString();


		var transport = HttpClientSseClientTransport.builder(baseUrl)
				.sseEndpoint(MCP_SERVER_SSE)
				.build();


		try (var client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(500))
				.capabilities(McpSchema.ClientCapabilities.builder()
						.build())
				.build()) {
			client.initialize();
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getBuild",
					Map.of("jobFullName", project.getFullName()));

			var response = client.callTool(request);
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0).type()).isEqualTo("text");
			assertThat(response.content()).first()
					.isInstanceOfSatisfying(McpSchema.TextContent.class,
							textContent -> {
								assertThat(textContent.type()).isEqualTo("text");

								ObjectMapper objectMapper = new ObjectMapper();
								try {
									var contetMap = objectMapper.readValue(textContent.text(), Map.class);
									assertThat(contetMap).extractingByKey("result").isEqualTo("SUCCESS");
									assertThat(contetMap).extractingByKey("number").isEqualTo(build.getNumber());

								} catch (JsonProcessingException e) {
									throw new RuntimeException(e);
								}
							});

		}

	}


	@Test
	void testMcpToolCallGetAllProjects(JenkinsRule jenkins) throws Exception {
		enableSecurity(jenkins);
		WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");

		project.setDefinition(new CpsFlowDefinition("", true));
		var build = project.scheduleBuild2(0).get();

		var url = jenkins.getURL();
		var baseUrl = url.toString();

		String username = "admin";
		String password = "admin";
		String authString = username + ":" + password;
		String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());

		var transport = HttpClientSseClientTransport.builder(baseUrl)
				.sseEndpoint(MCP_SERVER_SSE)
				.customizeRequest(request -> {
					request.setHeader("Authorization", "Basic " + encodedAuth);

				})
				.build();


		try (var client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(500))
				.capabilities(McpSchema.ClientCapabilities.builder()
						.build())
				.build()) {
			client.initialize();
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getAllJobs",
					Map.of());

			var response = client.callTool(request);
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0).type()).isEqualTo("text");
			assertThat(response.content()).first()
					.isInstanceOfSatisfying(McpSchema.TextContent.class,
							textContent -> {
								assertThat(textContent.type()).isEqualTo("text");

								ObjectMapper objectMapper = new ObjectMapper();
								try {
									var contetMap = objectMapper.readValue(textContent.text(), Map.class);
									assertThat(contetMap).extractingByKey("nextBuildNumber").isEqualTo(project.getNextBuildNumber());

								} catch (JsonProcessingException e) {
									throw new RuntimeException(e);
								}
							});

		}

	}

	@Test
	void testMcpToolCallSimpleJson(JenkinsRule jenkins) throws Exception {

		var url = jenkins.getURL();
		var baseUrl = url.toString();


		var transport = HttpClientSseClientTransport.builder(baseUrl)
				.sseEndpoint(MCP_SERVER_SSE)
				.build();


		try (var client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(500))
				.capabilities(McpSchema.ClientCapabilities.builder()
						.build())
				.build()) {
			client.initialize();
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("sayHello",
					Map.of("name", "foo"));

			var response = client.callTool(request);
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0).type()).isEqualTo("text");
			assertThat(response.content()).first()
					.isInstanceOfSatisfying(McpSchema.TextContent.class,
							textContent -> {
								assertThat(textContent.type()).isEqualTo("text");

								ObjectMapper objectMapper = new ObjectMapper();
								try {
									var contetMap = objectMapper.readValue(textContent.text(), Map.class);
									assertThat(contetMap).extractingByKey("message").isEqualTo("Hello, foo!");

								} catch (JsonProcessingException e) {
									throw new RuntimeException(e);
								}
							});

		}

	}


	@Test
	void testMcpToolCallIntResult(JenkinsRule jenkins) throws Exception {

		var url = jenkins.getURL();
		var baseUrl = url.toString();


		var transport = HttpClientSseClientTransport.builder(baseUrl)
				.sseEndpoint(MCP_SERVER_SSE)
				.build();


		try (var client = McpClient.sync(transport)
				.requestTimeout(Duration.ofSeconds(500))
				.capabilities(McpSchema.ClientCapabilities.builder()
						.build())
				.build()) {
			client.initialize();
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("testInt",
					Map.of());

			var response = client.callTool(request);
			assertThat(response.isError()).isFalse();
			assertThat(response.content()).hasSize(1);
			assertThat(response.content().get(0).type()).isEqualTo("text");
			assertThat(response.content()).first()
					.isInstanceOfSatisfying(McpSchema.TextContent.class,
							textContent -> {
								assertThat(textContent.type()).isEqualTo("text");

								ObjectMapper objectMapper = new ObjectMapper();
								try {
									var result = objectMapper.readValue(textContent.text(), Integer.class);
									assertThat(result).isEqualTo(10);

								} catch (JsonProcessingException e) {
									throw new RuntimeException(e);
								}
							});

		}

	}

	private void enableSecurity(JenkinsRule jenkins) throws Exception {
		JenkinsRule.DummySecurityRealm securityRealm = jenkins.createDummySecurityRealm();
		jenkins.jenkins.setSecurityRealm(securityRealm);
		// Create a user


		var authStrategy = new FullControlOnceLoggedInAuthorizationStrategy();
		authStrategy.setAllowAnonymousRead(false);
		jenkins.jenkins.setAuthorizationStrategy(authStrategy);

		jenkins.jenkins.save();
	}
}
