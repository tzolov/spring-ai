/*
 * Copyright 2024-2024 the original author or authors.
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

package org.springframework.ai.chat.history;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 */
public class FixedTokenCountChatHistoryTests {

	public record TestMessage(int id, String text, Role role, boolean isFinal) {

		public TestMessage(int id, String text, Role role) {
			this(id, text, role, false);
		}

		enum Role {

			USER, ASSISTANT, SYSTEM, TOOL_CALL, TOOL_RESPONSE

		}
	}

	public static class TestFixedTokenCountChatHistory extends AbstractFixedTokenCountChatHistory<TestMessage> {

		public TestFixedTokenCountChatHistory(int maxTokenSize) {
			super(maxTokenSize);
		}

		@Override
		public int estimateTokenCount(TestMessage message) {
			return estimateTokenCount(message.text());
		}

		@Override
		public boolean isAssistantMessage(TestMessage message) {
			return message.role() == TestMessage.Role.ASSISTANT;
		}

		@Override
		public boolean isUserMessage(TestMessage message) {
			return message.role() == TestMessage.Role.USER;
		}

		@Override
		public boolean isSystemMessage(TestMessage message) {
			return message.role() == TestMessage.Role.SYSTEM;
		}

		@Override
		public boolean isToolCallMessage(TestMessage message) {
			return message.role() == TestMessage.Role.TOOL_CALL;
		}

		@Override
		public boolean isToolCallResponseMessage(TestMessage message) {
			return message.role() == TestMessage.Role.TOOL_RESPONSE;
		}

	}

	@Test
	public void slidingWindow() {

		String sessionId = "1";
		int maxTokenSize = 500;

		try (TestFixedTokenCountChatHistory chatHistory = new TestFixedTokenCountChatHistory(maxTokenSize)) {

			int singleMessageTokenCount = chatHistory
				.estimateTokenCount(new TestMessage(0, SAMPLE_TEXT, TestMessage.Role.USER));

			for (int i = 0; i < 100; i++) {
				chatHistory.add(sessionId, new TestMessage(i, SAMPLE_TEXT, TestMessage.Role.USER));
			}

			assertThat(maxTokenSize / singleMessageTokenCount).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(98);
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(99);
		}
	}

	@Test
	public void systemMessageHandling() {

		String sessionId = "1";
		int maxTokenSize = 500;

		try (TestFixedTokenCountChatHistory chatHistory = new TestFixedTokenCountChatHistory(maxTokenSize)) {

			// Add system and user messages both within the maxTokenSize
			chatHistory.add(sessionId, new TestMessage(0, SAMPLE_TEXT, TestMessage.Role.SYSTEM));
			chatHistory.add(sessionId, new TestMessage(1, SAMPLE_TEXT, TestMessage.Role.USER));

			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);

			// Add 2 more user messages
			chatHistory.add(sessionId, new TestMessage(2, SAMPLE_TEXT, TestMessage.Role.USER));
			chatHistory.add(sessionId, new TestMessage(3, SAMPLE_TEXT, TestMessage.Role.USER));

			// The maxTokenSize permits only the last 2 messages to be retained. Since the
			// system message is
			// not purged, the previous 2 user messages are purged.
			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(0); // system
																				// message
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(3); // last user
																				// message

			// Add a new system message. It should replace the previous system message.
			// The user messages is retained.
			chatHistory.add(sessionId, new TestMessage(4, SAMPLE_TEXT, TestMessage.Role.SYSTEM));
			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(3); // last user
																				// message
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(4); // new system
																				// message

			// Add 2 more user messages. Only the last user message should be retained.
			chatHistory.add(sessionId, new TestMessage(5, SAMPLE_TEXT, TestMessage.Role.USER));
			chatHistory.add(sessionId, new TestMessage(6, SAMPLE_TEXT, TestMessage.Role.USER));

			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(4); // new system
																				// message
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(6); // last user
																				// message

			// Add the SAME system message again. Nothing should change.
			chatHistory.add(sessionId, new TestMessage(4, SAMPLE_TEXT, TestMessage.Role.SYSTEM));
			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(4); // new system
																				// message
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(6); // last user
																				// message
		}
	}

	@Test
	public void toolCallHandling() {

		String sessionId = "1";
		int maxTokenSize = 1100;

		try (TestFixedTokenCountChatHistory chatHistory = new TestFixedTokenCountChatHistory(maxTokenSize)) {

			// Add system and user messages both within the maxTokenSize
			chatHistory.add(sessionId, new TestMessage(0, SAMPLE_TEXT, TestMessage.Role.TOOL_CALL));
			chatHistory.add(sessionId, new TestMessage(1, SAMPLE_TEXT, TestMessage.Role.TOOL_RESPONSE));
			chatHistory.add(sessionId, new TestMessage(2, SAMPLE_TEXT, TestMessage.Role.TOOL_RESPONSE));
			chatHistory.add(sessionId, new TestMessage(3, SAMPLE_TEXT, TestMessage.Role.TOOL_RESPONSE));

			chatHistory.add(sessionId, new TestMessage(4, SAMPLE_TEXT, TestMessage.Role.USER));

			assertThat(chatHistory.get(sessionId).size()).isEqualTo(5);

			// this additional message will trigger the purging of the tool call and
			// responses.
			chatHistory.add(sessionId, new TestMessage(5, SAMPLE_TEXT, TestMessage.Role.USER));

			// All tool call and responses are purged, only the last user message is
			// retained.
			assertThat(chatHistory.get(sessionId).size()).isEqualTo(2);
			assertThat(chatHistory.get(sessionId).get(0).id()).isEqualTo(4);
			assertThat(chatHistory.get(sessionId).get(1).id()).isEqualTo(5);
		}
	}

	private static String SAMPLE_TEXT = """
			On the other hand, we denounce with righteous indignation and dislike men who are so beguiled
			and demoralized by the charms of pleasure of the moment, so blinded by desire, that they cannot
			foresee the pain and trouble that are bound to ensue; and equal blame belongs to those who
			fail in their duty through weakness of will, which is the same as saying through shrinking
			from toil and pain. These cases are perfectly simple and easy to distinguish. In a free hour,
			when our power of choice is untrammelled and when nothing prevents our being able to do what
			we like best, every pleasure is to be welcomed and every pain avoided. But in certain
			circumstances and owing to the claims of duty or the obligations of business it will
			frequently occur that pleasures have to be repudiated and annoyances accepted. The wise man
			therefore always holds in these matters to this principle of selection: he rejects pleasures
			to secure other greater pleasures, or else he endures pains to avoid worse pains.
			""";

}
