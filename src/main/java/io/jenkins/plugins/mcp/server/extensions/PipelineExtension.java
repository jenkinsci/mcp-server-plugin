/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Yigal Gvili.
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

import hudson.ExtensionList;
import hudson.model.Item;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;
import jenkins.model.ModifiableTopLevelItemGroup;
import lombok.SneakyThrows;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

@OptionalExtension(requirePlugins = {"workflow-job", "workflow-cps"})
public class PipelineExtension implements McpServerExtension {

    @Tool(
            description = "Create a new Jenkins pipeline job with the provided script",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    @SneakyThrows
    public String createPipeline(
            @ToolParam(description = "Name (or full path) of the pipeline to create") String jobName,
            @ToolParam(description = "The Jenkins Declarative or Scripted Pipeline script") String pipelineScript) {
        var jenkins = Jenkins.get();
        int lastSlash = jobName.lastIndexOf('/');
        ModifiableTopLevelItemGroup parent = jenkins;
        String name = jobName;
        if (lastSlash != -1) {
            String parentName = jobName.substring(0, lastSlash);
            name = jobName.substring(lastSlash + 1);
            Item parentItem = jenkins.getItemByFullName(parentName);
            if (parentItem instanceof ModifiableTopLevelItemGroup itemGroup) {
                parent = itemGroup;
            } else {
                throw new IllegalArgumentException(
                        "Parent folder '" + parentName + "' does not exist or cannot contain jobs.");
            }
        }

        if (parent.getItem(name) != null) {
            throw new IllegalArgumentException("A job with name '" + jobName + "' already exists.");
        }

        var descriptor = ExtensionList.lookupSingleton(WorkflowJob.DescriptorImpl.class);
        var createdJob = (WorkflowJob) parent.createProject(descriptor, name, true);
        createdJob.setDefinition(new CpsFlowDefinition(pipelineScript, true));
        createdJob.save();
        return jenkins.getRootUrl() + createdJob.getUrl();
    }
}
