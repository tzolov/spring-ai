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

package org.springframework.ai.ollama;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.ChatRequest;
import org.springframework.ai.ollama.api.OllamaApi.Message.Role;
import org.springframework.ai.ollama.api.OllamaApi.Message.ToolCall;
import org.springframework.ai.ollama.api.OllamaApi.Message.ToolCallFunction;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.common.OllamaApiConstants;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.OllamaModelManager;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ChatModel} implementation for {@literal Ollama}. Ollama allows developers to run
 * large language models and generate embeddings locally. It supports open-source models
 * available on [Ollama AI Library](<a href="https://ollama.ai/library">...</a>) and on
 * Hugging Face. Please refer to the <a href="https://ollama.ai/">official Ollama
 * website</a> for the most up-to-date information on available models.
 *
 * @author Christian Tzolov
 * @author luocongqiu
 * @author Thomas Vitale
 * @author Jihoon Kim
 * @author Alexandros Pappas
 * @author Ilayaperumal Gopinathan
 * @author Sun Yuhan
 * @author Sebastien Deleuze
 * @since 1.0.0
 */
public class OllamaChatModel implements ChatModel {

	private static final JsonHelper jsonHelper = new JsonHelper();

	private static final String DONE = "done";

	private static final String METADATA_PROMPT_EVAL_COUNT = "prompt-eval-count";

	private static final String METADATA_EVAL_COUNT = "eval-count";

	private static final String METADATA_CREATED_AT = "created-at";

	private static final String METADATA_TOTAL_DURATION = "total-duration";

	private static final String METADATA_LOAD_DURATION = "load-duration";

	private static final String METADATA_PROMPT_EVAL_DURATION = "prompt-eval-duration";

	private static final String METADATA_EVAL_DURATION = "eval-duration";

	/**
	 * Metadata key carrying the thinking text on the message and generation metadata.
	 * Superseded by {@link AssistantMessage#getReasoning()}; still written for one
	 * release and read as a replay fallback, to be removed afterwards.
	 */
	private static final String THINKING_METADATA_KEY = "thinking";

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build();

	private final OllamaApi chatApi;

	private final OllamaChatOptions options;

	private final ObservationRegistry observationRegistry;

	private final OllamaModelManager modelManager;

	private final ToolCallingManager toolCallingManager;

	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final RetryTemplate retryTemplate;

	public OllamaChatModel(OllamaApi ollamaApi, OllamaChatOptions options, ToolCallingManager toolCallingManager,
			ObservationRegistry observationRegistry, ModelManagementOptions modelManagementOptions) {
		this(ollamaApi, options, toolCallingManager, observationRegistry, modelManagementOptions,
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	public OllamaChatModel(OllamaApi ollamaApi, OllamaChatOptions options, ToolCallingManager toolCallingManager,
			ObservationRegistry observationRegistry, ModelManagementOptions modelManagementOptions,
			RetryTemplate retryTemplate) {

		Assert.notNull(ollamaApi, "ollamaApi must not be null");
		Assert.notNull(options, "options must not be null");
		Assert.notNull(toolCallingManager, "toolCallingManager must not be null");
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(modelManagementOptions, "modelManagementOptions must not be null");
		Assert.notNull(retryTemplate, "retryTemplate must not be null");
		this.chatApi = ollamaApi;
		this.options = options;
		this.toolCallingManager = toolCallingManager;
		this.observationRegistry = observationRegistry;
		this.modelManager = new OllamaModelManager(this.chatApi, modelManagementOptions);
		this.retryTemplate = retryTemplate;
		String model = options.getModel();
		Assert.state(model != null, "model must not be null");
		initializeModel(model, modelManagementOptions.pullModelStrategy());
	}

	public static Builder builder() {
		return new Builder();
	}

	static ChatResponseMetadata from(OllamaApi.ChatResponse response, @Nullable ChatResponse previousChatResponse) {
		Assert.notNull(response, "OllamaApi.ChatResponse must not be null");

		DefaultUsage newUsage = getDefaultUsage(response);
		Integer promptTokens = newUsage.getPromptTokens();
		Integer generationTokens = newUsage.getCompletionTokens();
		int totalTokens = newUsage.getTotalTokens();

		Duration evalDuration = response.getEvalDuration();
		Duration promptEvalDuration = response.getPromptEvalDuration();
		Duration loadDuration = response.getLoadDuration();
		Duration totalDuration = response.getTotalDuration();

		if (previousChatResponse != null && previousChatResponse.getMetadata() != null) {
			Object metadataEvalDuration = previousChatResponse.getMetadata().get(METADATA_EVAL_DURATION);
			if (metadataEvalDuration != null && evalDuration != null) {
				evalDuration = evalDuration.plus((Duration) metadataEvalDuration);
			}
			Object metadataPromptEvalDuration = previousChatResponse.getMetadata().get(METADATA_PROMPT_EVAL_DURATION);
			if (metadataPromptEvalDuration != null && promptEvalDuration != null) {
				promptEvalDuration = promptEvalDuration.plus((Duration) metadataPromptEvalDuration);
			}
			Object metadataLoadDuration = previousChatResponse.getMetadata().get(METADATA_LOAD_DURATION);
			if (metadataLoadDuration != null && loadDuration != null) {
				loadDuration = loadDuration.plus((Duration) metadataLoadDuration);
			}
			Object metadataTotalDuration = previousChatResponse.getMetadata().get(METADATA_TOTAL_DURATION);
			if (metadataTotalDuration != null && totalDuration != null) {
				totalDuration = totalDuration.plus((Duration) metadataTotalDuration);
			}
			if (previousChatResponse.getMetadata().getUsage() != null) {
				promptTokens += previousChatResponse.getMetadata().getUsage().getPromptTokens();
				generationTokens += previousChatResponse.getMetadata().getUsage().getCompletionTokens();
				totalTokens += previousChatResponse.getMetadata().getUsage().getTotalTokens();
			}
		}

		DefaultUsage aggregatedUsage = new DefaultUsage(promptTokens, generationTokens, totalTokens);

		return ChatResponseMetadata.builder()
			.usage(aggregatedUsage)
			.model(response.model())
			.keyValue(METADATA_CREATED_AT, response.createdAt())
			.keyValue(METADATA_EVAL_DURATION, evalDuration)
			.keyValue(METADATA_EVAL_COUNT, aggregatedUsage.getCompletionTokens())
			.keyValue(METADATA_LOAD_DURATION, loadDuration)
			.keyValue(METADATA_PROMPT_EVAL_DURATION, promptEvalDuration)
			.keyValue(METADATA_PROMPT_EVAL_COUNT, aggregatedUsage.getPromptTokens())
			.keyValue(METADATA_TOTAL_DURATION, totalDuration)
			.keyValue(DONE, response.done())
			.build();
	}

	private static DefaultUsage getDefaultUsage(OllamaApi.ChatResponse response) {
		return new DefaultUsage(Optional.ofNullable(response.promptEvalCount()).orElse(0),
				Optional.ofNullable(response.evalCount()).orElse(0));
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		// Before moving any further, build the final request Prompt,
		// merging runtime and default options.
		Prompt requestPrompt = buildRequestPrompt(prompt);
		verifyPromptChatOptions(requestPrompt);
		return this.internalCall(requestPrompt, null);
	}

	private ChatResponse internalCall(Prompt prompt, @Nullable ChatResponse previousChatResponse) {

		OllamaApi.ChatRequest request = ollamaChatRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(OllamaApiConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {

				OllamaApi.ChatResponse ollamaResponse = RetryUtils.execute(this.retryTemplate,
						() -> this.chatApi.chat(request));

				String thinking = ollamaResponse.message().thinking();
				Map<String, Object> messageProperties = thinking != null ? Map.of(THINKING_METADATA_KEY, thinking)
						: Map.of();
				var assistantMessage = AssistantMessage.builder()
					.parts(toMessageParts(ollamaResponse.message(), null))
					.properties(messageProperties)
					.build();

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;
				if (ollamaResponse.promptEvalCount() != null && ollamaResponse.evalCount() != null) {
					ChatGenerationMetadata.Builder builder = ChatGenerationMetadata.builder()
						.finishReason(ollamaResponse.doneReason());
					if (thinking != null) {
						builder.metadata(THINKING_METADATA_KEY, thinking);
					}
					generationMetadata = builder.build();
				}

				var generator = new Generation(assistantMessage, generationMetadata);
				ChatResponse chatResponse = new ChatResponse(List.of(generator),
						from(ollamaResponse, previousChatResponse));

				observationContext.setResponse(chatResponse);

				return chatResponse;

			});
		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		// Before moving any further, build the final request Prompt,
		// merging runtime and default options.
		Prompt requestPrompt = buildRequestPrompt(prompt);
		verifyPromptChatOptions(requestPrompt);
		return this.internalStream(requestPrompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, @Nullable ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			OllamaApi.ChatRequest request = ollamaChatRequest(prompt, true);

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(OllamaApiConstants.PROVIDER_NAME)
				.streaming(true)
				.build();

			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);

			Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
			observation.parentObservation(parentObservation);
			try (Observation.Scope ignored = parentObservation != null ? parentObservation.openScope()
					: Observation.Scope.NOOP) {
				observation.start();
			}

			Flux<OllamaApi.ChatResponse> ollamaResponse = this.chatApi.streamingChat(request);

			// One indexer per stream: Ollama has no response id or block index, so
			// consecutive deltas of one kind share a synthesized index.
			StreamingPartIndexer indexer = new StreamingPartIndexer();

			Flux<ChatResponse> chatResponse = ollamaResponse.map(chunk -> {
				String thinking = chunk.message().thinking();
				Map<String, Object> messageProperties = thinking != null ? Map.of(THINKING_METADATA_KEY, thinking)
						: Map.of();
				var assistantMessage = AssistantMessage.builder()
					.parts(toMessageParts(chunk.message(), indexer))
					.properties(messageProperties)
					.build();

				ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.NULL;
				boolean hasEvalCount = chunk.promptEvalCount() != null && chunk.evalCount() != null;
				if (hasEvalCount || thinking != null) {
					ChatGenerationMetadata.Builder builder = ChatGenerationMetadata.builder();
					if (hasEvalCount) {
						builder.finishReason(chunk.doneReason());
					}
					if (thinking != null) {
						builder.metadata(THINKING_METADATA_KEY, thinking);
					}
					generationMetadata = builder.build();
				}

				var generator = new Generation(assistantMessage, generationMetadata);
				return new ChatResponse(List.of(generator), from(chunk, previousChatResponse));
			});

			Flux<ChatResponse> chatResponseFlux = chatResponse.flatMap(response -> Flux.just(response))
				.doOnError(observation::error)
				.doFinally(s -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

			return new MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);
		});
	}

	/**
	 * Map an Ollama message to ordered {@link MessagePart}s: the thinking as a
	 * {@link ReasoningPart}, the content as a {@link TextPart} and the tool calls as
	 * {@link ToolCallPart}s. When {@code indexer} is non-null the message is a streamed
	 * chunk: thinking and text become partial indexed parts (only a non-empty content
	 * delta yields a text part), tool calls complete ones. A complete response always has
	 * a text part, so a tool-call answer keeps {@code getText() == ""}.
	 */
	private static List<MessagePart> toMessageParts(OllamaApi.Message message, @Nullable StreamingPartIndexer indexer) {
		List<MessagePart> parts = new ArrayList<>();
		String thinking = message.thinking();
		// hasLength, not hasText: streamed thinking deltas are often a single
		// whitespace or newline token that must be kept.
		if (StringUtils.hasLength(thinking)) {
			parts.add(ReasoningPart.of(thinking));
		}
		String content = message.content();
		if (indexer == null) {
			parts.add(TextPart.of(content != null ? content : ""));
		}
		else if (StringUtils.hasLength(content)) {
			parts.add(TextPart.of(content));
		}
		if (message.toolCalls() != null) {
			for (ToolCall toolCall : message.toolCalls()) {
				parts.add(ToolCallPart.of(new AssistantMessage.ToolCall(toolCall.id(), "function",
						toolCall.function().name(), jsonHelper.toJson(toolCall.function().arguments()))));
			}
		}
		if (indexer != null) {
			parts.replaceAll(indexer::stamp);
		}
		return parts;
	}

	/**
	 * The thinking text to send back on an assistant message: the joined text of the
	 * {@link ReasoningPart}s that carry no payload (signed reasoning from other providers
	 * is skipped), falling back to the {@link #THINKING_METADATA_KEY} metadata entry for
	 * messages that have no reasoning part. {@code null} when there is nothing to replay,
	 * so plain assistant messages are sent as before.
	 */
	private static @Nullable String replayableThinking(AssistantMessage assistantMessage) {
		List<ReasoningPart> reasoningParts = assistantMessage.getReasoning();
		if (reasoningParts.isEmpty()) {
			Object thinking = assistantMessage.getMetadata().get(THINKING_METADATA_KEY);
			return thinking instanceof String text && StringUtils.hasText(text) ? text : null;
		}
		StringBuilder thinking = new StringBuilder();
		for (ReasoningPart part : reasoningParts) {
			if (part.payload() == null && part.text() != null) {
				thinking.append(part.text());
			}
		}
		return StringUtils.hasText(thinking) ? thinking.toString() : null;
	}

	private void verifyPromptChatOptions(Prompt prompt) {
		var chatOptions = prompt.getOptions();

		if (chatOptions != null && !StringUtils.hasText(chatOptions.getModel())) {
			throw new IllegalArgumentException("model cannot be null or empty");
		}
	}

	/**
	 * Package access for testing.
	 */
	OllamaApi.ChatRequest ollamaChatRequest(Prompt prompt, boolean stream) {

		List<OllamaApi.Message> ollamaMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.SYSTEM) {
				return List.of(OllamaApi.Message.builder(Role.SYSTEM).content(message.getText()).build());
			}
			else if (message.getMessageType() == MessageType.USER) {
				var messageBuilder = OllamaApi.Message.builder(Role.USER).content(message.getText());
				if (message instanceof UserMessage userMessage) {
					if (!CollectionUtils.isEmpty(userMessage.getMedia())) {
						messageBuilder.images(userMessage.getMedia()
							.stream()
							.map(media -> this.fromMediaData(media.getData()))
							.toList());
					}
				}

				return List.of(messageBuilder.build());
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ToolCallFunction(toolCall.name(),
								jsonHelper.fromJsonToMap(toolCall.arguments()));
						return new ToolCall(toolCall.id(), function);
					}).toList();
				}
				return List.of(OllamaApi.Message.builder(Role.ASSISTANT)
					.content(assistantMessage.getText())
					.toolCalls(toolCalls)
					.thinking(replayableThinking(assistantMessage))
					.build());
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;
				return toolMessage.getResponses()
					.stream()
					.map(tr -> OllamaApi.Message.builder(Role.TOOL).content(tr.responseData()).build())
					.toList();
			}
			throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
		}).flatMap(List::stream).toList();

		OllamaChatOptions requestOptions = (OllamaChatOptions) prompt.getOptions();
		Assert.state(requestOptions != null, "ChatOptions must not be null");

		String model = requestOptions.getModel();
		Assert.state(model != null, "model must not be null");
		OllamaApi.ChatRequest.Builder requestBuilder = OllamaApi.ChatRequest.builder(model)
			.stream(stream)
			.messages(ollamaMessages)
			.options(requestOptions)
			.think(requestOptions.getThinkOption());

		if (requestOptions.getFormat() != null) {
			requestBuilder.format(requestOptions.getFormat());
		}

		if (requestOptions.getKeepAlive() != null) {
			requestBuilder.keepAlive(requestOptions.getKeepAlive());
		}

		List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			requestBuilder.tools(this.getTools(toolDefinitions));
		}

		return requestBuilder.build();
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

	private List<ChatRequest.Tool> getTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var tool = new ChatRequest.Tool.Function(toolDefinition.name(), toolDefinition.description(),
					toolDefinition.inputSchema());
			return new ChatRequest.Tool(tool);
		}).toList();
	}

	/**
	 * @since 2.0.0
	 */
	@Override
	public OllamaChatOptions getOptions() {
		return this.options;
	}

	/**
	 * Pull the given model into Ollama based on the specified strategy.
	 */
	private void initializeModel(String model, @Nullable PullModelStrategy pullModelStrategy) {
		if (pullModelStrategy != null && !PullModelStrategy.NEVER.equals(pullModelStrategy)) {
			this.modelManager.pullModel(model, pullModelStrategy);
		}
	}

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	/**
	 * Look at the options of the provided prompt. If none are provided, return a new
	 * prompt using this model {@link ChatModel#getOptions() options}. Otherwise, use the
	 * prompt as is.
	 */
	private Prompt buildRequestPrompt(Prompt prompt) {
		if (prompt.getOptions() == null) {
			return prompt.mutate().chatOptions(this.getOptions()).build();
		}
		else {
			return prompt;
		}
	}

	/**
	 * Assigns stream part indices: consecutive deltas of the same kind (thinking or text)
	 * share an index as partial parts; every other part is complete and gets its own
	 * index.
	 */
	private static final class StreamingPartIndexer {

		private int index = -1;

		private @Nullable Class<? extends MessagePart> lastDeltaKind;

		MessagePart stamp(MessagePart part) {
			boolean delta = part instanceof TextPart || part instanceof ReasoningPart;
			if (!delta || part.getClass() != this.lastDeltaKind) {
				this.index++;
			}
			this.lastDeltaKind = delta ? part.getClass() : null;
			return delta ? StreamingParts.partial(part, this.index) : StreamingParts.complete(part, this.index);
		}

	}

	public static final class Builder {

		private @Nullable OllamaApi ollamaApi;

		private @Nullable OllamaChatOptions options;

		private @Nullable ToolCallingManager toolCallingManager;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private ModelManagementOptions modelManagementOptions = ModelManagementOptions.defaults();

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private Builder() {
		}

		public Builder ollamaApi(OllamaApi ollamaApi) {
			this.ollamaApi = ollamaApi;
			return this;
		}

		public Builder options(OllamaChatOptions options) {
			this.options = options;
			return this;
		}

		/**
		 * Sets the tool calling manager used for internal tool execution.
		 * @param toolCallingManager the tool calling manager
		 * @return this builder
		 * @deprecated since 2.0.0 for removal in 3.0.0 — internal tool execution in
		 * {@link OllamaChatModel} is superseded by {@code ToolCallingAdvisor} used via
		 * {@code ChatClient}.
		 */
		@Deprecated(since = "2.0.0", forRemoval = true)
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder modelManagementOptions(ModelManagementOptions modelManagementOptions) {
			this.modelManagementOptions = modelManagementOptions;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public OllamaChatModel build() {
			Assert.state(this.ollamaApi != null, "OllamaApi must not be null");
			OllamaChatOptions resolvedOptions = this.options != null ? this.options
					: OllamaChatOptions.builder().build();
			return new OllamaChatModel(this.ollamaApi, resolvedOptions,
					Objects.requireNonNullElse(this.toolCallingManager, DEFAULT_TOOL_CALLING_MANAGER),
					this.observationRegistry, this.modelManagementOptions, this.retryTemplate);
		}

	}

}
