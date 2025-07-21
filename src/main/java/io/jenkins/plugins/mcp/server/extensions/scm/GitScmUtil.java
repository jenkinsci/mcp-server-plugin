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

package io.jenkins.plugins.mcp.server.extensions.scm;

import hudson.model.Run;
import hudson.plugins.git.Branch;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.scm.SCM;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.transport.URIish;

public class GitScmUtil {

    public static GitScmConfig extractGitScmInfo(SCM scm) {
        if (scm instanceof GitSCM gitSCM) {
            var branches =
                    gitSCM.getBranches().stream().map(BranchSpec::getName).toList();
            var uris = gitSCM.getRepositories().stream()
                    .flatMap(repo -> repo.getURIs().stream())
                    .map(URIish::toString)
                    .toList();

            return new GitScmConfig(uris, branches, null);
        } else {
            return null;
        }
    }

    public static GitScmConfig extractGitScmInfo(Run run) {
        return Optional.ofNullable(run.getAction(BuildData.class))
                .map(
                        buildData -> {
                            var branches = Optional.of(buildData)
                                    .map(BuildData::getLastBuiltRevision)
                                    .map(Revision::getBranches)
                                    .stream()
                                    .flatMap(Collection::stream)
                                    .map(Branch::getName)
                                    .toList();
                            var commit = Optional.of(buildData)
                                    .map(BuildData::getLastBuiltRevision)
                                    .map(Revision::getSha1)
                                    .map(AnyObjectId::toString)
                                    .orElse(null);
                            return new GitScmConfig(new ArrayList<>(buildData.getRemoteUrls()), branches, commit);
                        })
                .orElse(null);
    }
}
