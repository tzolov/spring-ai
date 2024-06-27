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
package org.springframework.ai.openai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * @author Christian Tzolov
 */
@SpringBootTest(classes = OpenAiChatModel3IT.Config.class)
@EnabledIfEnvironmentVariable(named = "NVIDIA_API_KEY", matches = ".+")
public class OpenAiChatModel3IT {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	@Autowired
	private OpenAiChatModel openAiChatModel;

	@Test
	void nvidiaChatClient() throws JsonMappingException, JsonProcessingException {

		Prompt prompt = new Prompt("List 8 planets. Use JSON response");

		ChatResponse response = this.openAiChatModel.call(prompt);

		assertThat(response).isNotNull();

		String content = response.getResult().getOutput().getContent();

		logger.info("Response content: {}", content);

		assertThat(content).isNotEmpty();
	}

	@SpringBootConfiguration
	static class Config {

		@Bean
		public OpenAiApi chatCompletionApi() {
			return new OpenAiApi("https://integrate.api.nvidia.com", System.getenv("NVIDIA_API_KEY"));
		}

		@Bean
		public OpenAiChatModel openAiClient(OpenAiApi openAiApi) {
			return new OpenAiChatModel(openAiApi,
					OpenAiChatOptions.builder().withModel("meta/llama3-70b-instruct").build());
		}

	}

}
