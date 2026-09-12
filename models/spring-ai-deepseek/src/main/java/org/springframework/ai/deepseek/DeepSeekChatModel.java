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

package org.springframework.ai.deepseek;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion.Choice;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.ai.deepseek.api.DeepSeekStreamFunctionCallingHelper;
import org.springframework.ai.deepseek.api.common.DeepSeekConstants;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.support.UsageCalculator;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link ChatModel} and {@link StreamingChatModel} implementation for {@literal DeepSeek}
 * backed by {@link DeepSeekApi}.
 *
 * @author Geng Rong
 * @author Thomas Vitale
 * @author Sebastien Deleuze
 * @author guan xu
 */
public class DeepSeekChatModel implements ChatModel {

	private static final Log logger = LogFactory.getLog(DeepSeekChatModel.class);

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build();

	/**
	 * Message metadata key marking an assistant message as a prefix-completion prefix
	 * (see the DeepSeek chat prefix completion feature). A plain {@link AssistantMessage}
	 * whose metadata holds {@code true} under this key is sent with {@code prefix: true}.
	 * @since 2.1.0
	 */
	public static final String PREFIX_METADATA_KEY = "deepseek.prefix";

	/**
	 * Message metadata key carrying the reasoning content as a single string. Superseded
	 * by {@link AssistantMessage#getReasoning()}; still written for one release and read
	 * as a replay fallback, to be removed afterwards.
	 */
	private static final String REASONING_CONTENT = "reasoningContent";

	/**
	 * Merges the buffered chunks of a streamed tool call into the complete call.
	 */
	private final DeepSeekStreamFunctionCallingHelper chunkMerger = new DeepSeekStreamFunctionCallingHelper();

	/**
	 * The default options used for the chat completion requests.
	 */
	private final DeepSeekChatOptions options;

	/**
	 * The retry template used to retry the DeepSeek API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the DeepSeek API.
	 */
	private final DeepSeekApi deepSeekApi;

	/**
	 * Observation registry used for instrumentation.
	 */
	private final ObservationRegistry observationRegistry;

	/**
	 * The tool calling manager used to resolve the tool definitions sent to the model.
	 */
	private final ToolCallingManager toolCallingManager;

	/**
	 * Conventions to use for generating observations.
	 */
	private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	public DeepSeekChatModel(DeepSeekApi deepSeekApi, DeepSeekChatOptions options,
			ToolCallingManager toolCallingManager, RetryTemplate retryTemplate,
			ObservationRegistry observationRegistry) {
		Assert.notNull(deepSeekApi, "deepSeekApi cannot be null");
		Assert.notNull(options, "options cannot be null");
		Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
		Assert.notNull(retryTemplate, "retryTemplate cannot be null");
		Assert.notNull(observationRegistry, "observationRegistry cannot be null");
		this.deepSeekApi = deepSeekApi;
		this.options = options;
		this.toolCallingManager = toolCallingManager;
		this.retryTemplate = retryTemplate;
		this.observationRegistry = observationRegistry;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return this.internalCall(requestPrompt, null);
	}

	private ChatResponse internalCall(Prompt prompt, @Nullable ChatResponse previousChatResponse) {

		ChatCompletionRequest request = createRequest(prompt, false);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(DeepSeekConstants.PROVIDER_NAME)
			.build();

		ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {

				ResponseEntity<ChatCompletion> completionEntity = RetryUtils.execute(this.retryTemplate,
						() -> this.deepSeekApi.chatCompletionEntity(request));

				var chatCompletion = completionEntity.getBody();

				if (chatCompletion == null) {
					if (logger.isWarnEnabled()) {
						logger.warn("No chat completion returned for prompt: " + prompt);
					}
					return new ChatResponse(List.of());
				}

				List<Choice> choices = chatCompletion.choices();
				if (choices == null) {
					if (logger.isWarnEnabled()) {
						logger.warn("No choices returned for prompt: " + prompt);
					}
					return new ChatResponse(List.of());
				}

				List<Generation> generations = choices.stream().map(choice -> {
			// @formatter:off
					Map<String, Object> metadata = Map.of(
							"id", chatCompletion.id() != null ? chatCompletion.id() : "",
							"role", choice.message().role() != null ? choice.message().role().name() : "",
							"index", choice.index(),
							"finishReason", choice.finishReason() != null ? choice.finishReason().name() : "",
							REASONING_CONTENT, reasoningContentOf(choice));
					// @formatter:on
					return buildGeneration(choice, metadata, null);
				}).toList();

				// Current usage
				ChatCompletion body = completionEntity.getBody();
				Assert.state(body != null, "Body must not be null");
				DeepSeekApi.Usage usage = body.usage();
				Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
				Usage accumulatedUsage = UsageCalculator.getCumulativeUsage(currentChatResponseUsage,
						previousChatResponse);
				ChatResponse chatResponse = new ChatResponse(generations, from(body, accumulatedUsage));

				observationContext.setResponse(chatResponse);

				return chatResponse;

			});

		return response;
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		Prompt requestPrompt = buildRequestPrompt(prompt);
		return internalStream(requestPrompt, null);
	}

	private Flux<ChatResponse> internalStream(Prompt prompt, @Nullable ChatResponse previousChatResponse) {
		return Flux.deferContextual(contextView -> {
			ChatCompletionRequest request = createRequest(prompt, true);

			// The raw chunks: the tool-call chunks are buffered and merged here (see
			// below) so their argument deltas can be streamed out as partial parts.
			Flux<DeepSeekApi.ChatCompletionChunk> completionChunks = this.deepSeekApi.chatCompletionStreamRaw(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();
			// Cumulative reasoning per choice for the deprecated metadata key, and the
			// part indexer per choice for the streaming contract.
			ConcurrentHashMap<String, String> reasoningMap = new ConcurrentHashMap<>();
			ConcurrentHashMap<String, StreamingPartIndexer> indexerMap = new ConcurrentHashMap<>();

			final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
				.prompt(prompt)
				.provider(DeepSeekConstants.PROVIDER_NAME)
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

			// Buffer the chunks of a tool call so the complete call is emitted once
			// (merged as DeepSeekApi.chatCompletionStream does), while each argument
			// delta is streamed out right away as a partial ToolCallPart that is not a
			// tool call yet.
			AtomicBoolean isInsideTool = new AtomicBoolean(false);
			List<DeepSeekApi.ChatCompletionChunk> toolCallBuffer = new ArrayList<>();
			Supplier<Flux<ChatResponse>> flushToolCallBuffer = () -> {
				DeepSeekApi.ChatCompletionChunk merged = null;
				for (DeepSeekApi.ChatCompletionChunk buffered : toolCallBuffer) {
					merged = this.chunkMerger.merge(merged, buffered);
				}
				toolCallBuffer.clear();
				isInsideTool.set(false);
				return merged == null ? Flux.empty() : Flux.just(toChatResponse(chunkToChatCompletion(merged),
						previousChatResponse, roleMap, reasoningMap, indexerMap));
			};
			Flux<ChatResponse> chatResponse = completionChunks.concatMap(chunk -> {
				if (this.chunkMerger.isStreamingToolFunctionCall(chunk)) {
					isInsideTool.set(true);
				}
				if (!isInsideTool.get()) {
					return Flux.just(toChatResponse(chunkToChatCompletion(chunk), previousChatResponse, roleMap,
							reasoningMap, indexerMap));
				}
				toolCallBuffer.add(chunk);
				ChatResponse deltaResponse = toolCallDeltaResponse(chunk, roleMap, indexerMap);
				Flux<ChatResponse> emitted = deltaResponse != null ? Flux.just(deltaResponse) : Flux.empty();
				if (this.chunkMerger.isStreamingToolFunctionCallFinish(chunk)) {
					emitted = emitted.concatWith(flushToolCallBuffer.get());
				}
				return emitted;
			})
				// A stream that ends inside a tool call (finish_reason "length", or a
				// server that never sends "tool_calls") still yields the merged chunk.
				.concatWith(Flux.defer(() -> toolCallBuffer.isEmpty() ? Flux.empty() : flushToolCallBuffer.get()));

			// @formatter:off
			Flux<ChatResponse> flux = chatResponse
				.doOnError(observation::error)
				.doFinally(s -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
			// @formatter:on

			return new MessageAggregator().aggregate(flux, observationContext::setResponse);

		});
	}

	/**
	 * Build the generation for one choice as ordered {@link MessagePart}s: reasoning,
	 * text and tool calls. When {@code indexer} is non-null the choice is a streaming
	 * delta: reasoning and text become partial indexed parts and tool calls complete
	 * ones. The message is still a {@link DeepSeekAssistantMessage} for one release so
	 * existing casts keep working.
	 */
	@SuppressWarnings("removal")
	private Generation buildGeneration(Choice choice, Map<String, Object> metadata,
			@Nullable StreamingPartIndexer indexer) {
		String finishReason = (choice.finishReason() != null ? choice.finishReason().name() : "");
		var generationMetadataBuilder = ChatGenerationMetadata.builder().finishReason(finishReason);

		List<MessagePart> parts = new ArrayList<>();
		// hasLength, not hasText: streamed reasoning deltas are often a single
		// whitespace or newline token that must be kept.
		String reasoning = reasoningContentOf(choice);
		if (StringUtils.hasLength(reasoning)) {
			parts.add(ReasoningPart.of(reasoning));
		}
		// In a stream only a non-empty content delta yields a text part, so the "" some
		// servers send next to every reasoning delta does not split the reasoning. A
		// complete response keeps the text the server sent, as it always has.
		String textContent = choice.message().content();
		if (textContent != null && (indexer == null || !textContent.isEmpty())) {
			parts.add(TextPart.of(textContent));
		}
		if (choice.message().toolCalls() != null) {
			for (ToolCall toolCall : choice.message().toolCalls()) {
				parts.add(ToolCallPart.of(new AssistantMessage.ToolCall(toolCall.id(), "function",
						toolCall.function().name(), toolCall.function().arguments())));
			}
		}
		if (indexer != null) {
			parts.replaceAll(indexer::stamp);
		}

		DeepSeekAssistantMessage.Builder assistantMessageBuilder = DeepSeekAssistantMessage.builder()
			.parts(parts)
			.properties(metadata);
		if (indexer != null && parts.isEmpty()) {
			// A chunk without any part keeps an empty text, as it always had, so chunk
			// texts can be joined without a null check.
			assistantMessageBuilder.content("");
		}
		DeepSeekAssistantMessage assistantMessage = assistantMessageBuilder.build();
		return new Generation(assistantMessage, generationMetadataBuilder.build());
	}

	private static String reasoningContentOf(Choice choice) {
		String reasoningContent = choice.message().reasoningContent();
		return reasoningContent != null ? reasoningContent : "";
	}

	/**
	 * The reasoning text to send back as {@code reasoning_content}: the joined text of
	 * the {@link ReasoningPart}s without payload (signed reasoning from other providers
	 * is skipped), falling back to the deprecated {@link DeepSeekAssistantMessage}
	 * accessor and then to the {@link #REASONING_CONTENT} metadata key for messages that
	 * have no reasoning part. Empty reasoning is never sent.
	 */
	@SuppressWarnings("removal")
	private static @Nullable String replayableReasoning(AssistantMessage assistantMessage) {
		String reasoning;
		if (!assistantMessage.getReasoning().isEmpty()) {
			StringBuilder joined = new StringBuilder();
			for (ReasoningPart part : assistantMessage.getReasoning()) {
				if (part.payload() == null && part.text() != null) {
					joined.append(part.text());
				}
			}
			reasoning = joined.toString();
		}
		else if (assistantMessage instanceof DeepSeekAssistantMessage deepSeekAssistantMessage
				&& deepSeekAssistantMessage.getReasoningContent() != null) {
			reasoning = deepSeekAssistantMessage.getReasoningContent();
		}
		else {
			reasoning = assistantMessage.getMetadata().get(REASONING_CONTENT) instanceof String value ? value : null;
		}
		return StringUtils.hasLength(reasoning) ? reasoning : null;
	}

	@SuppressWarnings("removal")
	private static boolean isPrefix(AssistantMessage assistantMessage) {
		if (Boolean.TRUE.equals(assistantMessage.getMetadata().get(PREFIX_METADATA_KEY))) {
			return true;
		}
		return assistantMessage instanceof DeepSeekAssistantMessage deepSeekAssistantMessage
				&& Boolean.TRUE.equals(deepSeekAssistantMessage.getPrefix());
	}

	private ChatResponseMetadata from(DeepSeekApi.ChatCompletion result, Usage usage) {
		Assert.notNull(result, "DeepSeek ChatCompletionResult must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(result.id() != null ? result.id() : "")
			.usage(usage)
			.model(result.model() != null ? result.model() : "")
			.keyValue("created", result.created() != null ? result.created() : 0L)
			.keyValue("system-fingerprint", result.systemFingerprint() != null ? result.systemFingerprint() : "");
		return builder.build();
	}

	private ChatResponseMetadata from(ChatResponseMetadata chatResponseMetadata, Usage usage) {
		Assert.notNull(chatResponseMetadata, "DeepSeek ChatResponseMetadata must not be null");
		var builder = ChatResponseMetadata.builder()
			.id(chatResponseMetadata.getId() != null ? chatResponseMetadata.getId() : "")
			.usage(usage)
			.model(chatResponseMetadata.getModel() != null ? chatResponseMetadata.getModel() : "");
		return builder.build();
	}

	/**
	 * Converts one streamed (possibly merged) chunk into a {@link ChatResponse}.
	 */
	private ChatResponse toChatResponse(DeepSeekApi.ChatCompletion chatCompletion,
			@Nullable ChatResponse previousChatResponse, ConcurrentHashMap<String, String> roleMap,
			ConcurrentHashMap<String, String> reasoningMap,
			ConcurrentHashMap<String, StreamingPartIndexer> indexerMap) {
		try {
			String id = chatCompletion.id();

			List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
				if (choice.message().role() != null) {
					roleMap.putIfAbsent(id, choice.message().role().name());
				}

				String choiceKey = id + ":" + choice.index();
				String accumulatedReasoning = reasoningMap.merge(choiceKey, reasoningContentOf(choice), String::concat);
				StreamingPartIndexer indexer = indexerMap.computeIfAbsent(choiceKey, key -> new StreamingPartIndexer());

				// @formatter:off
				Map<String, Object> metadata = Map.of(
						"id", chatCompletion.id(),
						"role", roleMap.getOrDefault(id, ""),
						"finishReason", choice.finishReason() != null ? choice.finishReason().name() : "",
						REASONING_CONTENT, accumulatedReasoning
				);
				// @formatter:on
				return buildGeneration(choice, metadata, indexer);
			}).toList();
			DeepSeekApi.Usage usage = chatCompletion.usage();
			Usage currentUsage = (usage != null) ? getDefaultUsage(usage) : new EmptyUsage();
			Usage cumulativeUsage = UsageCalculator.getCumulativeUsage(currentUsage, previousChatResponse);

			return new ChatResponse(generations, from(chatCompletion, cumulativeUsage));
		}
		catch (Exception e) {
			logger.error("Error processing chat completion", e);
			return new ChatResponse(List.of());
		}
	}

	/**
	 * The partial {@link ToolCallPart}s for the tool-call deltas of one raw streamed
	 * chunk, or {@code null} when the chunk carries none (or only deltas whose call has
	 * not been identified by id and name yet). Such a chunk has no text and, because
	 * partial parts are not tool calls, {@code hasToolCalls()} is false on it.
	 */
	private static @Nullable ChatResponse toolCallDeltaResponse(DeepSeekApi.ChatCompletionChunk chunk,
			ConcurrentHashMap<String, String> roleMap, ConcurrentHashMap<String, StreamingPartIndexer> indexerMap) {
		String id = chunk.id() != null ? chunk.id() : "";
		List<Generation> generations = new ArrayList<>();
		if (chunk.choices() == null) {
			return null;
		}
		for (DeepSeekApi.ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
			List<ToolCall> deltas = choice.delta() != null && choice.delta().toolCalls() != null
					? choice.delta().toolCalls() : List.of();
			if (deltas.isEmpty()) {
				continue;
			}
			StreamingPartIndexer indexer = indexerMap.computeIfAbsent(id + ":" + choice.index(),
					key -> new StreamingPartIndexer());
			AssistantMessage.Builder<?> messageBuilder = AssistantMessage.builder()
				.properties(Map.of("id", id, "role", roleMap.getOrDefault(id, ""), "index", choice.index()));
			boolean hasPart = false;
			for (int i = 0; i < deltas.size(); i++) {
				ToolCall delta = deltas.get(i);
				ChatCompletionFunction function = delta.function();
				String arguments = function != null && function.arguments() != null ? function.arguments() : "";
				long deltaIndex = delta.index() != null ? delta.index() : i;
				MessagePart part = indexer.toolCallDelta(deltaIndex, delta.id(),
						function != null ? function.name() : null, arguments);
				if (part != null) {
					messageBuilder.part(part);
					hasPart = true;
				}
			}
			if (hasPart) {
				generations.add(new Generation(messageBuilder.build()));
			}
		}
		if (generations.isEmpty()) {
			return null;
		}
		return new ChatResponse(generations, ChatResponseMetadata.builder().id(id).model(chunk.model()).build());
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private DeepSeekApi.ChatCompletion chunkToChatCompletion(DeepSeekApi.ChatCompletionChunk chunk) {
		List<Choice> choices = chunk.choices()
			.stream()
			.map(chunkChoice -> new Choice(chunkChoice.finishReason(), chunkChoice.index(), chunkChoice.delta(),
					chunkChoice.logprobs()))
			.toList();

		return new DeepSeekApi.ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(), chunk.serviceTier(),
				chunk.systemFingerprint(), chunk.usage());
	}

	private DefaultUsage getDefaultUsage(DeepSeekApi.Usage usage) {
		return new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage);
	}

	/**
	 * Accessible for testing.
	 */
	ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
		List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
			if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
				String text = message.getText();
				Assert.state(text != null, "text must not be null");
				return List.of(new ChatCompletionMessage(text,
						ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
			}
			else if (message.getMessageType() == MessageType.ASSISTANT) {
				var assistantMessage = (AssistantMessage) message;
				List<ToolCall> toolCalls = null;
				if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
					toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
						var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
						return new ToolCall(toolCall.id(), toolCall.type(), function);
					}).toList();
				}
				Boolean isPrefixAssistantMessage = isPrefix(assistantMessage) ? Boolean.TRUE : null;
				String reasoningContent = replayableReasoning(assistantMessage);
				// The text is null for tool-call turns without content, which the API
				// accepts; histories from other providers produce such messages.
				return List
					.of(new ChatCompletionMessage(assistantMessage.getText(), ChatCompletionMessage.Role.ASSISTANT,
							null, null, toolCalls, isPrefixAssistantMessage, reasoningContent));
			}
			else if (message.getMessageType() == MessageType.TOOL) {
				ToolResponseMessage toolMessage = (ToolResponseMessage) message;

				toolMessage.getResponses()
					.forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));
				return toolMessage.getResponses()
					.stream()
					.map(tr -> new ChatCompletionMessage(tr.responseData(), ChatCompletionMessage.Role.TOOL, tr.name(),
							tr.id(), null))
					.toList();
			}
			else {
				throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
			}
		}).flatMap(List::stream).toList();

		ChatCompletionRequest.Builder requestBuilder = ChatCompletionRequest.builder()
			.messages(chatCompletionMessages)
			.stream(stream);

		DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getOptions();
		Assert.state(options != null, "requestOptions must not be null");

		validateThinkingParameters(options);

		if (options.getModel() != null) {
			requestBuilder.model(options.getModel());
		}
		if (options.getFrequencyPenalty() != null) {
			requestBuilder.frequencyPenalty(options.getFrequencyPenalty());
		}
		if (options.getMaxTokens() != null) {
			requestBuilder.maxTokens(options.getMaxTokens());
		}
		if (options.getPresencePenalty() != null) {
			requestBuilder.presencePenalty(options.getPresencePenalty());
		}
		if (options.getResponseFormat() != null) {
			requestBuilder.responseFormat(options.getResponseFormat());
		}
		if (options.getStop() != null) {
			requestBuilder.stop(options.getStop());
		}
		if (options.getTemperature() != null) {
			requestBuilder.temperature(options.getTemperature());
		}
		if (options.getTopP() != null) {
			requestBuilder.topP(options.getTopP());
		}
		if (options.getLogprobs() != null) {
			requestBuilder.logprobs(options.getLogprobs());
		}
		if (options.getTopLogprobs() != null) {
			requestBuilder.topLogprobs(options.getTopLogprobs());
		}
		if (options.getTools() != null) {
			requestBuilder.tools(options.getTools());
		}
		if (options.getToolChoice() != null) {
			requestBuilder.toolChoice(options.getToolChoice());
		}
		if (options.getThinking() != null) {
			requestBuilder.thinking(options.getThinking());
		}
		if (options.getReasoningEffort() != null) {
			requestBuilder.reasoningEffort(options.getReasoningEffort());
		}

		// Add the tool definitions to the request's tools parameter.
		List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(options);
		if (!CollectionUtils.isEmpty(toolDefinitions)) {
			requestBuilder.tools(this.getFunctionTools(toolDefinitions));
		}

		return requestBuilder.build();
	}

	/**
	 * Thinking mode does not support the {@code temperature}, {@code top_p},
	 * {@code presence_penalty}, or {@code frequency_penalty} parameters. For
	 * compatibility with existing software, setting these parameters does not trigger an
	 * error but also has no effect, so a warning is logged to alert callers that the
	 * values will be silently ignored by the DeepSeek API.
	 * @param options the chat options to validate
	 */
	private void validateThinkingParameters(DeepSeekChatOptions options) {
		if (logger.isWarnEnabled()) {
			ChatCompletionRequest.Thinking thinking = options.getThinking();
			if (thinking == null || ChatCompletionRequest.Thinking.Type.ENABLED == thinking.type()) {
				List<String> ignoredParameters = new ArrayList<>();
				if (options.getTemperature() != null) {
					ignoredParameters.add("temperature");
				}
				if (options.getTopP() != null) {
					ignoredParameters.add("top_p");
				}
				if (options.getPresencePenalty() != null) {
					ignoredParameters.add("presence_penalty");
				}
				if (options.getFrequencyPenalty() != null) {
					ignoredParameters.add("frequency_penalty");
				}
				if (!ignoredParameters.isEmpty()) {
					logger.warn("Thinking mode does not support the " + String.join(", ", ignoredParameters)
							+ " parameter(s). Please note that, for compatibility with existing software, setting these parameters will not trigger an error but will also have no effect.");
				}
			}
		}
	}

	private List<DeepSeekApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions) {
		return toolDefinitions.stream().map(toolDefinition -> {
			var function = new DeepSeekApi.FunctionTool.Function(toolDefinition.description(), toolDefinition.name(),
					toolDefinition.inputSchema());
			return new DeepSeekApi.FunctionTool(function);
		}).toList();
	}

	/**
	 * @since 2.0.0
	 */
	@Override
	public DeepSeekChatOptions getOptions() {
		return this.options;
	}

	@Override
	public String toString() {
		return "DeepSeekChatModel [options=" + this.options + "]";
	}

	/**
	 * Use the provided convention for reporting observation data
	 * @param observationConvention The provided convention
	 */
	public void setObservationConvention(ChatModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention cannot be null");
		this.observationConvention = observationConvention;
	}

	public static Builder builder() {
		return new Builder();
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
	 * Assigns stream part indices per choice: consecutive deltas of the same kind
	 * (reasoning or text) share an index as partial parts; a tool call keeps one index
	 * from its first argument delta to the complete part, keyed by tool call id; every
	 * other part is complete and gets its own index.
	 */
	private static final class StreamingPartIndexer {

		private int index = -1;

		private @Nullable Class<? extends MessagePart> lastDeltaKind;

		private final Map<String, Integer> toolCallIndexById = new HashMap<>();

		// id and name of the tool call streamed at each delta index
		private final Map<Long, AssistantMessage.ToolCall> startedToolCalls = new HashMap<>();

		MessagePart stamp(MessagePart part) {
			if (part instanceof ToolCallPart toolCallPart) {
				this.lastDeltaKind = null;
				return StreamingParts.complete(part, toolCallIndex(toolCallPart.toolCall().id()));
			}
			boolean delta = part instanceof TextPart || part instanceof ReasoningPart;
			if (!delta || part.getClass() != this.lastDeltaKind) {
				this.index++;
			}
			this.lastDeltaKind = delta ? part.getClass() : null;
			return delta ? StreamingParts.partial(part, this.index) : StreamingParts.complete(part, this.index);
		}

		/**
		 * A partial tool call part for one tool-call delta, or {@code null} while the
		 * call at that delta index has not been identified by id and name yet.
		 * Continuation deltas omit the id or send an empty one.
		 */
		@Nullable MessagePart toolCallDelta(long deltaIndex, @Nullable String id, @Nullable String name, String argumentsDelta) {
			if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
				this.startedToolCalls.put(deltaIndex, new AssistantMessage.ToolCall(id, "function", name, ""));
			}
			AssistantMessage.ToolCall started = this.startedToolCalls.get(deltaIndex);
			if (started == null) {
				return null;
			}
			this.lastDeltaKind = null;
			ToolCallPart part = ToolCallPart
				.of(new AssistantMessage.ToolCall(started.id(), "function", started.name(), argumentsDelta));
			return StreamingParts.partial(part, toolCallIndex(started.id()));
		}

		private int toolCallIndex(String id) {
			Integer existing = this.toolCallIndexById.get(id);
			if (existing != null) {
				return existing;
			}
			this.index++;
			this.toolCallIndexById.put(id, this.index);
			return this.index;
		}

	}

	public static final class Builder {

		private @Nullable DeepSeekApi deepSeekApi;

		private DeepSeekChatOptions options = DeepSeekChatOptions.builder().build();

		private @Nullable ToolCallingManager toolCallingManager;

		private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private Builder() {
		}

		public Builder deepSeekApi(DeepSeekApi deepSeekApi) {
			this.deepSeekApi = deepSeekApi;
			return this;
		}

		public Builder options(DeepSeekChatOptions options) {
			this.options = options;
			return this;
		}

		/**
		 * Sets the tool calling manager used to resolve the tool definitions sent to the
		 * model.
		 * @param toolCallingManager the tool calling manager
		 * @return this builder
		 */
		public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
			this.toolCallingManager = toolCallingManager;
			return this;
		}

		public Builder retryTemplate(RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public DeepSeekChatModel build() {
			Assert.state(this.deepSeekApi != null, "DeepSeekApi must not be null");
			return new DeepSeekChatModel(this.deepSeekApi, this.options,
					Objects.requireNonNullElse(this.toolCallingManager, DEFAULT_TOOL_CALLING_MANAGER),
					this.retryTemplate, this.observationRegistry);
		}

	}

}
