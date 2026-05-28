/*
 * Copyright 2023-present the original author or authors.
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

package org.springframework.ai.anthropic.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.AnthropicTestConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying that multiple consecutive tool calls happen in the expected
 * order during streaming and non-streaming scenarios, using {@link ToolCallAdvisor} to
 * manage the tool calling loop.
 */
@SpringBootTest(classes = AnthropicTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class AnthropicChatClientToolCallOrderIT {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicChatClientToolCallOrderIT.class);

	@Autowired
	ChatModel chatModel;

	@Test
	void toolCallsShouldExecuteInExpectedOrder() {
		List<String> callOrder = new CopyOnWriteArrayList<>();

		// @formatter:off
		String content = ChatClient.builder(this.chatModel)
				// For M6+ theToolCallAdvisor is automatically registered.
				// .defaultAdvisors(ToolCallAdvisor.builder().build())
				.build()
				.prompt("""
						Help me plan what to wear today in Landsmeer, NL.
						Please suggest clothing shops that are open right now in the area.
						""")
				.tools(new WeatherPlanningTools(callOrder))
				.call()
				.content();
		// @formatter:on

		logger.info("Response: {}", content);
		logger.info("Tool call order: {}", callOrder);

		assertThat(content).isNotBlank();

		assertThat(callOrder).contains("currentTime", "weather", "clothing");

		// currentTime must be called before weather and clothing, because the AI
		// should resolve the current time before querying weather and open shops.
		int currentTimeIndex = callOrder.indexOf("currentTime");
		int weatherIndex = callOrder.indexOf("weather");
		int clothingIndex = callOrder.indexOf("clothing");

		assertThat(currentTimeIndex).as("currentTime must be called before weather").isLessThan(weatherIndex);
		assertThat(currentTimeIndex).as("currentTime must be called before clothing").isLessThan(clothingIndex);
	}

	@Test
	void streamingToolCallsShouldExecuteInExpectedOrder() {
		List<String> callOrder = new CopyOnWriteArrayList<>();

		// @formatter:off
		String content = ChatClient.builder(this.chatModel)
				// For M6+ theToolCallAdvisor is automatically registered.
				// .defaultAdvisors(ToolCallAdvisor.builder().build())
				.build()
				.prompt("""
						Help me plan what to wear today in Landsmeer, NL.
						Please suggest clothing shops that are open right now in the area.
						""")
				.tools(new WeatherPlanningTools(callOrder))
				.stream()
				.content()
				.collectList()
				.block()
				.stream()
				.collect(Collectors.joining());
		// @formatter:on

		logger.info("Response: {}", content);
		logger.info("Tool call order: {}", callOrder);

		assertThat(content).isNotBlank();

		assertThat(callOrder).contains("currentTime", "weather", "clothing");

		// currentTime must be called before weather and clothing, because the AI
		// should resolve the current time before querying weather and open shops.
		int currentTimeIndex = callOrder.indexOf("currentTime");
		int weatherIndex = callOrder.indexOf("weather");
		int clothingIndex = callOrder.indexOf("clothing");

		assertThat(currentTimeIndex).as("currentTime must be called before weather").isLessThan(weatherIndex);
		assertThat(currentTimeIndex).as("currentTime must be called before clothing").isLessThan(clothingIndex);
	}

	static class WeatherPlanningTools {

		private final List<String> callOrder;

		WeatherPlanningTools(List<String> callOrder) {
			this.callOrder = callOrder;
		}

		@Tool(description = "Provides the current date and time for a given location")
		public String currentTime(String location) {
			this.callOrder.add("currentTime");
			logger.info("[TOOL] currentTime called for location: {}", location);
			return LocalDateTime.now().toString();
		}

		@Tool(description = "Get the weather for a given location at a given time")
		public String weather(String location,
				@ToolParam(description = "ISO date-time string, e.g. YYYY-MM-DDTHH:mm:ss") String atTime) {
			this.callOrder.add("weather");
			logger.info("[TOOL] weather called for location: {}, atTime: {}", location, atTime);
			return "The weather in " + location + " at " + atTime + " is sunny with a temperature of 22°C.";
		}

		@Tool(description = "Get clothing shop names that are open at a given time in a given location")
		public List<String> clothing(String location,
				@ToolParam(description = "ISO date-time string, e.g. YYYY-MM-DDTHH:mm:ss") String openAtTime) {
			this.callOrder.add("clothing");
			logger.info("[TOOL] clothing called for location: {}, openAtTime: {}", location, openAtTime);
			return List.of("Zara", "H&M", "Mango");
		}

		private static final Logger logger = LoggerFactory.getLogger(WeatherPlanningTools.class);

	}

}
