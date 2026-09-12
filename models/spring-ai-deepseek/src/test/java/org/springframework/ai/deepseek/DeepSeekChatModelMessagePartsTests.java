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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletion.Choice;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionChunk;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionChunk.ChunkChoice;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionFinishReason;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.Role;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.deepseek.api.DeepSeekApi.ChatCompletionRequest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Tests for the {@link MessagePart} mapping of {@link DeepSeekChatModel}: reasoning
 * content as {@link ReasoningPart}, the streaming contract, and replay from plain
 * {@link AssistantMessage}s as well as from the deprecated
 * {@link DeepSeekAssistantMessage}.
 *
 * @author Christian Tzolov
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("removal")
class DeepSeekChatModelMessagePartsTests {

	private static final AssistantMessage.ToolCall WEATHER_CALL = new AssistantMessage.ToolCall("call_1", "function",
			"getWeather", "{\"city\":\"Seoul\"}");

	@Mock
	private DeepSeekApi deepSeekApi;

	// -- inbound, non-streaming --

	@Test
	void reasoningTextAndToolCallBecomeOrderedParts() {
		givenCompletion(
				new ChatCompletionMessage("Let me check.", Role.ASSISTANT, null, null,
						List.of(new ToolCall("call_1", "function",
								new ChatCompletionFunction("getWeather", "{\"city\":\"Seoul\"}"))),
						null, "Think first."));

		AssistantMessage message = chatModel().call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getParts()).containsExactly(ReasoningPart.of("Think first."), TextPart.of("Let me check."),
				ToolCallPart.of(WEATHER_CALL));
		assertThat(message.getText()).isEqualTo("Let me check.");
		assertThat(message.getReasoning()).containsExactly(ReasoningPart.of("Think first."));
		assertThat(message.getToolCalls()).containsExactly(WEATHER_CALL);
		// Deprecated access paths kept for one release
		assertThat(message).isInstanceOf(DeepSeekAssistantMessage.class);
		assertThat(((DeepSeekAssistantMessage) message).getReasoningContent()).isEqualTo("Think first.");
		assertThat(message.getMetadata()).containsEntry("reasoningContent", "Think first.");
	}

	@Test
	void plainAnswerIsASingleTextPart() {
		givenCompletion(new ChatCompletionMessage("hello", Role.ASSISTANT));

		AssistantMessage message = chatModel().call(new Prompt("hi")).getResult().getOutput();

		assertThat(message.getParts()).containsExactly(TextPart.of("hello"));
		assertThat(message.getReasoning()).isEmpty();
		assertThat(((DeepSeekAssistantMessage) message).getReasoningContent()).isNull();
	}

	@Test
	void toolCallResponseKeepsTheTextTheServerSent() {
		givenCompletion(new ChatCompletionMessage("", Role.ASSISTANT, null, null,
				List.of(new ToolCall("call_1", "function", new ChatCompletionFunction("getWeather", "{}"))), null,
				null));

		AssistantMessage message = chatModel().call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getText()).isEmpty();
		assertThat(message.getParts()).containsExactly(TextPart.of(""),
				ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}")));
	}

	@Test
	void toolCallResponseWithoutContentHasNoTextPart() {
		givenCompletion(new ChatCompletionMessage(null, Role.ASSISTANT, null, null,
				List.of(new ToolCall("call_1", "function", new ChatCompletionFunction("getWeather", "{}"))), null,
				null));

		AssistantMessage message = chatModel().call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getText()).isNull();
		assertThat(message.getParts())
			.containsExactly(ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}")));
	}

	// -- outbound --

	@Test
	void reasoningPartIsReplayedAsReasoningContent() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(ReasoningPart.of("25 * 4 = 100."))
			.part(TextPart.of("100"))
			.build();

		ChatCompletionMessage param = assistantParam(assistant);

		assertThat(param.content()).isEqualTo("100");
		assertThat(param.reasoningContent()).isEqualTo("25 * 4 = 100.");
		assertThat(param.prefix()).isNull();
	}

	@Test
	void plainAssistantMessageWithMetadataKeyIsReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("100")
			.properties(Map.of("reasoningContent", "25 * 4 = 100."))
			.build();

		assertThat(assistantParam(assistant).reasoningContent()).isEqualTo("25 * 4 = 100.");
	}

	@Test
	void foreignSignedReasoningIsNotReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("anthropic thoughts", null, false,
					new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.part(TextPart.of("100"))
			.build();

		assertThat(assistantParam(assistant).reasoningContent()).isNull();
	}

	@Test
	void emptyReasoningIsNotReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("100")
			.properties(Map.of("reasoningContent", ""))
			.build();

		assertThat(assistantParam(assistant).reasoningContent()).isNull();
	}

	@Test
	void toolCallPartsAreReplayed() {
		AssistantMessage assistant = AssistantMessage.builder().part(ToolCallPart.of(WEATHER_CALL)).build();

		ChatCompletionMessage param = assistantParam(assistant);

		assertThat(param.content()).isNull();
		assertThat(param.toolCalls()).hasSize(1);
		assertThat(param.toolCalls().get(0).id()).isEqualTo("call_1");
		assertThat(param.toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"Seoul\"}");
	}

	@Test
	void prefixIsReplayedFromTheMetadataKey() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("```python\n")
			.properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, true))
			.build();

		ChatCompletionMessage param = assistantParam(assistant);

		assertThat(param.prefix()).isTrue();
		assertThat(param.content()).isEqualTo("```python\n");
	}

	@Test
	void prefixIsReplayedFromTheDeprecatedBuilder() {
		DeepSeekAssistantMessage assistant = DeepSeekAssistantMessage.builder()
			.content("```python\n")
			.reasoningContent("Write code.")
			.prefix(true)
			.build();

		assertThat(assistant.getMetadata()).containsEntry(DeepSeekChatModel.PREFIX_METADATA_KEY, true);
		assertThat(assistant.getParts()).containsExactly(ReasoningPart.of("Write code."), TextPart.of("```python\n"));

		ChatCompletionMessage param = assistantParam(assistant);

		assertThat(param.prefix()).isTrue();
		assertThat(param.reasoningContent()).isEqualTo("Write code.");
	}

	@Test
	void prefixFalseIsNotSent() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("hi")
			.properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, false))
			.build();

		assertThat(assistantParam(assistant).prefix()).isNull();
	}

	// -- streaming --

	@Test
	void streamingReasoningTextAndToolCallProduceIndexedParts() {
		List<ChatCompletionChunk> chunks = List.of(chunk(delta(null, "Think "), null),
				chunk(delta(null, "first."), null), chunk(delta("Let me ", null), null),
				chunk(delta("check.", null), null), chunk(
						new ChatCompletionMessage(null, null, null, null,
								List.of(new ToolCall("call_1", "function",
										new ChatCompletionFunction("getWeather", "{\"city\":\"Seoul\"}"))),
								null, null),
						ChatCompletionFinishReason.TOOL_CALLS));
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.fromIterable(chunks));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Weather?")), aggregatedRef::set)
			.collectList()
			.block();

		// reasoning deltas, text deltas, the argument delta, then the merged call
		assertThat(responses).hasSize(6);
		assertThat(responses).allSatisfy(r -> assertThat(r.getMetadata().getId()).isEqualTo("chatcmpl-123"));
		assertThat(responses.get(0).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("Think "), 0));
		assertThat(responses.get(0).getResult().getOutput().getText()).isNull();
		assertThat(responses.get(1).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("first."), 0));
		assertThat(responses.get(2).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(TextPart.of("Let me "), 1));
		assertThat(responses.get(2).getResult().getOutput().getText()).isEqualTo("Let me ");
		assertThat(responses.get(4).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ToolCallPart.of(WEATHER_CALL), 2));
		assertThat(responses.get(4).hasToolCalls()).isFalse();
		MessagePart call = responses.get(5).getResult().getOutput().getParts().get(0);
		assertThat(call).isInstanceOf(ToolCallPart.class);
		assertThat(StreamingParts.partIndex(call)).isEqualTo(2);
		assertThat(StreamingParts.isPartial(call)).isFalse();
		assertThat(responses.get(5).hasToolCalls()).isTrue();
		// Deprecated per-chunk cumulative metadata
		assertThat(responses.get(1).getResult().getOutput().getMetadata()).containsEntry("reasoningContent",
				"Think first.");

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(ReasoningPart.of("Think first."),
				TextPart.of("Let me check."), ToolCallPart.of(WEATHER_CALL));
		assertThat(aggregated.getText()).isEqualTo("Let me check.");
		assertThat(aggregated.getToolCalls()).containsExactly(WEATHER_CALL);
		assertThat(aggregated.getMetadata()).containsEntry("reasoningContent", "Think first.");
	}

	@Test
	void streamingKeepsWhitespaceOnlyReasoningDeltasAndIgnoresEmptyContent() {
		// "content": "" next to every reasoning delta must not split the reasoning
		List<ChatCompletionChunk> chunks = List.of(chunk(delta("", "Step one."), null), chunk(delta("", "\n\n"), null),
				chunk(delta("", "Step two."), null), chunk(delta("Done", null), ChatCompletionFinishReason.STOP));
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.fromIterable(chunks));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Think")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(responses.get(1).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("\n\n"), 0));
		assertThat(responses.get(1).getResult().getOutput().getText()).isNull();
		assertThat(aggregatedRef.get().getResult().getOutput().getParts())
			.containsExactly(ReasoningPart.of("Step one.\n\nStep two."), TextPart.of("Done"));
	}

	@Test
	void streamEndingInsideAToolCallStillEmitsTheMergedCall() {
		// finish_reason "length" mid-arguments: the buffered chunks are flushed on
		// completion instead of being dropped
		ChatCompletionMessage firstDelta = new ChatCompletionMessage(null, Role.ASSISTANT, null, null,
				List.of(new ToolCall(0, "call_1", "function", new ChatCompletionFunction("getWeather", "{\"city\":"))),
				null, null);
		ChatCompletionMessage secondDelta = new ChatCompletionMessage(null, null, null, null,
				List.of(new ToolCall(0, "", null, new ChatCompletionFunction(null, "\"Seo"))), null, null);
		List<ChatCompletionChunk> chunks = List.of(chunk(firstDelta, null),
				chunk(secondDelta, ChatCompletionFinishReason.LENGTH));
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.fromIterable(chunks));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Weather?")), aggregatedRef::set)
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
	void streamingToolCallArgumentDeltasAreEmittedAsPartialPartsBeforeTheMergedCall() {
		// DeepSeek sends the id and name on the first delta and an empty id afterwards
		ChatCompletionMessage firstDelta = new ChatCompletionMessage(null, Role.ASSISTANT, null, null,
				List.of(new ToolCall(0, "call_1", "function", new ChatCompletionFunction("getWeather", "{\"city\":"))),
				null, null);
		ChatCompletionMessage secondDelta = new ChatCompletionMessage(null, null, null, null,
				List.of(new ToolCall(0, "", null, new ChatCompletionFunction(null, "\"Seoul\"}"))), null, null);
		List<ChatCompletionChunk> chunks = List.of(chunk(delta(null, "Think "), null),
				chunk(delta(null, "first."), null), chunk(delta("Checking.", null), null), chunk(firstDelta, null),
				chunk(secondDelta, null), chunk(new ChatCompletionMessage(null, null, null, null, null, null, null),
						ChatCompletionFinishReason.TOOL_CALLS));
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.fromIterable(chunks));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Weather?")), aggregatedRef::set)
			.collectList()
			.block();

		// two reasoning deltas, one text delta, two argument deltas, the merged call
		assertThat(responses).hasSize(6);
		assertThat(responses).allSatisfy(r -> assertThat(r.getMetadata().getId()).isEqualTo("chatcmpl-123"));
		assertThat(responses.get(3).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{\"city\":")), 2));
		assertThat(responses.get(4).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "\"Seoul\"}")), 2));
		// Argument deltas are not tool calls yet
		assertThat(responses.get(3).hasToolCalls()).isFalse();
		assertThat(responses.get(4).hasToolCalls()).isFalse();
		assertThat(responses.get(3).getResult().getOutput().getText()).isNull();

		// The merged chunk is what it was before: complete call, cumulative reasoning
		ChatResponse merged = responses.get(5);
		assertThat(merged.hasToolCalls()).isTrue();
		assertThat(merged.getResult().getOutput().getToolCalls()).containsExactly(WEATHER_CALL);
		assertThat(merged.getResult().getOutput().getParts())
			.containsExactly(StreamingParts.complete(ToolCallPart.of(WEATHER_CALL), 2));
		assertThat(merged.getResult().getOutput().getMetadata()).containsEntry("reasoningContent", "Think first.");

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(ReasoningPart.of("Think first."), TextPart.of("Checking."),
				ToolCallPart.of(WEATHER_CALL));
		assertThat(aggregated.getToolCalls()).containsExactly(WEATHER_CALL);
	}

	@Test
	void streamingReasoningInsideTheToolCallWindowIsKept() {
		ChatCompletionMessage callDelta = new ChatCompletionMessage(null, Role.ASSISTANT, null, null,
				List.of(new ToolCall(0, "call_1", "function", new ChatCompletionFunction("getWeather", "{}"))), null,
				null);
		List<ChatCompletionChunk> chunks = List.of(chunk(callDelta, null), chunk(delta(null, "Think "), null),
				chunk(delta(null, "more."), ChatCompletionFinishReason.TOOL_CALLS));
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.fromIterable(chunks));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		new MessageAggregator().aggregate(chatModel().stream(new Prompt("Weather?")), aggregatedRef::set).blockLast();

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getReasoning()).containsExactly(ReasoningPart.of("Think more."));
		assertThat(aggregated.getToolCalls())
			.containsExactly(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}"));
	}

	@Test
	void streamedChunksAreStillDeepSeekAssistantMessages() {
		given(this.deepSeekApi.chatCompletionStreamRaw(any(ChatCompletionRequest.class)))
			.willReturn(Flux.just(chunk(delta("Hello", "Quick"), ChatCompletionFinishReason.STOP)));

		ChatResponse response = chatModel().stream(new Prompt("hi")).blockLast();

		AssistantMessage output = response.getResult().getOutput();
		assertThat(output).isInstanceOf(DeepSeekAssistantMessage.class);
		assertThat(((DeepSeekAssistantMessage) output).getReasoningContent()).isEqualTo("Quick");
		assertThat(output.getText()).isEqualTo("Hello");
	}

	// -- helpers --

	private DeepSeekChatModel chatModel() {
		return DeepSeekChatModel.builder()
			.deepSeekApi(this.deepSeekApi)
			.options(DeepSeekChatOptions.builder().model("deepseek-reasoner").build())
			.build();
	}

	private void givenCompletion(ChatCompletionMessage message) {
		ChatCompletion completion = new ChatCompletion("chatcmpl-123",
				List.of(new Choice(ChatCompletionFinishReason.STOP, 0, message, null)), 1777799928L,
				"deepseek-reasoner", "fp", "chat.completion", new DeepSeekApi.Usage(1, 1, 2));
		given(this.deepSeekApi.chatCompletionEntity(any(ChatCompletionRequest.class)))
			.willReturn(ResponseEntity.ok(completion));
	}

	private ChatCompletionMessage assistantParam(AssistantMessage assistant) {
		Prompt prompt = new Prompt(List.<Message>of(new UserMessage("Weather?"), assistant,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "getWeather", "{\"temp\":20}")))
					.build()),
				DeepSeekChatOptions.builder().model("deepseek-reasoner").build());
		return chatModel().createRequest(prompt, false)
			.messages()
			.stream()
			.filter(m -> m.role() == Role.ASSISTANT)
			.findFirst()
			.orElseThrow();
	}

	private static ChatCompletionMessage delta(String content, String reasoning) {
		return new ChatCompletionMessage(content, Role.ASSISTANT, null, null, null, null, reasoning);
	}

	private static ChatCompletionChunk chunk(ChatCompletionMessage delta, ChatCompletionFinishReason finishReason) {
		return new ChatCompletionChunk("chatcmpl-123", List.of(new ChunkChoice(finishReason, 0, delta, null)),
				1777799928L, "deepseek-reasoner", null, "fp", "chat.completion.chunk", null);
	}

}
