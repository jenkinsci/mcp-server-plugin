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

package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.extensions.DefaultMcpServer.FULL_NAME;
import static io.jenkins.plugins.mcp.server.junit.TestUtils.enableSecurity;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.jenkins.plugins.mcp.server.tool.ToolResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DefaultMcpServerTest {
    static final int MIN_1 = 60 * 1000;

    public static Stream<Arguments> whoAmITestParameters() {
        Stream<Arguments> baseArgs = Stream.of(Arguments.of(true, "admin"), Arguments.of(false, "anonymous"));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @McpClientTest
    void testMcpToolCallGetBuild(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getBuild", Map.of("jobFullName", project.getFullName()));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                DocumentContext documentContext =
                        JsonPath.using(Configuration.defaultConfiguration()).parse(textContent.text());
                var contentMap = documentContext.read("$.result", Map.class);
                assertThat(contentMap).extractingByKey("result").isEqualTo("SUCCESS");
                assertThat(contentMap).extractingByKey("number").isEqualTo(build.getNumber());
            });
        }
    }

    static Stream<Arguments> triggerSecurityTestParameters() {
        Stream<Arguments> baseArgs = Stream.of(
                // security enable, no auth -> no, AccessDenied
                //                Arguments.of(true, false, true, "AccessDenied", false),
                // security enable, auth -> no, triggered
                Arguments.of(true, true, false, "COMPLETED", true),
                // security not enable, no auth -> run triggered yeah freedom!
                Arguments.of(false, false, false, "COMPLETED", true),
                // security not enable, auth -> run triggered root is the king
                Arguments.of(false, true, false, "COMPLETED", true));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @ParameterizedTest
    @MethodSource("triggerSecurityTestParameters")
    // @DisabledOnOs(OS.WINDOWS)
    void testMcpToolCallTriggerBuild(
            Boolean enableSecurity,
            Boolean runAsAdmin,
            Boolean isError,
            String expectedText,
            Boolean expectedBuildRunned,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        if (enableSecurity) {
            enableSecurity(jenkins);
        }
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var nextNumber = project.getNextBuildNumber();
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(request -> {
                    if (runAsAdmin) {
                        String username = "admin";
                        String password = "admin";
                        String authString = username + ":" + password;
                        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                        request.setHeader("Authorization", "Basic " + encodedAuth);
                    }
                })
                .build()) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("triggerBuild", Map.of("jobFullName", project.getFullName()));

            var response = client.callTool(request);

            assertThat(response.isError()).isEqualTo(isError);
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(expectedText);
            });
            if (!expectedBuildRunned) {
                jenkins.waitUntilNoActivityUpTo(MIN_1);
                assertThat(project.getLastBuild()).isNull();
            } else {
                await().atMost(10, SECONDS)
                        .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
                await().atMost(10, SECONDS)
                        .untilAsserted(() ->
                                assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
                assertThat(project.getLastBuild().getNumber()).isEqualTo(nextNumber);
            }
        }
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    @McpClientTest
    void testMcpToolCallTriggerBuildWithParameters(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("STRING_P", "default1", "Parameter 1"),
                new BooleanParameterDefinition("BOOLEAN_P", false, "Parameter 2"),
                new ChoiceParameterDefinition("CHOICE_P", new String[] {"option1", "option2"}, "Parameter 3")));

        project.setDefinition(
                new CpsFlowDefinition("echo env.STRING_P \n" + "echo env.BOOLEAN_P \n" + "echo env.CHOICE_P", true));
        var nextNumber = project.getNextBuildNumber();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "triggerBuild",
                    Map.of(
                            "jobFullName",
                            project.getFullName(),
                            "parameters",
                            Map.of("STRING_P", "string_value", "BOOLEAN_P", false, "CHOICE_P", "option2")));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains("true");
            });
        }
        await().atMost(10, SECONDS)
                .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
        await().atMost(10, SECONDS)
                .untilAsserted(
                        () -> assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
        assertThat(project.getLastBuild().getNumber()).isEqualTo(nextNumber);
        assertThat(project.getLastBuild().getLog()).contains(List.of("string_value", "false", "option2"));
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    @McpClientTest
    void testMcpToolCallTriggerBuildWithPartialParameters(
            JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder) throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("STRING_P", "default1", "Parameter 1"),
                new BooleanParameterDefinition("BOOLEAN_P", false, "Parameter 2"),
                new ChoiceParameterDefinition("CHOICE_P", new String[] {"option1", "option2"}, "Parameter 3")));

        project.setDefinition(
                new CpsFlowDefinition("echo env.STRING_P \n" + "echo env.BOOLEAN_P \n" + "echo env.CHOICE_P", true));
        var nextNumber = project.getNextBuildNumber();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "triggerBuild",
                    Map.of(
                            "jobFullName",
                            project.getFullName(),
                            "parameters",
                            Map.of("BOOLEAN_P", false, "CHOICE_P", "option2")));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains("true");
            });
        }
        await().atMost(10, SECONDS)
                .untilAsserted(() -> assertThat(project.getLastBuild()).isNotNull());
        await().atMost(10, SECONDS)
                .untilAsserted(
                        () -> assertThat(project.getLastBuild().getResult()).isEqualTo(Result.SUCCESS));
        assertThat(project.getLastBuild().getNumber()).isEqualTo(nextNumber);
        assertThat(project.getLastBuild().getLog()).contains(List.of("default1", "false", "option2"));
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    @McpClientTest
    void testMcpToolCallGetJobNotExist(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        project.scheduleBuild2(0).get();

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getJob", Map.of("jobFullName", "non-exist-job-name"));

            var response = client.callTool(request);
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(ToolResponse.NO_DATA_MSG);
            });
        }
    }

    @McpClientTest
    void testMcpToolCallGetJobsWithAuth(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        enableSecurity(jenkins);
        for (int i = 0; i < 2; i++) {
            jenkins.createProject(WorkflowJob.class, "demo-job" + i);
        }

        var folder = jenkins.createFolder("test");

        for (int i = 0; i < 20; i++) {
            folder.createProject(WorkflowJob.class, "sub-demo-job" + i);
        }
        String username = "admin";
        String password = "admin";
        String authString = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(request -> request.setHeader("Authorization", "Basic " + encodedAuth))
                .build()) {
            {
                McpSchema.CallToolRequest request =
                        new McpSchema.CallToolRequest("getJobs", Map.of("parentFullName", "test", "limit", 10));

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                        .parse(((McpSchema.TextContent) response.content().get(0)).text());
                var contentList = documentContext.read("$.result", List.class);
                assertThat(contentList).hasSize(10);
                assertThat(contentList.get(0)).isInstanceOfSatisfying(Map.class, contentMap -> {
                    // var contentMap = objectMapper.readValue(textContent.text(), Map.class);
                    assertThat(contentMap).containsEntry("name", "sub-demo-job0");
                });
            }
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getJobs", Map.of());

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                        .parse(((McpSchema.TextContent) response.content().get(0)).text());
                var contentList = documentContext.read("$.result", List.class);
                assertThat(contentList).hasSize(3);
                assertThat(contentList.get(0)).isInstanceOfSatisfying(Map.class, contentMap -> {
                    // var contentMap = objectMapper.readValue(textContent.text(), Map.class);
                    assertThat(contentMap).containsEntry("name", "demo-job0");
                });
            }
        }
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    @ParameterizedTest
    @MethodSource("whoAmITestParameters")
    @WithJenkins
    void testMcpToolCallWhoAmI(
            boolean enableSecurity,
            String expectedUser,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        if (enableSecurity) {
            enableSecurity(jenkins);
        }
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(request -> {
                    if (enableSecurity) {
                        String username = "admin";
                        String password = "admin";
                        String authString = username + ":" + password;
                        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
                        request.setHeader("Authorization", "Basic " + encodedAuth);
                    }
                })
                .build()) {
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("whoAmI", Map.of());

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content()).hasSize(1);
                assertThat(response.content().get(0).type()).isEqualTo("text");

                assertThat(response.content())
                        .first()
                        .isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                            assertThat(textContent.type()).isEqualTo("text");

                            DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                                    .parse(textContent.text());

                            var contentMap = documentContext.read("$.result", Map.class);

                            assertThat(contentMap).containsEntry(FULL_NAME, expectedUser);
                        });
            }
        }
    }

    @McpClientTest
    void testMcpToolCallGetStatusWithAuth(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        enableSecurity(jenkins);
        String username = "admin";
        String password = "admin";
        String authString = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer(request -> request.setHeader("Authorization", "Basic " + encodedAuth))
                .build()) {
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("getStatus", Map.of());

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                assertThat(response.content())
                        .first()
                        .isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                            assertThat(textContent.type()).isEqualTo("text");
                            DocumentContext documentContext = JsonPath.using(Configuration.defaultConfiguration())
                                    .parse(textContent.text());
                            var contentMap = documentContext.read("$.result", Map.class);
                            // Do not want to be too specific here as defaults may change and test become flaky
                            assertThat(contentMap).containsKey("Active administrative monitors");
                            assertThat(contentMap).containsKey("Available executors (any label)");
                            assertThat(contentMap).containsKey("Buildable Queue Size");
                            assertThat(contentMap).containsKey("Defined clouds that can provide agents (any label)");
                            // This should not change
                            assertThat(contentMap).containsEntry("Full Queue Size", 0);
                            assertThat(contentMap).containsEntry("Quiet Mode", false);
                        });
            }
        }
    }
}
