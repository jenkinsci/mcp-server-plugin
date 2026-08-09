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

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import hudson.FilePath;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.extensions.TestResultExtensionTest;
import io.jenkins.plugins.mcp.server.jackson.JenkinsExportedBeanModule;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import tools.jackson.databind.json.JsonMapper;

@WithJenkins
public class JenkinsObjectSerializationTest {
    private JsonMapper objectMapper =
            JsonMapper.builder().addModule(new JenkinsExportedBeanModule()).build();

    @Test
    void testSerializeExportedBean(JenkinsRule jenkins)
            throws IOException, Descriptor.FormException, ExecutionException, InterruptedException {

        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        var json = objectMapper.writeValueAsString(build);
        var map = new JsonMapper().readValue(json, Map.class);
        assertThat(map).extracting("_class").isEqualTo("org.jenkinsci.plugins.workflow.job.WorkflowRun");
    }

    @Test
    void testSerializeMixedMapWithExportedBean(JenkinsRule jenkins)
            throws IOException, Descriptor.FormException, ExecutionException, InterruptedException {

        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        var result = Map.of("build", build, "number", build.getNumber());
        var json = objectMapper.writeValueAsString(result);

        var map = new JsonMapper().readValue(json, Map.class);
        assertThat(map).extractingByKey("number").isEqualTo(build.getNumber());
        assertThat(map).extractingByKey("build").isInstanceOfSatisfying(Map.class, buildMap -> {
            assertThat(buildMap).extracting("_class").isEqualTo("org.jenkinsci.plugins.workflow.job.WorkflowRun");
        });
    }

    @Test
    void testSerializeSimpleMap()
            throws IOException, Descriptor.FormException, ExecutionException, InterruptedException {

        var json = objectMapper.writeValueAsString(Map.of("key", "value", "key1", "value1"));
        var map = new JsonMapper().readValue(json, Map.class);
        assertThat(map).extractingByKey("key").isEqualTo("value");
    }

    @Test
    void testExcludedPropertiesCascadeIntoNestedBeans(JenkinsRule jenkins) throws Exception {
        // Given a pipeline run with junit inside stage { node { } }, which sets SuiteResult.nodeId
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "junit-cascade");
        project.setDefinition(new CpsFlowDefinition("""
                        stage('first') {
                          node {
                            def results = junit(testResults: '*.xml')
                            assert results.totalCount == 6
                          }
                        }
                        """, true));
        FilePath ws = jenkins.jenkins.getWorkspaceFor(project);
        Objects.requireNonNull(ws)
                .child("test-result.xml")
                .copyFrom(TestResultExtensionTest.class.getResource("junit-report-20090516.xml"));

        // When the TestResult is serialized
        var run = jenkins.buildAndAssertStatus(Result.FAILURE, project);
        var testResult = run.getAction(TestResultAction.class).getResult();
        assertThat(testResult.getSuites().iterator().next().getNodeId()).isNotNull();

        var json = objectMapper.writeValueAsString(testResult);
        var doc = JsonPath.using(Configuration.defaultConfiguration()).parse(json);

        // Then nodeId, enclosingBlocks and enclosingBlockNames are absent at every depth
        assertThat((List<?>) doc.read("$..nodeId")).isEmpty();
        assertThat((List<?>) doc.read("$..enclosingBlocks")).isEmpty();
        assertThat((List<?>) doc.read("$..enclosingBlockNames")).isEmpty();
    }
}
