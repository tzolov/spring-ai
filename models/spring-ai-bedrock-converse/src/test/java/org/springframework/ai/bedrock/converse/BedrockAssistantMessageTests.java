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

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BedrockAssistantMessage}.
 *
 * @author guan xu
 */
@SuppressWarnings("removal")
class BedrockAssistantMessageTests {

	private static final BedrockReasoningContent SIGNED = BedrockReasoningContent.builder()
		.text("think")
		.signature("sig-1")
		.build();

	private static final ReasoningPart SIGNED_PART = new ReasoningPart("think", null, false,
			new OpaquePayload(BedrockProxyChatModel.BEDROCK_PROVIDER, BedrockProxyChatModel.PAYLOAD_SIGNATURE, "sig-1"),
			Map.of());

	@Test
	void copyPreservesReasoningContents() {
		BedrockAssistantMessage original = BedrockAssistantMessage.builder()
			.content("hello")
			.reasoningContents(List.of(SIGNED))
			.build();

		BedrockAssistantMessage copy = (BedrockAssistantMessage) original.copy();

		assertThat(copy).isNotSameAs(original);
		assertThat(copy).isEqualTo(original);
		assertThat(copy.getReasoningContents()).containsExactly(SIGNED);
		assertThat(copy.getParts()).containsExactly(SIGNED_PART, TextPart.of("hello"));
	}

	@Test
	void promptCopyPreservesBedrockAssistantMessage() {
		BedrockAssistantMessage original = BedrockAssistantMessage.builder()
			.content("hello")
			.reasoningContents(List.of(SIGNED))
			.build();

		Prompt copy = new Prompt(List.of(original)).copy();

		Message copied = copy.getInstructions().get(0);
		assertThat(copied).isInstanceOf(BedrockAssistantMessage.class);
		assertThat(((BedrockAssistantMessage) copied).getReasoningContents()).containsExactly(SIGNED);
	}

	@Test
	void legacyReasoningContentsAreMaterializedAsReasoningPartsBeforeTheText() {
		byte[] redacted = "opaque".getBytes(StandardCharsets.UTF_8);
		BedrockAssistantMessage message = BedrockAssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "getWeather", "{}")))
			.reasoningContents(List.of(SIGNED, BedrockReasoningContent.builder().redactedContent(redacted).build()))
			.build();

		assertThat(message.getParts()).hasSize(4);
		assertThat(message.getParts().get(0)).isEqualTo(SIGNED_PART);
		ReasoningPart redactedPart = (ReasoningPart) message.getParts().get(1);
		assertThat(redactedPart.redacted()).isTrue();
		assertThat(redactedPart.payload().kind()).isEqualTo(BedrockProxyChatModel.PAYLOAD_REDACTED_CONTENT);
		assertThat(redactedPart.payload().data()).isEqualTo(Base64.getEncoder().encodeToString(redacted));
		assertThat(message.getParts().get(2)).isEqualTo(TextPart.of(""));
		assertThat(message.getParts().get(3)).isInstanceOf(ToolCallPart.class);
		assertThat(message.getReasoning()).hasSize(2);
		assertThat(message.getReasoningContents()).hasSize(2);
		assertThat(message.getReasoningContents().get(1).getRedactedContent()).isEqualTo(redacted);
	}

	@Test
	void explicitPartsAreHonoredAndReasoningContentsDerivedFromThem() {
		BedrockAssistantMessage message = BedrockAssistantMessage.builder()
			.part(SIGNED_PART)
			.part(new ReasoningPart("foreign", null, false, new OpaquePayload("anthropic", "signature", "x"), Map.of()))
			.part(TextPart.of("answer"))
			.build();

		assertThat(message.getParts()).hasSize(3);
		assertThat(message.getText()).isEqualTo("answer");
		// Only Bedrock reasoning is visible through the deprecated view
		assertThat(message.getReasoningContents()).containsExactly(SIGNED);
		assertThat(message.hasReasoningContents()).isTrue();
	}

}
