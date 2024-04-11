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
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.messages.Message;

/**
 *
 * @author Christian Tzolov
 */
public class InMemoryChatHistory2 implements ChatHistory2 {

	protected final ConcurrentHashMap<String, List<Message>> chatHistory;

	public InMemoryChatHistory2() {
		this.chatHistory = new ConcurrentHashMap<>();
	}

	@Override
	public void add(String sessionId, List<Message> messages) {
		this.chatHistory.putIfAbsent(sessionId, new ArrayList<>());
		this.chatHistory.get(sessionId).addAll(messages);
	}

	@Override
	public List<Message> get(String sessionId) {
		return this.chatHistory.get(sessionId);
	}

	@Override
	public void clear(String sessionId) {
		this.chatHistory.remove(sessionId);
	}

}
