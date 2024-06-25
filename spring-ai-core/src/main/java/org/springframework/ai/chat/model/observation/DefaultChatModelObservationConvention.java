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

import org.springframework.ai.chat.model.observation.ChatModelObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.client.observation.ClientRequestObservationContext;

import io.micrometer.common.KeyValue;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class DefaultChatModelObservationConvention implements ChatModelObservationConvention {

	private static final String DEFAULT_NAME = "spring.ai.chat.model";

	private static final KeyValue MODEL_NONE = KeyValue.of(LowCardinalityKeyNames.MODEL, KeyValue.NONE_VALUE);

	private static final KeyValue STATUS_NONE = KeyValue.of(LowCardinalityKeyNames.STATUS, KeyValue.NONE_VALUE);

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

	protected KeyValue method(ChatModelObservationContext context) {
		if (context.getPrompt() != null && context.getPrompt().getOptions() != null) {
			return KeyValue.of(LowCardinalityKeyNames.MODEL, context.getPrompt().getOptions().getModel());
		}
		else {
			return MODEL_NONE;
		}
	}

	protected KeyValue status(ChatModelObservationContext context) {
		if (context.getChatResponse() != null && context.getChatResponse().getMetadata() != null) {
			return KeyValue.of(LowCardinalityKeyNames.STATUS, context.getPrompt().getOptions().getModel());
		}
		else {
			return STATUS_NONE;
		}
	}

}
