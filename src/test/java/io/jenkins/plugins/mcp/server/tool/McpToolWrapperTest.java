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

package io.jenkins.plugins.mcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpToolWrapperTest {
    MockMethods target = new MockMethods();
    JsonMapper objectMapper = new JsonMapper();
    ;

    @Test
    void malformedDefaultTreeFailsAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new McpToolWrapper(
                        objectMapper, target, MockMethods.class.getDeclaredMethod("malformedDefaultTreeMethod")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultTree")
                .hasMessageContaining("malformedDefaultTreeMethod");
    }

    @Test
    void generateForOutputOfBoolean() throws NoSuchMethodException {

        McpToolWrapper wrapper =
                new McpToolWrapper(objectMapper, target, MockMethods.class.getDeclaredMethod("boolMethod"));
        var output = wrapper.generateForOutput();
        System.out.println(output);
    }

    @Test
    void generateForOutputOfMap() throws NoSuchMethodException {

        McpToolWrapper wrapper =
                new McpToolWrapper(objectMapper, target, MockMethods.class.getDeclaredMethod("mapMethod"));
        var output = wrapper.generateForOutput();
        System.out.println(output);
    }

    @Test
    void generateForOutputOfComplex() throws NoSuchMethodException {

        McpToolWrapper wrapper =
                new McpToolWrapper(objectMapper, target, MockMethods.class.getDeclaredMethod("complexMethod"));
        var output = wrapper.generateForOutput();
        System.out.println(output);
    }

    @Test
    void additionalPropertiesOverridesGeneratedValueSchema() throws Exception {
        McpToolWrapper wrapper = new McpToolWrapper(
                objectMapper, target, MockMethods.class.getDeclaredMethod("openMapParam", Map.class));

        JsonNode parametersSchema = objectMapper
                .readTree(wrapper.generateForMethodInput())
                .path("properties")
                .path("parameters");

        // the annotation value shows up as-is under additionalProperties
        JsonNode expected =
                objectMapper.readTree("{\"type\":[\"string\",\"boolean\",\"integer\",\"number\",\"array\"]}");
        assertThat(parametersSchema.path("additionalProperties")).isEqualTo(expected);
        // no fixed properties, so any key is still allowed
        assertThat(parametersSchema.path("properties").isMissingNode()).isTrue();
    }

    @Test
    void additionalPropertiesAcceptsBoolean() throws Exception {
        McpToolWrapper wrapper = new McpToolWrapper(
                objectMapper, target, MockMethods.class.getDeclaredMethod("booleanMapParam", Map.class));

        JsonNode additionalProperties = objectMapper
                .readTree(wrapper.generateForMethodInput())
                .path("properties")
                .path("parameters")
                .path("additionalProperties");

        // a boolean is valid here too and passes straight through
        assertThat(additionalProperties.isBoolean()).isTrue();
        assertThat(additionalProperties).isEqualTo(objectMapper.readTree("false"));
    }

    @Test
    void malformedAdditionalPropertiesFailsFast() throws Exception {
        McpToolWrapper wrapper =
                new McpToolWrapper(objectMapper, target, MockMethods.class.getDeclaredMethod("badMapParam", Map.class));

        assertThatThrownBy(() -> wrapper.generateForMethodInput())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("badMapParam")
                .hasMessageContaining("parameters")
                .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test
    void missingRequiredArgumentUsesSchemaParameterName() throws Exception {
        McpToolWrapper wrapper = searchLikeWrapper();

        JsonNode required =
                objectMapper.readTree(wrapper.generateForMethodInput()).path("required");
        assertThat(required.toString()).contains("jobFullName").doesNotContain("fullJobName");

        assertThat(wrapper.missingRequiredArguments(Map.of("pattern", "ERROR"))).containsExactly("jobFullName");
        assertThat(McpToolWrapper.missingRequiredMessage(wrapper.missingRequiredArguments(Map.of("pattern", "ERROR"))))
                .isEqualTo("Missing required parameter: jobFullName")
                .doesNotContain("fullJobName")
                .doesNotContain("NullPointerException");
    }

    @Test
    void wrongKeyDoesNotSatisfyRequiredSchemaParameter() throws Exception {
        McpToolWrapper wrapper = searchLikeWrapper();

        assertThat(wrapper.missingRequiredArguments(Map.of("fullJobName", "demo-job")))
                .containsExactly("jobFullName", "pattern");
        assertThat(McpToolWrapper.missingRequiredMessage(
                        wrapper.missingRequiredArguments(Map.of("fullJobName", "demo-job"))))
                .isEqualTo("Missing required parameters: jobFullName, pattern")
                .doesNotContain("fullJobName")
                .doesNotContain("NullPointerException");
    }

    @Test
    void presentSchemaParametersAreNotReportedMissing() throws Exception {
        McpToolWrapper wrapper = searchLikeWrapper();

        assertThat(wrapper.missingRequiredArguments(Map.of("jobFullName", "demo-job", "pattern", "ERROR")))
                .isEmpty();
        // optional buildNumber may be omitted
        assertThat(wrapper.missingRequiredArguments(
                        Map.of("jobFullName", "demo-job", "pattern", "ERROR", "buildNumber", 1)))
                .isEmpty();
    }

    @Test
    void nullPointerExceptionMessageIsNotReturnedToClients() {
        var npe = new NullPointerException("fullJobName is marked non-null but is null");
        assertThat(McpToolWrapper.clientSafeFailureMessage(new InvocationTargetException(npe), "searchBuildLog"))
                .isEqualTo("Error invoking method: searchBuildLog")
                .doesNotContain("fullJobName")
                .doesNotContain("NullPointerException")
                .doesNotContain("marked non-null");
    }

    @Test
    void nonNullGuardOnOtherExceptionTypesIsNotReturnedToClients() {
        var guarded = new IllegalArgumentException("fullJobName is marked non-null but is null");
        assertThat(McpToolWrapper.clientSafeFailureMessage(guarded, "searchBuildLog"))
                .isEqualTo("Error invoking method: searchBuildLog")
                .doesNotContain("fullJobName");
    }

    @Test
    void intentionalIllegalArgumentExceptionMessageIsPreserved() {
        var error =
                new InvocationTargetException(new IllegalArgumentException("Search pattern cannot be null or empty"));
        assertThat(McpToolWrapper.clientSafeFailureMessage(error, "searchBuildLog"))
                .isEqualTo("IllegalArgumentException: Search pattern cannot be null or empty");
    }

    private McpToolWrapper searchLikeWrapper() throws NoSuchMethodException {
        return new McpToolWrapper(
                objectMapper,
                target,
                MockMethods.class.getDeclaredMethod("searchLike", String.class, String.class, Integer.class));
    }

    @Test
    void nonObjectOrBooleanAdditionalPropertiesFailsFast() throws Exception {
        McpToolWrapper wrapper = new McpToolWrapper(
                objectMapper, target, MockMethods.class.getDeclaredMethod("wrongTypeMapParam", Map.class));

        assertThatThrownBy(() -> wrapper.generateForMethodInput())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a JSON object or boolean")
                .hasMessageContaining("wrongTypeMapParam");
    }

    public static class MockMethods {
        @Tool
        public boolean boolMethod() {
            return false;
        }

        @Tool(defaultTree = "name,lastBuild[number") // unbalanced bracket
        public hudson.model.Job<?, ?> malformedDefaultTreeMethod() {
            return null;
        }

        @Tool
        public Map<String, String> mapMethod() {
            return Map.of("result", "ok");
        }

        @Tool
        public ComplexType complexMethod() {
            return new ComplexType(true, Map.of("result", "ok"), 200);
        }

        @Tool
        public ComplexTypeClass complexMethodA() {
            return new ComplexTypeClass(true, Map.of("result", "ok"), 200);
        }

        @Tool
        public boolean openMapParam(
                @ToolParam(
                                additionalProperties =
                                        "{\"type\":[\"string\",\"boolean\",\"integer\",\"number\",\"array\"]}")
                        Map<String, Object> parameters) {
            return true;
        }

        @Tool
        public boolean booleanMapParam(@ToolParam(additionalProperties = "false") Map<String, Object> parameters) {
            return true;
        }

        @Tool
        public boolean badMapParam(
                @ToolParam(additionalProperties = "{not valid json") Map<String, Object> parameters) {
            return true;
        }

        @Tool
        public boolean wrongTypeMapParam(@ToolParam(additionalProperties = "42") Map<String, Object> parameters) {
            return true;
        }

        @Tool
        public String searchLike(
                @ToolParam(description = "Job full name of the Jenkins job") String jobFullName,
                @ToolParam(description = "The search pattern") String pattern,
                @ToolParam(description = "The build number", required = false) Integer buildNumber) {
            return jobFullName + ":" + pattern + ":" + buildNumber;
        }

        public record ComplexType(boolean success, Map<String, Object> data, int code) {}

        @Data
        @AllArgsConstructor
        public static class ComplexTypeClass {
            boolean success;
            Map<String, Object> data;
            int code;
        }
    }
}
