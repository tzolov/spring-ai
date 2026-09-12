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

import java.time.Instant;
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
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaApi.Message.Role;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link MessagePart} mapping of {@link OllamaChatModel}: thinking as
 * {@link ReasoningPart}, tool calls as {@link ToolCallPart}s, the streaming contract and
 * the replay of reasoning as the {@code thinking} field.
 *
 * @author Christian Tzolov
 */
@ExtendWith(MockitoExtension.class)
class OllamaChatModelMessagePartsTests {

	@Mock
	OllamaApi ollamaApi;

	// -- call --

	@Test
	void thinkingAndTextBecomeReasoningThenTextParts() {
		when(this.ollamaApi.chat(any())).thenReturn(response(OllamaApi.Message.builder(Role.ASSISTANT)
			.content("The answer is 42.")
			.thinking("Let me think.")
			.build(), true));

		ChatResponse response = chatModel().call(new Prompt("What is the answer?"));
		AssistantMessage message = response.getResult().getOutput();

		assertThat(message.getParts()).containsExactly(ReasoningPart.of("Let me think."),
				TextPart.of("The answer is 42."));
		assertThat(message.getReasoning()).containsExactly(ReasoningPart.of("Let me think."));
		assertThat(message.getText()).isEqualTo("The answer is 42.");
		// Legacy key kept for one release on both message and generation metadata
		assertThat(message.getMetadata()).containsEntry("thinking", "Let me think.");
		assertThat(response.getResult().getMetadata().<String>get("thinking")).isEqualTo("Let me think.");
	}

	@Test
	void answerWithoutThinkingIsASingleTextPart() {
		when(this.ollamaApi.chat(any()))
			.thenReturn(response(OllamaApi.Message.builder(Role.ASSISTANT).content("hello").build(), true));

		AssistantMessage message = chatModel().call(new Prompt("hi")).getResult().getOutput();

		assertThat(message.getParts()).containsExactly(TextPart.of("hello"));
		assertThat(message.getReasoning()).isEmpty();
		assertThat(message.getMetadata()).doesNotContainKey("thinking");
	}

	@Test
	void toolCallResponseKeepsEmptyTextBeforeToolCallParts() {
		OllamaApi.Message.ToolCall toolCall = new OllamaApi.Message.ToolCall("call_1",
				new OllamaApi.Message.ToolCallFunction("getWeather", Map.of("city", "Seoul")));
		when(this.ollamaApi.chat(any())).thenReturn(response(
				OllamaApi.Message.builder(Role.ASSISTANT).content("").toolCalls(List.of(toolCall)).build(), true));

		AssistantMessage message = chatModel().call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getText()).isEmpty();
		assertThat(message.getParts()).hasSize(2);
		assertThat(message.getParts().get(0)).isEqualTo(TextPart.of(""));
		assertThat(message.getParts().get(1)).isInstanceOf(ToolCallPart.class);
		assertThat(message.getToolCalls()).hasSize(1);
		assertThat(message.getToolCalls().get(0).id()).isEqualTo("call_1");
		assertThat(message.getToolCalls().get(0).arguments()).isEqualTo("{\"city\":\"Seoul\"}");
	}

	// -- stream --

	@Test
	void streamingThinkingAndTextProduceIndexedParts() {
		when(this.ollamaApi.streamingChat(any())).thenReturn(Flux.just(
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("Think ").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("first.").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("Hello").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("!").build(), true)));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> chunks = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Say hi")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(chunks).hasSize(4);
		AssistantMessage first = chunks.get(0).getResult().getOutput();
		assertThat(first.getParts()).containsExactly(StreamingParts.partial(ReasoningPart.of("Think "), 0));
		assertThat(first.getText()).isNull();
		assertThat(first.getMetadata()).containsEntry("thinking", "Think ");
		assertThat(chunks.get(0).getResult().getMetadata().<String>get("thinking")).isEqualTo("Think ");
		assertThat(chunks.get(1).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("first."), 0));
		AssistantMessage third = chunks.get(2).getResult().getOutput();
		assertThat(third.getParts()).containsExactly(StreamingParts.partial(TextPart.of("Hello"), 1));
		assertThat(third.getText()).isEqualTo("Hello");
		assertThat(third.getMetadata()).doesNotContainKey("thinking");

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(ReasoningPart.of("Think first."), TextPart.of("Hello!"));
		assertThat(aggregated.getText()).isEqualTo("Hello!");
		assertThat(aggregated.getReasoning()).containsExactly(ReasoningPart.of("Think first."));
	}

	@Test
	void streamingKeepsWhitespaceOnlyThinkingDeltas() {
		when(this.ollamaApi.streamingChat(any())).thenReturn(Flux.just(
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("Step one.").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("\n\n").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("Step two.").build(), false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("Done").build(), true)));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> chunks = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Go")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(chunks.get(1).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("\n\n"), 0));
		assertThat(aggregatedRef.get().getResult().getOutput().getParts())
			.containsExactly(ReasoningPart.of("Step one.\n\nStep two."), TextPart.of("Done"));
	}

	@Test
	void streamingToolCallChunkProducesCompleteToolCallPart() {
		OllamaApi.Message.ToolCall toolCall = new OllamaApi.Message.ToolCall("call_1",
				new OllamaApi.Message.ToolCallFunction("getWeather", Map.of("city", "Seoul")));
		when(this.ollamaApi.streamingChat(any())).thenReturn(Flux.just(
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").thinking("Need the tool.").build(),
						false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").toolCalls(List.of(toolCall)).build(),
						false),
				response(OllamaApi.Message.builder(Role.ASSISTANT).content("").build(), true)));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> chunks = new MessageAggregator()
			.aggregate(chatModel().stream(new Prompt("Weather?")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(chunks.get(0).hasToolCalls()).isFalse();
		AssistantMessage second = chunks.get(1).getResult().getOutput();
		assertThat(second.getParts()).hasSize(1);
		MessagePart part = second.getParts().get(0);
		assertThat(part).isInstanceOf(ToolCallPart.class);
		assertThat(StreamingParts.isPartial(part)).isFalse();
		assertThat(StreamingParts.partIndex(part)).isEqualTo(1);
		assertThat(chunks.get(1).hasToolCalls()).isTrue();
		assertThat(second.getText()).isNull();

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).hasSize(2);
		assertThat(aggregated.getParts().get(0)).isEqualTo(ReasoningPart.of("Need the tool."));
		assertThat(aggregated.getToolCalls()).hasSize(1);
		assertThat(aggregated.getToolCalls().get(0).arguments()).isEqualTo("{\"city\":\"Seoul\"}");
	}

	// -- replay --

	@Test
	void reasoningPartIsReplayedAsThinking() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(ReasoningPart.of("17 x 23 = 391."))
			.part(TextPart.of("391"))
			.build();

		OllamaApi.Message replayed = assistantRequestMessage(assistant);

		assertThat(replayed.thinking()).isEqualTo("17 x 23 = 391.");
		assertThat(replayed.content()).isEqualTo("391");
	}

	@Test
	void legacyThinkingMetadataIsReplayedWhenThereIsNoReasoningPart() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("391")
			.properties(Map.of("thinking", "17 x 23 = 391."))
			.build();

		assertThat(assistantRequestMessage(assistant).thinking()).isEqualTo("17 x 23 = 391.");
	}

	@Test
	void foreignSignedReasoningIsNotReplayedAsThinking() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("anthropic thoughts", null, false,
					new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.part(TextPart.of("391"))
			.build();

		assertThat(assistantRequestMessage(assistant).thinking()).isNull();
	}

	@Test
	void plainAssistantMessageHasNoThinkingOnReplay() {
		assertThat(assistantRequestMessage(new AssistantMessage("391")).thinking()).isNull();
	}

	// -- helpers --

	private OllamaChatModel chatModel() {
		return OllamaChatModel.builder()
			.ollamaApi(this.ollamaApi)
			.options(OllamaChatOptions.builder().model("qwen3").build())
			.build();
	}

	private OllamaApi.Message assistantRequestMessage(AssistantMessage assistant) {
		Prompt prompt = new Prompt(List.<Message>of(new UserMessage("Calculate 17 x 23"), assistant),
				OllamaChatOptions.builder().model("qwen3").build());
		return chatModel().ollamaChatRequest(prompt, false)
			.messages()
			.stream()
			.filter(m -> m.role() == Role.ASSISTANT)
			.findFirst()
			.orElseThrow();
	}

	private static OllamaApi.ChatResponse response(OllamaApi.Message message, boolean done) {
		return new OllamaApi.ChatResponse("qwen3", Instant.now(), message, done ? "stop" : null, done, null, null,
				done ? 10 : null, null, done ? 20 : null, null);
	}

}
