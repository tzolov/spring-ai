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
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.ra.PromptContext;
import org.springframework.ai.chat.ra.tokenizer.TokenCountEstimator;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 *
 * @author Christian Tzolov
 */
public class DocumentTextChatHistoryTransformer extends AbstractSlidingWindowDatumTransformer<Document> {

	public static final String LONG_TERM_HISTORY_PROMPT = """
			Use the long term conversation history from the LONG-TERM-HISTORY section to provide accurate answers.

			LONG-TERM-HISTORY:
			{history}
				""";

	private final VectorStore vectorStore;

	private final int searchTopK;

	public DocumentTextChatHistoryTransformer(TokenCountEstimator tokenCountEstimator, int maxTokenSize,
			VectorStore vectorStore, int searchTopK) {
		super(tokenCountEstimator, maxTokenSize);

		this.vectorStore = vectorStore;
		this.searchTopK = searchTopK;
	}

	@Override
	protected List<Document> doGetDatum(PromptContext promptContext) {

		var searchRequest = SearchRequest.query(promptContext.gePrompt().getInstructions().stream()
				.filter(m -> m.getMessageType() != MessageType.SYSTEM)
				.map(Message::getContent)
				.collect(Collectors.joining(System.lineSeparator())))
				.withFilterExpression("conversationId == " + promptContext.getConversationId())
				.withTopK(this.searchTopK);

		return this.vectorStore.similaritySearch(searchRequest);
	}

	@Override
	protected int doEstimateTokenCount(Document datum) {
		return this.tokenCountEstimator.estimate(datum);
	}

	@Override
	protected int doEstimateTokenCount(List<Document> datum) {
		return this.tokenCountEstimator.estimateDocuments(datum);
	}

	@Override
	public PromptContext transform(PromptContext promptContext) {

		PromptContext purgedPromptContext = super.transform(promptContext);

		var originalPrompt = purgedPromptContext.gePrompt();

		List<Message> systemMessages = (originalPrompt.getInstructions() != null) ? originalPrompt.getInstructions()
				.stream()
				.filter(m -> m.getMessageType() == MessageType.SYSTEM)
				.toList() : List.of();

		List<Message> nonSystemMessages = (originalPrompt.getInstructions() != null) ? originalPrompt.getInstructions()
				.stream()
				.filter(m -> m.getMessageType() != MessageType.SYSTEM)
				.toList() : List.of();

		SystemMessage originalSystemMessage = (!systemMessages.isEmpty()) ? (SystemMessage) systemMessages.get(0)
				: new SystemMessage("");

		String longTermHistoryContext = purgedPromptContext.getDocumentList().stream()
				.map(doc -> doc.getMetadata().get("historyMessageType") + ": " + doc.getContent())
				.collect(Collectors.joining(System.lineSeparator()));

		SystemMessage newSystemMessage = new SystemMessage(originalSystemMessage.getContent() + System.lineSeparator()
				+ LONG_TERM_HISTORY_PROMPT.replace("{history}", longTermHistoryContext));

		List<Message> newPromptMessages = new ArrayList<>();
		newPromptMessages.add(newSystemMessage);
		newPromptMessages.addAll(nonSystemMessages);

		Prompt newPrompt = new Prompt(newPromptMessages, (ChatOptions) originalPrompt.getOptions());

		return PromptContext.builder()
				.withPrompt(newPrompt)
				.withConversationId(purgedPromptContext.getConversationId())
				.withMessageList(purgedPromptContext.getMessageList())
				.build();
	}
}
