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

package io.jenkins.plugins.mcp.server.jackson;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.kohsuke.stapler.export.NamedPathPruner;
import org.kohsuke.stapler.export.Property;
import org.kohsuke.stapler.export.TreePruner;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class JenkinsExportedBeanSerializer extends ValueSerializer<Object> {

    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();
    // remove some values which are not useful in the JSON output
    private static final List<String> EXCLUDED_PROPERTIES =
            List.of("enclosingBlocks", "enclosingBlockNames", "nodeId");

    private static final class ExclusionPruner extends TreePruner {
        private final TreePruner delegate;
        private ExclusionPruner next;

        private ExclusionPruner(TreePruner delegate) {
            this.delegate = delegate;
        }

        @Override
        public TreePruner accept(Object node, Property prop) {
            if (EXCLUDED_PROPERTIES.contains(prop.name)) {
                return null;
            }
            TreePruner child = delegate.accept(node, prop);
            if (child == null) {
                return null;
            }
            if (child == delegate) {
                // delegate did not advance depth (inline/merge property), as done in ByDepth#accept
                return this;
            }
            if (next == null) {
                next = new ExclusionPruner(child);
            }
            return next;
        }
    }

    private static final TreePruner CLEANER_PRUNER = new ExclusionPruner(new TreePruner.ByDepth(1));

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializationContext serializers) {
        String tree = (String) serializers.getAttribute("tree");

        StringWriter sw = new StringWriter();
        Model p = MODEL_BUILDER.get(value.getClass());

        TreePruner treePruner;
        if (StringUtils.isEmpty(tree)) {
            treePruner = CLEANER_PRUNER;
        } else {
            treePruner = new NamedPathPruner(tree);
        }

        try {
            var dw = Flavor.JSON.createDataWriter(value, sw);
            p.writeTo(value, treePruner, dw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        gen.writeRawValue(sw.toString());
    }
}
