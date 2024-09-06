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

package org.springframework.ai.chat.client.advisor.api;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Advisor called after the {@link ChatModel#call(Prompt)} (or
 * {@link ChatModel#stream(Prompt)}) method call. The {@link ChatClient} maintains a chain
 * of advisors with shared advise context.
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
public interface ResponseAdvisor extends Advisor {

	/**
	 * @param response the {@link ChatResponse} data to be advised. Represents the row
	 * {@link ChatResponse} data after the {@link ChatModel#call(Prompt)} method is
	 * called.
	 * @return the advised {@link ChatResponse}.
	 */
	AdvisedResponse adviseResponse(AdvisedResponse advisedResponse);

	/**
	 * Different modes of advising the streaming responses.
	 */
	public enum StreamResponseMode {

		/**
		 * Called for each response element in the Flux. The response advisor can modify
		 * the elements before they are returned to the client.
		 */
		PER_ELEMENT,
		/**
		 * Called only on Flux elements that contain a finish reason. Usually the last
		 * element in the Flux. The response advisor can modify the elements before they
		 * are returned to the client.
		 */
		ON_FINISH_ELEMENT,
		/**
		 * Called only once after all Flux elements have been consumed. All elements are
		 * merged into a single ChatResponse element and provided to the response advisor
		 * to process. <br/>
		 * Mind that at that stage the response advisor can not longer modify the response
		 * returned to the client.
		 */
		AGGREGATE,
		/**
		 * Delegates to the stream advisor implementation.
		 */
		CUSTOM;

	}

	default StreamResponseMode getStreamResponseMode() {
		return StreamResponseMode.AGGREGATE;
	}

	/**
	 * @param fluxResponse the streaming {@link ChatResponse} data to be advised.
	 * Represents the row {@link ChatResponse} stream data after the
	 * {@link ChatModel#stream(Prompt)} method is called.
	 * @return the advised {@link ChatResponse} flux.
	 */
	default StreamAdvisedResponse adviseResponse(StreamAdvisedResponse fluxResponse) {
		return fluxResponse;
	}

}
