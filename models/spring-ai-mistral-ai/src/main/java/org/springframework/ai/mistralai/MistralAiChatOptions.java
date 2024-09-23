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
package org.springframework.ai.mistralai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi.ChatCompletionRequest.ResponseFormat;
import org.springframework.ai.mistralai.api.MistralAiApi.ChatCompletionRequest.ToolChoice;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.ai.mistralai.api.MistralAiApi.FunctionTool;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.Assert;

/**
 * @author Ricken Bazolo
 * @author Christian Tzolov
 * @author Thomas Vitale
 * @since 0.8.1
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MistralAiChatOptions implements FunctionCallingOptions, ChatOptions {

	/**
	 * ID of the model to use
	 */
	private @JsonProperty("model") String model;

	/**
	 * What sampling temperature to use, between 0.0 and 1.0. Higher values like 0.8 will
	 * make the output more random, while lower values like 0.2 will make it more focused
	 * and deterministic. We generally recommend altering this or top_p but not both.
	 */
	private @JsonProperty("temperature") Double temperature;

	/**
	 * Nucleus sampling, where the model considers the results of the tokens with top_p
	 * probability mass. So 0.1 means only the tokens comprising the top 10% probability
	 * mass are considered. We generally recommend altering this or temperature but not
	 * both.
	 */
	private @JsonProperty("top_p") Double topP;

	/**
	 * The maximum number of tokens to generate in the completion. The token count of your
	 * prompt plus max_tokens cannot exceed the model's context length.
	 */
	private @JsonProperty("max_tokens") Integer maxTokens;

	/**
	 * Whether to inject a safety prompt before all conversations.
	 */
	private @JsonProperty("safe_prompt") Boolean safePrompt;

	/**
	 * The seed to use for random sampling. If set, different calls will generate
	 * deterministic results.
	 */
	private @JsonProperty("random_seed") Integer randomSeed;

	/**
	 * An object specifying the format that the model must output. Setting to { "type":
	 * "json_object" } enables JSON mode, which guarantees the message the model generates
	 * is valid JSON.
	 */
	private @JsonProperty("response_format") ResponseFormat responseFormat;

	/**
	 * Stop generation if this token is detected. Or if one of these tokens is detected
	 * when providing an array.
	 */
	@NestedConfigurationProperty
	private @JsonProperty("stop") List<String> stop;

	/**
	 * A list of tools the model may call. Currently, only functions are supported as a
	 * tool. Use this to provide a list of functions the model may generate JSON inputs
	 * for.
	 */
	@NestedConfigurationProperty
	private @JsonProperty("tools") List<FunctionTool> tools;

	/**
	 * Controls which (if any) function is called by the model. none means the model will
	 * not call a function and instead generates a message. auto means the model can pick
	 * between generating a message or calling a function.
	 */
	@NestedConfigurationProperty
	private @JsonProperty("tool_choice") ToolChoice toolChoice;

	/**
	 * MistralAI Tool Function Callbacks to register with the ChatModel. For Prompt
	 * Options the functionCallbacks are automatically enabled for the duration of the
	 * prompt execution. For Default Options the functionCallbacks are registered but
	 * disabled by default. Use the enableFunctions to set the functions from the registry
	 * to be used by the ChatModel chat completion requests.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private List<FunctionCallback> functionCallbacks = new ArrayList<>();

	/**
	 * List of functions, identified by their names, to configure for function calling in
	 * the chat completion requests. Functions with those names must exist in the
	 * functionCallbacks registry. The {@link #functionCallbacks} from the PromptOptions
	 * are automatically enabled for the duration of the prompt execution.
	 *
	 * Note that function enabled with the default options are enabled for all chat
	 * completion requests. This could impact the token count and the billing. If the
	 * functions is set in a prompt options, then the enabled functions are only active
	 * for the duration of this prompt execution.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private Set<String> functions = new HashSet<>();

	@JsonIgnore
	private Boolean proxyToolCalls;

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final MistralAiChatOptions options = new MistralAiChatOptions();

		public Builder withModel(String model) {
			this.options.setModel(model);
			return this;
		}

		public Builder withModel(MistralAiApi.ChatModel chatModel) {
			this.options.setModel(chatModel.getName());
			return this;
		}

		public Builder withMaxTokens(Integer maxTokens) {
			this.options.setMaxTokens(maxTokens);
			return this;
		}

		public Builder withSafePrompt(Boolean safePrompt) {
			this.options.setSafePrompt(safePrompt);
			return this;
		}

		public Builder withRandomSeed(Integer randomSeed) {
			this.options.setRandomSeed(randomSeed);
			return this;
		}

		public Builder withStop(List<String> stop) {
			this.options.setStop(stop);
			return this;
		}

		public Builder withTemperature(Double temperature) {
			this.options.setTemperature(temperature);
			return this;
		}

		public Builder withTopP(Double topP) {
			this.options.setTopP(topP);
			return this;
		}

		public Builder withResponseFormat(ResponseFormat responseFormat) {
			this.options.responseFormat = responseFormat;
			return this;
		}

		public Builder withTools(List<FunctionTool> tools) {
			this.options.tools = tools;
			return this;
		}

		public Builder withToolChoice(ToolChoice toolChoice) {
			this.options.toolChoice = toolChoice;
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

		public Builder withProxyToolCalls(Boolean proxyToolCalls) {
			this.options.proxyToolCalls = proxyToolCalls;
			return this;
		}

		public MistralAiChatOptions build() {
			return this.options;
		}

	}

	@Override
	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@Override
	public Integer getMaxTokens() {
		return this.maxTokens;
	}

	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	public Boolean getSafePrompt() {
		return this.safePrompt;
	}

	public void setSafePrompt(Boolean safePrompt) {
		this.safePrompt = safePrompt;
	}

	public Integer getRandomSeed() {
		return this.randomSeed;
	}

	public void setRandomSeed(Integer randomSeed) {
		this.randomSeed = randomSeed;
	}

	public ResponseFormat getResponseFormat() {
		return this.responseFormat;
	}

	public void setResponseFormat(ResponseFormat responseFormat) {
		this.responseFormat = responseFormat;
	}

	@Override
	@JsonIgnore
	public List<String> getStopSequences() {
		return getStop();
	}

	@JsonIgnore
	public void setStopSequences(List<String> stopSequences) {
		setStop(stopSequences);
	}

	public List<String> getStop() {
		return this.stop;
	}

	public void setStop(List<String> stop) {
		this.stop = stop;
	}

	public void setTools(List<FunctionTool> tools) {
		this.tools = tools;
	}

	public List<FunctionTool> getTools() {
		return this.tools;
	}

	public void setToolChoice(ToolChoice toolChoice) {
		this.toolChoice = toolChoice;
	}

	public ToolChoice getToolChoice() {
		return this.toolChoice;
	}

	@Override
	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	@Override
	public Double getTopP() {
		return this.topP;
	}

	public void setTopP(Double topP) {
		this.topP = topP;
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
		Assert.notNull(functions, "Function must not be null");
		this.functions = functions;
	}

	@Override
	@JsonIgnore
	public Double getFrequencyPenalty() {
		return null;
	}

	@Override
	@JsonIgnore
	public Double getPresencePenalty() {
		return null;
	}

	@Override
	@JsonIgnore
	public Integer getTopK() {
		return null;
	}

	@Override
	public Boolean getProxyToolCalls() {
		return this.proxyToolCalls;
	}

	public void setProxyToolCalls(Boolean proxyToolCalls) {
		this.proxyToolCalls = proxyToolCalls;
	}

	@Override
	public MistralAiChatOptions copy() {
		return fromOptions(this);
	}

	public static MistralAiChatOptions fromOptions(MistralAiChatOptions fromOptions) {
		return builder().withModel(fromOptions.getModel())
			.withMaxTokens(fromOptions.getMaxTokens())
			.withSafePrompt(fromOptions.getSafePrompt())
			.withRandomSeed(fromOptions.getRandomSeed())
			.withTemperature(fromOptions.getTemperature())
			.withTopP(fromOptions.getTopP())
			.withResponseFormat(fromOptions.getResponseFormat())
			.withStop(fromOptions.getStop())
			.withTools(fromOptions.getTools())
			.withToolChoice(fromOptions.getToolChoice())
			.withFunctionCallbacks(fromOptions.getFunctionCallbacks())
			.withFunctions(fromOptions.getFunctions())
			.withProxyToolCalls(fromOptions.getProxyToolCalls())
			.build();
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((model == null) ? 0 : model.hashCode());
		result = prime * result + ((temperature == null) ? 0 : temperature.hashCode());
		result = prime * result + ((topP == null) ? 0 : topP.hashCode());
		result = prime * result + ((maxTokens == null) ? 0 : maxTokens.hashCode());
		result = prime * result + ((safePrompt == null) ? 0 : safePrompt.hashCode());
		result = prime * result + ((randomSeed == null) ? 0 : randomSeed.hashCode());
		result = prime * result + ((responseFormat == null) ? 0 : responseFormat.hashCode());
		result = prime * result + ((stop == null) ? 0 : stop.hashCode());
		result = prime * result + ((tools == null) ? 0 : tools.hashCode());
		result = prime * result + ((toolChoice == null) ? 0 : toolChoice.hashCode());
		result = prime * result + ((functionCallbacks == null) ? 0 : functionCallbacks.hashCode());
		result = prime * result + ((functions == null) ? 0 : functions.hashCode());
		result = prime * result + ((proxyToolCalls == null) ? 0 : proxyToolCalls.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		MistralAiChatOptions other = (MistralAiChatOptions) obj;
		if (model == null) {
			if (other.model != null)
				return false;
		}
		else if (!model.equals(other.model))
			return false;
		if (temperature == null) {
			if (other.temperature != null)
				return false;
		}
		else if (!temperature.equals(other.temperature))
			return false;
		if (topP == null) {
			if (other.topP != null)
				return false;
		}
		else if (!topP.equals(other.topP))
			return false;
		if (maxTokens == null) {
			if (other.maxTokens != null)
				return false;
		}
		else if (!maxTokens.equals(other.maxTokens))
			return false;
		if (safePrompt == null) {
			if (other.safePrompt != null)
				return false;
		}
		else if (!safePrompt.equals(other.safePrompt))
			return false;
		if (randomSeed == null) {
			if (other.randomSeed != null)
				return false;
		}
		else if (!randomSeed.equals(other.randomSeed))
			return false;
		if (responseFormat == null) {
			if (other.responseFormat != null)
				return false;
		}
		else if (!responseFormat.equals(other.responseFormat))
			return false;
		if (stop == null) {
			if (other.stop != null)
				return false;
		}
		else if (!stop.equals(other.stop))
			return false;
		if (tools == null) {
			if (other.tools != null)
				return false;
		}
		else if (!tools.equals(other.tools))
			return false;
		if (toolChoice != other.toolChoice)
			return false;
		if (functionCallbacks == null) {
			if (other.functionCallbacks != null)
				return false;
		}
		else if (!functionCallbacks.equals(other.functionCallbacks))
			return false;
		if (functions == null) {
			if (other.functions != null)
				return false;
		}
		else if (!functions.equals(other.functions))
			return false;
		if (proxyToolCalls == null) {
			if (other.proxyToolCalls != null)
				return false;
		}
		else if (!proxyToolCalls.equals(other.proxyToolCalls))
			return false;
		return true;
	}

}
