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

package io.jenkins.plugins.mcp.server.tool;

import static io.jenkins.plugins.mcp.server.Endpoint.USER_ID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;
import hudson.model.User;
import hudson.security.ACL;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.mcp.server.jackson.JenkinsExportedBeanModule;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Slf4j
public class McpToolWrapper {

    private static final SchemaGenerator SUBTYPE_SCHEMA_GENERATOR;
    private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = true;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        OBJECT_MAPPER.registerModule(new JenkinsExportedBeanModule());
    }

    static {
        com.github.victools.jsonschema.generator.Module jacksonModule =
                new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        com.github.victools.jsonschema.generator.Module openApiModule = new Swagger2Module();

        SchemaGeneratorConfigBuilder schemaGeneratorConfigBuilder = new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .with(jacksonModule)
                .with(openApiModule)
                .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
                .with(Option.PLAIN_DEFINITION_KEYS);

        SchemaGeneratorConfig subtypeSchemaGeneratorConfig = schemaGeneratorConfigBuilder
                .without(Option.SCHEMA_VERSION_INDICATOR)
                .build();
        SUBTYPE_SCHEMA_GENERATOR = new SchemaGenerator(subtypeSchemaGeneratorConfig);
    }

    private final Method method;
    private final Object target;

    private final ObjectMapper objectMapper;

    public McpToolWrapper(ObjectMapper objectMapper, Object target, Method method) {
        this.objectMapper = objectMapper;
        this.target = target;
        this.method = method;
    }

    private static boolean isMethodParameterRequired(Method method, int index) {
        Parameter parameter = method.getParameters()[index];

        var propertyAnnotation = parameter.getAnnotation(JsonProperty.class);
        if (propertyAnnotation != null) {
            return propertyAnnotation.required();
        }

        var schemaAnnotation = parameter.getAnnotation(Schema.class);
        if (schemaAnnotation != null) {
            return schemaAnnotation.requiredMode() == Schema.RequiredMode.REQUIRED
                    || schemaAnnotation.requiredMode() == Schema.RequiredMode.AUTO
                    || schemaAnnotation.required();
        }

        var nullableAnnotation = parameter.getAnnotation(Nullable.class);
        if (nullableAnnotation != null) {
            return false;
        }
        var jakartaNullableAnnotation = parameter.getAnnotation(jakarta.annotation.Nullable.class);
        if (jakartaNullableAnnotation != null) {
            return false;
        }

        var toolParamAnnotation = parameter.getAnnotation(ToolParam.class);
        if (toolParamAnnotation != null) {
            return toolParamAnnotation.required();
        }
        return PROPERTY_REQUIRED_BY_DEFAULT;
    }

    @Nullable
    private static String getMethodParameterDescription(Method method, int index) {
        Parameter parameter = method.getParameters()[index];

        var toolParamAnnotation = parameter.getAnnotation(ToolParam.class);
        if (toolParamAnnotation != null && StringUtils.hasText(toolParamAnnotation.description())) {
            return toolParamAnnotation.description();
        }

        var jacksonAnnotation = parameter.getAnnotation(JsonPropertyDescription.class);
        if (jacksonAnnotation != null && StringUtils.hasText(jacksonAnnotation.value())) {
            return jacksonAnnotation.value();
        }

        var schemaAnnotation = parameter.getAnnotation(Schema.class);
        if (schemaAnnotation != null && StringUtils.hasText(schemaAnnotation.description())) {
            return schemaAnnotation.description();
        }

        return null;
    }

    private static String toJson(Object item) throws IOException {
        return OBJECT_MAPPER.writeValueAsString(item);
    }

    String generateForMethodInput() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", SchemaVersion.DRAFT_2020_12.getIdentifier());
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        List<String> required = new ArrayList<>();

        for (int i = 0; i < method.getParameterCount(); i++) {
            String parameterName = method.getParameters()[i].getName();
            Type parameterType = method.getGenericParameterTypes()[i];

            if (isMethodParameterRequired(method, i)) {
                required.add(parameterName);
            }
            ObjectNode parameterNode = SUBTYPE_SCHEMA_GENERATOR.generateSchema(parameterType);
            String parameterDescription = getMethodParameterDescription(method, i);
            if (StringUtils.hasText(parameterDescription)) {
                parameterNode.put("description", parameterDescription);
            }
            properties.set(parameterName, parameterNode);
        }

        var requiredArray = schema.putArray("required");
        required.forEach(requiredArray::add);

        return schema.toPrettyString();
    }

    String getToolName() {
        Assert.notNull(method, "method cannot be null");
        var tool = method.getAnnotation(Tool.class);
        if (tool == null) {
            return method.getName();
        }
        return StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
    }

    String getToolDescription() {
        Assert.notNull(method, "method cannot be null");
        var tool = method.getAnnotation(Tool.class);
        if (tool != null && !tool.description().isEmpty()) {
            return tool.description();
        }
        return getToolName();
    }

    McpSchema.CallToolResult toMcpResult(Object result) {

        if (result == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Result is null")
                    .isError(false)
                    .build();
        }
        try {

            var resultBuilder = McpSchema.CallToolResult.builder().isError(false);
            // Serialize all results the same way - this fixes the JSON concatenation issue
            // for top-level lists while maintaining proper JSON structure
            resultBuilder.addTextContent(toJson(result));
            return resultBuilder.build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    McpSchema.CallToolResult callRequest(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var oldUser = User.current();
        try {
            var user = tryGetUser(exchange, request);
            if (user != null) {
                ACL.as(user);
            }
            if (log.isTraceEnabled()) {
                log.trace(
                        "Tool call: {} as user '{}', arguments: {}",
                        request.name(),
                        user == null ? "" : user.getId(),
                        request.arguments());
            }

            var methodArgs = Arrays.stream(method.getParameters())
                    .map(param -> {
                        var arg = args.get(param.getName());
                        if (arg != null) {
                            return objectMapper.convertValue(arg, param.getType());
                        } else {
                            return null;
                        }
                    })
                    .toArray();

            var result = method.invoke(target, methodArgs);
            return toMcpResult(result);
        } catch (Exception e) {
            var rootCauseMessage = ExceptionUtils.getRootCauseMessage(e);
            if (rootCauseMessage.isEmpty()) {
                rootCauseMessage = "Error invoking method: " + method.getName();
            }
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(rootCauseMessage)
                    .build();
        } finally {
            ACL.as(oldUser);
        }
    }

    private static User tryGetUser(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        String userId = null;
        var context = exchange.transportContext();
        if (context != null) {
            userId = (String) context.get(USER_ID);
        }
        if (userId == null && request.meta() != null) {
            userId = (String) request.meta().get(USER_ID);
        }
        var user = User.get(userId, false, Map.of());
        return user;
    }

    private Supplier<Map<String, Object>> _meta() {
        var tool = method.getAnnotation(Tool.class);
        if (tool.metas().length > 0) {
            Map<String, Object> metaMap = Arrays.stream(tool.metas())
                    .sequential()
                    .filter(meta -> StringUtils.hasText(meta.property()) && StringUtils.hasText(meta.parameter()))
                    .collect(Collectors.toMap(Tool.Meta::property, Tool.Meta::parameter));
            return () -> metaMap;
        }
        return Map::of;
    }

    private Supplier<McpSchema.ToolAnnotations> toolAnnotations() {
        var tool = method.getAnnotation(Tool.class);
        if (tool.annotations() != null) {
            return () -> new McpSchema.ToolAnnotations(
                    tool.annotations().title(),
                    tool.annotations().readOnlyHint(),
                    tool.annotations().destructiveHint(),
                    tool.annotations().idempotentHint(),
                    tool.annotations().openWorldHint(),
                    tool.annotations().returnDirect());
        }
        return () -> null;
    }

    public McpServerFeatures.SyncToolSpecification asSyncToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(getToolName())
                        .description(getToolDescription())
                        .meta(_meta().get())
                        .annotations(toolAnnotations().get())
                        .inputSchema(generateForMethodInput())
                        .build())
                .callHandler(this::callRequest)
                .build();
    }
}
