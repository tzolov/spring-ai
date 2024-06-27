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
package org.springframework.ai.vectorstore.observation;

import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.lang.Nullable;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class DefaultVectorStoreObservationConvention implements VectorStoreObservationConvention {

	private static final String DEFAULT_NAME = "spring.ai.vector.store";

	private static final KeyValue STATUS_NONE = KeyValue.of(LowCardinalityKeyNames.STATUS, KeyValue.NONE_VALUE);

	private static final KeyValue DIMENSIONS_NONE = KeyValue.of(LowCardinalityKeyNames.DIMENSIONS, KeyValue.NONE_VALUE);

	private final String name;

	public DefaultVectorStoreObservationConvention() {
		this(DEFAULT_NAME);
	}

	public DefaultVectorStoreObservationConvention(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	@Nullable
	public String getContextualName(VectorStoreObservationContext context) {
		return (context.getModelClassName() != null ? "SpringAi" + context.getModelClassName() : null);
	}

	@Override
	public KeyValues getLowCardinalityKeyValues(VectorStoreObservationContext context) {
		// Make sure that KeyValues entries are already sorted by name for better
		// performance
		return KeyValues.of(status(context), dimmensions(context));
	}

	protected KeyValue status(VectorStoreObservationContext context) {
		return STATUS_NONE;
	}

	protected KeyValue dimmensions(VectorStoreObservationContext context) {
		if (context.getDimenssions() > 0) {
			return KeyValue.of(LowCardinalityKeyNames.DIMENSIONS, "" + context.getDimenssions());
		}
		return DIMENSIONS_NONE;
	}

}
