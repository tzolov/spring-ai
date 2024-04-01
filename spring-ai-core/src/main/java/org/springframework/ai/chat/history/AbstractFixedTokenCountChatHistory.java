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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.CollectionUtils;

/**
 * @author Christian Tzolov
 */
public abstract class AbstractFixedTokenCountChatHistory<Msg> implements ChatHistory<Msg>, AutoCloseable {

	static final Logger logger = LoggerFactory.getLogger(AbstractFixedTokenCountChatHistory.class);

	/**
	 * Chat history storage.
	 */
	protected final ConcurrentHashMap<String, List<Msg>> chatHistory;

	/**
	 * Token encoding used to estimate the size of the message content.
	 */
	protected final Encoding tokenEncoding;

	/**
	 * Maximum token size allowed in the chat history.
	 */
	private final int maxTokenSize;

	public AbstractFixedTokenCountChatHistory(int maxTokenSize) {
		this(Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE), maxTokenSize);
	}

	public AbstractFixedTokenCountChatHistory(Encoding tokenEncoding, int maxTokenSize) {
		this.tokenEncoding = tokenEncoding;
		this.maxTokenSize = maxTokenSize;
		this.chatHistory = new ConcurrentHashMap<>();
	}

	@Override
	public void add(String sessionId, Msg message) {

		if (message == null) {
			return;
		}

		List<Msg> sessionMessages = this.get(sessionId);

		if (this.isSystemMessage(message)) {
			Optional<Msg> existingSystemMessage = this.findSystem(sessionMessages);
			if (existingSystemMessage.isPresent()) {
				if (message.equals(existingSystemMessage.get())) {
					// do nothing
				}
				else {
					sessionMessages.remove(existingSystemMessage.get());
					sessionMessages.add(message);
				}
			}
			else {
				sessionMessages.add(message);
			}
		}
		else {
			sessionMessages.add(message);
		}

		sessionMessages = this.purgeExcess(sessionMessages);

		this.update(sessionId, sessionMessages);
	}

	@Override
	public void update(String sessionId, List<Msg> sessionMessages) {
		this.chatHistory.put(sessionId, sessionMessages);
	}

	@Override
	public void delete(String sessionId) {
		this.chatHistory.remove(sessionId);
	}

	@Override
	public void clean() {
		this.chatHistory.clear();
	}

	@Override
	public List<Msg> get(String sessionId) {
		this.chatHistory.putIfAbsent(sessionId, new ArrayList<>());
		return new ArrayList<>(this.chatHistory.get(sessionId));
	}

	protected List<Msg> purgeExcess(List<Msg> sessionMessages) {

		int totalSize = this.estimateTokenCount(sessionMessages);

		if (totalSize <= this.maxTokenSize) {
			return sessionMessages;
		}

		int index = 0;
		List<Msg> newList = new ArrayList<>();

		while (index < sessionMessages.size() && totalSize > this.maxTokenSize) {
			Msg oldMessage = sessionMessages.get(index++);

			int oldMessageTokenSize = estimateTokenCount(oldMessage);

			if (isSystemMessage(oldMessage)) {
				// retain system messages.
				newList.add(oldMessage);
			}
			else {
				totalSize = totalSize - oldMessageTokenSize;
			}

			if (isToolCallMessage(oldMessage)) {
				// If a tool call request is evicted then remove all related function
				// call response ¬messages.
				while (index < sessionMessages.size() && isToolCallResponseMessage(sessionMessages.get(index))) {
					totalSize = totalSize - estimateTokenCount(sessionMessages.get(index++));
				}
			}
		}

		if (index >= sessionMessages.size()) {
			throw new IllegalStateException("Failed to rebalance the token size!");
		}

		// add the rest of the messages.
		newList.addAll(sessionMessages.subList(index, sessionMessages.size()));

		return newList;
	}

	protected Optional<Msg> findSystem(List<Msg> sessionMessages) {
		for (Msg message : sessionMessages) {
			if (this.isSystemMessage(message)) {
				return Optional.of(message);
			}
		}
		return Optional.empty();
	}

	protected int estimateTokenCount(String text) {
		if (text == null) {
			return 0;
		}
		return this.tokenEncoding.countTokens(text);
	}

	protected int estimateTokenCount(List<Msg> sessionMessages) {
		if (CollectionUtils.isEmpty(sessionMessages)) {
			return 0;
		}

		int totalSize = 0;
		for (Msg message : sessionMessages) {
			totalSize += this.estimateTokenCount(message);
		}
		return totalSize;
	}

	@Override
	public void close() {
		this.chatHistory.clear();
	}

	public abstract int estimateTokenCount(Msg message);

	public abstract boolean isAssistantMessage(Msg message);

	public abstract boolean isUserMessage(Msg message);

	public abstract boolean isSystemMessage(Msg message);

	public abstract boolean isToolCallMessage(Msg message);

	public abstract boolean isToolCallResponseMessage(Msg message);

}
