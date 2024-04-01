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

package org.springframework.ai.openai.api;

import org.springframework.ai.chat.history.AbstractFixedTokenCountChatHistory;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.MediaContent;
import org.springframework.util.CollectionUtils;

/**
 * @author Christian Tzolov
 */
public class OpenAiFixedTokenCountChatHistory extends AbstractFixedTokenCountChatHistory<ChatCompletionMessage> {

	public OpenAiFixedTokenCountChatHistory(int maxTokenSize) {
		super(maxTokenSize);
	}

	@Override
	public int estimateTokenCount(ChatCompletionMessage message) {
		int estimate = 0;
		if (message.rawContent() instanceof MediaContent mediaContent) {
			estimate += estimateTokenCount(mediaContent.type());
			if (mediaContent.text() != null) {
				estimate += estimateTokenCount(mediaContent.text());
			}
			if (mediaContent.imageUrl() != null) {
				estimate += estimateTokenCount(mediaContent.imageUrl().url());
				estimate += estimateTokenCount(mediaContent.imageUrl().detail());
			}
		}
		else if (message.rawContent() instanceof String text) {
			estimate += estimateTokenCount(text);
		}
		return estimate;
	}

	@Override
	public boolean isAssistantMessage(ChatCompletionMessage message) {
		return message.role() == ChatCompletionMessage.Role.ASSISTANT;
	}

	@Override
	public boolean isUserMessage(ChatCompletionMessage message) {
		return message.role() == ChatCompletionMessage.Role.USER;
	}

	@Override
	public boolean isSystemMessage(ChatCompletionMessage message) {
		return message.role() == ChatCompletionMessage.Role.SYSTEM;
	}

	@Override
	public boolean isToolCallMessage(ChatCompletionMessage message) {
		return !CollectionUtils.isEmpty(message.toolCalls());
	}

	@Override
	public boolean isToolCallResponseMessage(ChatCompletionMessage message) {
		return message.role() == ChatCompletionMessage.Role.TOOL;
	}

}
