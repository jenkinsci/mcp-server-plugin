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
import java.util.regex.Pattern;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;

/**
 * Custom serializer for Run objects that excludes the artifacts field to reduce payload size.
 * This is used by the getBuild tool to provide build information without the potentially large artifacts array.
 * Use the getBuildArtifacts tool to retrieve artifacts separately.
 */
public class RunWithoutArtifactsSerializer extends JsonSerializer<Run> {
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();
    private static final Pattern ARTIFACTS_PATTERN = Pattern.compile("\"artifacts\"\\s*:\\s*\\[.*?\\]\\s*,?", Pattern.DOTALL);

    @Override
    public void serialize(Run value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        StringWriter sw = new StringWriter();
        org.kohsuke.stapler.export.DataWriter dw = Flavor.JSON.createDataWriter(value, sw);
        Model p = MODEL_BUILDER.get(value.getClass());

        // Use the standard writeTo method
        p.writeTo(value, dw);

        // Post-process the JSON to remove the artifacts field
        String json = sw.toString();
        String filteredJson = ARTIFACTS_PATTERN.matcher(json).replaceAll("");

        // Clean up any trailing commas that might be left
        filteredJson = filteredJson.replaceAll(",\\s*}", "}");

        gen.writeRawValue(filteredJson);
    }
}
