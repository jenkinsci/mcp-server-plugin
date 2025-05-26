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

package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

import java.util.List;

@Extension
public class DefaultMcpServer implements McpServerExtension {


	@Tool(description = "Get a specific build or the last build of a Jenkins job")
	public Run getBuild(
			@ToolParam(description = "Job full nam of the Jenkins job (e.g., 'folder/job-name')") String jobFullName,
			@Nullable @ToolParam(
					description = "Build number (optional, if not provided, returns the last build)",
					required = false) String buildNumber
	) {
		var item = Jenkins.get().getItemByFullName(jobFullName);
		if (item instanceof hudson.model.Job job) {
			if (buildNumber == null || buildNumber.isEmpty()) {
				return job.getLastBuild();
			} else {
				return job.getBuildByNumber(Integer.parseInt(buildNumber));
			}
		}
		return null;
	}


	@Tool(description = "Get a Jenkins job by its full path")
	public Job getJob(
			@ToolParam(description = "Job full name of the Jenkins job (e.g., 'folder/job-name')") String jobFullName
	) {
		return Jenkins.get().getItemByFullName(jobFullName, Job.class);
	}


	@Tool(description = "Trigger a build for a Jenkins job")
	public boolean triggerBuild(
			@ToolParam(description = "Full path of the Jenkins job (e.g., 'folder/job-name')") String jobFullName
	) {
		var item = Jenkins.get().getItemByFullName(jobFullName);
		if (item instanceof ParameterizedJobMixIn.ParameterizedJob job) {
			job.scheduleBuild2(0);
			return true;
		}
		return false;
	}

	@Tool(description = "Get a list of all Jenkins jobs")
	public List<Job> getAllJobs() {
		return Jenkins.get().getAllItems(Job.class);
	}


}
