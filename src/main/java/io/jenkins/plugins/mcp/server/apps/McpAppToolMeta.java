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

import io.jenkins.plugins.mcp.server.annotation.Tool;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class McpAppToolMeta {

    private McpAppToolMeta() {}

    public static Map<String, Object> of(Tool tool) {
        var meta = new HashMap<String, Object>();
        var ui = new HashMap<String, Object>();
        var resourceUri = tool.ui().resourceUri();
        if (!resourceUri.isEmpty()) {
            if (!resourceUri.startsWith("ui://")) {
                throw new IllegalArgumentException("The ui resourceUri must use the ui:// scheme, got: " + resourceUri);
            }
            ui.put("resourceUri", resourceUri);
            meta.put("ui/resourceUri", resourceUri);
        }
        if (!isReadOnly(tool.annotations())) {
            ui.put("visibility", List.of("model"));
        }
        if (!ui.isEmpty()) {
            meta.put("ui", ui);
        }
        return meta;
    }

    static boolean isReadOnly(Tool.Annotations annotations) {
        return annotations.readOnlyHint() && !annotations.destructiveHint();
    }
}
