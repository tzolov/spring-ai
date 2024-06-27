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
package org.springframework.ai.chat.model.observation;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.Nullable;

import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Scope;
import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Flux;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class ObservableChatModel implements ChatModel {

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private final ChatModel chatModel;

	private final ObservationRegistry observationRegistry;

	@Nullable
	private final ChatModelObservationConvention observationConvention;

	public ObservableChatModel(ChatModel chatModel, ObservationRegistry observationRegistry) {
		this(chatModel, observationRegistry, null);
	}

	public ObservableChatModel(ChatModel chatModel, ObservationRegistry observationRegistry,
			ChatModelObservationConvention observationConvention) {
		this.chatModel = chatModel;
		this.observationRegistry = observationRegistry;
		this.observationConvention = observationConvention;
	}

	@Override
	public ChatResponse call(Prompt prompt) {

		ChatModelObservationContext observationContext = new ChatModelObservationContext();
		observationContext.setPrompt(prompt);
		observationContext.setChatModelName(this.chatModel.getClass().getSimpleName());

		return ChatModelObservationDocumentation.AI_CHAT_MODEL
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ChatResponse chatResponse = this.chatModel.call(prompt);
				observationContext.setChatResponse(chatResponse);
				return chatResponse;
			});
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		return Flux.defer(() -> {
			ChatModelObservationContext observationContext = new ChatModelObservationContext();
			observationContext.setPrompt(prompt);

			Observation observation = ChatModelObservationDocumentation.AI_CHAT_MODEL
				.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
						this.observationRegistry)
				.start();

			try (Scope scope = observation.openScope()) {
				return this.chatModel.stream(prompt).doOnSubscribe(subscription -> {

				}).doOnNext(chatResponse -> {
					observationContext.setChatResponse(chatResponse);
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

		});
	}

	@Override
	public ChatOptions getDefaultOptions() {
		return this.chatModel.getDefaultOptions();
	}

}
