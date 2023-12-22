/*
 * Copyright 2023-2023 the original author or authors.
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

package org.springframework.ai.openai.api;

import java.beans.PropertyDescriptor;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.prompt.PromptOptions;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/**
 *
 * @author Christian Tzolov
 */
public class OpenAiOptions implements PromptOptions {

	// @formatter:off
	@JsonProperty("model") String model;
	@JsonProperty("frequency_penalty") Float frequencyPenalty;
	@JsonProperty("logit_bias") Map<String, Object> logitBias;
	@JsonProperty("max_tokens") Integer maxTokens;
	@JsonProperty("n") Integer n;
	@JsonProperty("presence_penalty") Float presencePenalty;
	@JsonProperty("seed") Integer seed;
	@JsonProperty("stop") String stop;
	@JsonProperty("stream") Boolean stream;
	@JsonProperty("temperature") Float temperature;
	@JsonProperty("top_p") Float topP;
	@JsonProperty("user") String user;
	// @formatter:on

	@Override
	public String getName() {
		return "OpenAi";
	}

	@Override
	public Map<String, Object> toMap() {
		try {
			var json = new ObjectMapper().writeValueAsString(this);
			return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public static void merge(OpenAiOptions source, OpenAiOptions target) {
		BeanWrapper srcWrap = new BeanWrapperImpl(source);
		BeanWrapper trgWrap = new BeanWrapperImpl(target);

		for (PropertyDescriptor descriptor : srcWrap.getPropertyDescriptors()) {
			String propertyName = descriptor.getName();
			Object value = srcWrap.getPropertyValue(propertyName);

			// Copy value to the target object
			trgWrap.setPropertyValue(propertyName, value);
		}
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Float getFrequencyPenalty() {
		return frequencyPenalty;
	}

	public void setFrequencyPenalty(Float frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	public Map<String, Object> getLogitBias() {
		return logitBias;
	}

	public void setLogitBias(Map<String, Object> logitBias) {
		this.logitBias = logitBias;
	}

	public Integer getMaxTokens() {
		return maxTokens;
	}

	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	public Integer getN() {
		return n;
	}

	public void setN(Integer n) {
		this.n = n;
	}

	public Float getPresencePenalty() {
		return presencePenalty;
	}

	public void setPresencePenalty(Float presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	public Integer getSeed() {
		return seed;
	}

	public void setSeed(Integer seed) {
		this.seed = seed;
	}

	public String getStop() {
		return stop;
	}

	public void setStop(String stop) {
		this.stop = stop;
	}

	public Boolean getStream() {
		return stream;
	}

	public void setStream(Boolean stream) {
		this.stream = stream;
	}

	public Float getTemperature() {
		return temperature;
	}

	public void setTemperature(Float temperature) {
		this.temperature = temperature;
	}

	public Float getTopP() {
		return topP;
	}

	public void setTopP(Float topP) {
		this.topP = topP;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	    // Copy constructor
    public OpenAiOptions(OpenAiOptions original) {
        this.model = original.model;
        this.frequencyPenalty = original.frequencyPenalty;

        // Create a deep copy of logitBias
        if (original.logitBias != null) {
            this.logitBias = new HashMap<>(original.logitBias);
        }

        this.maxTokens = original.maxTokens;
        this.n = original.n;
        this.presencePenalty = original.presencePenalty;
        this.seed = original.seed;
        this.stop = original.stop;
        this.stream = original.stream;
        this.temperature = original.temperature;
        this.topP = original.topP;
        this.user = original.user;
    }
}
