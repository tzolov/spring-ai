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
package org.springframework.ai.anthropic;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletion;
import org.springframework.ai.anthropic.api.AnthropicApi.ChatCompletionRequest;
import org.springframework.ai.anthropic.api.AnthropicApi.MediaContent;
import org.springframework.ai.anthropic.api.AnthropicApi.MediaContent.Type;
import org.springframework.ai.anthropic.api.AnthropicApi.RequestMessage;
import org.springframework.ai.anthropic.api.AnthropicApi.Role;
import org.springframework.ai.anthropic.api.AnthropicApi.StreamResponse;
import org.springframework.ai.anthropic.api.AnthropicApi.Usage;
import org.springframework.ai.anthropic.metadata.AnthropicChatResponseMetadata;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.AbstractFunctionCallSupport;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.model.function.XmlHelper;
import org.springframework.ai.model.function.XmlHelper.FunctionCalls;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * The {@link ChatClient} implementation for the Anthropic service.
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
public class AnthropicChatClient
		extends AbstractFunctionCallSupport<RequestMessage, ChatCompletionRequest, ResponseEntity<ChatCompletion>>
		implements ChatClient, StreamingChatClient {

	private static final Logger logger = LoggerFactory.getLogger(AnthropicChatClient.class);

	public static final String DEFAULT_MODEL_NAME = AnthropicApi.ChatModel.CLAUDE_3_OPUS.getValue();

	public static final Integer DEFAULT_MAX_TOKENS = 500;

	public static final Float DEFAULT_TEMPERATURE = 0.8f;

	/**
	 * The lower-level API for the Anthropic service.
	 */
	public final AnthropicApi anthropicApi;

	/**
	 * The default options used for the chat completion requests.
	 */
	private AnthropicChatOptions defaultOptions;

	/**
	 * The retry template used to retry the OpenAI API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Construct a new {@link AnthropicChatClient} instance.
	 * @param anthropicApi the lower-level API for the Anthropic service.
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi) {
		this(anthropicApi,
				AnthropicChatOptions.builder()
					.withModel(DEFAULT_MODEL_NAME)
					.withMaxTokens(DEFAULT_MAX_TOKENS)
					.withTemperature(DEFAULT_TEMPERATURE)
					.build());
	}

	/**
	 * Construct a new {@link AnthropicChatClient} instance.
	 * @param anthropicApi the lower-level API for the Anthropic service.
	 * @param defaultOptions the default options used for the chat completion requests.
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi, AnthropicChatOptions defaultOptions) {
		this(anthropicApi, defaultOptions, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Construct a new {@link AnthropicChatClient} instance.
	 * @param anthropicApi the lower-level API for the Anthropic service.
	 * @param defaultOptions the default options used for the chat completion requests.
	 * @param retryTemplate the retry template used to retry the Anthropic API calls.
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi, AnthropicChatOptions defaultOptions,
			RetryTemplate retryTemplate) {
		this(anthropicApi, defaultOptions, retryTemplate, null);
	}

	/**
	 * Construct a new {@link AnthropicChatClient} instance.
	 * @param anthropicApi the lower-level API for the Anthropic service.
	 * @param defaultOptions the default options used for the chat completion requests.
	 * @param retryTemplate the retry template used to retry the Anthropic API calls.
	 * @param functionCallbackContext
	 */
	public AnthropicChatClient(AnthropicApi anthropicApi, AnthropicChatOptions defaultOptions,
			RetryTemplate retryTemplate, FunctionCallbackContext functionCallbackContext) {

		super(functionCallbackContext);

		Assert.notNull(anthropicApi, "AnthropicApi must not be null");
		Assert.notNull(defaultOptions, "DefaultOptions must not be null");
		Assert.notNull(retryTemplate, "RetryTemplate must not be null");

		this.anthropicApi = anthropicApi;
		this.defaultOptions = defaultOptions;
		this.retryTemplate = retryTemplate;
	}

	@Override
	public ChatResponse call(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, false);

		return this.retryTemplate.execute(ctx -> {
			ResponseEntity<ChatCompletion> completionEntity = this.callWithFunctionSupport(request);
			// ResponseEntity<ChatCompletion> completionEntity =
			// this.anthropicApi.chatCompletionEntity(request);
			return toChatResponse(completionEntity.getBody());
		});
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, true);

		Flux<StreamResponse> response = this.anthropicApi.chatCompletionStream(request);

		AtomicReference<ChatCompletionBuilder> chatCompletionReference = new AtomicReference<>();

		// https://docs.anthropic.com/claude/reference/messages-streaming

		return response.map(chunk -> {

			if (chunk.type().equals("message_start")) {
				chatCompletionReference.set(new ChatCompletionBuilder());
				chatCompletionReference.get()
					.withType(chunk.type())
					.withId(chunk.message().id())
					.withRole(chunk.message().role())
					.withModel(chunk.message().model())
					.withUsage(chunk.message().usage())
					.withContent(new ArrayList<>());
			}
			else if (chunk.type().equals("content_block_start")) {
				var content = new MediaContent(chunk.contentBlock().type(), null, chunk.contentBlock().text(),
						chunk.index());
				chatCompletionReference.get().withType(chunk.type()).withContent(List.of(content));
			}
			else if (chunk.type().equals("content_block_delta")) {
				var content = new MediaContent(Type.TEXT_DELTA, null, (String) chunk.delta().get("text"),
						chunk.index());
				chatCompletionReference.get().withType(chunk.type()).withContent(List.of(content));
			}
			else if (chunk.type().equals("message_delta")) {

				ChatCompletion delta = ModelOptionsUtils.mapToClass(chunk.delta(), ChatCompletion.class);

				chatCompletionReference.get().withType(chunk.type());
				if (delta.id() != null) {
					chatCompletionReference.get().withId(delta.id());
				}
				if (delta.role() != null) {
					chatCompletionReference.get().withRole(delta.role());
				}
				if (delta.model() != null) {
					chatCompletionReference.get().withModel(delta.model());
				}
				if (delta.usage() != null) {
					chatCompletionReference.get().withUsage(delta.usage());
				}
				if (delta.content() != null) {
					chatCompletionReference.get().withContent(delta.content());
				}
				if (delta.stopReason() != null) {
					chatCompletionReference.get().withStopReason(delta.stopReason());
				}
				if (delta.stopSequence() != null) {
					chatCompletionReference.get().withStopSequence(delta.stopSequence());
				}
			}
			else {
				chatCompletionReference.get().withType(chunk.type()).withContent(List.of());
			}
			return chatCompletionReference.get().build();

		}).map(this::toChatResponse);
	}

	private ChatResponse toChatResponse(ChatCompletion chatCompletion) {
		if (chatCompletion == null) {
			logger.warn("Null chat completion returned");
			return new ChatResponse(List.of());
		}

		List<Generation> generations = chatCompletion.content().stream().map(content -> {
			return new Generation(content.text(), Map.of())
				.withGenerationMetadata(ChatGenerationMetadata.from(chatCompletion.stopReason(), null));
		}).toList();

		return new ChatResponse(generations, AnthropicChatResponseMetadata.from(chatCompletion));
	}

	private String fromMediaData(Object mediaData) {
		if (mediaData instanceof byte[] bytes) {
			return Base64.getEncoder().encodeToString(bytes);
		}
		else if (mediaData instanceof String text) {
			return text;
		}
		else {
			throw new IllegalArgumentException("Unsupported media data type: " + mediaData.getClass().getSimpleName());
		}

	}

	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		Set<String> functionsForThisRequest = new HashSet<>();

		List<RequestMessage> userMessages = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() != MessageType.SYSTEM)
			.map(m -> {
				List<MediaContent> contents = new ArrayList<>(List.of(new MediaContent(m.getContent())));
				if (!CollectionUtils.isEmpty(m.getMedia())) {
					List<MediaContent> mediaContent = m.getMedia()
						.stream()
						.map(media -> new MediaContent(media.getMimeType().toString(),
								this.fromMediaData(media.getData())))
						.toList();
					contents.addAll(mediaContent);
				}
				return new RequestMessage(contents, Role.valueOf(m.getMessageType().name()));
			})
			.toList();

		String systemPrompt = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.SYSTEM)
			.map(m -> m.getContent())
			.collect(Collectors.joining(System.lineSeparator()));

		ChatCompletionRequest request = new ChatCompletionRequest(this.defaultOptions.getModel(), userMessages,
				systemPrompt, this.defaultOptions.getMaxTokens(), this.defaultOptions.getTemperature(), stream);

		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ChatOptions runtimeOptions) {
				AnthropicChatOptions updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(runtimeOptions,
						ChatOptions.class, AnthropicChatOptions.class);

				Set<String> promptEnabledFunctions = this.handleFunctionCallbackConfigurations(updatedRuntimeOptions,
						IS_RUNTIME_CALL);
				functionsForThisRequest.addAll(promptEnabledFunctions);

				request = ModelOptionsUtils.merge(updatedRuntimeOptions, request, ChatCompletionRequest.class);
			}
			else {
				throw new IllegalArgumentException("Prompt options are not of type ChatOptions: "
						+ prompt.getOptions().getClass().getSimpleName());
			}
		}

		if (this.defaultOptions != null) {
			Set<String> defaultEnabledFunctions = this.handleFunctionCallbackConfigurations(this.defaultOptions,
					!IS_RUNTIME_CALL);
			functionsForThisRequest.addAll(defaultEnabledFunctions);

			request = ModelOptionsUtils.merge(request, this.defaultOptions, ChatCompletionRequest.class);
		}

		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {

			String toolDescription = getFunctionTools(functionsForThisRequest);
			String functionCallSystemPrompt = String.format(XmlHelper.TOO_SYSTEM_PROMPT_TEMPLATE, toolDescription);

			List<String> stopSequence = request.stopSequences() != null ? new ArrayList<>(request.stopSequences())
					: new ArrayList<>();
			stopSequence.add("</function_calls>");

			request = new ChatCompletionRequest(request.model(), request.messages(),
					request.stream() + System.lineSeparator() + functionCallSystemPrompt, request.maxTokens(),
					request.metadata(), stopSequence, request.stream(), request.temperature(), request.topP(),
					request.topK());
		}

		return request;
	}

	private final static String TOOL_DESCRIPTION_XML_TEMPLATE = """
			<tool_description>
				<tool_name>%s</tool_name>
				<description>%s</description>
				%s
			</tool_description>
				""";

	private String getFunctionTools(Set<String> functionNames) {

		String tooDescriptions = this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			String toolDescription = String.format(TOOL_DESCRIPTION_XML_TEMPLATE, functionCallback.getName(),
					functionCallback.getDescription(), functionCallback.getInputTypeSchema());
			return toolDescription;
		}).collect(Collectors.joining(System.lineSeparator()));

		return tooDescriptions;
	}

	private static class ChatCompletionBuilder {

		private String type;

		private String id;

		private Role role;

		private List<MediaContent> content;

		private String model;

		private String stopReason;

		private String stopSequence;

		private Usage usage;

		public ChatCompletionBuilder() {
		}

		public ChatCompletionBuilder withType(String type) {
			this.type = type;
			return this;
		}

		public ChatCompletionBuilder withId(String id) {
			this.id = id;
			return this;
		}

		public ChatCompletionBuilder withRole(Role role) {
			this.role = role;
			return this;
		}

		public ChatCompletionBuilder withContent(List<MediaContent> content) {
			this.content = content;
			return this;
		}

		public ChatCompletionBuilder withModel(String model) {
			this.model = model;
			return this;
		}

		public ChatCompletionBuilder withStopReason(String stopReason) {
			this.stopReason = stopReason;
			return this;
		}

		public ChatCompletionBuilder withStopSequence(String stopSequence) {
			this.stopSequence = stopSequence;
			return this;
		}

		public ChatCompletionBuilder withUsage(Usage usage) {
			this.usage = usage;
			return this;
		}

		public ChatCompletion build() {
			return new ChatCompletion(this.id, this.type, this.role, this.content, this.model, this.stopReason,
					this.stopSequence, this.usage);
		}

	}

	@Override
	protected ChatCompletionRequest doCreateToolResponseRequest(ChatCompletionRequest previousRequest,
			RequestMessage responseMessage, List<RequestMessage> conversationHistory) {

		FunctionCalls functionCalls = XmlHelper
			.extractFunctionCalls(responseMessage.content().get(0).text() + "</function_calls>");

		var functionName = functionCalls.invoke().toolName();
		Map<String, Object> functionArguments = functionCalls.invoke().parameters();

		if (!this.functionCallbackRegister.containsKey(functionName)) {
			throw new IllegalStateException("No function callback found for function name: " + functionName);
		}

		String functionResponse = this.functionCallbackRegister.get(functionName).call(functionArguments);

		XmlHelper.FunctionResults functionResults = new XmlHelper.FunctionResults(
				List.of(new XmlHelper.FunctionResults.Result(functionCalls.invoke().toolName(), functionResponse)));

		String content = XmlHelper.toXml(functionResults);

		logger.info("Function response XML : " + content);

		RequestMessage chatCompletionMessage2 = new RequestMessage(List.of(new MediaContent(content)), Role.USER);

		// Add the function response to the conversation.
		conversationHistory.add(chatCompletionMessage2);

		ChatCompletionRequest newRequest = new ChatCompletionRequest(previousRequest.model(), conversationHistory,
				previousRequest.system(), previousRequest.maxTokens(), previousRequest.stopSequences(),
				previousRequest.temperature(), false);
		// ChatCompletionRequest newRequest = new
		// ChatCompletionRequest(previousRequest.model(), conversationHistory,
		// previousRequest.system(), previousRequest.maxTokens(),
		// previousRequest.stopSequences(),
		// previousRequest.temperature(), previousRequest.stream());

		return newRequest;
	}

	@Override
	protected List<RequestMessage> doGetUserMessages(ChatCompletionRequest request) {
		return request.messages();
	}

	@Override
	protected RequestMessage doGetToolResponseMessage(ResponseEntity<ChatCompletion> response) {
		var body = response.getBody();
		Assert.notNull(body, "ChatCompletion body must not be null");
		return new RequestMessage(body.content(), body.role());
	}

	@Override
	protected ResponseEntity<ChatCompletion> doChatCompletion(ChatCompletionRequest request) {
		return this.anthropicApi.chatCompletionEntity(request);
	}

	@Override
	protected boolean isToolFunctionCall(ResponseEntity<ChatCompletion> response) {
		var body = response.getBody();
		if (body == null) {
			return false;
		}
		if ("end_turn".equals(body.stopReason())) {
			return body.content() != null && body.content().size() == 1
					&& body.content().get(0).text().contains("<function_calls>");
		}
		else {
			return ("stop_sequence".equals(body.stopReason()) && "</function_calls>".equals(body.stopSequence()));
		}
	}

}
