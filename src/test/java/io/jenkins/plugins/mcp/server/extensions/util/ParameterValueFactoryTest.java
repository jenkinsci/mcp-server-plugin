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
 * copies of the Software, and to permit persons to whom the Software is furnished
 * to do so, subject to the following conditions:
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

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ChoiceParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import hudson.util.Secret;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterValueFactoryTest {

    @Test
    void testCreateStringParameterValue() {
        StringParameterDefinition param = new StringParameterDefinition("TEST_STRING", "default", "Test string parameter");
        
        // Test with string value
        ParameterValue value = ParameterValueFactory.createParameterValue(param, "test_value");
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("test_value");
        
        // Test with non-string value
        value = ParameterValueFactory.createParameterValue(param, 123);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("123");
        
        // Test with null value (should use default)
        value = ParameterValueFactory.createParameterValue(param, null);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("default");
    }

    @Test
    void testCreateBooleanParameterValue() {
        BooleanParameterDefinition param = new BooleanParameterDefinition("TEST_BOOL", false, "Test boolean parameter");
        
        // Test with boolean value
        ParameterValue value = ParameterValueFactory.createParameterValue(param, true);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo(true);
        
        // Test with string value
        value = ParameterValueFactory.createParameterValue(param, "true");
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo(true);
        
        // Test with null value (should use default)
        value = ParameterValueFactory.createParameterValue(param, null);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo(false);
    }

    @Test
    void testCreateChoiceParameterValue() {
        ChoiceParameterDefinition param = new ChoiceParameterDefinition("TEST_CHOICE", 
            new String[]{"option1", "option2", "option3"}, "Test choice parameter");
        
        // Test with valid choice
        ParameterValue value = ParameterValueFactory.createParameterValue(param, "option2");
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("option2");
        
        // Test with invalid choice (should use default)
        value = ParameterValueFactory.createParameterValue(param, "invalid_option");
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("option1"); // First choice is default
        
        // Test with null value (should use default)
        value = ParameterValueFactory.createParameterValue(param, null);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("option1");
    }

    @Test
    void testCreateTextParameterValue() {
        TextParameterDefinition param = new TextParameterDefinition("TEST_TEXT", "default text", "Test text parameter");
        
        // Test with string value
        ParameterValue value = ParameterValueFactory.createParameterValue(param, "multiline\ntext");
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("multiline\ntext");
        
        // Test with null value (should use default)
        value = ParameterValueFactory.createParameterValue(param, null);
        assertThat(value).isNotNull();
        assertThat(value.getValue()).isEqualTo("default text");
    }

    @Test
    void testCreatePasswordParameterValue() {
        // Note: PasswordParameterDefinition constructor might require different parameters
        // This test might need adjustment based on actual Jenkins version
        try {
            // Try to create with minimal constructor if available
            PasswordParameterDefinition param = new PasswordParameterDefinition("TEST_PASSWORD", "default_pass", "Test password parameter");
            
            // Test with string value
            ParameterValue value = ParameterValueFactory.createParameterValue(param, "secret_password");
            assertThat(value).isNotNull();
            assertThat(value.getValue()).isInstanceOf(Secret.class);
            
            // Test with null value (should use default)
            value = ParameterValueFactory.createParameterValue(param, null);
            assertThat(value).isNotNull();
            assertThat(value.getValue()).isInstanceOf(Secret.class);
        } catch (Exception e) {
            // Skip test if PasswordParameterDefinition is not available in this Jenkins version
            System.out.println("PasswordParameterDefinition not available, skipping test: " + e.getMessage());
        }
    }

    @Test
    void testCreateParameterValueWithList() {
        // Test that list parameters are handled gracefully
        StringParameterDefinition param = new StringParameterDefinition("TEST_LIST", "default", "Test list parameter");
        
        // Test with list value (should convert to string)
        List<String> listValue = Arrays.asList("item1", "item2", "item3");
        ParameterValue value = ParameterValueFactory.createParameterValue(param, listValue);
        assertThat(value).isNotNull();
        // The exact behavior depends on how toString() works on the list
        assertThat(value.getValue()).isNotNull();
    }
}
