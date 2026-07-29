/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Olivier Lamy.
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

import hudson.ExtensionComponent;
import hudson.ExtensionList;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class ToolOverrideTest {

    static final String OVERRIDDEN_BUILD_LOG_DESCRIPTION = "Overridden getBuildLog from a separate extension";
    static final String OVERRIDDEN_BUILD_LOG_RESULT = "overridden-build-log-result";
    static final String PROGRAMMATIC_TOOL_NAME = "customProgrammaticTool";
    static final String PROGRAMMATIC_OVERRIDE_RESULT = "from-annotation-override";

    @McpClientTest
    void overrideReplacesBuiltinTool(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var getBuildLogTools = client.listTools().tools().stream()
                    .filter(tool -> "getBuildLog".equals(tool.name()))
                    .toList();

            // The built-in getBuildLog is replaced, so the name appears exactly once.
            assertThat(getBuildLogTools).hasSize(1);
            assertThat(getBuildLogTools.get(0).description()).isEqualTo(OVERRIDDEN_BUILD_LOG_DESCRIPTION);

            var response = client.callTool(new McpSchema.CallToolRequest("getBuildLog", Map.of()));
            assertThat(response.isError()).isFalse();
            assertThat(response.content())
                    .first()
                    .isInstanceOfSatisfying(
                            McpSchema.TextContent.class,
                            textContent -> assertThat(textContent.text()).contains(OVERRIDDEN_BUILD_LOG_RESULT));
        }
    }

    @McpClientTest
    void duplicateWithoutOverrideKeepsBuiltinTool(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var searchBuildLogTools = client.listTools().tools().stream()
                    .filter(tool -> "searchBuildLog".equals(tool.name()))
                    .toList();

            // The duplicate does not declare override=true, so the built-in tool is kept (name listed once).
            assertThat(searchBuildLogTools).hasSize(1);
            assertThat(searchBuildLogTools.get(0).description()).contains("Search for log lines matching a pattern");
        }
    }

    @McpClientTest
    void programmaticToolOverriddenByAnnotation(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var tools = client.listTools().tools().stream()
                    .filter(tool -> PROGRAMMATIC_TOOL_NAME.equals(tool.name()))
                    .toList();

            assertThat(tools).hasSize(1);

            var response = client.callTool(new McpSchema.CallToolRequest(PROGRAMMATIC_TOOL_NAME, Map.of()));
            assertThat(response.isError()).isFalse();
            assertThat(response.content())
                    .first()
                    .isInstanceOfSatisfying(
                            McpSchema.TextContent.class,
                            textContent -> assertThat(textContent.text()).contains(PROGRAMMATIC_OVERRIDE_RESULT));
        }
    }

    @McpClientTest
    void noDuplicateToolNamesAreExposed(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) {
        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            var names = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name)
                    .toList();
            assertThat(names).doesNotHaveDuplicates();
        }
    }

    @Test
    @WithJenkins
    void highestOrdinalWinsAmongOverrides(JenkinsRule jenkins) {
        var endpoint = ExtensionList.lookupSingleton(Endpoint.class);

        var lowOrdinal = new ExtensionComponent<McpServerExtension>(new LowOrdinalOverride(), 1d);
        var highOrdinal = new ExtensionComponent<McpServerExtension>(new HighOrdinalOverride(), 5d);

        var winners = endpoint.resolveTools(List.of(lowOrdinal, highOrdinal));

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).name()).isEqualTo("tieTool");
        assertThat(winners.get(0).description()).contains(HighOrdinalOverride.class.getName());
    }

    @Test
    @WithJenkins
    void overrideBeatsNonOverrideRegardlessOfOrdinal(JenkinsRule jenkins) {
        var endpoint = ExtensionList.lookupSingleton(Endpoint.class);

        // The non-override base has a much higher ordinal but must still lose to the explicit override.
        var base = new ExtensionComponent<McpServerExtension>(new HighOrdinalBase(), 100d);
        var override = new ExtensionComponent<McpServerExtension>(new LowOrdinalOverride(), 1d);

        var winners = endpoint.resolveTools(List.of(base, override));

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).override()).isTrue();
        assertThat(winners.get(0).description()).contains(LowOrdinalOverride.class.getName());
    }

    @TestExtension
    public static class OverridingBuildLogExtension implements McpServerExtension {
        @Tool(name = "getBuildLog", description = OVERRIDDEN_BUILD_LOG_DESCRIPTION, override = true)
        public String overriddenGetBuildLog() {
            return OVERRIDDEN_BUILD_LOG_RESULT;
        }
    }

    @TestExtension
    public static class DuplicateSearchBuildLogExtension implements McpServerExtension {
        @Tool(name = "searchBuildLog", description = "Duplicate searchBuildLog without override")
        public String duplicateSearchBuildLog() {
            return "should-not-win";
        }
    }

    @TestExtension
    public static class ProgrammaticToolExtension implements McpServerExtension {
        @Override
        public List<McpServerFeatures.SyncToolSpecification> getSyncTools() {
            var tool = McpSchema.Tool.builder()
                    .name(PROGRAMMATIC_TOOL_NAME)
                    .description("Programmatic base tool")
                    .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                    .build();
            return List.of(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                            .addTextContent("from-programmatic")
                            .build())
                    .build());
        }
    }

    @TestExtension
    public static class ProgrammaticToolOverrideExtension implements McpServerExtension {
        @Tool(name = PROGRAMMATIC_TOOL_NAME, description = "Annotation override of programmatic tool", override = true)
        public String overrideProgrammaticTool() {
            return PROGRAMMATIC_OVERRIDE_RESULT;
        }
    }

    public static class LowOrdinalOverride implements McpServerExtension {
        @Tool(name = "tieTool", override = true)
        public String tieTool() {
            return "low";
        }
    }

    public static class HighOrdinalOverride implements McpServerExtension {
        @Tool(name = "tieTool", override = true)
        public String tieTool() {
            return "high";
        }
    }

    public static class HighOrdinalBase implements McpServerExtension {
        @Tool(name = "tieTool")
        public String tieTool() {
            return "base";
        }
    }
}
