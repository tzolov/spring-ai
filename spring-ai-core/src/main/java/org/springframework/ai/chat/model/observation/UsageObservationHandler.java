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

import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Context;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.metadata.Usage;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.ObservationHandler;

/**
 * Handler for {@link ChatModel} Usage {@link Counter}.
 *
 * @author Christian Tzolov
 * @since 1.0.0
 */
public class UsageObservationHandler implements ObservationHandler<ChatModelObservationContext> {

	private final MeterRegistry meterRegistry;

	public UsageObservationHandler(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void onStop(ChatModelObservationContext context) {

		if (context.getChatResponse() != null && context.getChatResponse().getMetadata() != null
				&& context.getChatResponse().getMetadata().getUsage() != null) {

			Usage usage = context.getChatResponse().getMetadata().getUsage();

			Counter.builder(context.getName() + ".total.tokens")
				.tags(createTags(context))
				.description("The total number of tokens from both the AI request and response.")
				.register(this.meterRegistry)
				.increment(usage.getTotalTokens());

			Counter.builder(context.getName() + ".innput.tokens")
				.tags(createTags(context))
				.description("The number of tokens used in the Prompt of the AI request.")
				.register(this.meterRegistry)
				.increment(usage.getPromptTokens());

			Counter.builder(context.getName() + ".output.tokens")
				.tags(createTags(context))
				.description("The number of tokens returned in the chat generation (aka completion)")
				.register(this.meterRegistry)
				.increment(usage.getGenerationTokens());
		}
	}

	private List<Tag> createTags(Observation.Context context) {
		List<Tag> tags = new ArrayList<>();
		for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
			tags.add(Tag.of(keyValue.getKey(), keyValue.getValue()));
		}
		return tags;
	}

	@Override
	public boolean supportsContext(Context context) {
		return context instanceof ChatModelObservationContext;
	}

}
