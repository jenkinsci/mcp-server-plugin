/*
 * The MIT License
 *
 * Copyright (c) 2026, Jenkins Contributors.
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
 */

package io.jenkins.plugins.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.cli.CLICommand;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import io.jenkins.plugins.mcp.server.extensions.util.ParameterValueFactory;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

@WithJenkins
class McpParameterValueProviderTest {

    public static class CustomTestParameterDefinition extends ParameterDefinition {

        public CustomTestParameterDefinition(String name) {
            super(name, "Custom test parameter definition");
        }

        @Override
        public ParameterValue createValue(StaplerRequest2 req, JSONObject jo) {
            return null;
        }

        @Override
        public ParameterValue createValue(CLICommand command, String value) {
            return null;
        }

        @Override
        public ParameterValue getDefaultParameterValue() {
            return new StringParameterValue(getName(), "default-custom");
        }
    }

    @TestExtension
    public static class CustomParameterValueProvider implements McpParameterValueProvider {

        @Override
        public boolean isApplicable(@NonNull ParameterDefinition parameter) {
            return parameter instanceof CustomTestParameterDefinition;
        }

        @Override
        @CheckForNull
        public ParameterValue createValue(@NonNull ParameterDefinition parameter, @Nullable Object inputValue) {
            if (inputValue == null) {
                return parameter.getDefaultParameterValue();
            }
            return new StringParameterValue(parameter.getName(), "custom:" + inputValue);
        }
    }

    @Test
    void testCustomParameterValueProviderConvertsValue(JenkinsRule jenkins) {
        assertThat(McpParameterValueProvider.all()).anyMatch(p -> p instanceof CustomParameterValueProvider);

        var param = new CustomTestParameterDefinition("CUSTOM_PARAM");
        ParameterValue value = ParameterValueFactory.createParameterValue(param, "test-input");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("custom:test-input"));
    }

    @Test
    void testCustomParameterValueProviderHandlesNullValue(JenkinsRule jenkins) {
        var param = new CustomTestParameterDefinition("CUSTOM_PARAM");
        ParameterValue value = ParameterValueFactory.createParameterValue(param, null);

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("default-custom"));
    }
}
