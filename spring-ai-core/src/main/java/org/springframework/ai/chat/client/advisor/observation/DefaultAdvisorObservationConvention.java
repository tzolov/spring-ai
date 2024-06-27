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
package org.springframework.ai.chat.client.advisor.observation;

import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.lang.Nullable;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class DefaultAdvisorObservationConvention implements AdvisorObservationConvention {

	private static final String DEFAULT_NAME = "spring.ai.advisor";

	private static final KeyValue TYPE_NONE = KeyValue.of(LowCardinalityKeyNames.TYPE, KeyValue.NONE_VALUE);

	private static final KeyValue STATUS_NONE = KeyValue.of(LowCardinalityKeyNames.STATUS, KeyValue.NONE_VALUE);

	private final String name;

	public DefaultAdvisorObservationConvention() {
		this(DEFAULT_NAME);
	}

	public DefaultAdvisorObservationConvention(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	@Nullable
	public String getContextualName(AdvisorObservationContext context) {
		return (context.getModelClassName() != null ? "SpringAi" + context.getModelClassName() : "UnknownAdvisor");
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(AdvisorObservationContext context) {
		return KeyValues.of(type(context), status(context));
	}

	protected KeyValue type(AdvisorObservationContext context) {
		if (context.getType() != null) {
			return KeyValue.of(LowCardinalityKeyNames.TYPE, context.getType().name());
		}
		return TYPE_NONE;
	}

	protected KeyValue status(AdvisorObservationContext context) {
		return STATUS_NONE;
	}

}
