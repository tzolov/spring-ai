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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import org.springframework.ai.chat.ChatOptions;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.Assert;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author Christian Tzolov
 * @since 0.8.1
 */
@JsonInclude(Include.NON_NULL)
public class VertexAiGeminiChatOptions implements ChatOptions {

	// https://cloud.google.com/vertex-ai/docs/reference/rest/v1/GenerationConfig

	public enum TransportType {

		GRPC, REST

	}

	// @formatter:off
	/**
	 * Optional. Stop sequences.
	 */
	private @JsonProperty("stopSequences") List<String> stopSequences;
	/**
	 * Optional. Controls the randomness of predictions.
	 */
	private @JsonProperty("temperature") Float temperature;
	/**
	 * Optional. If specified, nucleus sampling will be used.
	 */
	private @JsonProperty("topP") Float topP;
	/**
	 * Optional. If specified, top k sampling will be used.
	 */
	private @JsonProperty("topK") Float topK;
	/**
	 * Optional. The maximum number of tokens to generate.
	 */
	private @JsonProperty("candidateCount") Integer candidateCount;
	/**
	 * Optional. The maximum number of tokens to generate.
	 */
	private @JsonProperty("maxOutputTokens") Integer maxOutputTokens;
	/**
	 * Gemini model name.
	 */
	private @JsonProperty("modelName") String modelName;

	/**
	 * Tool Function Callbacks to register with the ChatClient.
	 * For Prompt Options the functionCallbacks are automatically enabled for the duration of the prompt execution.
	 * For Default Options the functionCallbacks are registered but disabled by default. Use the enableFunctions to set the functions
	 * from the registry to be used by the ChatClient chat completion requests.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private List<FunctionCallback> functionCallbacks = new ArrayList<>();

	/**
	 * List of functions, identified by their names, to configure for function calling in
	 * the chat completion requests.
	 * Functions with those names must exist in the functionCallbacks registry.
	 * The {@link #functionCallbacks} from the PromptOptions are automatically enabled for the duration of the prompt execution.
	 *
	 * Note that function enabled with the default options are enabled for all chat completion requests. This could impact the token count and the billing.
	 * If the functions is set in a prompt options, then the enabled functions are only active for the duration of this prompt execution.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private Set<String> functions = new HashSet<>();

	/**
	 * The transport type to use for the Gemini Chat Client.
	 */
	private TransportType transportType = TransportType.GRPC;
	// @formatter:on

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private VertexAiGeminiChatOptions options = new VertexAiGeminiChatOptions();

		public Builder withStopSequences(List<String> stopSequences) {
			this.options.setStopSequences(stopSequences);
			return this;
		}

		public Builder withTemperature(Float temperature) {
			this.options.setTemperature(temperature);
			return this;
		}

		public Builder withTopP(Float topP) {
			this.options.setTopP(topP);
			return this;
		}

		public Builder withTopK(Float topK) {
			this.options.setTopK(topK);
			return this;
		}

		public Builder withCandidateCount(Integer candidateCount) {
			this.options.setCandidateCount(candidateCount);
			return this;
		}

		public Builder withMaxOutputTokens(Integer maxOutputTokens) {
			this.options.setMaxOutputTokens(maxOutputTokens);
			return this;
		}

		public Builder withModelName(String modelName) {
			this.options.setModelName(modelName);
			return this;
		}

		public Builder withFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
			this.options.functionCallbacks = functionCallbacks;
			return this;
		}

		public Builder withFunctions(Set<String> functionNames) {
			Assert.notNull(functionNames, "Function names must not be null");
			this.options.functions = functionNames;
			return this;
		}

		public Builder withFunction(String functionName) {
			Assert.hasText(functionName, "Function name must not be empty");
			this.options.functions.add(functionName);
			return this;
		}

		public Builder withTransportType(TransportType transportType) {
			this.options.setTransportType(transportType);
			return this;
		}

		public VertexAiGeminiChatOptions build() {
			return this.options;
		}

	}

	public List<String> getStopSequences() {
		return this.stopSequences;
	}

	public void setStopSequences(List<String> stopSequences) {
		this.stopSequences = stopSequences;
	}

	@Override
	public Float getTemperature() {
		return this.temperature;
	}

	@Override
	public void setTemperature(Float temperature) {
		this.temperature = temperature;
	}

	@Override
	public Float getTopP() {
		return this.topP;
	}

	@Override
	public void setTopP(Float topP) {
		this.topP = topP;
	}

	@Override
	@JsonIgnore
	public Integer getTopK() {
		return (this.topK != null) ? this.topK.intValue() : null;
	}

	public void setTopK(Float topK) {
		this.topK = topK;
	}

	@Override
	@JsonIgnore
	public void setTopK(Integer topK) {
		this.topK = (topK != null) ? topK.floatValue() : null;
	}

	public Integer getCandidateCount() {
		return this.candidateCount;
	}

	public void setCandidateCount(Integer candidateCount) {
		this.candidateCount = candidateCount;
	}

	public Integer getMaxOutputTokens() {
		return this.maxOutputTokens;
	}

	public void setMaxOutputTokens(Integer maxOutputTokens) {
		this.maxOutputTokens = maxOutputTokens;
	}

	public String getModelName() {
		return this.modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	public List<FunctionCallback> getFunctionCallbacks() {
		return this.functionCallbacks;
	}

	public void setFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
		this.functionCallbacks = functionCallbacks;
	}

	public Set<String> getFunctions() {
		return this.functions;
	}

	public void setFunctions(Set<String> functions) {
		this.functions = functions;
	}

	public TransportType getTransportType() {
		return this.transportType;
	}

	public void setTransportType(TransportType transportType) {
		this.transportType = transportType;
	}

}
