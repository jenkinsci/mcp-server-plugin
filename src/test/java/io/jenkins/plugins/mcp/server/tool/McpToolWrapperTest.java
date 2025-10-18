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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.junit.jupiter.api.Test;

class McpToolWrapperTest {
    MockMethods target = new MockMethods();
    ObjectMapper objectMapper = new ObjectMapper();
    ;

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

    public static class MockMethods {
        @Tool
        public boolean boolMethod() {
            return false;
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
