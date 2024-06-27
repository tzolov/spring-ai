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
package org.springframework.ai.embedding.observation;

import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import io.micrometer.observation.Observation;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class EmbeddingModelObservationContext extends Observation.Context {

	private EmbeddingRequest request;

	private EmbeddingResponse response;

	private String modelClassName;

	private int dimenssions;

	public void setModelClassName(String chatModelName) {
		this.modelClassName = chatModelName;
	}

	public String getModelClassName() {
		return this.modelClassName;
	}

	public EmbeddingRequest getRequest() {
		return this.request;
	}

	public void setRequest(EmbeddingRequest request) {
		this.request = request;
	}

	public EmbeddingResponse getResponse() {
		return this.response;
	}

	public void setResponse(EmbeddingResponse response) {
		this.response = response;
	}

	public void setDimenssions(int dimenssions) {
		this.dimenssions = dimenssions;
	}

	public int getDimenssions() {
		return this.dimenssions;
	}

}
