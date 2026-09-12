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

package org.springframework.ai.bedrock.converse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.awscore.DefaultAwsResponseMetadata;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.document.internal.MapDocument;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeResponseMetadata;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

import org.springframework.ai.chat.messages.AssistantMessage;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.BDDMockito.given;

/**
 * Tests for the {@link MessagePart} mapping of {@link BedrockProxyChatModel}: signed and
 * redacted reasoning as {@link ReasoningPart}s with a Bedrock payload, ordered parts in
 * one generation, replay from parts, and the streaming contract.
 *
 * @author Christian Tzolov
 */
@ExtendWith(MockitoExtension.class)
class BedrockProxyChatModelMessagePartsTests {

	private static final String REASONING_TEXT = "The user wants the weather in Paris.";

	private static final String SIGNATURE = "EsoBCkgIBhABGAIiQ-signature-token";

	private static final byte[] REDACTED = "redacted-reasoning-bytes".getBytes(StandardCharsets.UTF_8);

	private static final OpaquePayload SIGNATURE_PAYLOAD = new OpaquePayload(BedrockProxyChatModel.BEDROCK_PROVIDER,
			BedrockProxyChatModel.PAYLOAD_SIGNATURE, SIGNATURE);

	private static final OpaquePayload REDACTED_PAYLOAD = new OpaquePayload(BedrockProxyChatModel.BEDROCK_PROVIDER,
			BedrockProxyChatModel.PAYLOAD_REDACTED_CONTENT, Base64.getEncoder().encodeToString(REDACTED));

	private static final ReasoningPart SIGNED_REASONING = new ReasoningPart(REASONING_TEXT, null, false,
			SIGNATURE_PAYLOAD, Map.of());

	private static final ReasoningPart REDACTED_REASONING = new ReasoningPart(null, null, true, REDACTED_PAYLOAD,
			Map.of());

	private static final AssistantMessage.ToolCall WEATHER_CALL = new AssistantMessage.ToolCall("tooluse_123",
			"function", "getCurrentWeather", "{\"location\":\"Paris\"}");

	private @Mock BedrockRuntimeClient bedrockRuntimeClient;

	private @Mock BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;

	private BedrockProxyChatModel chatModel;

	@BeforeEach
	void beforeEach() {
		this.chatModel = BedrockProxyChatModel.builder()
			.bedrockRuntimeClient(this.bedrockRuntimeClient)
			.bedrockRuntimeAsyncClient(this.bedrockRuntimeAsyncClient)
			.build();
	}

	// -- inbound, non-streaming --

	@Test
	void reasoningTextAndToolUseBecomeOrderedPartsInOneGeneration() {
		given(this.bedrockRuntimeClient.converse(isA(ConverseRequest.class)))
			.willReturn(response(StopReason.TOOL_USE, signedReasoningBlock(), redactedReasoningBlock(),
					ContentBlock.fromText("Let me check."), toolUseBlock()));

		ChatResponse chatResponse = this.chatModel.call(new Prompt("Weather in Paris?"));

		assertThat(chatResponse.getResults()).hasSize(1);
		AssistantMessage output = chatResponse.getResult().getOutput();
		assertThat(output.getParts()).hasSize(4);
		assertThat(output.getParts().get(0)).isEqualTo(SIGNED_REASONING);
		assertThat(output.getParts().get(1)).isEqualTo(REDACTED_REASONING);
		assertThat(output.getParts().get(2)).isEqualTo(TextPart.of("Let me check."));
		assertThat(output.getParts().get(3)).isInstanceOf(ToolCallPart.class);
		assertThat(output.getReasoning()).containsExactly(SIGNED_REASONING, REDACTED_REASONING);
		assertThat(output.getText()).isEqualTo("Let me check.");
		assertThat(output.getToolCalls()).hasSize(1);
		assertThat(output.getToolCalls().get(0).name()).isEqualTo("getCurrentWeather");
		assertThat(chatResponse.getResult().getMetadata().getFinishReason()).isEqualTo("tool_use");

		// Deprecated view kept for one release
		assertThat(output).isInstanceOf(BedrockAssistantMessage.class);
		List<BedrockReasoningContent> reasoningContents = ((BedrockAssistantMessage) output).getReasoningContents();
		assertThat(reasoningContents).hasSize(2);
		assertThat(reasoningContents.get(0).getText()).isEqualTo(REASONING_TEXT);
		assertThat(reasoningContents.get(0).getSignature()).isEqualTo(SIGNATURE);
		assertThat(reasoningContents.get(1).getRedactedContent()).isEqualTo(REDACTED);
	}

	@Test
	void plainTextResponseIsASingleTextPart() {
		given(this.bedrockRuntimeClient.converse(isA(ConverseRequest.class)))
			.willReturn(response(StopReason.END_TURN, ContentBlock.fromText("15°C")));

		AssistantMessage output = this.chatModel.call(new Prompt("Weather?")).getResult().getOutput();

		assertThat(output.getParts()).containsExactly(TextPart.of("15°C"));
		assertThat(output.getReasoning()).isEmpty();
		assertThat(output).isNotInstanceOf(BedrockAssistantMessage.class);
	}

	@Test
	void toolUseWithoutTextHasNoTextPartAndTheToolCallOnTheResult() {
		given(this.bedrockRuntimeClient.converse(isA(ConverseRequest.class)))
			.willReturn(response(StopReason.TOOL_USE, toolUseBlock()));

		ChatResponse chatResponse = this.chatModel.call(new Prompt("Weather?"));

		assertThat(chatResponse.getResults()).hasSize(1);
		AssistantMessage output = chatResponse.getResult().getOutput();
		assertThat(output.getParts()).hasSize(1);
		assertThat(output.getParts().get(0)).isInstanceOf(ToolCallPart.class);
		assertThat(output.getText()).isEmpty();
		assertThat(chatResponse.hasToolCalls()).isTrue();
	}

	// -- outbound --

	@Test
	void partsAreReplayedInOrderAsReasoningTextAndToolUseBlocks() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(SIGNED_REASONING)
			.part(REDACTED_REASONING)
			.part(TextPart.of("Let me check."))
			.part(ToolCallPart.of(WEATHER_CALL))
			.build();

		List<ContentBlock> content = replayedAssistantContent(assistant);

		assertThat(content).extracting(ContentBlock::type)
			.containsExactly(ContentBlock.Type.REASONING_CONTENT, ContentBlock.Type.REASONING_CONTENT,
					ContentBlock.Type.TEXT, ContentBlock.Type.TOOL_USE);
		assertThat(content.get(0).reasoningContent().reasoningText().text()).isEqualTo(REASONING_TEXT);
		assertThat(content.get(0).reasoningContent().reasoningText().signature()).isEqualTo(SIGNATURE);
		assertThat(content.get(1).reasoningContent().redactedContent().asByteArray()).isEqualTo(REDACTED);
		assertThat(content.get(2).text()).isEqualTo("Let me check.");
		assertThat(content.get(3).toolUse().toolUseId()).isEqualTo("tooluse_123");
		assertThat(content.get(3).toolUse().input().asMap().get("location").asString()).isEqualTo("Paris");
	}

	@Test
	void foreignAndUnsignedReasoningIsNotReplayed() {
		AssistantMessage assistant = AssistantMessage.builder()
			.part(new ReasoningPart("anthropic thoughts", null, false,
					new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.part(ReasoningPart.of("unsigned thoughts"))
			.part(TextPart.of(""))
			.part(ToolCallPart.of(WEATHER_CALL))
			.build();

		List<ContentBlock> content = replayedAssistantContent(assistant);

		assertThat(content).extracting(ContentBlock::type).containsExactly(ContentBlock.Type.TOOL_USE);
	}

	@Test
	@SuppressWarnings("removal")
	void deprecatedBedrockAssistantMessageStillReplaysItsReasoningContents() {
		BedrockAssistantMessage assistant = BedrockAssistantMessage.builder()
			.content("")
			.toolCalls(List.of(WEATHER_CALL))
			.reasoningContents(
					List.of(BedrockReasoningContent.builder().text(REASONING_TEXT).signature(SIGNATURE).build()))
			.build();

		assertThat(assistant.getReasoning()).containsExactly(SIGNED_REASONING);

		List<ContentBlock> content = replayedAssistantContent(assistant);

		assertThat(content).extracting(ContentBlock::type)
			.containsExactly(ContentBlock.Type.REASONING_CONTENT, ContentBlock.Type.TOOL_USE);
		assertThat(content.get(0).reasoningContent().reasoningText().signature()).isEqualTo(SIGNATURE);
	}

	// -- streaming --

	@Test
	void streamingEmitsIndexedPartsAndAggregatesReasoningTextAndToolCall() {
		List<ConverseStreamOutput> events = List.of(reasoningDelta(0, "Think "), reasoningDelta(0, "hard."),
				signatureDelta(0, SIGNATURE),
				ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(0).build(), textDelta(1, "Let me "),
				textDelta(1, "check."), ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(1).build(),
				ConverseStreamOutput.contentBlockStartBuilder()
					.contentBlockIndex(2)
					.start(ContentBlockStart.builder()
						.toolUse(ToolUseBlockStart.builder().toolUseId("tooluse_123").name("getCurrentWeather").build())
						.build())
					.build(),
				toolUseDelta(2, "{\"location\":"), toolUseDelta(2, "\"Paris\"}"),
				ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(2).build(),
				ConverseStreamOutput.messageStopBuilder().stopReason(StopReason.TOOL_USE).build(),
				ConverseStreamOutput.metadataBuilder()
					.usage(TokenUsage.builder().inputTokens(10).outputTokens(20).totalTokens(30).build())
					.build());
		givenStream(events);

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> chunks = new MessageAggregator()
			.aggregate(this.chatModel.stream(new Prompt("Weather in Paris?")), aggregatedRef::set)
			.collectList()
			.block();

		// 3 reasoning deltas, 2 text deltas, the started tool call, 2 argument deltas,
		// 1 final chunk with the complete tool call
		assertThat(chunks).hasSize(9);
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata().getId()).isEqualTo("req-1"));
		assertThat(chunks.get(0).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(ReasoningPart.of("Think "), 0));
		assertThat(chunks.get(0).getResult().getOutput().getText()).isEmpty();
		assertThat(chunks.get(2).getResult().getOutput().getParts()).containsExactly(
				StreamingParts.partial(new ReasoningPart(null, null, false, SIGNATURE_PAYLOAD, Map.of()), 0));
		assertThat(chunks.get(3).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(TextPart.of("Let me "), 1));
		assertThat(chunks.get(3).getResult().getOutput().getText()).isEqualTo("Let me ");
		// The started tool call and each argument delta are partial ToolCallParts at the
		// tool-use block index; they are not tool calls yet and carry no text
		assertThat(chunks.get(5).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart.of(new AssistantMessage.ToolCall("tooluse_123", "function", "getCurrentWeather", "")), 2));
		assertThat(chunks.get(6).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(ToolCallPart
			.of(new AssistantMessage.ToolCall("tooluse_123", "function", "getCurrentWeather", "{\"location\":")), 2));
		assertThat(chunks.get(7).getResult().getOutput().getParts()).containsExactly(StreamingParts.partial(
				ToolCallPart
					.of(new AssistantMessage.ToolCall("tooluse_123", "function", "getCurrentWeather", "\"Paris\"}")),
				2));
		assertThat(chunks.get(5).getResult().getOutput().getText()).isEmpty();
		// Delta chunks never look like tool-call responses
		assertThat(chunks.subList(0, 8)).allSatisfy(chunk -> assertThat(chunk.hasToolCalls()).isFalse());
		AssistantMessage last = chunks.get(8).getResult().getOutput();
		assertThat(last.getToolCalls()).containsExactly(WEATHER_CALL);
		MessagePart toolCallPart = last.getParts().get(0);
		assertThat(toolCallPart).isInstanceOf(ToolCallPart.class);
		assertThat(StreamingParts.partIndex(toolCallPart)).isEqualTo(2);
		assertThat(StreamingParts.isPartial(toolCallPart)).isFalse();
		assertThat(chunks.get(8).getResult().getMetadata().getFinishReason()).isEqualTo("tool_use");

		AssistantMessage aggregated = aggregatedRef.get().getResult().getOutput();
		assertThat(aggregated.getParts()).containsExactly(
				new ReasoningPart("Think hard.", null, false, SIGNATURE_PAYLOAD, Map.of()),
				TextPart.of("Let me check."), ToolCallPart.of(WEATHER_CALL));
		assertThat(aggregated.getText()).isEqualTo("Let me check.");
		assertThat(aggregated.getToolCalls()).containsExactly(WEATHER_CALL);

		// The aggregated turn replays with the signed reasoning before the tool use
		List<ContentBlock> content = replayedAssistantContent(aggregated);
		assertThat(content).extracting(ContentBlock::type)
			.containsExactly(ContentBlock.Type.REASONING_CONTENT, ContentBlock.Type.TEXT, ContentBlock.Type.TOOL_USE);
		assertThat(content.get(0).reasoningContent().reasoningText().signature()).isEqualTo(SIGNATURE);
	}

	@Test
	void streamingRedactedReasoningIsARedactedPartWithPayload() {
		List<ConverseStreamOutput> events = List.of(ConverseStreamOutput.contentBlockDeltaBuilder()
			.contentBlockIndex(0)
			.delta(ContentBlockDelta.builder()
				.reasoningContent(
						ReasoningContentBlockDelta.builder().redactedContent(SdkBytes.fromByteArray(REDACTED)).build())
				.build())
			.build(), textDelta(1, "15°C"),
				ConverseStreamOutput.messageStopBuilder().stopReason(StopReason.END_TURN).build(),
				ConverseStreamOutput.metadataBuilder()
					.usage(TokenUsage.builder().inputTokens(10).outputTokens(20).totalTokens(30).build())
					.build());
		givenStream(events);

		AtomicReference<ChatResponse> aggregatedRef = new AtomicReference<>();
		List<ChatResponse> chunks = new MessageAggregator()
			.aggregate(this.chatModel.stream(new Prompt("Weather?")), aggregatedRef::set)
			.collectList()
			.block();

		assertThat(chunks.get(0).getResult().getOutput().getParts())
			.containsExactly(StreamingParts.partial(REDACTED_REASONING, 0));
		assertThat(aggregatedRef.get().getResult().getOutput().getParts()).containsExactly(REDACTED_REASONING,
				TextPart.of("15°C"));
		// The last chunk keeps the empty text of a plain end of turn
		assertThat(chunks.get(chunks.size() - 1).getResult().getOutput().getText()).isEmpty();
	}

	// -- helpers --

	private List<ContentBlock> replayedAssistantContent(AssistantMessage assistant) {
		Prompt prompt = new Prompt(List.of(new UserMessage("Weather in Paris?"), assistant,
				ToolResponseMessage.builder()
					.responses(
							List.of(new ToolResponseMessage.ToolResponse("tooluse_123", "getCurrentWeather", "15°C")))
					.build()),
				this.chatModel.getDefaultOptions());
		return this.chatModel.createRequest(prompt)
			.messages()
			.stream()
			.filter(m -> m.role() == ConversationRole.ASSISTANT)
			.findFirst()
			.orElseThrow()
			.content();
	}

	private void givenStream(List<ConverseStreamOutput> events) {
		given(this.bedrockRuntimeAsyncClient.converseStream(any(ConverseStreamRequest.class),
				any(ConverseStreamResponseHandler.class)))
			.willAnswer(invocation -> {
				ConverseStreamResponseHandler handler = invocation.getArgument(1);
				ConverseStreamResponse.Builder responseBuilder = ConverseStreamResponse.builder();
				responseBuilder.responseMetadata(BedrockRuntimeResponseMetadata
					.create(DefaultAwsResponseMetadata.create(Map.of("AWS_REQUEST_ID", "req-1"))));
				handler.responseReceived(responseBuilder.build());
				handler.onEventStream(SdkPublisher.adapt(Flux.fromIterable(events)));
				handler.complete();
				return CompletableFuture.completedFuture(null);
			});
	}

	private static ConverseResponse response(StopReason stopReason, ContentBlock... content) {
		return ConverseResponse.builder()
			.output(ConverseOutput.builder()
				.message(Message.builder().role(ConversationRole.ASSISTANT).content(content).build())
				.build())
			.usage(TokenUsage.builder().inputTokens(20).outputTokens(30).totalTokens(50).build())
			.stopReason(stopReason)
			.build();
	}

	private static ContentBlock signedReasoningBlock() {
		return ContentBlock.fromReasoningContent(ReasoningContentBlock.builder()
			.reasoningText(ReasoningTextBlock.builder().text(REASONING_TEXT).signature(SIGNATURE).build())
			.build());
	}

	private static ContentBlock redactedReasoningBlock() {
		return ContentBlock.fromReasoningContent(
				ReasoningContentBlock.builder().redactedContent(SdkBytes.fromByteArray(REDACTED)).build());
	}

	private static ContentBlock toolUseBlock() {
		return ContentBlock.fromToolUse(ToolUseBlock.builder()
			.toolUseId("tooluse_123")
			.name("getCurrentWeather")
			.input(MapDocument.mapBuilder().putString("location", "Paris").build())
			.build());
	}

	private static ConverseStreamOutput reasoningDelta(int index, String text) {
		return ConverseStreamOutput.contentBlockDeltaBuilder()
			.contentBlockIndex(index)
			.delta(ContentBlockDelta.builder()
				.reasoningContent(ReasoningContentBlockDelta.builder().text(text).build())
				.build())
			.build();
	}

	private static ConverseStreamOutput signatureDelta(int index, String signature) {
		return ConverseStreamOutput.contentBlockDeltaBuilder()
			.contentBlockIndex(index)
			.delta(ContentBlockDelta.builder()
				.reasoningContent(ReasoningContentBlockDelta.builder().signature(signature).build())
				.build())
			.build();
	}

	private static ConverseStreamOutput textDelta(int index, String text) {
		return ConverseStreamOutput.contentBlockDeltaBuilder()
			.contentBlockIndex(index)
			.delta(ContentBlockDelta.builder().text(text).build())
			.build();
	}

	private static ConverseStreamOutput toolUseDelta(int index, String input) {
		return ConverseStreamOutput.contentBlockDeltaBuilder()
			.contentBlockIndex(index)
			.delta(ContentBlockDelta.builder().toolUse(ToolUseBlockDelta.builder().input(input).build()).build())
			.build();
	}

}
