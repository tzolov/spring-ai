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

package org.springframework.ai.chat.ra;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.util.CollectionUtils;

/**
 *
 * @author Christian Tzolov
 */
public class PromptContext {

	private final String conversationId;

	private final Prompt prompt;

	private List<Message> messageList;

	private List<Document> documentList;

	public PromptContext(Prompt prompt, String conversationId, List<Message> messageList, List<Document> documentList) {
		this.prompt = prompt;
		this.conversationId = conversationId;
		this.messageList = messageList;
		this.documentList = documentList;
	}

	public static class Builder {

		private Prompt prompt;

		private String conversationId = "default";

		private List<Message> messageList;

		private List<Document> documentList;

		private Builder() {
		}

		public Builder withConversationId(String conversationId) {
			this.conversationId = conversationId;
			return this;
		}

		public Builder withMessageList(List<Message> messageList) {
			this.messageList = messageList;
			return this;
		}

		public Builder withDocumentList(List<Document> documentList) {
			this.documentList = documentList;
			return this;
		}

		public Builder withPrompt(Prompt prompt) {
			this.prompt = prompt;
			return this;
		}

		public Builder withDatum(List<?> datum) {
			if (!CollectionUtils.isEmpty(datum)) {
				for (Object data : datum) {

					if (data instanceof Message message) {
						this.messageList.add(message);
					}
					else if (data instanceof Document document) {
						this.documentList.add(document);
					}
					else {
						throw new IllegalArgumentException("Unsupported data type: " + datum.getClass());
					}
				}
			}
			return this;

		}

		public PromptContext build() {
			return new PromptContext(this.prompt, this.conversationId, this.messageList, this.documentList);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public void addData(Object datum) {
		if (datum instanceof Message) {
			this.messageList.add((Message) datum);
		}
		else if (datum instanceof Document) {
			this.documentList.add((Document) datum);
		}
		else {
			throw new IllegalArgumentException("Unsupported data type: " + datum.getClass());
		}
	}

	public void addData(List<Object> datum) {
		if (CollectionUtils.isEmpty(datum)) {
			return;
		}

		for (Object data : datum) {
			this.addData(data);
		}

	}

	public String getConversationId() {
		return this.conversationId;
	}

	public Prompt gePrompt() {
		return this.prompt;
	}

	public List<Message> getMessageList() {
		return this.messageList;
	}

	public List<Document> getDocumentList() {
		return this.documentList;
	}
}
