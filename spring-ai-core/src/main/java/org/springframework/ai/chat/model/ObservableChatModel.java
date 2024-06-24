/*
* Copyright 2024 - 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.springframework.ai.chat.model;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class ObservableChatModel implements ChatModel {

	private final ChatModel chatModel;

	private final ObservationRegistry observationRegistry;

	public ObservableChatModel(ChatModel chatModel, ObservationRegistry observationRegistry) {
		this.chatModel = chatModel;
		this.observationRegistry = observationRegistry;
	}

	@Override
	public ChatResponse call(Prompt prompt) {

		Observation observation = Observation.createNotStarted("chatModel", this.observationRegistry).start();

		try {
			return this.chatModel.call(prompt);
		}
		catch (Exception e) {
			if (observation != null) {
				observation.error(e);
			}
			throw new RuntimeException(e);
		}
		finally {
			if (observation != null) {
				observation.stop();
			}
		}
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		Observation observation = Observation.createNotStarted("streamChatModel", this.observationRegistry).start();

		return this.chatModel.stream(prompt).doOnSubscribe(subscription -> {
		}).doOnNext(chatResponse -> {
		}).doOnComplete(() -> {
			if (observation != null) {
				observation.stop();
			}
		}).doOnError(e -> {
			if (observation != null) {
				observation.error(e);
			}
		});
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return this.chatModel.getDefaultOptions();
	}

}
