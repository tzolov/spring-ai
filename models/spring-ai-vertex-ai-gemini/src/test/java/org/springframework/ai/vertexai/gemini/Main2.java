/*
 * Copyright 2024-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vertexai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

import org.springframework.ai.vertexai.gemini.function.MockWeatherService2;

/**
 * @author Christian Tzolov
 */
public class Main2 {

	public static void main(String[] args) {
		JacksonModule jacksonModule = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
		Swagger2Module swaggerModule = new Swagger2Module();

		SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12,
				OptionPreset.PLAIN_JSON)
			.with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
			.with(Option.PLAIN_DEFINITION_KEYS)
			.with(swaggerModule)
			.with(jacksonModule);

		SchemaGeneratorConfig config = configBuilder.build();
		SchemaGenerator generator = new SchemaGenerator(config);

		ObjectNode node = generator.generateSchema(MockWeatherService2.Request.class);

		System.out.println(node.toPrettyString());

		substituteTypeAttributeValuesToUpperCase(node);

		System.out.println(node.toPrettyString());
	}

	// ArrayNode arrayNode;
	public static void substituteTypeAttributeValuesToUpperCase(ObjectNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			node.fields().forEachRemaining(entry -> {
				JsonNode value = entry.getValue();
				if (value.isObject()) {
					substituteTypeAttributeValuesToUpperCase((ObjectNode) value);
				}
				else if (value.isArray()) {
					((ArrayNode) value).elements().forEachRemaining(element -> {
						if (element.isObject() || element.isArray()) {
							substituteTypeAttributeValuesToUpperCase((ObjectNode) element);
						}
					});
				}
				else if (value.isTextual() && entry.getKey().equals("type")) {
					String oldValue = ((ObjectNode) node).get("type").asText();
					((ObjectNode) node).put("type", oldValue.toUpperCase());
				}
			});
		}
		else if (node.isArray()) {
			node.elements().forEachRemaining(element -> {
				if (element.isObject() || element.isArray()) {
					substituteTypeAttributeValuesToUpperCase((ObjectNode) element);
				}
			});
		}
	}

}
