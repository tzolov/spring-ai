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

import org.springframework.ai.chat.history.ChatHistory2;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * Chat history retriever that retrieves the chat history window based on the max token count.
 *
 * @author Christian Tzolov
 */
public class ShortTermTokenWindowChatHistoryRetriever implements ChatRetriever<List<Message>> {

	private final ChatHistory2 chatHistory;

	/**
	 * Token encoding used to estimate the token count.
	 */
	protected final TokenCountEstimator tokenCountEstimator;

	/**
	 * Maximum token size allowed in the chat history.
	 */
	private final int maxTokenSize;

	public ShortTermTokenWindowChatHistoryRetriever(ChatHistory2 chatHistory, int maxTokenSize) {
		this(chatHistory, new JTokkitTokenCountEstimator(), maxTokenSize);
	}

	public ShortTermTokenWindowChatHistoryRetriever(ChatHistory2 chatHistory, TokenCountEstimator tokenCountEstimator,
			int maxTokenSize) {
		this.chatHistory = chatHistory;
		this.tokenCountEstimator = tokenCountEstimator;
		this.maxTokenSize = maxTokenSize;
	}

	@Override
	public List<Message> retrieve(EngineRequest3 request) {
		List<Message> nonSystemChatMessages = (this.chatHistory.get(request.getConversationId()) != null)
				? this.chatHistory.get(request.getConversationId()).stream()
						.filter(m -> m.getMessageType() != MessageType.SYSTEM)
						.toList()
				: List.of();

		// int totalSize = this.tokenCountEstimator.estimate(nonSystemChatMessages) - request.getTokenRunningTotal();
		int totalSize = this.tokenCountEstimator.estimate(nonSystemChatMessages);

		if (totalSize <= this.maxTokenSize) {
			return nonSystemChatMessages;
		}

		List<Message> newSessionMessages = this.purgeExcess(nonSystemChatMessages, totalSize);

		return newSessionMessages;
	}

	protected List<Message> purgeExcess(List<Message> sessionMessages, int totalSize) {

		int index = 0;
		List<Message> newList = new ArrayList<>();

		while (index < sessionMessages.size() && totalSize > this.maxTokenSize) {
			Message oldMessage = sessionMessages.get(index++);
			int oldMessageTokenSize = this.tokenCountEstimator.estimate(oldMessage);
			totalSize = totalSize - oldMessageTokenSize;
		}

		if (index >= sessionMessages.size()) {
			return List.of();
		}

		// add the rest of the messages.
		newList.addAll(sessionMessages.subList(index, sessionMessages.size()));

		return newList;
	}

}
