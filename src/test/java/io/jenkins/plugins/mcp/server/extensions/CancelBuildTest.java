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

import static io.jenkins.plugins.mcp.server.junit.TestUtils.MIN_1;
import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.jenkins.plugins.mcp.server.junit.TestUtils;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CancelBuildTest {

    static Stream<Arguments> cancelBuildTestParameters() {
        Stream<Arguments> baseArgs = Stream.of(
                // run already finished
                Arguments.of("canceller", 1, "pipeline", false, true),
                // run successfully cancelled
                Arguments.of("canceller", 2, "pipeline", true, false),
                // run not existing
                Arguments.of("canceller", 3, "pipeline", false, true),
                // job not existing
                Arguments.of("canceller", 1, "missing", false, true),
                // missing permission to cancel
                Arguments.of("reader", 2, "pipeline", false, true),
                // missing permission to see job
                Arguments.of("unknown", 2, "pipeline", false, true));
        return TestUtils.appendMcpClientArgs(baseArgs);
    }

    @ParameterizedTest
    @MethodSource("cancelBuildTestParameters")
    void testMcpToolCallCancelBuildPipeline(
            String user,
            int buildNumber,
            String jobNameToCancel,
            boolean expectedResults,
            boolean expectedRunning,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder,
            JenkinsRule jenkins)
            throws Exception {
        enableSecurity(jenkins);
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "pipeline");
        project.setDefinition(new CpsFlowDefinition("", true));
        var finishedBuild = project.scheduleBuild2(0).get();
        assertThat(finishedBuild.isBuilding()).isFalse();
        project.setDefinition(new CpsFlowDefinition("sleep 30", true));
        var runningBuild = project.scheduleBuild2(0).waitForStart();

        String authString = user + ":" + user;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) ->
                        builder.setHeader("Authorization", "Basic " + encodedAuth))
                .build()) {
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                        "cancelBuild", Map.of("jobFullName", jobNameToCancel, "buildNumber", buildNumber), null);

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                assertThat(response.content())
                        .first()
                        .isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                            assertThat(textContent.type()).isEqualTo("text");
                            assertThat(textContent.text()).contains(String.valueOf(expectedResults));
                        });
                TimeUnit.SECONDS.sleep(2);
                assertThat(runningBuild.isBuilding()).isEqualTo(expectedRunning);
                runningBuild.doStop();
            }
        }
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    @McpClientTest
    void testMcpToolCallCancelBuildFreestyle(JenkinsRule jenkins, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        enableSecurity(jenkins);
        FreeStyleProject project = jenkins.createFreeStyleProject("freestyle");
        project.getBuildersList().add(new SleepBuilder(30000));
        var build = project.scheduleBuild2(0).waitForStart();

        String username = "admin";
        String password = "admin";
        String authString = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes());
        try (var client = jenkinsMcpClientBuilder
                .jenkins(jenkins)
                .requestCustomizer((builder, method, endpoint, body, context) ->
                        builder.setHeader("Authorization", "Basic " + encodedAuth))
                .build()) {
            {
                McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                        "cancelBuild", Map.of("jobFullName", "freestyle", "buildNumber", 1), null);

                var response = client.callTool(request);
                assertThat(response.isError()).isFalse();
                assertThat(response.content().get(0).type()).isEqualTo("text");
                assertThat(response.content())
                        .first()
                        .isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                            assertThat(textContent.type()).isEqualTo("text");
                            assertThat(textContent.text()).contains("true");
                        });

                assertThat(build.isBuilding()).isFalse();
            }
        }
        jenkins.waitUntilNoActivityUpTo(MIN_1);
    }

    private void enableSecurity(JenkinsRule jenkins) throws Exception {
        JenkinsRule.DummySecurityRealm securityRealm = jenkins.createDummySecurityRealm();
        jenkins.jenkins.setSecurityRealm(securityRealm);
        var authStrategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin");
        authStrategy.grant(Jenkins.READ).everywhere().toEveryone();
        authStrategy.grant(Item.READ).everywhere().to("canceller", "reader");
        authStrategy.grant(Item.CANCEL).everywhere().to("canceller");
        jenkins.jenkins.setAuthorizationStrategy(authStrategy);
        jenkins.jenkins.save();
    }
}
