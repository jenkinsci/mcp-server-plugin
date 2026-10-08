/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Omar Mahamid
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

package io.jenkins.plugins.mcp.server.apps;

import hudson.Extension;
import java.util.List;

public final class JenkinsApps {

    public static final String BUILD_LOG = "ui://jenkins/build-log";

    public static final String BUILD = "ui://jenkins/build";

    public static final String JOB = "ui://jenkins/job";

    public static final String JOBS = "ui://jenkins/jobs";

    public static final String TEST_RESULTS = "ui://jenkins/test-results";

    public static final String QUEUE_ITEM = "ui://jenkins/queue-item";

    public static final String SYSTEM_LOG = "ui://jenkins/system-log";

    public static final String STATUS = "ui://jenkins/status";

    static final String PAGE = "io/jenkins/plugins/mcp/server/apps/jenkins.html";

    static final List<String> READ_ONLY_TOOLS = List.of(
            "getJobs",
            "getJob",
            "getBuild",
            "getBuildLog",
            "searchBuildLog",
            "getBuildChangeSets",
            "getBuildScm",
            "getJobScm",
            "findJobsWithScmUrl",
            "getReplayScripts",
            "getTestResults",
            "getFlakyFailures",
            "getQueueItem",
            "getSystemLog",
            "getLogRecorders",
            "getStatus",
            "whoAmI");

    @Extension
    public static final McpApp BUILD_LOG_APP = page(BUILD_LOG, "Jenkins console", "The console output of a build");

    @Extension
    public static final McpApp BUILD_APP =
            page(BUILD, "Jenkins build", "A build with its details, changes, SCM and pipeline script");

    @Extension
    public static final McpApp JOB_APP = page(JOB, "Jenkins job", "A job with its build history and SCM");

    @Extension
    public static final McpApp JOBS_APP = page(JOBS, "Jenkins jobs", "A list of Jenkins jobs");

    @Extension
    public static final McpApp TEST_RESULTS_APP =
            page(TEST_RESULTS, "Jenkins test result", "The failing and flaky tests of a build");

    @Extension
    public static final McpApp QUEUE_ITEM_APP = page(QUEUE_ITEM, "Jenkins queue item", "An item of the build queue");

    @Extension
    public static final McpApp SYSTEM_LOG_APP =
            page(SYSTEM_LOG, "Jenkins system log", "The Jenkins system log and its log recorders");

    @Extension
    public static final McpApp STATUS_APP = page(STATUS, "Jenkins status", "The Jenkins status and the current user");

    private JenkinsApps() {}

    private static McpApp page(String uri, String title, String description) {
        return new McpApp(uri, title, description, READ_ONLY_TOOLS, PAGE);
    }
}
