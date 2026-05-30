/*
 *
 * The MIT License
 *
 * Copyright (c) 2025, Derek Taubert.
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

package io.jenkins.plugins.mcp.server.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import hudson.model.Run;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.kohsuke.stapler.export.NamedPathPruner;
import org.kohsuke.stapler.export.Property;
import org.kohsuke.stapler.export.TreePruner;

/**
 * Custom serializer for Run objects that excludes the artifacts field to reduce payload size.
 * This is used by the getBuild tool to provide build information without the potentially large artifacts array.
 * Use the getBuildArtifacts tool to retrieve artifacts separately.
 *
 * This implementation mirrors {@link JenkinsExportedBeanSerializer}: it honors the optional "tree" field
 * selection expression when present, and otherwise applies a cleaner pruner. In both cases the "artifacts"
 * field is always excluded so getBuild never returns artifacts.
 */
public class RunWithoutArtifactsSerializer extends JsonSerializer<Run> {
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();

    // Properties excluded from the default (no tree expression) output. Mirrors
    // JenkinsExportedBeanSerializer's cleaner and additionally drops "artifacts".
    private static final List<String> EXCLUDED_PROPERTIES = List.of("enclosingBlocks", "nodeId", "artifacts");

    private static final TreePruner CLEANER_PRUNER = new TreePruner() {
        @Override
        public TreePruner accept(Object node, Property prop) {
            return EXCLUDED_PROPERTIES.contains(prop.name) ? null : TreePruner.DEFAULT;
        }
    };

    @Override
    public void serialize(Run value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        String tree = (String) serializers.getAttribute("tree");

        StringWriter sw = new StringWriter();
        var dw = Flavor.JSON.createDataWriter(value, sw);
        Model model = MODEL_BUILDER.get(value.getClass());

        TreePruner treePruner;
        if (StringUtils.isEmpty(tree)) {
            treePruner = CLEANER_PRUNER;
        } else {
            // Honor the caller's field selection, but still guarantee artifacts are never returned.
            treePruner = new ArtifactsExcludingPruner(new NamedPathPruner(tree));
        }

        model.writeTo(value, treePruner, dw);
        gen.writeRawValue(sw.toString());
    }

    // Delegating pruner that always drops the "artifacts" property while otherwise honoring the delegate.
    private static final class ArtifactsExcludingPruner extends TreePruner {
        private final TreePruner delegate;

        ArtifactsExcludingPruner(TreePruner delegate) {
            this.delegate = delegate;
        }

        @Override
        public TreePruner accept(Object node, Property prop) {
            if ("artifacts".equals(prop.name)) {
                return null;
            }
            return delegate.accept(node, prop);
        }
    }
}
