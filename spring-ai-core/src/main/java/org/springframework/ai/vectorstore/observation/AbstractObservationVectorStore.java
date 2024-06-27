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

import java.util.List;
import java.util.Optional;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.lang.Nullable;

import io.micrometer.observation.ObservationRegistry;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */
public abstract class AbstractObservationVectorStore implements VectorStore {

	private static final VectorStoreObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultVectorStoreObservationConvention();

	private final ObservationRegistry observationRegistry;

	@Nullable
	private final VectorStoreObservationConvention observationConvention;

	public AbstractObservationVectorStore() {
		this(ObservationRegistry.NOOP, null);
	}

	public AbstractObservationVectorStore(ObservationRegistry observationRegistry) {
		this(observationRegistry, null);
	}

	public AbstractObservationVectorStore(ObservationRegistry observationRegistry,
			VectorStoreObservationConvention observationConvention) {
		this.observationRegistry = observationRegistry;
		this.observationConvention = observationConvention;
	}

	@Override
	public void add(List<Document> documents) {

		VectorStoreObservationContext observationContext = new VectorStoreObservationContext();
		observationContext.setModelClassName(this.getClass().getSimpleName());

		VectorStoreObservationDocumentation.AI_VECTOR_STORE
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					observationRegistry)
			.observe(() -> this.doAdd(documents));
	}

	@Override
	public Optional<Boolean> delete(List<String> idList) {
		VectorStoreObservationContext observationContext = new VectorStoreObservationContext();
		observationContext.setModelClassName(this.getClass().getSimpleName());

		return VectorStoreObservationDocumentation.AI_VECTOR_STORE
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					observationRegistry)
			.observe(() -> this.doDelete(idList));
	}

	@Override
	public List<Document> similaritySearch(SearchRequest request) {
		VectorStoreObservationContext observationContext = new VectorStoreObservationContext();
		observationContext.setModelClassName(this.getClass().getSimpleName());

		return VectorStoreObservationDocumentation.AI_VECTOR_STORE
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					observationRegistry)
			.observe(() -> this.doSimilaritySearch(request));
	}

	abstract public void doAdd(List<Document> documents);

	abstract public Optional<Boolean> doDelete(List<String> idList);

	abstract public List<Document> doSimilaritySearch(SearchRequest request);

}
