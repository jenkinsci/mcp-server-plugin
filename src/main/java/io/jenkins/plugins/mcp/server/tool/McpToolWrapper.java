/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2025, Gong Yi.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */

package io.jenkins.plugins.mcp.server.tool;

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
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class McpToolWrapper {
	private static final SchemaGenerator TYPE_SCHEMA_GENERATOR;
	private static final SchemaGenerator SUBTYPE_SCHEMA_GENERATOR;
	private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = true;
	private static final ModelBuilder MODEL_BUILDER = new ModelBuilder();
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	static {
		com.github.victools.jsonschema.generator.Module jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
		com.github.victools.jsonschema.generator.Module openApiModule = new Swagger2Module();


		SchemaGeneratorConfigBuilder schemaGeneratorConfigBuilder = new SchemaGeneratorConfigBuilder(
				SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
				.with(jacksonModule)
				.with(openApiModule)
				.with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
				.with(Option.PLAIN_DEFINITION_KEYS);

		SchemaGeneratorConfig typeSchemaGeneratorConfig = schemaGeneratorConfigBuilder.build();
		TYPE_SCHEMA_GENERATOR = new SchemaGenerator(typeSchemaGeneratorConfig);

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
					|| schemaAnnotation.requiredMode() == Schema.RequiredMode.AUTO || schemaAnnotation.required();
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
		var isExported = item.getClass().getAnnotation(ExportedBean.class) != null;
		if (isExported) {
			StringWriter sw = new StringWriter();
			var dw = Flavor.JSON.createDataWriter(item, sw);
			Model p = MODEL_BUILDER.get(item.getClass());
			p.writeTo(item, dw);
			return sw.toString();
		} else {
			return OBJECT_MAPPER.writeValueAsString(item);
		}
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

			var resultBuilder = McpSchema.CallToolResult.builder()
					.isError(false);
			if (result instanceof List listResult) {
				for (var item : listResult) {
					resultBuilder.addTextContent(toJson(item));
				}
			} else {
				resultBuilder.addTextContent(toJson(result));

			}
			return resultBuilder.build();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}


	}

	McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> args) {

		var oldUser = User.current();
		try {
			String userId= (String) args.get("userId");
			var user=User.get(userId,false,Map.of());
			if (user != null) {
				ACL.as(user);
			}


			var methodArgs = Arrays.stream(method.getParameters()).map(
							param -> {
								var arg = args.get(param.getName());
								if (arg != null) {
									return objectMapper.convertValue(arg, param.getType());
								} else {
									return null;
								}
							}
					)
					.toArray();

			var result = method.invoke(target, methodArgs);
			return toMcpResult(result);
		} catch (Exception e) {
			return McpSchema.CallToolResult.builder()
					.addTextContent(e.getMessage())
					.isError(true)
					.build();
		} finally {
			ACL.as(oldUser);
		}
	}


	public McpServerFeatures.SyncToolSpecification asSyncToolSpecification() {
		return new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool(getToolName(),
						getToolDescription(),
						generateForMethodInput()),
				this::call
		);


	}

}
