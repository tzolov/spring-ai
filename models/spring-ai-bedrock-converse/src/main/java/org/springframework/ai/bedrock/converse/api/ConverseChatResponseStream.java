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

package org.springframework.ai.bedrock.converse.api;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.Assert;

/**
 * Sends a {@link ConverseStreamRequest} to Bedrock and returns {@link ChatResponse}
 * stream.
 * <p>
 * Each content block delta is emitted as a partial {@link MessagePart} indexed by the
 * Converse content block index: text and reasoning deltas, and the reasoning signature
 * and redacted content as payload-only deltas. The tool-use input is buffered and emitted
 * as complete {@link ToolCallPart}s on the final chunk, exactly as before, so
 * {@link org.springframework.ai.chat.model.MessageAggregator} rebuilds the message from
 * the parts and tool execution still happens on that chunk. When tool-call delta
 * streaming is enabled, the started tool use and each of its input deltas are also
 * emitted as partial {@link ToolCallPart}s that are not tool calls yet.
 *
 * @author Jared Rufer
 * @author Christian Tzolov
 * @since 1.1.0
 */
public class ConverseChatResponseStream implements ConverseStreamResponseHandler.Visitor {

	private static final Log logger = LogFactory.getLog(ConverseChatResponseStream.class);

	public static final Sinks.EmitFailureHandler DEFAULT_EMIT_FAILURE_HANDLER = Sinks.EmitFailureHandler
		.busyLooping(Duration.ofSeconds(10));

	private final AtomicReference<String> requestIdRef = new AtomicReference<>("Unknown");

	private final AtomicReference<TokenUsage> tokenUsageRef = new AtomicReference<>();

	private final AtomicInteger promptTokens = new AtomicInteger();

	private final AtomicInteger generationTokens = new AtomicInteger();

	private final AtomicInteger totalTokens = new AtomicInteger();

	private final AtomicReference<String> stopReason = new AtomicReference<>();

	private final Map<Integer, StreamingToolCallBuilder> toolUseMap = new ConcurrentHashMap<>();

	private final Sinks.Many<ChatResponse> eventSink = Sinks.many().multicast().onBackpressureBuffer();

	private final BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;

	private final ConverseStreamRequest converseStreamRequest;

	private final boolean streamToolCallDeltas;

	public ConverseChatResponseStream(BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient,
			ConverseStreamRequest converseStreamRequest, @Nullable Usage accumulatedUsage) {
		this(bedrockRuntimeAsyncClient, converseStreamRequest, accumulatedUsage, false);
	}

	/**
	 * Creates a stream, optionally emitting the tool-use start and input deltas as
	 * partial {@link ToolCallPart} chunks before the complete calls on the final chunk.
	 * @param streamToolCallDeltas whether to emit tool-call delta chunks
	 * @since 2.1.0
	 */
	public ConverseChatResponseStream(BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient,
			ConverseStreamRequest converseStreamRequest, @Nullable Usage accumulatedUsage,
			boolean streamToolCallDeltas) {

		Assert.notNull(bedrockRuntimeAsyncClient, "'bedrockRuntimeAsyncClient' must not be null");
		Assert.notNull(converseStreamRequest, "'converseStreamRequest' must not be null");

		this.bedrockRuntimeAsyncClient = bedrockRuntimeAsyncClient;
		this.converseStreamRequest = converseStreamRequest;
		this.streamToolCallDeltas = streamToolCallDeltas;
		if (accumulatedUsage != null) {
			this.totalTokens.set(accumulatedUsage.getTotalTokens());
			this.promptTokens.set(accumulatedUsage.getPromptTokens());
			this.generationTokens.set(accumulatedUsage.getCompletionTokens());
			if (accumulatedUsage.getNativeUsage() instanceof TokenUsage tokenUsage) {
				this.mergeNativeTokenUsage(tokenUsage);
			}
		}
	}

	@Override
	public void visitContentBlockStart(ContentBlockStartEvent event) {
		if (ContentBlockStart.Type.TOOL_USE.equals(event.start().type())) {
			String id = event.start().toolUse().toolUseId();
			String name = event.start().toolUse().name();
			this.toolUseMap.put(event.contentBlockIndex(), new StreamingToolCallBuilder().id(id).name(name));
			this.emitToolCallDelta(event.contentBlockIndex(), id, name, "");
		}
	}

	@Override
	public void visitContentBlockDelta(ContentBlockDeltaEvent event) {
		StreamingToolCallBuilder toolCallBuilder = this.toolUseMap.get(event.contentBlockIndex());

		if (toolCallBuilder != null) {
			String inputDelta = event.delta().toolUse().input();
			toolCallBuilder.delta(inputDelta);
			this.emitToolCallDelta(event.contentBlockIndex(), toolCallBuilder.id(), toolCallBuilder.name(), inputDelta);
		}
		else if (ContentBlockDelta.Type.TEXT.equals(event.delta().type())) {
			this.emitPart(StreamingParts.partial(TextPart.of(event.delta().text()), event.contentBlockIndex()));
		}
		else if (ContentBlockDelta.Type.REASONING_CONTENT.equals(event.delta().type())) {
			ReasoningPart reasoningDelta = toReasoningDelta(event.delta().reasoningContent());
			if (reasoningDelta != null) {
				this.emitPart(StreamingParts.partial(reasoningDelta, event.contentBlockIndex()));
			}
		}
	}

	/**
	 * A reasoning delta carries either text, the signature that closes the block, or
	 * redacted content. The signature and the redacted content are emitted as
	 * payload-only deltas of the same block.
	 */
	private static @Nullable ReasoningPart toReasoningDelta(ReasoningContentBlockDelta delta) {
		if (delta.text() != null) {
			return ReasoningPart.of(delta.text());
		}
		if (delta.signature() != null) {
			return new ReasoningPart(null, null, false, new OpaquePayload(ConverseApiUtils.BEDROCK_PROVIDER,
					ConverseApiUtils.PAYLOAD_SIGNATURE, delta.signature()), Map.of());
		}
		if (delta.redactedContent() != null) {
			return new ReasoningPart(null, null, true,
					new OpaquePayload(ConverseApiUtils.BEDROCK_PROVIDER, ConverseApiUtils.PAYLOAD_REDACTED_CONTENT,
							Base64.getEncoder().encodeToString(delta.redactedContent().asByteArray())),
					Map.of());
		}
		return null;
	}

	@Override
	public void visitMessageStop(MessageStopEvent event) {
		this.stopReason.set(event.stopReasonAsString());
	}

	@Override
	public void visitMetadata(ConverseStreamMetadataEvent event) {
		this.promptTokens.addAndGet(event.usage().inputTokens());
		this.generationTokens.addAndGet(event.usage().outputTokens());
		this.totalTokens.addAndGet(event.usage().totalTokens());
		this.mergeNativeTokenUsage(event.usage());

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(this.stopReason.get())
			.build();

		// The buffered tool calls are complete parts at their content block index. The
		// final chunk keeps an empty text as it always had.
		List<MessagePart> toolCallParts = this.toolUseMap.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.map(entry -> StreamingParts.complete(ToolCallPart.of(entry.getValue().build()), entry.getKey()))
			.toList();

		this.emitChatResponse(new Generation(AssistantMessage.builder().parts(toolCallParts).content("").build(),
				generationMetadata));
	}

	private void emitPart(MessagePart part) {
		this.emitChatResponse(new Generation(AssistantMessage.builder().part(part).build()));
	}

	/**
	 * Emits a partial {@link ToolCallPart} holding only the given input delta, when
	 * tool-call delta streaming is enabled. A partial part is not a tool call (see
	 * {@link AssistantMessage#getToolCalls()}), so the chunk cannot trigger tool
	 * execution; the complete call follows on the final chunk.
	 */
	private void emitToolCallDelta(int contentBlockIndex, @Nullable String id, @Nullable String name,
			String inputDelta) {
		if (!this.streamToolCallDeltas || id == null || name == null) {
			return;
		}
		ToolCallPart delta = ToolCallPart.of(new AssistantMessage.ToolCall(id, "function", name, inputDelta));
		this.emitPart(StreamingParts.partial(delta, contentBlockIndex));
	}

	private void mergeNativeTokenUsage(TokenUsage tokenUsage) {
		this.tokenUsageRef.accumulateAndGet(tokenUsage, (current, next) -> {
			if (current == null) {
				return next;
			}
			else {
				return TokenUsage.builder()
					.inputTokens(addTokens(current.inputTokens(), next.inputTokens()))
					.outputTokens(addTokens(current.outputTokens(), next.outputTokens()))
					.totalTokens(addTokens(current.totalTokens(), next.totalTokens()))
					.cacheReadInputTokens(addTokens(current.cacheReadInputTokens(), next.cacheReadInputTokens()))
					.cacheWriteInputTokens(addTokens(current.cacheWriteInputTokens(), next.cacheWriteInputTokens()))
					.build();
			}
		});
	}

	private static Integer addTokens(Integer current, Integer next) {
		if (current == null) {
			return next;
		}
		if (next == null) {
			return current;
		}
		return current + next;
	}

	private void emitChatResponse(Generation generation) {
		var metadataBuilder = ChatResponseMetadata.builder();
		metadataBuilder.id(this.requestIdRef.get());
		metadataBuilder.usage(this.getCurrentUsage());

		ChatResponse chatResponse = new ChatResponse(generation == null ? List.of() : List.of(generation),
				metadataBuilder.build());

		this.eventSink.emitNext(chatResponse, DEFAULT_EMIT_FAILURE_HANDLER);
	}

	private Usage getCurrentUsage() {
		TokenUsage nativeUsage = this.tokenUsageRef.get();
		Integer cacheReadInt = nativeUsage != null ? nativeUsage.cacheReadInputTokens() : null;
		Integer cacheWriteInt = nativeUsage != null ? nativeUsage.cacheWriteInputTokens() : null;
		return new DefaultUsage(this.promptTokens.get(), this.generationTokens.get(), this.totalTokens.get(),
				nativeUsage, cacheReadInt != null ? cacheReadInt.longValue() : null,
				cacheWriteInt != null ? cacheWriteInt.longValue() : null);
	}

	/**
	 * Invoke the model and return the chat response stream.
	 * @see <a href=
	 * "https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters.html">
	 * https://docs.aws.amazon.com/bedrock/latest/userguide/model-parameters.html</a>
	 * @see <a href=
	 * "https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html">
	 * https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_Converse.html</a>
	 * @see <a href=
	 * "https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/bedrockruntime/BedrockRuntimeAsyncClient.html#converseStream">
	 * https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/bedrockruntime/BedrockRuntimeAsyncClient.html#converseStream</a>
	 */
	public Flux<ChatResponse> stream() {

		ConverseStreamResponseHandler responseHandler = ConverseStreamResponseHandler.builder()
			.subscriber(this)
			.onResponse(converseStreamResponse -> this.requestIdRef
				.set(converseStreamResponse.responseMetadata().requestId()))
			.onComplete(() -> {
				this.eventSink.emitComplete(DEFAULT_EMIT_FAILURE_HANDLER);
				logger.info("Completed streaming response.");
			})
			.onError(error -> {
				logger.error("Error handling Bedrock converse stream response", error);
				this.eventSink.emitError(error, DEFAULT_EMIT_FAILURE_HANDLER);
			})
			.build();
		this.bedrockRuntimeAsyncClient.converseStream(this.converseStreamRequest, responseHandler);

		return this.eventSink.asFlux();
	}

}
