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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.chat.model.ChatResponse;

import reactor.core.publisher.Flux;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */
public record StreamAdvisedResponse(
	// @formatter:off
	Flux<ChatResponse> responses, 
	Map<String, Object> adviseContext) {
	// @formatter:on

	public StreamAdvisedResponse transform(Function<Flux<ChatResponse>, Flux<ChatResponse>> transformer) {
		return new StreamAdvisedResponse(transformer.apply(this.responses), this.adviseContext());
	}

	public StreamAdvisedResponse transform(Function<Flux<ChatResponse>, Flux<ChatResponse>> transformer,
			Function<Map<String, Object>, Map<String, Object>> contextTransformer) {
		var map = new HashMap<>(this.adviseContext());
		return new StreamAdvisedResponse(transformer.apply(this.responses), contextTransformer.apply(map));
	}

}
