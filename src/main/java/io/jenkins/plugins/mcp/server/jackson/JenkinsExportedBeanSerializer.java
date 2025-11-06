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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.kohsuke.stapler.export.TreePruner;

public class JenkinsExportedBeanSerializer extends JsonSerializer<Object> {
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();

    // remove some values which are not useful in the JSON output
    private static final List<String> EXCLUDED_PROPERTIES = List.of("enclosingBlocks", "nodeId");

    private static final TreePruner CLEANER_PRUNER = new TreePruner() {
        @Override
        public TreePruner accept(Object node, org.kohsuke.stapler.export.Property prop) {
            return EXCLUDED_PROPERTIES.contains(prop.name) ? null : TreePruner.DEFAULT;
        }
    };

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        StringWriter sw = new StringWriter();
        var dw = Flavor.JSON.createDataWriter(value, sw);
        Model p = MODEL_BUILDER.get(value.getClass());
        p.writeTo(value, CLEANER_PRUNER, dw);
        gen.writeRawValue(sw.toString());
    }
}
