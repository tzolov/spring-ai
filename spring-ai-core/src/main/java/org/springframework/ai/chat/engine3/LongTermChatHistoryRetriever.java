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
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.CollectionUtils;

/**
 *
 * @author Christian Tzolov
 */
public class LongTermChatHistoryRetriever implements ChatRetriever<List<Document>>	{

	private final VectorStore vectorStore;

	public LongTermChatHistoryRetriever(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	@Override
	public List<Document> retrieve(EngineRequest3 request) {

		String userMessage = request.getPrompt().getInstructions().stream()
			.filter(m -> m.getMessageType() == MessageType.USER)
			.map(m -> m.getContent())
			.collect(Collectors.joining(System.lineSeparator()));

		var vectorSearchRequest = SearchRequest
			.query(userMessage)
			.withTopK(5)
			.withFilterExpression("type == 'long-term-chat-history' && sessionId == '" + request.getConversationId() + "'");

		List<Document> vectorStoreResponse = this.vectorStore.similaritySearch(vectorSearchRequest);

		return (!CollectionUtils.isEmpty(vectorStoreResponse)) ? vectorStoreResponse : List.of();

	}
}
