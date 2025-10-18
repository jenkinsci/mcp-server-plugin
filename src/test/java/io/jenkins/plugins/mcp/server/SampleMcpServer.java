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

package io.jenkins.plugins.mcp.server;

import hudson.Extension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Extension
public class SampleMcpServer implements McpServerExtension {
    @Tool(
            annotations =
                    @Tool.Annotations(
                            title = "Beta tool",
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false,
                            returnDirect = false),
            metas = {
                @Tool.Meta(property = "version", parameter = "1.0"),
                @Tool.Meta(property = "author", parameter = "Someone")
            })
    public Map sayHello(@ToolParam(description = "The name to greet") String name) {
        return Map.of("message", "Hello, " + name + "!");
    }

    @Tool
    public int testInt() {
        return 10;
    }

    @Tool
    public int testWithError() {
        throw new IllegalArgumentException("Error occurred during execution");
    }

    public static List<String> getAllToolNames() {
        return Arrays.stream(SampleMcpServer.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .toList();
    }

    @Tool(structuredOutput = true)
    public int intWithStructuredOutput() {
        return 1;
    }

    @Tool(structuredOutput = true)
    public Map mapWithStructuredOutput() {
        return Map.of("key1", "value1");
    }

    @Tool(structuredOutput = true)
    public Map mapAsNullWithStructuredOutput() {
        return null;
    }

    @Tool(structuredOutput = true)
    public Collection<String> collectionWithStructuredOutput() {
        return List.of("item1", "item2");
    }

    @Tool(structuredOutput = true)
    public Collection<String> collectionAsNullWithStructuredOutput() {
        return null;
    }

    @Tool(structuredOutput = true)
    public Object genericObjectWithStructuredOutput() {
        return new CustomObject("John Doe", 30);
    }

    @Tool(structuredOutput = true)
    public CustomObject customObjectWithStructuredOutput() {
        return new CustomObject("John Doe", 30);
    }

    @Tool(structuredOutput = true)
    public ParentObject nestedObjectWithStructuredOutput() {
        return new ParentObject("John Doe", 30, new ChildObject("Child", 20));
    }

    @Tool(structuredOutput = true)
    public ParentObject nestedObjectChildNullWithStructuredOutput() {
        return new ParentObject("John Doe", 30, null);
    }

    @Tool(structuredOutput = true)
    public SelfNestedObject selfNestedObjectWithStructuredOutput() {
        return new SelfNestedObject("item1", new SelfNestedObject("item2", null));
    }

    public record CustomObject(String name, int age) {}

    public record ChildObject(String name, int age) {}

    public record ParentObject(String name, int age, ChildObject child) {}

    public record SelfNestedObject(String name, @Nullable SelfNestedObject ref) {}
}
