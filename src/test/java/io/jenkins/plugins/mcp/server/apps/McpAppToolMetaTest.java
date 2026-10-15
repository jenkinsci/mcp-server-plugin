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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jenkins.plugins.mcp.server.annotation.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpAppToolMetaTest {

    @Test
    void testReadOnlyToolWithoutApp() throws Exception {
        assertThat(McpAppToolMeta.of(toolOf("readOnly"))).isEmpty();
    }

    @Test
    void testReadOnlyToolWithApp() throws Exception {
        assertThat(McpAppToolMeta.of(toolOf("readOnlyWithApp")))
                .isEqualTo(Map.of(
                        "ui", Map.of("resourceUri", "ui://jenkins/test"), "ui/resourceUri", "ui://jenkins/test"));
    }

    @Test
    void testWriteToolIsVisibleToTheModelOnly() throws Exception {
        assertThat(McpAppToolMeta.of(toolOf("write"))).isEqualTo(Map.of("ui", Map.of("visibility", List.of("model"))));
        assertThat(McpAppToolMeta.of(toolOf("readOnlyButDestructive")))
                .isEqualTo(Map.of("ui", Map.of("visibility", List.of("model"))));
    }

    @Test
    void testResourceUriMustUseTheUiScheme() {
        assertThatThrownBy(() -> McpAppToolMeta.of(toolOf("wrongScheme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://example.com/view.html");
    }

    private static Tool toolOf(String methodName) throws NoSuchMethodException {
        return Tools.class.getDeclaredMethod(methodName).getAnnotation(Tool.class);
    }

    static class Tools {

        @Tool(annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
        void readOnly() {}

        @Tool(
                annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false),
                ui = @Tool.Ui(resourceUri = "ui://jenkins/test"))
        void readOnlyWithApp() {}

        @Tool
        void write() {}

        @Tool(annotations = @Tool.Annotations(readOnlyHint = true))
        void readOnlyButDestructive() {}

        @Tool(
                annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false),
                ui = @Tool.Ui(resourceUri = "https://example.com/view.html"))
        void wrongScheme() {}
    }
}
