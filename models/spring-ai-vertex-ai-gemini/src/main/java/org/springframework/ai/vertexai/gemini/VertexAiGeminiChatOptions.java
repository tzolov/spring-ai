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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import org.springframework.ai.chat.ChatOptions;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author Christian Tzolov
 * @since 0.8.1
 */
@JsonInclude(Include.NON_NULL)
public class VertexAiGeminiChatOptions implements ChatOptions {

	// https://cloud.google.com/vertex-ai/docs/reference/rest/v1/GenerationConfig

	// @formatter:off
	/**
	 * Optional. Stop sequences.
	 */
	private @JsonProperty("stopSequences") List<String> stopSequences;
	/**
	 * Optional. Controls the randomness of predictions.
	 */
	private @JsonProperty("temperature") Float temperature = 0.8f;
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

	private @JsonProperty("echo") Boolean echo;

	private @JsonProperty("frequencyPenalty") Float frequencyPenalty;

	private @JsonProperty("logprobs") Integer logprobs;

	private @JsonProperty("presencePenalty") Float presencePenalty;

	/**
	 * Gemini model name.
	 */
	private @JsonProperty("modelName") String modelName = "gemini-pro-vision";
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

		public Builder withEcho(Boolean echo) {
			this.options.setEcho(echo);
			return this;
		}

		public Builder withFrequencyPenalty(Float frequencyPenalty) {
			this.options.setFrequencyPenalty(frequencyPenalty);
			return this;
		}

		public Builder withLogprobs(Integer logprobs) {
			this.options.setLogprobs(logprobs);
			return this;
		}

		public Builder withPresencePenalty(Float presencePenalty) {
			this.options.setPresencePenalty(presencePenalty);
			return this;
		}

		public Builder withModelName(String modelName) {
			this.options.setModelName(modelName);
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
	public Integer getTopK() {
		return (this.topK != null) ? this.topK.intValue() : null;
	}

	public void setTopK(Float topK) {
		this.topK = topK;
	}

	@Override
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

	public Boolean getEcho() {
		return this.echo;
	}

	public void setEcho(Boolean echo) {
		this.echo = echo;
	}

	public Float getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	public void setFrequencyPenalty(Float frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	public Integer getLogprobs() {
		return this.logprobs;
	}

	public void setLogprobs(Integer logprobs) {
		this.logprobs = logprobs;
	}

	public Float getPresencePenalty() {
		return this.presencePenalty;
	}

	public void setPresencePenalty(Float presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	public String getModelName() {
		return this.modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

}
