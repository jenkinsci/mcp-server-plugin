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

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Descriptor;
import io.jenkins.plugins.mcp.server.jackson.JenkinsExportedBeanModule;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class JenkinsObjectSerializationTest {
    private ObjectMapper objectMapper = new ObjectMapper();

    {
        objectMapper.registerModule(new JenkinsExportedBeanModule());
    }

    @Test
    void testSerializeExportedBean(JenkinsRule jenkins)
            throws IOException, Descriptor.FormException, ExecutionException, InterruptedException {

        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "demo-job");
        project.setDefinition(new CpsFlowDefinition("", true));
        var build = project.scheduleBuild2(0).get();

        var json = objectMapper.writeValueAsString(build);
        var map = new ObjectMapper().readValue(json, Map.class);
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

        var map = new ObjectMapper().readValue(json, Map.class);
        assertThat(map).extractingByKey("number").isEqualTo(build.getNumber());
        assertThat(map).extractingByKey("build").isInstanceOfSatisfying(Map.class, buildMap -> {
            assertThat(buildMap).extracting("_class").isEqualTo("org.jenkinsci.plugins.workflow.job.WorkflowRun");
        });
    }

    @Test
    void testSerializeSimpleMap()
            throws IOException, Descriptor.FormException, ExecutionException, InterruptedException {

        var json = objectMapper.writeValueAsString(Map.of("key", "value", "key1", "value1"));
        var map = new ObjectMapper().readValue(json, Map.class);
        assertThat(map).extractingByKey("key").isEqualTo("value");
    }
}
