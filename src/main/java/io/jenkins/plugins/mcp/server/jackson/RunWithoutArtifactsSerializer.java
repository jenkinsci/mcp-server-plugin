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
import org.kohsuke.stapler.export.DataWriter;
import org.kohsuke.stapler.export.ExportConfig;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.kohsuke.stapler.export.TreePruner;

/**
 * Custom serializer for Run objects that excludes the artifacts field to reduce payload size.
 * This is used by the getBuild tool to provide build information without the potentially large artifacts array.
 * Use the getBuildArtifacts tool to retrieve artifacts separately.
 *
 * This implementation uses Jenkins' TreePruner to filter out the artifacts field during serialization,
 * which is more efficient and robust than post-processing approaches. The pruner ensures that artifacts
 * are never serialized in the first place, saving both memory and processing time.
 */
public class RunWithoutArtifactsSerializer extends JsonSerializer<Run> {
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();

    // TreePruner that excludes only the "artifacts" field
    private static final TreePruner ARTIFACTS_PRUNER = new TreePruner() {
        @Override
        public TreePruner accept(Object node, org.kohsuke.stapler.export.Property prop) {
            return "artifacts".equals(prop.name) ? null : TreePruner.DEFAULT;
        }
    };

    @Override
    public void serialize(Run value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        StringWriter sw = new StringWriter();
        ExportConfig config = new ExportConfig().withFlavor(Flavor.JSON);
        DataWriter dw = Flavor.JSON.createDataWriter(value, sw, config);
        Model model = MODEL_BUILDER.get(value.getClass());

        // Use TreePruner to exclude only the artifacts field during serialization
        model.writeTo(value, ARTIFACTS_PRUNER, dw);

        gen.writeRawValue(sw.toString());
    }
}
