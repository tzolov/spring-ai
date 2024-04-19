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

import org.springframework.ai.chat.ra.PromptContext;
import org.springframework.ai.chat.ra.PromptTransformer;
import org.springframework.ai.chat.ra.tokenizer.TokenCountEstimator;

/**
 * Returns a new list of datum (e.g list of messages of list of documents) that is a subset of the input list of
 * messages and complies with the max token size constraint. The token estimator is used to estimate the token count of
 * the datum.
 *
 * @author Christian Tzolov
 */
public abstract class AbstractSlidingWindowDatumTransformer<T> implements PromptTransformer {

	protected final TokenCountEstimator tokenCountEstimator;

	protected final int maxTokenSize;

	public AbstractSlidingWindowDatumTransformer(TokenCountEstimator tokenCountEstimator, int maxTokenSize) {
		this.tokenCountEstimator = tokenCountEstimator;
		this.maxTokenSize = maxTokenSize;
	}

	abstract protected List<T> doGetDatum(PromptContext promptContext);

	abstract protected int doEstimateTokenCount(T datum);

	abstract protected int doEstimateTokenCount(List<T> datum);

	@Override
	public PromptContext transform(PromptContext promptContext) {

		List<T> datum = this.doGetDatum(promptContext);

		// int totalSize = this.tokenCountEstimator.estimate(nonSystemChatMessages) -
		// retrievalRequest.getTokenRunningTotal();
		// int totalSize = this.tokenCountEstimator.estimate(messageHistory);
		int totalSize = this.doEstimateTokenCount(datum);

		if (totalSize <= this.maxTokenSize) {
			return promptContext;
		}

		List<T> newSessionMessages = this.purgeExcess(datum, totalSize);

		return PromptContext.builder()
				.withPrompt(promptContext.gePrompt())
				.withConversationId(promptContext.getConversationId())
				.withDatum(newSessionMessages)
				.build();
	}

	protected List<T> purgeExcess(List<T> datum, int totalSize) {

		int index = 0;
		List<T> newList = new ArrayList<>();

		while (index < datum.size() && totalSize > this.maxTokenSize) {
			T oldDatum = datum.get(index++);
			// int oldMessageTokenSize = this.tokenCountEstimator.estimate(oldDatum);
			int oldMessageTokenSize = this.doEstimateTokenCount(oldDatum);
			totalSize = totalSize - oldMessageTokenSize;
		}

		if (index >= datum.size()) {
			return List.of();
		}

		// add the rest of the messages.
		newList.addAll(datum.subList(index, datum.size()));

		return newList;
	}
}
