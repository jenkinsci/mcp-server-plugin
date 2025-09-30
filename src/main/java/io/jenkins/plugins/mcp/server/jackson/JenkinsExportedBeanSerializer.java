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
import java.lang.reflect.Type;
import org.jvnet.tiger_types.Types;
import org.kohsuke.stapler.export.DataWriter;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;

public class JenkinsExportedBeanSerializer extends JsonSerializer<Object> {
    private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        // StringWriter sw = new StringWriter();
        // var dw = Flavor.JSON.createDataWriter(value, sw);
        Model p = MODEL_BUILDER.get(value.getClass());
        p.writeTo(value, new JsonGeneratorDataWriter(gen));
    }

    private static class JsonGeneratorDataWriter implements DataWriter {
        private final JsonGenerator gen;
        Class classAttr;

        public JsonGeneratorDataWriter(JsonGenerator gen) {
            this.gen = gen;
            classAttr = null;
        }

        @Override
        public void name(String name) throws IOException {
            gen.writeFieldName(name);
        }

        @Override
        public void valuePrimitive(Object v) throws IOException {
            if (v instanceof Integer) {
                gen.writeNumber((Integer) v);
            } else if (v instanceof Long) {
                gen.writeNumber((Long) v);
            } else if (v instanceof Float) {
                gen.writeNumber((Float) v);
            } else if (v instanceof Double) {
                gen.writeNumber((Double) v);
            } else if (v instanceof Boolean) {
                gen.writeBoolean((Boolean) v);
            } else if (v instanceof Byte) {
                gen.writeNumber((Byte) v);
            } else if (v instanceof Short) {
                gen.writeNumber((Short) v);
            } else if (v instanceof Character) {
                gen.writeString(v.toString());
            } else {
                throw new IllegalArgumentException("Unsupported primitive type: " + v.getClass());
            }
        }

        @Override
        public void value(String v) throws IOException {
            gen.writeString(v);
        }

        @Override
        public void valueNull() throws IOException {
            gen.writeNull();
        }

        @Override
        public void startArray() throws IOException {
            gen.writeStartArray();
        }

        @Override
        public void endArray() throws IOException {
            gen.writeEndArray();
        }

        @Override
        public void type(Type expected, Class actual) throws IOException {
            classAttr = map(expected, actual);
        }

        @Override
        public void startObject() throws IOException {
            gen.writeStartObject();
            if (classAttr != null) {
                name(CLASS_PROPERTY_NAME);
                value(classAttr.getName());
                classAttr = null;
            }
        }

        @Override
        public void endObject() throws IOException {
            gen.writeEndObject();
        }

        Class map(Type expected, Class actual) {
            if (actual == null) {
                return null; // nothing to write
            }
            if (expected == actual) {
                return null; // fast pass when we don't need it
            }
            if (expected == null) {
                return actual; // we need to print it
            }
            if (Types.erasure(expected) == actual) {
                return null; // slow pass
            }
            return actual;
        }
    }
}
