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
package org.springframework.ai.anthropic.api.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletion;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.anthropic.api.AnthropicApi.MediaContent;
import org.springframework.ai.anthropic.api.AnthropicApi.RequestMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.Role;
import org.springframework.ai.anthropic.api.tool.XmlHelper.FunctionCalls;
import org.springframework.ai.anthropic.api.tool.XmlHelper.Tools;
import org.springframework.ai.anthropic.api.tool.XmlHelper.Tools.ToolDescription;
import org.springframework.ai.anthropic.api.tool.XmlHelper.Tools.ToolDescription.Parameter;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiments with
 * <a href="https://docs.anthropic.com/claude/docs/functions-external-tools">Anthropic
 * Functions & external tools</a>.
 *
 * <p>
 * <a href=
 * "https://www.linkedin.com/pulse/tool-usefunction-calling-anthropics-claude-3-opus-llm-micky-multani-fsmrc">Tool
 * Use(Function Calling) with Anthropic's Claude 3 Opus LLM</a>
 * <p>
 * <a href=
 * "https://www.codeproject.com/Articles/5379174/Csharp-Anthropic-Claude-Library-You-Can-Call-Claud">Anthropic
 * Functions & external tools</a>
 *
 * @author Christian Tzolov
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SuppressWarnings("null")
public class AnthropicApiToolIT {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicApiToolIT.class);

	AnthropicApi anthropicApi = new AnthropicApi(System.getenv("ANTHROPIC_API_KEY"));

	public static final String TOO_SYSTEM_PROMPT_TEMPLATE = """
			In this environment you have access to a set of tools you can use to answer the user's question.

			You may call them like this:
			<function_calls>
				<invoke>
					<tool_name>$TOOL_NAME</tool_name>
					<parameters>
						<$PARAMETER_NAME>$PARAMETER_VALUE</$PARAMETER_NAME>
						...
					</parameters>
				</invoke>
			</function_calls>

			Here are the tools available:
			<tools>%s</tools>
			""";

	public static final ConcurrentHashMap<String, Function> FUNCTIONS = new ConcurrentHashMap<>();

	static {
		FUNCTIONS.put("getCurrentWeather", new MockWeatherService());
	}

	@Test
	void toolCalls() {

		String toolDescription = XmlHelper.toXml(new Tools(List.of(new ToolDescription("getCurrentWeather",
				"Get the weather in location. Return temperature in 30°F or 30°C format.",
				List.of(new Parameter("location", "string", "The city and state"),
						new Parameter("unit", "enum", "Temperature unit. Use only C or F. Default is C."))))));
		// List.of(new Parameter("location", "string", "The city and state e.g. San
		// Francisco, CA"),
		// new Parameter("unit", "enum", "Temperature unit. Use only C or F. Default is
		// C."))))));

		logger.info("TOOLS: " + toolDescription);

		String systemPrompt = String.format(TOO_SYSTEM_PROMPT_TEMPLATE, toolDescription);

		RequestMessage chatCompletionMessage = new RequestMessage(
				// List.of(new MediaContent("What's the weather like in Paris? Show the
				// temperature in Celsius.")),
				List.of(new MediaContent(
						"What's the weather in Paris, France and in Tokyo, Japan ? Show the temperature in Celsius.")),
				// "What's the weather like in San Francisco, Tokyo, and Paris? Show the
				// temperature in Celsius.")),
				Role.USER);

		List<RequestMessage> conversationHistory = new ArrayList<>();

		ChatCompletionRequest chatCompletionRequest = new ChatCompletionRequest(
				AnthropicApi.ChatModel.CLAUDE_3_OPUS.getValue(), List.of(chatCompletionMessage), systemPrompt, 2000,
				List.of("</function_calls>"), 0.8f, false);

		ResponseEntity<ChatCompletion> chatCompletion = doCall(chatCompletionRequest, conversationHistory);

		var responseText = chatCompletion.getBody().content().get(0).text();
		logger.info("FINAL RESPONSE: " + responseText);

		assertThat(responseText).contains("15");
	}

	private ResponseEntity<ChatCompletion> doCall(ChatCompletionRequest chatCompletionRequest,
			List<RequestMessage> conversationHistory) {

		conversationHistory.addAll(chatCompletionRequest.messages());

		var request1 = new ChatCompletionRequest(chatCompletionRequest.model(), conversationHistory,
				chatCompletionRequest.system(), chatCompletionRequest.maxTokens(),
				chatCompletionRequest.stopSequences(), chatCompletionRequest.temperature(),
				chatCompletionRequest.stream());

		ResponseEntity<ChatCompletion> response = anthropicApi.chatCompletionEntity(request1);

		FunctionCalls functionCalls = XmlHelper
			.extractFunctionCalls(response.getBody().content().get(0).text() + "</function_calls>");

		if (functionCalls == null) {
			return response;
		}

		logger.info("FunctionCalls from the LLM: " + functionCalls);

		conversationHistory.add(new RequestMessage(response.getBody().content(), response.getBody().role()));

		MockWeatherService.Request functionRequest = ModelOptionsUtils.mapToClass(functionCalls.invoke().parameters(),
				MockWeatherService.Request.class);

		logger.info("Resolved function request param: " + functionRequest);

		Object functionCallResponseData = FUNCTIONS.get(functionCalls.invoke().toolName()).apply(functionRequest);

		XmlHelper.FunctionResults functionResults = new XmlHelper.FunctionResults(List
			.of(new XmlHelper.FunctionResults.Result(functionCalls.invoke().toolName(), functionCallResponseData)));

		String content = XmlHelper.toXml(functionResults);

		logger.info("Function response XML : " + content);

		RequestMessage chatCompletionMessage2 = new RequestMessage(List.of(new MediaContent(content)), Role.USER);

		return doCall(new ChatCompletionRequest(chatCompletionRequest.model(), List.of(chatCompletionMessage2),
				chatCompletionRequest.system(), chatCompletionRequest.maxTokens(),
				chatCompletionRequest.stopSequences(), chatCompletionRequest.temperature(),
				chatCompletionRequest.stream()), conversationHistory);
	}

}
