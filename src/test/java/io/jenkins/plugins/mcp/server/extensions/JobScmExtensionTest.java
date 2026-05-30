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

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.FreeStyleProject;
import hudson.plugins.git.GitSCM;
import io.jenkins.plugins.mcp.server.junit.JenkinsMcpClientBuilder;
import io.jenkins.plugins.mcp.server.junit.McpClientTest;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.File;
import java.util.Map;
import jenkins.plugins.git.GitSampleRepoRule;
import jenkins.plugins.git.junit.jupiter.GitSampleRepoExtension;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@ExtendWith(GitSampleRepoExtension.class)
@WithJenkins
public class JobScmExtensionTest {

    @McpClientTest
    void testGetJobScm(JenkinsRule jenkins, GitSampleRepoRule gitRepo, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        // Setup Git repository
        gitRepo.init();
        gitRepo.write("file", "content");
        gitRepo.git("add", "file");
        gitRepo.git("commit", "--message=initial commit");

        // Create a job with Git SCM
        FreeStyleProject project = jenkins.createFreeStyleProject("test-project");
        String repoRoot = gitRepo.getRoot().getAbsolutePath().replace(File.separator, "/");
        GitSCM scm = new GitSCM(repoRoot);
        project.setScm(scm);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Call getJobScm tool
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("getJobScm", Map.of("jobFullName", project.getFullName()));

            var response = client.callTool(request);

            // Assert response
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(repoRoot);
            });
        }
    }

    @McpClientTest
    void testGetBuildScm(
            JenkinsRule jenkins, GitSampleRepoRule gitRepo, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        // Setup Git repository
        gitRepo.init();
        gitRepo.write("file", "content");
        gitRepo.git("add", "file");
        gitRepo.git("commit", "--message=initial commit");

        // Create a job with Git SCM
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "test-project");
        String repoRoot = gitRepo.getRoot().getAbsolutePath().replace(File.separator, "/");
        project.setDefinition(new CpsFlowDefinition("node { git '" + repoRoot + "' }", true));

        // Trigger a build
        jenkins.buildAndAssertSuccess(project);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Call getBuildScm tool
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuildScm", Map.of("jobFullName", project.getFullName(), "buildNumber", 1));

            var response = client.callTool(request);

            // Assert response
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                assertThat(textContent.text()).contains(repoRoot);
            });
        }
    }

    @McpClientTest
    void testGetBuildChangeSets(
            JenkinsRule jenkins, GitSampleRepoRule gitRepo, JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        // Setup Git repository
        gitRepo.init();
        gitRepo.write("file1", "initial content");
        gitRepo.git("add", "file1");
        gitRepo.git("commit", "--message=initial commit");

        // Create a job with Git SCM
        WorkflowJob project = jenkins.createProject(WorkflowJob.class, "change-set-test-project");
        String repoRoot = gitRepo.getRoot().getAbsolutePath().replace(File.separator, "/");
        project.setDefinition(new CpsFlowDefinition("node { git '" + repoRoot + "' }", true));

        // Trigger first build
        jenkins.buildAndAssertSuccess(project);

        // Make changes to the repository
        gitRepo.write("file2", "new file content");
        gitRepo.git("add", "file2");
        gitRepo.git("commit", "--message=add new file");

        gitRepo.write("file1", "updated content");
        gitRepo.git("add", "file1");
        gitRepo.git("commit", "--message=update existing file");

        // Trigger second build
        jenkins.buildAndAssertSuccess(project);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Call getBuildChangeSets tool for the second build
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                    "getBuildChangeSets", Map.of("jobFullName", project.getFullName(), "buildNumber", 2));

            var response = client.callTool(request);

            // Assert response
            assertThat(response.isError()).isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                String changeSetContent = textContent.text();
                assertThat(changeSetContent).contains("add new file");
                assertThat(changeSetContent).contains("update existing file");
                assertThat(changeSetContent).contains("file1");
                assertThat(changeSetContent).contains("file2");
            });
        }
    }

    @McpClientTest
    void testFindJobsWithScmUrl(
            JenkinsRule jenkins,
            GitSampleRepoRule gitRepo1,
            GitSampleRepoRule gitRepo2,
            JenkinsMcpClientBuilder jenkinsMcpClientBuilder)
            throws Exception {
        // Setup Git repository
        gitRepo1.init();
        gitRepo1.git("remote", "add", "origin", "git@github.com:example/repo1.git");
        String repoRoot1 = gitRepo1.getRoot().getAbsolutePath().replace(File.separator, "/");

        gitRepo2.init();
        gitRepo2.git("remote", "add", "origin", "git@github.com:example/repo2.git");
        String repoRoot2 = gitRepo2.getRoot().getAbsolutePath().replace(File.separator, "/");

        // Create a job with Git SCM
        WorkflowJob project1 = jenkins.createProject(WorkflowJob.class, "git-scm-test-job-1a");
        project1.setDefinition(new CpsFlowDefinition("node { git '" + repoRoot1 + "' }", true));

        jenkins.buildAndAssertSuccess(project1);

        WorkflowJob project2 = jenkins.createProject(WorkflowJob.class, "git-scm-test-job-1b");
        project2.setDefinition(new CpsFlowDefinition("node { git '" + repoRoot1 + "' }", true));

        jenkins.buildAndAssertSuccess(project2);

        WorkflowJob project3 = jenkins.createProject(WorkflowJob.class, "git-scm-test-job-2");
        project3.setDefinition(new CpsFlowDefinition("node { git '" + repoRoot2 + "' }", true));

        jenkins.buildAndAssertSuccess(project3);

        try (var client = jenkinsMcpClientBuilder.jenkins(jenkins).build()) {
            // Call getBuildChangeSets tool for the second build
            McpSchema.CallToolRequest request =
                    new McpSchema.CallToolRequest("findJobsWithScmUrl", Map.of("scmUrl", "file://" + repoRoot1));

            var response = client.callTool(request);

            assertThat(response.isError())
                    .as("Expected successful message. Content:", response.content())
                    .isFalse();
            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).type()).isEqualTo("text");
            assertThat(response.content()).first().isInstanceOfSatisfying(McpSchema.TextContent.class, textContent -> {
                assertThat(textContent.type()).isEqualTo("text");
                String jobsListContent = textContent.text();
                assertThat(jobsListContent).contains("git-scm-test-job-1a");
                assertThat(jobsListContent).contains("git-scm-test-job-1b");
                assertThat(jobsListContent).doesNotContain("git-scm-test-job-2");
            });
        }
    }
}
