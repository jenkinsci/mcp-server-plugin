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

import hudson.Extension;
import hudson.ExtensionList;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.tool.McpToolWrapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jenkins.model.Jenkins;
import lombok.SneakyThrows;

@Extension
public class McpAppsExtension implements McpServerExtension {

    static final String RESOURCE_MIME_TYPE = "text/html;profile=mcp-app";

    static final Map<String, Object> RESOURCE_META = Map.of(
            "ui",
            Map.of("prefersBorder", true, "csp", Map.of("connectDomains", List.of(), "resourceDomains", List.of())));

    private final Map<String, String> pages = new ConcurrentHashMap<>();

    @Override
    public List<McpServerFeatures.SyncResourceSpecification> getSyncResources() {
        return ExtensionList.lookup(McpApp.class).stream()
                .map(this::resourceFor)
                .toList();
    }

    private McpServerFeatures.SyncResourceSpecification resourceFor(McpApp app) {
        var resource = McpSchema.Resource.builder(app.uri(), app.name())
                .title(app.title())
                .description(app.description())
                .mimeType(RESOURCE_MIME_TYPE)
                .meta(RESOURCE_META)
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> read(app));
    }

    private McpSchema.ReadResourceResult read(McpApp app) {
        var page = this.pages.computeIfAbsent(app.uri(), uri -> pageOf(app));
        var contents = McpSchema.TextResourceContents.builder(app.uri(), page)
                .mimeType(RESOURCE_MIME_TYPE)
                .meta(RESOURCE_META)
                .build();
        return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
    }

    private static String pageOf(McpApp app) {
        var look = JenkinsLook.readFrom(Jenkins.get().getServletContext(), Jenkins.VERSION);
        return McpAppPage.assemble(builtPage(app), app.name(), readOnly(app.callableTools()), look);
    }

    @SneakyThrows
    private static String builtPage(McpApp app) {
        try (InputStream in = Jenkins.get().getPluginManager().uberClassLoader.getResourceAsStream(app.page())) {
            return new String(
                    Objects.requireNonNull(in, "The MCP App page " + app.page() + " is missing, run mvn package")
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
        }
    }

    static List<String> readOnly(List<String> tools) {
        var readOnly = new HashMap<String, Boolean>();
        for (var extension : McpServerExtension.all()) {
            for (var method : extension.getClass().getMethods()) {
                var tool = method.getAnnotation(Tool.class);
                if (tool != null) {
                    readOnly.merge(
                            McpToolWrapper.toolName(method),
                            McpAppToolMeta.isReadOnly(tool.annotations()),
                            Boolean::logicalAnd);
                }
            }
        }
        return tools.stream().filter(name -> readOnly.getOrDefault(name, false)).toList();
    }
}
