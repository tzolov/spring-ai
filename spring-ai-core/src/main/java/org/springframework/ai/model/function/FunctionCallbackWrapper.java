/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.model.function;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.util.Assert;

/**
 * Note that the underlying function is responsible for converting the output into format
 * that can be consumed by the Model. The default implementation converts the output into
 * String before sending it to the Model. Provide a custom function responseConverter
 * implementation to override this.
 *
 */
public class FunctionCallbackWrapper<I, O> extends AbstractFunctionCallback<I, O> {

	private final Function<I, O> function;

	/**
	 * Constructs a new {@link FunctionCallbackWrapper} with the given name, description,
	 * input type and JsonSchema request instance parser.
	 * @param name Function name.
	 * @param description Function description.
	 * @param inputTypeSchema Function input type schema.
	 * @param inputType Function input type class.
	 * @param responseConverter Optional function response converter.
	 * @param function The function to be wrapped.
	 */
	private FunctionCallbackWrapper(String name, String description, String inputTypeSchema, Class<I> inputType,
			Function<O, String> responseConverter, Function<I, O> function) {
		this(name, description, inputTypeSchema, inputType, responseConverter, function, (jsonArgumentRequest,
				requestClass) -> ModelOptionsUtils.jsonToObject((String) jsonArgumentRequest, requestClass));
	}

	/**
	 * Constructs a new {@link FunctionCallbackWrapper} with the given name, description,
	 * input type and request instance parser.
	 * @param name Function name.
	 * @param description Function description.
	 * @param inputTypeSchema Function input type schema.
	 * @param inputType Function input type class.
	 * @param responseConverter Optional function response converter.
	 * @param function The function to be wrapped.
	 * @param requestInstanceParser The function to parse the input request string into
	 * the input type instance.
	 */
	private FunctionCallbackWrapper(String name, String description, String inputTypeSchema, Class<I> inputType,
			Function<O, String> responseConverter, Function<I, O> function,
			BiFunction<Object, Class<I>, I> requestInstanceParser) {
		super(name, description, inputTypeSchema, inputType, responseConverter, requestInstanceParser);
		Assert.notNull(function, "Function must not be null");
		this.function = function;
	}

	@SuppressWarnings("unchecked")
	private static <I, O> Class<I> resolveInputType(Function<I, O> function) {
		return (Class<I>) TypeResolverHelper.getFunctionInputClass((Class<Function<I, O>>) function.getClass());
	}

	@Override
	public O apply(I input) {
		return this.function.apply(input);
	}

	public static <I, O> Builder<I, O> builder(Function<I, O> function) {
		return new Builder<>(function);
	}

	public static class Builder<I, O> {

		public enum SchemaType {

			JSON_SCHEMA, OPEN_API_SCHEMA, ANTHROPIC_XML_SCHEMA

		}

		private String name;

		private String description;

		private Class<I> inputType;

		private final Function<I, O> function;

		private SchemaType schemaType = SchemaType.JSON_SCHEMA;

		private BiFunction<Object, Class<I>, I> requestInstanceParser = (jsonArgumentRequest, requestClass) -> {
			return ModelOptionsUtils.jsonToObject((String) jsonArgumentRequest, requestClass);
		};

		public Builder(Function<I, O> function) {
			Assert.notNull(function, "Function must not be null");
			this.function = function;
		}

		// By default the response is converted to a JSON string.
		private Function<O, String> responseConverter = (response) -> ModelOptionsUtils.toJsonString(response);

		private String inputTypeSchema;

		private ObjectMapper objectMapper = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

		public Builder<I, O> withName(String name) {
			Assert.hasText(name, "Name must not be empty");
			this.name = name;
			return this;
		}

		public Builder<I, O> withDescription(String description) {
			Assert.hasText(description, "Description must not be empty");
			this.description = description;
			return this;
		}

		@SuppressWarnings("unchecked")
		public Builder<I, O> withInputType(Class<?> inputType) {
			this.inputType = (Class<I>) inputType;
			return this;
		}

		public Builder<I, O> withResponseConverter(Function<O, String> responseConverter) {
			Assert.notNull(responseConverter, "ResponseConverter must not be null");
			this.responseConverter = responseConverter;
			return this;
		}

		public Builder<I, O> withInputTypeSchema(String inputTypeSchema) {
			Assert.hasText(inputTypeSchema, "InputTypeSchema must not be empty");
			this.inputTypeSchema = inputTypeSchema;
			return this;
		}

		public Builder<I, O> withObjectMapper(ObjectMapper objectMapper) {
			Assert.notNull(objectMapper, "ObjectMapper must not be null");
			this.objectMapper = objectMapper;
			return this;
		}

		public Builder<I, O> withSchemaType(SchemaType schemaType) {
			Assert.notNull(schemaType, "SchemaType must not be null");
			this.schemaType = schemaType;
			return this;
		}

		public Builder<I, O> withRequestInstanceParser(BiFunction<Object, Class<I>, I> requestInstanceParser) {
			Assert.notNull(requestInstanceParser, "RequestInstanceParser must not be null");
			this.requestInstanceParser = requestInstanceParser;
			return this;
		}

		public FunctionCallbackWrapper<I, O> build() {

			Assert.hasText(this.name, "Name must not be empty");
			Assert.hasText(this.description, "Description must not be empty");
			// Assert.notNull(this.inputType, "InputType must not be null");
			Assert.notNull(this.function, "Function must not be null");
			Assert.notNull(this.responseConverter, "ResponseConverter must not be null");
			Assert.notNull(this.objectMapper, "ObjectMapper must not be null");

			if (this.inputType == null) {
				this.inputType = resolveInputType(this.function);
			}

			if (this.inputTypeSchema == null) {
				if (this.schemaType == SchemaType.ANTHROPIC_XML_SCHEMA) {
					XmlHelper.Parameters params = XmlHelper.xmlSchemaParams(this.inputType);
					this.inputTypeSchema = XmlHelper.toXml(params);
					this.requestInstanceParser = (xmlArguments, requestClass) -> {
						return ModelOptionsUtils.mapToClass((Map<String, Object>) xmlArguments, requestClass);
					};
				}
				else {
					boolean upperCaseTypeValues = this.schemaType == SchemaType.OPEN_API_SCHEMA;
					this.inputTypeSchema = ModelOptionsUtils.getJsonSchema(this.inputType, upperCaseTypeValues);
				}
			}

			return new FunctionCallbackWrapper<>(this.name, this.description, this.inputTypeSchema, this.inputType,
					this.responseConverter, this.function, this.requestInstanceParser);
		}

	}

}