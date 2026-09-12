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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link BedrockProxyChatModel} sends the parts of a {@link UserMessage} in
 * their order, so text and media can interleave, while legacy messages keep the text then
 * media order.
 *
 * @author Christian Tzolov
 */
@ExtendWith(MockitoExtension.class)
class BedrockProxyChatModelUserPartsTests {

	private static final Media IMAGE = Media.builder()
		.mimeType(MimeTypeUtils.IMAGE_PNG)
		.data(new byte[] { 1, 2, 3 })
		.build();

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

	@Test
	void interleavedPartsKeepTheirOrder() {
		UserMessage message = UserMessage.builder()
			.part(TextPart.of("Look at "))
			.part(MediaPart.of(IMAGE))
			.part(TextPart.of("this."))
			.build();

		List<ContentBlock> blocks = userContent(message);

		assertThat(blocks).hasSize(3);
		assertThat(blocks.get(0).text()).isEqualTo("Look at ");
		assertThat(blocks.get(1).type()).isEqualTo(ContentBlock.Type.IMAGE);
		assertThat(blocks.get(2).text()).isEqualTo("this.");
	}

	@Test
	void legacyMessageKeepsTextThenMedia() {
		UserMessage message = UserMessage.builder().text("describe").media(IMAGE).build();

		List<ContentBlock> blocks = userContent(message);

		assertThat(blocks).hasSize(2);
		assertThat(blocks.get(0).text()).isEqualTo("describe");
		assertThat(blocks.get(1).type()).isEqualTo(ContentBlock.Type.IMAGE);
	}

	@Test
	void mediaOnlyMessageSendsNoEmptyTextBlock() {
		UserMessage message = UserMessage.builder().part(MediaPart.of(IMAGE)).build();

		List<ContentBlock> blocks = userContent(message);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).type()).isEqualTo(ContentBlock.Type.IMAGE);
	}

	@Test
	void emptyTextPartsAreSkipped() {
		UserMessage message = UserMessage.builder()
			.part(MediaPart.of(IMAGE))
			.part(TextPart.of(""))
			.part(MediaPart.of(IMAGE))
			.build();

		List<ContentBlock> blocks = userContent(message);

		assertThat(blocks).hasSize(2);
		assertThat(blocks).allSatisfy(block -> assertThat(block.type()).isEqualTo(ContentBlock.Type.IMAGE));
	}

	@Test
	void unsupportedPartsAreSkipped() {
		UserMessage message = UserMessage.builder()
			.part(new UnknownPart("anthropic", "document", "{\"type\":\"document\"}", null, Map.of()))
			.part(TextPart.of("Summarize."))
			.build();

		List<ContentBlock> blocks = userContent(message);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).text()).isEqualTo("Summarize.");
	}

	private List<ContentBlock> userContent(UserMessage message) {
		return this.chatModel.createRequest(new Prompt(List.of(message), this.chatModel.getDefaultOptions()))
			.messages()
			.stream()
			.filter(m -> m.role() == ConversationRole.USER)
			.findFirst()
			.orElseThrow()
			.content();
	}

}
