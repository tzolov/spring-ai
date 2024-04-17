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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.util.StringUtils;

/**
 * @author Christian Tzolov
 */
public abstract class AbstractTextPromptAugmenter<T> implements ChatAugmenter<T> {

	private static final String DEFAULT_USER_PROMPT_TEXT = """
			"Context information is below.\\n"
			"---------------------\\n"
			"{context}\\n"
			"---------------------\\n"
			"Given the context information and not prior knowledge, "
			"answer the question. If the answer is not in the context, inform "
			"the user that you can't answer the question.\\n"
			"Question: {question}\\n"
			"Answer: "
			""";

	public static final String DEFAULT_SYSTEM_PROMPT_TEXT = """
			Use the conversation history from the HISTORY section to provide accurate answers.

			HISTORY:
			{history}
				""";

	public final String systemPromptText;

	public final String userPromptText;

	public AbstractTextPromptAugmenter() {
		this(DEFAULT_SYSTEM_PROMPT_TEXT, DEFAULT_USER_PROMPT_TEXT);
	}

	public AbstractTextPromptAugmenter(String systemPromptText, String userPromptText) {
		this.systemPromptText = systemPromptText;
		this.userPromptText = userPromptText;
	}

	@Override
	public Prompt augment(Prompt originalPrompt, T dataToAugment) {

		List<Message> systemMessages = (originalPrompt.getInstructions() != null) ? originalPrompt.getInstructions()
				.stream()
				.filter(m -> m.getMessageType() == MessageType.SYSTEM)
				.toList() : List.of();

		SystemMessage originalSystemMessage = (!systemMessages.isEmpty()) ? (SystemMessage) systemMessages.get(0)
				: new SystemMessage("");

		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(
				originalSystemMessage.getContent() + System.lineSeparator() + this.systemPromptText);

		Message augmentedSystemMessage = systemPromptTemplate
				.createMessage(this.doCreateSystemContextMap(originalPrompt, dataToAugment));

		System.out.println(augmentedSystemMessage.getContent());

		List<Message> newPromptMessages = new ArrayList<>();
		newPromptMessages.add(augmentedSystemMessage);

		List<Message> nonSystemMessages = (originalPrompt.getInstructions() != null)
				? originalPrompt.getInstructions()
						.stream()
						.filter(m -> m.getMessageType() != MessageType.SYSTEM)
						.toList()
				: List.of();

		newPromptMessages.addAll(nonSystemMessages);

		if (StringUtils.hasText(this.userPromptText)) {
			PromptTemplate promptTemplate = new PromptTemplate(this.userPromptText);
			Message augmentedUserMessage = promptTemplate
					.createMessage(doCreateUserContextMap(originalPrompt, dataToAugment));

			newPromptMessages.add(augmentedUserMessage);
		}

		return new Prompt(newPromptMessages, (ChatOptions) originalPrompt.getOptions());
	}

	protected abstract Map<String, Object> doCreateUserContextMap(Prompt originalPrompt, T dataToAugment);

	protected abstract Map<String, Object> doCreateSystemContextMap(Prompt originalPrompt, T dataToAugment);
}
