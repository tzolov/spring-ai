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

package org.springframework.ai.chat.ra.history;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.ra.PromptContext;
import org.springframework.ai.chat.ra.tokenizer.TokenCountEstimator;

/**
 *
 * @author Christian Tzolov
 */
public class MessageListChatHistoryTransformer extends AbstractSlidingWindowDatumTransformer<Message> {

	private final ChatHistory chatHistory;

	public MessageListChatHistoryTransformer(TokenCountEstimator tokenCountEstimator, int maxTokenSize, ChatHistory chatHistory) {
		super(tokenCountEstimator, maxTokenSize);
		this.chatHistory = chatHistory;
	}

	@Override
	protected List<Message> doGetDatum(PromptContext promptContext) {

		List<Message> messageHistory = this.chatHistory.get(promptContext.getConversationId());

		return (messageHistory != null)
				? messageHistory
						.stream()
						.filter(m -> m.getMessageType() != MessageType.SYSTEM)
						.toList()
				: List.of();
	}

	@Override
	protected int doEstimateTokenCount(Message datum) {
		return this.tokenCountEstimator.estimate(datum);
	}

	@Override
	protected int doEstimateTokenCount(List<Message> datum) {
		return this.tokenCountEstimator.estimate(datum);
	}

	@Override
	public PromptContext transform(PromptContext promptContext) {

		PromptContext purgedPromptContext = super.transform(promptContext);

		var promptMessagesWithHistory = new ArrayList<>(purgedPromptContext.getMessageList());

		promptMessagesWithHistory.addAll(purgedPromptContext.gePrompt().getInstructions());

		var newPrompt = new Prompt(promptMessagesWithHistory, (ChatOptions) purgedPromptContext.gePrompt().getOptions());

		return PromptContext.builder()
				.withPrompt(newPrompt)
				.withConversationId(purgedPromptContext.getConversationId())
				.withMessageList(purgedPromptContext.getMessageList())
				.build();
	}
}
