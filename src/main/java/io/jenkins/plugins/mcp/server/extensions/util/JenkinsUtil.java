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

package io.jenkins.plugins.mcp.server.extensions.util;

import hudson.model.Job;
import hudson.model.Run;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import lombok.NonNull;

public class JenkinsUtil {

    private static final Logger LOGGER = Logger.getLogger(JenkinsUtil.class.getName());

    public static Optional<Run> getBuildByNumberOrLast(@NonNull String fullJobName, @Nullable Integer buildNumber) {
        var jenkins = Jenkins.get();
        var job = jenkins.getItemByFullName(fullJobName, Job.class);
        if (job == null) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(
                        Level.FINE,
                        "Build lookup failed: job not found or not visible. jobFullName={0}, buildNumber={1}, user={2}, rootUrl={3}",
                        new Object[] {
                            fullJobName, buildNumber, jenkins.getAuthentication2().getName(), jenkins.getRootUrl()
                        });
            }
            return Optional.empty();
        }

        if (buildNumber == null || buildNumber <= 0)
            return Optional.ofNullable(job.getLastBuild());
        return Optional.ofNullable(job.getBuildByNumber(buildNumber));
    }
}
