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

import static io.jenkins.plugins.mcp.server.junit.TestUtils.findToolByName;
import static org.assertj.core.api.Assertions.assertThat;

import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class StructuredOutputTest {
    @McpClientTest
    void testIntStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "intWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("intWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isEqualTo(1);
        }
    }

    @McpClientTest
    void testMapStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "mapWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("mapWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isInstanceOfSatisfying(Map.class, value -> assertThat(value)
                    .containsEntry("key1", "value1"));
        }
    }

    @McpClientTest
    void testMapAsNullStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "mapAsNullWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("mapAsNullWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isNull();
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testCollectionWithStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "collectionWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("collectionWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isInstanceOfSatisfying(List.class, value -> assertThat(value)
                    .contains("item1", "item2"));
        }
    }

    @McpClientTest
    void testCollectionAsNullWithStructuredOutput(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "collectionAsNullWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("collectionAsNullWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isNull();
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testGenericObjectWithStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "genericObjectWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("genericObjectWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isInstanceOfSatisfying(Map.class, value -> assertThat(value)
                    .containsEntry("name", "John Doe")
                    .containsEntry("age", 30));
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testCustomObjectWithStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "customObjectWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("customObjectWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            assertThat(response.structuredContent()).isInstanceOfSatisfying(Map.class, value -> assertThat(value)
                    .containsEntry("name", "John Doe")
                    .containsEntry("age", 30));
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testNestedObjectWithStructuredOutput(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "nestedObjectWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("nestedObjectWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            Map<String, Object> nestedObject = (Map<String, Object>) response.structuredContent();
            var childObject = (Map<String, Object>) nestedObject.get("child");
            assertThat(childObject).containsEntry("name", "Child").containsEntry("age", 20);
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testNestedObjectChildNullWithStructuredOutput(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "nestedObjectChildNullWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("nestedObjectChildNullWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            Map<String, Object> nestedObject = (Map<String, Object>) response.structuredContent();
            assertThat(nestedObject).containsEntry("child", null);
        }
    }

    @McpClientTest
    @SuppressWarnings("unchecked")
    void testSelfNestedObjectWithStructuredOutput(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            client.getServerCapabilities();
            var tool = findToolByName(client.listTools(), "selfNestedObjectWithStructuredOutput");
            assertThat(tool.outputSchema()).isNotNull();

            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("selfNestedObjectWithStructuredOutput", Map.of());
            var response = client.callTool(request);
            Map<String, Object> nestedObject = (Map<String, Object>) response.structuredContent();
            var childObject = (Map<String, Object>) nestedObject.get("ref");
            assertThat(childObject).containsEntry("name", "item2").containsEntry("ref", null);
        }
    }
}
