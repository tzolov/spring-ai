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

package org.springframework.ai.openai;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionAudio;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.async.ChatServiceAsync;
import com.openai.services.async.chat.ChatCompletionServiceAsync;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link MessagePart} mapping of {@link OpenAiChatModel}: reasoning content
 * as {@link ReasoningPart}, per-tool-call extra properties as {@link ToolCallPart}
 * attributes, and the streaming contract.
 *
 * @author Christian Tzolov
 */
@ExtendWith(MockitoExtension.class)
class OpenAiChatModelMessagePartsTests {

	private static final String EXTRA_CONTENT = "{\"extra_content\":{\"google\":{\"thought_signature\":\"sig-1\"}}}";

	@Mock
	OpenAIClient openAiClient;

	@Mock
	OpenAIClientAsync openAiClientAsync;

	// -- inbound, non-streaming --

	@Test
	void reasoningTextAndToolCallBecomeOrderedParts() {
		givenCompletion(ChatCompletionMessage.builder()
			.content("Let me check.")
			.refusal(Optional.empty())
			.role(JsonValue.from("assistant"))
			.annotations(List.of())
			.toolCalls(List.of(ChatCompletionMessageToolCall.ofFunction(ChatCompletionMessageFunctionToolCall.builder()
				.id("call_1")
				.function(ChatCompletionMessageFunctionToolCall.Function.builder()
					.name("getWeather")
					.arguments("{\"city\":\"Seoul\"}")
					.build())
				.putAdditionalProperty("extra_content",
						JsonValue.from(Map.of("google", Map.of("thought_signature", "sig-1"))))
				.build())))
			.putAdditionalProperty("reasoning_content", JsonValue.from("Think first."))
			.build());

		AssistantMessage message = chatModel("deepseek-reasoner").call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getParts()).hasSize(3);
		assertThat(message.getParts().get(0)).isEqualTo(ReasoningPart.of("Think first."));
		assertThat(message.getParts().get(1)).isEqualTo(TextPart.of("Let me check."));
		assertThat(message.getParts().get(2)).isInstanceOf(ToolCallPart.class);
		ToolCallPart toolCallPart = (ToolCallPart) message.getParts().get(2);
		assertThat(toolCallPart.toolCall().id()).isEqualTo("call_1");
		assertThat(toolCallPart.attributes()).containsKey(OpenAiChatModel.TOOL_CALL_ADDITIONAL_PROPERTIES_ATTRIBUTE);
		assertThat(toolCallPart.attributes().get(OpenAiChatModel.TOOL_CALL_ADDITIONAL_PROPERTIES_ATTRIBUTE))
			.contains("thought_signature");
		assertThat(message.getText()).isEqualTo("Let me check.");
		assertThat(message.getReasoning()).containsExactly(ReasoningPart.of("Think first."));
		// Deprecated metadata keys kept for one release
		assertThat(message.getMetadata()).containsEntry("reasoningContent", "Think first.")
			.containsKey("openai.tool_calls.additional_properties");
	}

	@Test
	void plainAnswerIsASingleTextPart() {
		givenCompletion(ChatCompletionMessage.builder()
			.content("hello")
			.refusal(Optional.empty())
			.role(JsonValue.from("assistant"))
			.annotations(List.of())
			.toolCalls(List.of())
			.build());

		AssistantMessage message = chatModel("gpt-4o-mini").call(new Prompt("hi")).getResult().getOutput();

		assertThat(message.getParts()).containsExactly(TextPart.of("hello"));
		assertThat(message.getReasoning()).isEmpty();
	}

	@Test
	void toolCallResponseKeepsEmptyTextBeforeToolCalls() {
		givenCompletion(ChatCompletionMessage.builder()
			.content(Optional.empty())
			.refusal(Optional.empty())
			.role(JsonValue.from("assistant"))
			.annotations(List.of())
			.toolCalls(List.of(ChatCompletionMessageToolCall.ofFunction(ChatCompletionMessageFunctionToolCall.builder()
				.id("call_1")
				.function(ChatCompletionMessageFunctionToolCall.Function.builder()
					.name("getWeather")
					.arguments("{}")
					.build())
				.build())))
			.build());

		AssistantMessage message = chatModel("gpt-4o-mini").call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getText()).isEmpty();
		assertThat(message.getParts()).hasSize(2);
		assertThat(message.getParts().get(0)).isEqualTo(TextPart.of(""));
		assertThat(message.getParts().get(1)).isInstanceOf(ToolCallPart.class);
		assertThat(((ToolCallPart) message.getParts().get(1)).attributes()).isEmpty();
	}

	// -- outbound --

	@Test
	void toolCallPartAttributeIsReplayedAsAdditionalProperties() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(TextPart.of(""))
			.part(new ToolCallPart(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}"), null,
					Map.of(OpenAiChatModel.TOOL_CALL_ADDITIONAL_PROPERTIES_ATTRIBUTE, EXTRA_CONTENT)))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("call_2", "function", "getTime", "{}")))
			.build();

		ChatCompletionAssistantMessageParam param = assistantParam(chatModel("gemini-3.5-flash"), assistant);

		List<ChatCompletionMessageToolCall> toolCalls = param.toolCalls().orElseThrow();
		assertThat(toolCalls).hasSize(2);
		@SuppressWarnings("unchecked")
		Map<String, Object> extra = toolCalls.get(0)
			.asFunction()
			._additionalProperties()
			.get("extra_content")
			.convert(Map.class);
		assertThat(extra).containsEntry("google", Map.of("thought_signature", "sig-1"));
		assertThat(toolCalls.get(1).asFunction()._additionalProperties()).doesNotContainKey("extra_content");
	}

	@Test
	void reasoningPartIsReplayedAsReasoningContent() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(ReasoningPart.of("25 * 4 = 100."))
			.part(TextPart.of("100"))
			.build();

		ChatCompletionAssistantMessageParam param = assistantParam(chatModel("deepseek-reasoner"), assistant);

		assertThat(param._additionalProperties()).containsEntry("reasoning_content", JsonValue.from("25 * 4 = 100."));
	}

	@Test
	void foreignSignedReasoningIsNotReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("anthropic thoughts", null, false,
					new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.part(TextPart.of("100"))
			.build();

		ChatCompletionAssistantMessageParam param = assistantParam(chatModel("deepseek-reasoner"), assistant);

		assertThat(param._additionalProperties()).doesNotContainKey("reasoning_content");
	}

	// -- streaming --

	@Test
	void streamingReasoningTextAndToolCallProduceIndexedParts() {
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
					.putAdditionalProperty("reasoning_content", JsonValue.from("Think ")), null),
				streamingChunk(delta -> delta.putAdditionalProperty("reasoning_content", JsonValue.from("first.")),
						null),
				streamingChunk(delta -> delta.content("Let me "), null),
				streamingChunk(delta -> delta.content("check."), null),
				streamingChunk(delta -> delta.toolCalls(List.of(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
					.index(0L)
					.id("call_1")
					.function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
						.name("getWeather")
						.arguments("{\"city\":")
						.build())
					.build())), null),
				streamingChunk(delta -> delta.toolCalls(List.of(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
					.index(0L)
					.function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
						.arguments("\"Seoul\"}")
						.build())
					.build())), null),
				streamingChunk(delta -> {
				}, ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set)
			.collectList()
			.block();

		// reasoning deltas, text deltas, two argument-delta chunks, then the merged
		// tool-call chunk
		assertThat(responses).hasSize(7);
		assertThat(responses).allSatisfy(r -> assertThat(r.getMetadata().getId()).isEqualTo("chatcmpl-123"));
		assertThat(responses.get(4).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{\"city\":")), 2));
		assertThat(responses.get(5).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "\"Seoul\"}")), 2));
		// Argument deltas are not tool calls yet
		assertThat(responses.get(4).hasToolCalls()).isFalse();
		assertThat(responses.get(5).hasToolCalls()).isFalse();
		assertThat(responses.get(4).getResult().getOutput().getText()).isEmpty();
		assertThat(responses.get(6).hasToolCalls()).isTrue();
		MessagePart first = responses.get(0).getResult().getOutput().getParts().get(0);
		assertThat(first).isEqualTo(StreamingParts.partial(ReasoningPart.of("Think "), 0));
		assertThat(responses.get(0).getResult().getOutput().getText()).isEmpty();
		MessagePart second = responses.get(1).getResult().getOutput().getParts().get(0);
		assertThat(second).isEqualTo(StreamingParts.partial(ReasoningPart.of("first."), 0));
		MessagePart text = responses.get(2).getResult().getOutput().getParts().get(0);
		assertThat(text).isEqualTo(StreamingParts.partial(TextPart.of("Let me "), 1));
		assertThat(responses.get(2).getResult().getOutput().getText()).isEqualTo("Let me ");
		List<MessagePart> lastParts = responses.get(6).getResult().getOutput().getParts();
		MessagePart call = lastParts.get(lastParts.size() - 1);
		assertThat(call).isInstanceOf(ToolCallPart.class);
		assertThat(StreamingParts.partIndex(call)).isEqualTo(2);
		assertThat(StreamingParts.isPartial(call)).isFalse();
		// Deprecated per-chunk cumulative metadata is still there
		assertThat(responses.get(1).getResult().getOutput().getMetadata()).containsEntry("reasoningContent",
				"Think first.");

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).hasSize(3);
		assertThat(aggregated.getParts().get(0)).isEqualTo(ReasoningPart.of("Think first."));
		assertThat(aggregated.getParts().get(1)).isEqualTo(TextPart.of("Let me check."));
		assertThat(aggregated.getToolCalls()).hasSize(1);
		assertThat(aggregated.getToolCalls().get(0).arguments()).isEqualTo("{\"city\":\"Seoul\"}");
		assertThat(aggregated.getText()).isEqualTo("Let me check.");
	}

	@Test
	void streamEndingInsideAToolCallStillEmitsTheMergedCall() {
		// finish_reason "length" mid-arguments (or a server that never sends
		// "tool_calls"): the buffered chunks must be flushed on completion
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
					.toolCalls(List.of(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
						.index(0L)
						.id("call_1")
						.function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
							.name("getWeather")
							.arguments("{\"city\":")
							.build())
						.build())), null),
				streamingChunk(delta -> delta.toolCalls(List.of(ChatCompletionChunk.Choice.Delta.ToolCall.builder()
					.index(0L)
					.function(ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder().arguments("\"Seo").build())
					.build())), ChatCompletionChunk.Choice.FinishReason.LENGTH));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set)
			.collectList()
			.block();

		// two argument deltas, then the merged chunk flushed on completion
		assertThat(responses).hasSize(3);
		assertThat(responses.get(2).hasToolCalls()).isTrue();
		assertThat(responses.get(2).getResult().getOutput().getToolCalls().get(0).arguments())
			.isEqualTo("{\"city\":\"Seo");
		assertThat(responses.get(2).getResult().getMetadata().getFinishReason()).isEqualTo("LENGTH");
		assertThat(aggregatedRef.get().getResult().getOutput().getToolCalls()).hasSize(1);
	}

	@Test
	void streamingWithoutToolCallsAggregatesReasoningAndText() {
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
					.putAdditionalProperty("reasoning", JsonValue.from("Quick thought.")), null),
				streamingChunk(delta -> delta.content("Hello"), null),
				streamingChunk(delta -> delta.content("!"), ChatCompletionChunk.Choice.FinishReason.STOP));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set).blockLast();

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(ReasoningPart.of("Quick thought."), TextPart.of("Hello!"));
		assertThat(aggregated.getMetadata()).containsEntry("reasoningContent", "Quick thought.");
	}

	@Test
	void streamingKeepsWhitespaceOnlyReasoningDeltas() {
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
					.putAdditionalProperty("reasoning_content", JsonValue.from("Step one.")), null),
				streamingChunk(delta -> delta.putAdditionalProperty("reasoning_content", JsonValue.from("\n\n")), null),
				streamingChunk(delta -> delta.putAdditionalProperty("reasoning_content", JsonValue.from("Step two.")),
						null),
				streamingChunk(delta -> delta.content("Done"), ChatCompletionChunk.Choice.FinishReason.STOP));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(responses.get(1).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("\n\n"), 0));
		assertThat(aggregatedRef.get().getResult().getOutput().getParts())
			.containsExactly(ReasoningPart.of("Step one.\n\nStep two."), TextPart.of("Done"));
	}

	@Test
	void streamingEmptyContentNextToReasoningDoesNotSplitTheReasoning() {
		// OpenRouter-style deltas: "content": "" on every reasoning chunk
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT)
					.content("")
					.putAdditionalProperty("reasoning", JsonValue.from("Think ")), null),
				streamingChunk(delta -> delta.content("").putAdditionalProperty("reasoning", JsonValue.from("first.")),
						null),
				streamingChunk(delta -> delta.content("Hello"), null),
				streamingChunk(delta -> delta.content("!"), ChatCompletionChunk.Choice.FinishReason.STOP));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(responses.get(0).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("Think "), 0));
		assertThat(responses.get(0).getResult().getOutput().getText()).isEmpty();
		assertThat(responses.get(2).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(TextPart.of("Hello"), 1));
		assertThat(aggregatedRef.get().getResult().getOutput().getParts())
			.containsExactly(ReasoningPart.of("Think first."), TextPart.of("Hello!"));
	}

	@Test
	void streamingRoleOnlyChunkHasOnlyAnEmptyText() {
		List<ChatCompletionChunk> chunks = List.of(
				streamingChunk(delta -> delta.role(ChatCompletionChunk.Choice.Delta.Role.ASSISTANT).content(""), null),
				streamingChunk(delta -> delta.content("Hello"), null), streamingChunk(delta -> {
				}, ChatCompletionChunk.Choice.FinishReason.STOP));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator().aggregate(streamResponses(chunks), aggregatedRef::set)
			.collectList()
			.block();

		AssistantMessage first = responses.get(0).getResult().getOutput();
		// No indexed part, only the legacy empty text so the chunk text is not null
		assertThat(first.getParts()).containsExactly(TextPart.of(""));
		assertThat(first.getText()).isEmpty();
		assertThat(first.getMetadata()).containsEntry("role", "assistant");
		assertThat(aggregatedRef.get().getResult().getOutput().getParts()).containsExactly(TextPart.of("Hello"));
	}

	@Test
	void audioOutputBecomesMediaPartAfterText() {
		givenCompletion(ChatCompletionMessage.builder()
			.content(Optional.empty())
			.refusal(Optional.empty())
			.role(JsonValue.from("assistant"))
			.annotations(List.of())
			.toolCalls(List.of())
			.audio(ChatCompletionAudio.builder()
				.id("audio_1")
				.data(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }))
				.expiresAt(1777799999L)
				.transcript("Hello there")
				.build())
			.build());
		OpenAiChatModel chatModel = chatModel("gpt-4o-audio-preview");
		Prompt prompt = new Prompt("Say hi",
				OpenAiChatOptions.builder()
					.model("gpt-4o-audio-preview")
					.outputModalities(List.of("text", "audio"))
					.outputAudio(new OpenAiChatOptions.AudioParameters(OpenAiChatOptions.AudioParameters.Voice.ALLOY,
							OpenAiChatOptions.AudioParameters.AudioResponseFormat.WAV))
					.build());

		AssistantMessage message = chatModel.call(prompt).getResult().getOutput();

		assertThat(message.getParts()).hasSize(2);
		assertThat(message.getParts().get(0)).isEqualTo(TextPart.of("Hello there"));
		assertThat(message.getParts().get(1)).isInstanceOf(MediaPart.class);
		assertThat(message.getMedia()).hasSize(1);
		assertThat(message.getMedia().get(0).getId()).isEqualTo("audio_1");
		assertThat(message.getMedia().get(0).getDataAsByteArray()).containsExactly(1, 2, 3);
		assertThat(message.getMedia().get(0).getMimeType().toString()).isEqualTo("audio/wav");
	}

	// -- helpers --

	private OpenAiChatModel chatModel(String model) {
		return OpenAiChatModel.builder()
			.openAiClient(this.openAiClient)
			.openAiClientAsync(this.openAiClientAsync)
			.options(OpenAiChatOptions.builder().model(model).build())
			.build();
	}

	private void givenCompletion(ChatCompletionMessage message) {
		ChatService chatService = mock(ChatService.class);
		ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
		when(this.openAiClient.chat()).thenReturn(chatService);
		when(chatService.completions()).thenReturn(chatCompletionService);
		when(chatCompletionService.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
			.thenReturn(ChatCompletion.builder()
				.id("chatcmpl-123")
				.created(1777799928)
				.model("test-model")
				.usage(CompletionUsage.builder().promptTokens(1).completionTokens(1).totalTokens(2).build())
				.addChoice(ChatCompletion.Choice.builder()
					.finishReason(ChatCompletion.Choice.FinishReason.STOP)
					.index(0)
					.logprobs(Optional.empty())
					.message(message)
					.build())
				.build());
	}

	private static ChatCompletionAssistantMessageParam assistantParam(OpenAiChatModel chatModel,
			AssistantMessage assistant) {
		Prompt prompt = new Prompt(List.<Message>of(new UserMessage("Weather?"), assistant,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "getWeather", "{\"temp\":20}")))
					.build()),
				chatModel.getOptions());
		return chatModel.createRequest(prompt, false)
			.messages()
			.stream()
			.filter(ChatCompletionMessageParam::isAssistant)
			.map(ChatCompletionMessageParam::asAssistant)
			.findFirst()
			.orElseThrow();
	}

	private Flux<ChatResponse> streamResponses(List<ChatCompletionChunk> chunks) {
		ChatServiceAsync chatServiceAsync = mock(ChatServiceAsync.class);
		ChatCompletionServiceAsync chatCompletionServiceAsync = mock(ChatCompletionServiceAsync.class);
		when(this.openAiClientAsync.chat()).thenReturn(chatServiceAsync);
		when(chatServiceAsync.completions()).thenReturn(chatCompletionServiceAsync);
		when(chatCompletionServiceAsync.createStreaming(any(ChatCompletionCreateParams.class),
				any(RequestOptions.class)))
			.thenReturn(asyncStreamResponseOf(chunks));
		OpenAiChatModel chatModel = chatModel("deepseek-reasoner");
		return chatModel.stream(new Prompt("Stream parts", chatModel.getOptions()));
	}

	private static ChatCompletionChunk streamingChunk(
			Consumer<ChatCompletionChunk.Choice.Delta.Builder> deltaCustomizer,
			ChatCompletionChunk.Choice.FinishReason finishReason) {
		ChatCompletionChunk.Choice.Delta.Builder deltaBuilder = ChatCompletionChunk.Choice.Delta.builder();
		deltaCustomizer.accept(deltaBuilder);
		return ChatCompletionChunk.builder()
			.id("chatcmpl-123")
			.created(1777799928L)
			.model("deepseek-reasoner")
			.addChoice(ChatCompletionChunk.Choice.builder()
				.index(0L)
				.delta(deltaBuilder.build())
				.finishReason(finishReason)
				.build())
			.build();
	}

	private static AsyncStreamResponse<ChatCompletionChunk> asyncStreamResponseOf(List<ChatCompletionChunk> chunks) {
		CompletableFuture<Void> onComplete = new CompletableFuture<>();
		return new AsyncStreamResponse<>() {

			@Override
			public AsyncStreamResponse<ChatCompletionChunk> subscribe(Handler<? super ChatCompletionChunk> handler) {
				chunks.forEach(handler::onNext);
				handler.onComplete(Optional.empty());
				onComplete.complete(null);
				return this;
			}

			@Override
			public AsyncStreamResponse<ChatCompletionChunk> subscribe(Handler<? super ChatCompletionChunk> handler,
					Executor executor) {
				return subscribe(handler);
			}

			@Override
			public CompletableFuture<Void> onCompleteFuture() {
				return onComplete;
			}

			@Override
			public void close() {
			}

		};
	}

}
