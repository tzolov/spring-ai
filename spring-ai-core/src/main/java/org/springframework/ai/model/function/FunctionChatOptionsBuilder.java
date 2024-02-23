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

package org.springframework.ai.model.function;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.util.Assert;

/**
 * Builder for {@link FunctionChatOptions}. Using the {@link FunctionChatOptions} permits
 * options portability between different AI providers that support function-calling.
 *
 * @author Christian Tzolov
 * @since 0.8.1
 */
public class FunctionChatOptionsBuilder {

	private final FunctionChatOptions options;

	public FunctionChatOptionsBuilder() {

		this.options = new FunctionChatOptions() {
			private Float temperature;

			private Float topP;

			private Integer topK;

			private List<FunctionCallback> functionCallbacks = new ArrayList<>();

			private Set<String> functions = new HashSet<>();

			@Override
			public Float getTemperature() {
				return this.temperature;
			}

			@Override
			public void setTemperature(Float temperature) {
				Assert.notNull(temperature, "Temperature must not be null");
				this.temperature = temperature;
			}

			@Override
			public Float getTopP() {
				return this.topP;
			}

			@Override
			public void setTopP(Float topP) {
				Assert.notNull(topP, "TopP must not be null");
				this.topP = topP;
			}

			@Override
			public Integer getTopK() {
				return this.topK;
			}

			@Override
			public void setTopK(Integer topK) {
				Assert.notNull(topK, "TopK must not be null");
				this.topK = topK;
			}

			@Override
			public List<FunctionCallback> getFunctionCallbacks() {
				return this.functionCallbacks;
			}

			@Override
			public void setFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
				Assert.notNull(functionCallbacks, "FunctionCallbacks must not be null");
				this.functionCallbacks = functionCallbacks;
			}

			@Override
			public Set<String> getFunctions() {
				return this.functions;
			}

			@Override
			public void setFunctions(Set<String> functions) {
				Assert.notNull(functions, "Functions must not be null");
				this.functions = functions;
			}
		};
	}

	public FunctionChatOptionsBuilder withFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
		this.options.setFunctionCallbacks(functionCallbacks);
		return this;
	}

	public FunctionChatOptionsBuilder withFunctionCallback(FunctionCallback functionCallback) {
		Assert.notNull(functionCallback, "FunctionCallback must not be null");
		this.options.getFunctionCallbacks().add(functionCallback);
		return this;
	}

	public FunctionChatOptionsBuilder withFunctions(Set<String> functions) {
		this.options.setFunctions(functions);
		return this;
	}

	public FunctionChatOptionsBuilder withFunction(String function) {
		Assert.notNull(function, "Function must not be null");
		this.options.getFunctions().add(function);
		return this;
	}

	public FunctionChatOptionsBuilder withTemperature(Float temperature) {
		this.options.setTemperature(temperature);
		return this;
	}

	public FunctionChatOptionsBuilder withTopP(Float topP) {
		this.options.setTopP(topP);
		return this;
	}

	public FunctionChatOptionsBuilder withTopK(Integer topK) {
		this.options.setTopK(topK);
		return this;
	}

	public FunctionChatOptions build() {
		return this.options;
	}

}
