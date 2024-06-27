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
package org.springframework.ai.chat.client.observation;

import org.springframework.ai.chat.client.observation.ChatClientObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.lang.Nullable;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class DefaultChatClientObservationConvention implements ChatClientObservationConvention {

	private static final String DEFAULT_NAME = "spring.ai.chat.client";

	private static final KeyValue MODEL_NONE = KeyValue.of(LowCardinalityKeyNames.MODEL, KeyValue.NONE_VALUE);

	private static final KeyValue STATUS_NONE = KeyValue.of(LowCardinalityKeyNames.STATUS, KeyValue.NONE_VALUE);

	private final String name;

	public DefaultChatClientObservationConvention() {
		this(DEFAULT_NAME);
	}

	public DefaultChatClientObservationConvention(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	@Nullable
	public String getContextualName(ChatClientObservationContext context) {
		return (context.getModelClassName() != null ? "SpringAi" + context.getModelClassName() : null);
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(ChatClientObservationContext context) {
		return KeyValues.of(model(context), stream(context), status(context));
	}

	protected KeyValue model(ChatClientObservationContext context) {
		if (context.getModelClassName() != null) {
			return KeyValue.of(LowCardinalityKeyNames.MODEL, context.getModelClassName());
		}
		return MODEL_NONE;
	}

	protected KeyValue stream(ChatClientObservationContext context) {
		return KeyValue.of(LowCardinalityKeyNames.STREAM, "" + context.isStream());
	}

	protected KeyValue status(ChatClientObservationContext context) {
		return STATUS_NONE;
	}

}
