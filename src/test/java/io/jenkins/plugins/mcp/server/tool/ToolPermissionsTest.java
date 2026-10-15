/*
 *
 * The MIT License
 *
 * Copyright (c) 2026, Olivier Lamy
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

package io.jenkins.plugins.mcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.model.User;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ToolPermissionsTest {

    @Test
    void resolveHandlesKnownEmptyAndUnknownIds(JenkinsRule jenkins) {
        assertThat(ToolPermissions.resolve(null)).isEmpty();
        assertThat(ToolPermissions.resolve(new String[0])).isEmpty();
        assertThat(ToolPermissions.resolve(new String[] {"hudson.model.Hudson.SystemRead"}))
                .containsExactly(Jenkins.SYSTEM_READ);
        assertThatThrownBy(() -> ToolPermissions.resolve(new String[] {"there.is.no.Such.Permission"}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noRequirementIsAlwaysAllowed(JenkinsRule jenkins) {
        assertThat(ToolPermissions.isAllowed(null, List.of())).isTrue();
    }

    @Test
    void unsecuredJenkinsAllowsEverything(JenkinsRule jenkins) {
        // default JenkinsRule leaves security off
        assertThat(ToolPermissions.isAllowed(Jenkins.ANONYMOUS2, List.of(Jenkins.ADMINISTER)))
                .isTrue();
    }

    @Test
    void securedJenkinsRequiresOneOfTheDeclaredPermissions(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setSecurityRealm(jenkins.createDummySecurityRealm());
        jenkins.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("reader"));

        var admin = User.getById("admin", true).impersonate2();
        var reader = User.getById("reader", true).impersonate2();

        assertThat(ToolPermissions.isAllowed(admin, List.of(Jenkins.SYSTEM_READ)))
                .isTrue();
        assertThat(ToolPermissions.isAllowed(reader, List.of(Jenkins.SYSTEM_READ)))
                .isFalse();
        // at least one of several is enough
        assertThat(ToolPermissions.isAllowed(reader, List.of(Jenkins.SYSTEM_READ, Jenkins.READ)))
                .isTrue();
        // and a missing authentication on a secured instance is denied
        assertThat(ToolPermissions.isAllowed(null, List.of(Jenkins.SYSTEM_READ)))
                .isFalse();
    }
}
