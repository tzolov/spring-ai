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

package org.springframework.ai.google.genai;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Tests for the {@link MessagePart} mapping of {@link GoogleGenAiChatModel}: Gemini parts
 * to ordered message parts in both directions, thought signatures as part payloads, and
 * the streaming contract with synthesized part indices.
 *
 * @author Christian Tzolov
 */
class GoogleGenAiChatModelMessagePartsTests {

	private static final byte[] SIGNATURE = "sig-bytes".getBytes(StandardCharsets.UTF_8);

	private static final String SIGNATURE_BASE64 = Base64.getEncoder().encodeToString(SIGNATURE);

	private static final OpaquePayload SIGNATURE_PAYLOAD = new OpaquePayload("google", "thought_signature",
			SIGNATURE_BASE64);

	@Mock
	private Client client;

	private TestGoogleGenAiGeminiChatModel chatModel;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
			.model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
			.includeThoughts(true)
			.build();
		this.chatModel = new TestGoogleGenAiGeminiChatModel(this.client, options, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	// -- inbound, non-streaming --

	@Test
	void thoughtAndTextCandidateIsOneGenerationWithOrderedParts() {
		this.chatModel
			.setMockGenerateContentResponse(response(thoughtPart("thinking", null), textPart("answer", null)));

		ChatResponse response = this.chatModel.call(new Prompt("Explain"));

		assertThat(response.getResults()).hasSize(1);
		AssistantMessage message = response.getResult().getOutput();
		assertThat(message.getParts()).containsExactly(ReasoningPart.of("thinking"), TextPart.of("answer"));
		assertThat(message.getText()).isEqualTo("answer");
		assertThat(message.getMetadata()).doesNotContainKey("isThought");
	}

	@Test
	void functionCallCandidateKeepsTextAndAttachesSignatureToTheCall() {
		this.chatModel.setMockGenerateContentResponse(response(thoughtPart("thinking", null),
				functionCallPart("getWeather", Map.of("city", "Paris"), SIGNATURE)));

		ChatResponse response = this.chatModel.call(new Prompt("Weather?"));

		AssistantMessage message = response.getResult().getOutput();
		assertThat(message.getParts()).hasSize(2);
		assertThat(message.getParts().get(0)).isEqualTo(ReasoningPart.of("thinking"));
		assertThat(message.getParts().get(1)).isInstanceOf(ToolCallPart.class);
		ToolCallPart toolCallPart = (ToolCallPart) message.getParts().get(1);
		assertThat(toolCallPart.toolCall().name()).isEqualTo("getWeather");
		assertThat(toolCallPart.toolCall().arguments()).contains("Paris");
		assertThat(toolCallPart.payload()).isEqualTo(SIGNATURE_PAYLOAD);
		assertThat(message.hasToolCalls()).isTrue();
		// Deprecated metadata list kept for one release
		assertThat(message.getMetadata().get("thoughtSignatures")).isInstanceOf(List.class);
	}

	@Test
	void textInFunctionCallCandidateIsNoLongerDropped() {
		this.chatModel.setMockGenerateContentResponse(response(textPart("Let me check.", null),
				functionCallPart("getWeather", Map.of("city", "Paris"), null)));

		AssistantMessage message = this.chatModel.call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(message.getText()).isEqualTo("Let me check.");
		assertThat(message.getParts().get(0)).isEqualTo(TextPart.of("Let me check."));
		assertThat(message.getToolCalls()).hasSize(1);
	}

	@Test
	void signatureOnTextPartIsKeptAsPayload() {
		this.chatModel.setMockGenerateContentResponse(response(textPart("answer", SIGNATURE)));

		AssistantMessage message = this.chatModel.call(new Prompt("Explain")).getResult().getOutput();

		assertThat(message.getParts()).containsExactly(new TextPart("answer", SIGNATURE_PAYLOAD, Map.of()));
	}

	@Test
	void serverSideToolPartsBecomeUnknownParts() {
		Part serverToolCall = Part.builder()
			.toolCall(ToolCall.builder().id("srv_1").args(Map.of("q", "x")).build())
			.build();
		this.chatModel.setMockGenerateContentResponse(response(serverToolCall, textPart("answer", null)));

		AssistantMessage message = this.chatModel.call(new Prompt("Search")).getResult().getOutput();

		assertThat(message.getParts()).hasSize(2);
		assertThat(message.getParts().get(0)).isInstanceOf(UnknownPart.class);
		UnknownPart unknown = (UnknownPart) message.getParts().get(0);
		assertThat(unknown.provider()).isEqualTo("google");
		assertThat(unknown.kind()).isEqualTo("toolCall");
		assertThat(unknown.rawJson()).contains("srv_1");
		assertThat(message.getText()).isEqualTo("answer");
		assertThat(message.getMetadata()).containsKey("serverSideToolInvocations");
	}

	// -- outbound --

	@Test
	void toolCallPartPayloadIsReplayedOnItsOwnFunctionCall() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(ReasoningPart.of("thinking"))
			.part(new ToolCallPart(new AssistantMessage.ToolCall("", "function", "getWeather", "{\"city\":\"Paris\"}"),
					SIGNATURE_PAYLOAD, Map.of()))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("", "function", "getTime", "{}")))
			.build();
		Prompt prompt = new Prompt(
				List.<Message>of(new UserMessage("Weather?"), assistant, toolResponse("getWeather", "{\"tempC\":15}")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(2);
		assertThat(parts.get(0).functionCall().get().name()).contains("getWeather");
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
		assertThat(parts.get(1).functionCall().get().name()).contains("getTime");
		assertThat(parts.get(1).thoughtSignature()).isEmpty();
	}

	@Test
	void legacyThoughtSignaturesMetadataIsStillReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("", "function", "getWeather", "{\"city\":\"Paris\"}")))
			.properties(Map.of("thoughtSignatures", List.of(SIGNATURE)))
			.build();
		Prompt prompt = new Prompt(
				List.<Message>of(new UserMessage("Weather?"), assistant, toolResponse("getWeather", "{\"tempC\":15}")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(1);
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
	}

	@Test
	void textPartPayloadIsReplayedAndForeignReasoningIsSkipped() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("openai thoughts", null, false,
					new OpaquePayload("openai", "encrypted_content", "enc"), Map.of()))
			.part(new TextPart("answer", SIGNATURE_PAYLOAD, Map.of()))
			.build();
		Prompt prompt = new Prompt(List.<Message>of(new UserMessage("Hi"), assistant, new UserMessage("Again")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(1);
		assertThat(parts.get(0).text()).contains("answer");
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
	}

	@Test
	void signatureOnThoughtPartIsCarriedToTheFirstFunctionCall() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("thinking", null, false, SIGNATURE_PAYLOAD, Map.of()))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("", "function", "getWeather", "{\"city\":\"Paris\"}")))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("", "function", "getTime", "{}")))
			.build();
		Prompt prompt = new Prompt(
				List.<Message>of(new UserMessage("Weather?"), assistant, toolResponse("getWeather", "{\"tempC\":15}")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(2);
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
		assertThat(parts.get(1).thoughtSignature()).isEmpty();
	}

	@Test
	void signatureOnEmptyTextPartIsCarriedToTheFunctionCall() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new TextPart("", SIGNATURE_PAYLOAD, Map.of()))
			.part(ToolCallPart.of(new AssistantMessage.ToolCall("", "function", "getWeather", "{\"city\":\"Paris\"}")))
			.build();
		Prompt prompt = new Prompt(
				List.<Message>of(new UserMessage("Weather?"), assistant, toolResponse("getWeather", "{\"tempC\":15}")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(1);
		assertThat(parts.get(0).functionCall()).isPresent();
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
	}

	@Test
	void signatureOnlyPartRoundTripsThroughTheModel() {
		Part signatureOnly = Part.builder().thoughtSignature(SIGNATURE).build();
		this.chatModel.setMockGenerateContentResponse(
				response(signatureOnly, functionCallPart("getWeather", Map.of("city", "Paris"), null)));
		AssistantMessage assistant = this.chatModel.call(new Prompt("Weather?")).getResult().getOutput();
		Prompt prompt = new Prompt(
				List.<Message>of(new UserMessage("Weather?"), assistant, toolResponse("getWeather", "{\"tempC\":15}")),
				this.chatModel.getOptions());

		List<Part> parts = assistantParts(this.chatModel.createGeminiRequest(prompt));

		assertThat(parts).hasSize(1);
		assertThat(parts.get(0).thoughtSignature()).contains(SIGNATURE);
	}

	// -- streaming --

	@Test
	void streamingThoughtTextAndFunctionCallProduceIndexedParts() {
		List<GenerateContentResponse> chunks = List.of(chunk("resp_1", thoughtPart("think ", null)),
				chunk("resp_1", thoughtPart("ing", SIGNATURE)), chunk("resp_1", textPart("Let me ", null)),
				chunk("resp_1", textPart("check.", null)),
				chunk("resp_1", functionCallPart("getWeather", Map.of("city", "Paris"), SIGNATURE)));
		givenStream(chunks);

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(this.chatModel.stream(new Prompt("Weather?")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(responses).hasSize(5);
		assertThat(responses).allSatisfy(r -> assertThat(r.getMetadata().getId()).isEqualTo("resp_1"));
		MessagePart first = responses.get(0).getResult().getOutput().getParts().get(0);
		assertThat(first).isEqualTo(StreamingParts.partial(ReasoningPart.of("think "), 0));
		assertThat(responses.get(0).getResult().getOutput().getText()).isNull();
		MessagePart signed = responses.get(1).getResult().getOutput().getParts().get(0);
		assertThat(signed.payload()).isEqualTo(SIGNATURE_PAYLOAD);
		assertThat(StreamingParts.partIndex(signed)).isZero();
		MessagePart text = responses.get(2).getResult().getOutput().getParts().get(0);
		assertThat(text).isEqualTo(StreamingParts.partial(TextPart.of("Let me "), 1));
		assertThat(responses.get(2).getResult().getOutput().getText()).isEqualTo("Let me ");
		MessagePart call = responses.get(4).getResult().getOutput().getParts().get(0);
		assertThat(call).isInstanceOf(ToolCallPart.class);
		assertThat(StreamingParts.partIndex(call)).isEqualTo(2);
		assertThat(StreamingParts.isPartial(call)).isFalse();

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getText()).isEqualTo("Let me check.");
		assertThat(aggregated.getParts()).hasSize(3);
		assertThat(aggregated.getParts().get(0))
			.isEqualTo(new ReasoningPart("think ing", null, false, SIGNATURE_PAYLOAD, Map.of()));
		assertThat(aggregated.getParts().get(1)).isEqualTo(TextPart.of("Let me check."));
		assertThat(((ToolCallPart) aggregated.getParts().get(2)).payload()).isEqualTo(SIGNATURE_PAYLOAD);
		assertThat(aggregated.getToolCalls()).hasSize(1);
	}

	@Test
	void streamingChunkWithSeveralPartsAssignsConsecutiveIndices() {
		givenStream(List.of(chunk("resp_2", thoughtPart("t", null), textPart("a", null)),
				chunk("resp_2", textPart("b", null))));

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> responses = new MessageAggregator()
			.aggregate(this.chatModel.stream(new Prompt("Explain")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(responses.get(0).getResult().getOutput().getParts()).containsExactly(
				StreamingParts.partial(ReasoningPart.of("t"), 0), StreamingParts.partial(TextPart.of("a"), 1));
		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(ReasoningPart.of("t"), TextPart.of("ab"));
	}

	// -- helpers --

	private static Part thoughtPart(String text, byte @Nullable [] signature) {
		Part.Builder builder = Part.builder().text(text).thought(true);
		if (signature != null) {
			builder.thoughtSignature(signature);
		}
		return builder.build();
	}

	private static Part textPart(String text, byte @Nullable [] signature) {
		Part.Builder builder = Part.builder().text(text);
		if (signature != null) {
			builder.thoughtSignature(signature);
		}
		return builder.build();
	}

	private static Part functionCallPart(String name, Map<String, Object> args, byte @Nullable [] signature) {
		Part.Builder builder = Part.builder().functionCall(FunctionCall.builder().name(name).args(args).build());
		if (signature != null) {
			builder.thoughtSignature(signature);
		}
		return builder.build();
	}

	private static GenerateContentResponse response(Part... parts) {
		return chunk("resp_call", parts);
	}

	private static GenerateContentResponse chunk(String responseId, Part... parts) {
		Content content = Content.builder().role("model").parts(List.of(parts)).build();
		Candidate candidate = Candidate.builder().content(content).build();
		return GenerateContentResponse.builder()
			.responseId(responseId)
			.candidates(List.of(candidate))
			.modelVersion("gemini-2.5-flash")
			.build();
	}

	private static ToolResponseMessage toolResponse(String name, String data) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("", name, data)))
			.build();
	}

	private static List<Part> assistantParts(GoogleGenAiChatModel.GeminiRequest request) {
		for (Content content : request.contents()) {
			if (content.role().filter("model"::equals).isPresent()) {
				return content.parts().orElse(List.of());
			}
		}
		return List.of();
	}

	@SuppressWarnings("unchecked")
	private void givenStream(List<GenerateContentResponse> chunks) {
		Models models = Mockito.mock(Models.class);
		ReflectionTestUtils.setField(this.client, "models", models);
		ResponseStream<GenerateContentResponse> iterable = Mockito.mock(ResponseStream.class);
		given(iterable.iterator()).willReturn(chunks.iterator());
		given(iterable.spliterator()).willCallRealMethod();
		given(models.generateContentStream(any(String.class), any(List.class), any())).willReturn(iterable);
	}

}
