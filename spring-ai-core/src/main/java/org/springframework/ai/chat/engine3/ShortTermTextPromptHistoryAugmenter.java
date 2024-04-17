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

package org.springframework.ai.chat.engine3;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/**
 *
 * @author Christian Tzolov
 */
public class ShortTermTextPromptHistoryAugmenter extends AbstractTextPromptAugmenter<List<Message>> {

	public static final String DEFAULT_HISTORY_SYSTEM_PROMPT_TEXT = """
			Use the conversation history from the HISTORY section to provide accurate answers.

			HISTORY:
			{history}
				""";

	public ShortTermTextPromptHistoryAugmenter() {
		super(DEFAULT_HISTORY_SYSTEM_PROMPT_TEXT, "");
	}

	protected String doCreateContext(List<Message> chatHistory) {
		return chatHistory.stream()
				.map(msg -> msg.getMessageType() + ": " + msg.getContent())
				.collect(Collectors.joining(System.lineSeparator()));
	}

	@Override
	protected Map<String, Object> doCreateUserContextMap(Prompt originalPrompt, List<Message> dataToAugment) {
		return Map.of();
	}

	@Override
	protected Map<String, Object> doCreateSystemContextMap(Prompt originalPrompt, List<Message> dataToAugment) {

		String shortTermChatHistory = dataToAugment.stream()
				.map(msg -> msg.getMessageType() + ": " + msg.getContent())
				.collect(Collectors.joining(System.lineSeparator()));

		return Map.of("history", shortTermChatHistory);
	}
}
