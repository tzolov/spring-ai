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

import java.util.regex.Pattern;

import org.springframework.ai.chat.model.observation.ChatModelObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.lang.Nullable;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class DefaultChatModelObservationConvention implements ChatModelObservationConvention {

	private static final String DEFAULT_NAME = "spring.ai.chat.model";

	private static final Pattern PATTERN_BEFORE_PATH = Pattern.compile("^https?://[^/]+/");

	private static final KeyValue MODEL_NONE = KeyValue.of(LowCardinalityKeyNames.MODEL, KeyValue.NONE_VALUE);

	private static final KeyValue STATUS_NONE = KeyValue.of(LowCardinalityKeyNames.STATUS, KeyValue.NONE_VALUE);

	private static final KeyValue URI_NONE = KeyValue.of(LowCardinalityKeyNames.URI, KeyValue.NONE_VALUE);

	private final String name;

	public DefaultChatModelObservationConvention() {
		this(DEFAULT_NAME);
	}

	public DefaultChatModelObservationConvention(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	@Nullable
	public String getContextualName(ChatModelObservationContext context) {
		Prompt request = context.getPrompt();
		String model = (request.getOptions().getModel() != null) ? request.getOptions().getModel() : "";
		return (request != null ? "SpringAi" + context.getChatModelName() + " " + model : null);
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(ChatModelObservationContext context) {
		// Make sure that KeyValues entries are already sorted by name for better
		// performance
		return KeyValues.of(model(context), status(context), uri(context));
	}

	protected KeyValue model(ChatModelObservationContext context) {
		if (context.getPrompt() != null && context.getPrompt().getOptions() != null
				&& context.getPrompt().getOptions().getModel() != null) {
			return KeyValue.of(LowCardinalityKeyNames.MODEL, context.getPrompt().getOptions().getModel());
		}
		else {
			return MODEL_NONE;
		}
	}

	protected KeyValue status(ChatModelObservationContext context) {

		if (context.getChatResponse() != null && context.getChatResponse().getMetadata() != null) {
			String finishReason = context.getChatResponse().getResult().getMetadata().getFinishReason();
			return KeyValue.of(LowCardinalityKeyNames.STATUS, finishReason);
		}
		else {
			return STATUS_NONE;
		}
	}

	protected KeyValue uri(ChatModelObservationContext context) {
		// if (context.getUriTemplate() != null) {
		// return KeyValue.of(LowCardinalityKeyNames.URI,
		// extractPath(context.getUriTemplate()));
		// }
		return URI_NONE;
	}

}
