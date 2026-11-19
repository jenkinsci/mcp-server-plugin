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

package io.jenkins.plugins.mcp.server.extensions.util;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryCliFallbackTest {

    @Test
    void createsParameterValueForSingleStringViaCli() {
        var param = new TestGenericCliParameterDefinition("CLI_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "test-value");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("test-value"));
    }

    @Test
    void createsParameterValueForListOfStringsViaCli() {
        var param = new TestGenericCliParameterDefinition("CLI_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, List.of("alpha", "beta"));

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("alpha,beta"));
    }

    @Test
    void createsParameterValueForArrayViaCli() {
        var param = new TestGenericCliParameterDefinition("CLI_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, new String[] {"one", "two"});

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("one,two"));
    }

    @Test
    void fallsBackToDefaultWhenCliValidationFails() {
        var param = new TestGenericCliParameterDefinition("CLI_PARAM", true);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, "bad-value");

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("generic-default"));
    }

    @Test
    void fallsBackToDefaultWhenInputValueIsNull() {
        var param = new TestGenericCliParameterDefinition("CLI_PARAM", false);

        ParameterValue value = ParameterValueFactory.createParameterValue(param, null);

        assertThat(value)
                .isInstanceOfSatisfying(
                        StringParameterValue.class,
                        v -> assertThat(v.getValue()).isEqualTo("generic-default"));
    }
}
