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
package org.springframework.ai.chat.client.advisor.api;

import java.util.Map;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.Assert;

import reactor.core.publisher.Flux;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public record AdvisedResponse(ChatResponse response, Flux<ChatResponse> responseFlux,
		Map<String, Object> adviseContext) {

	public AdvisedResponse(ChatResponse response, Map<String, Object> adviseContext) {
		this(response, null, adviseContext);
	}

	public AdvisedResponse(Flux<ChatResponse> responseFlux, Map<String, Object> adviseContext) {
		this(null, responseFlux, adviseContext);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private ChatResponse response;

		private Flux<ChatResponse> responseFlux;

		private Map<String, Object> adviseContext;

		public Builder() {
		}

		public static Builder from(AdvisedResponse advisedResponse) {
			return new Builder().withResponse(advisedResponse.response)
				.withResponseFlux(advisedResponse.responseFlux)
				.withAdviseContext(advisedResponse.adviseContext);
		}

		public Builder withResponse(ChatResponse response) {
			Assert.notNull(response, "the response must be non-null");
			Assert.isNull(this.responseFlux, "responseFlux must be null when response is set");

			this.response = response;
			return this;
		}

		public Builder withResponseFlux(Flux<ChatResponse> responseFlux) {
			Assert.notNull(responseFlux, "the responseFlux must be non-null");
			Assert.isNull(this.response, "response must be null when responseFlux is set");

			this.responseFlux = responseFlux;
			return this;
		}

		public Builder withAdviseContext(Map<String, Object> adviseContext) {
			Assert.notNull(adviseContext, "the adviseContext must be non-null");
			this.adviseContext = adviseContext;
			return this;
		}

		public AdvisedResponse build() {
			Assert.notNull(this.adviseContext, "the adviseContext must be non-null");
			Assert.isTrue(this.response != null || this.responseFlux != null,
					"either response or responseFlux must be non-null");
			Assert.isTrue(this.response == null || this.responseFlux == null,
					"either response or responseFlux must be null");
			return new AdvisedResponse(response, responseFlux, adviseContext);
		}

	}

}
