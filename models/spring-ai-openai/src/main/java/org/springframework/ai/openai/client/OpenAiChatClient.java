/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.openai.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.metadata.ChoiceMetadata;
import org.springframework.ai.metadata.RateLimit;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiOptions;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.OpenAiApi.OpenAiApiException;
import org.springframework.ai.openai.metadata.OpenAiGenerationMetadata;
import org.springframework.ai.openai.metadata.support.OpenAiResponseHeaderExtractor;
import org.springframework.ai.prompt.Prompt;
import org.springframework.ai.prompt.PromptOptions;
import org.springframework.ai.prompt.messages.Message;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;

/**
 * {@link ChatClient} implementation for {@literal OpenAI} backed by {@link OpenAiApi}.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Ueibin Kim
 * @author John Blum
 * @author Josh Long
 * @author Jemin Huh
 * @see ChatClient
 * @see StreamingChatClient
 * @see OpenAiApi
 */
public class OpenAiChatClient implements ChatClient, StreamingChatClient {

	private OpenAiOptions clientOptions;

	private Double temperature = 0.7;

	private String model = "gpt-3.5-turbo";

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public final RetryTemplate retryTemplate = RetryTemplate.builder()
		.maxAttempts(10)
		.retryOn(OpenAiApiException.class)
		.exponentialBackoff(Duration.ofMillis(2000), 5, Duration.ofMillis(3 * 60000))
		.build();

	private final OpenAiApi openAiApi;

	public OpenAiChatClient(OpenAiApi openAiApi) {
		Assert.notNull(openAiApi, "OpenAiApi must not be null");
		this.openAiApi = openAiApi;
	}

	public OpenAiOptions getClientOptions() {
		return clientOptions;
	}

	public void setClientOptions(OpenAiOptions clientOptions) {
		this.clientOptions = clientOptions;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	@Override
	public ChatResponse generate(Prompt prompt) {

		return this.retryTemplate.execute(ctx -> {

			OpenAiOptions clientOpenAiOptionsCopy = new OpenAiOptions(this.clientOptions);

			if (prompt.getOptions() != null) {
				OpenAiOptions requestOpenAiOptions = null;
				if (prompt.getOptions().getName().equals("OpenAi")) {
					requestOpenAiOptions = (OpenAiOptions) prompt.getOptions();
					OpenAiOptions.merge(clientOpenAiOptionsCopy, requestOpenAiOptions);
				} if (prompt.getOptions().getName().equals("Generic")) {
					requestOpenAiOptions = null; // convert generic to OpenAiOptions
					OpenAiOptions.merge(clientOpenAiOptionsCopy, requestOpenAiOptions);
				}
			}

			List<Message> messages = prompt.getMessages();

			List<ChatCompletionMessage> chatCompletionMessages = messages.stream()
				.map(m -> new ChatCompletionMessage(m.getContent(),
						ChatCompletionMessage.Role.valueOf(m.getMessageType().getValue())))
				.toList();

			ChatCompletionRequest request = new OpenAiApi.ChatCompletionRequest(chatCompletionMessages, clientOpenAiOptionsCopy);

			ResponseEntity<ChatCompletion> completionEntity = this.openAiApi.chatCompletionEntity(request);

			// ResponseEntity<ChatCompletion> completionEntity = this.openAiApi
			// 	.chatCompletionEntity(new OpenAiApi.ChatCompletionRequest(chatCompletionMessages, this.model,
			// 			this.temperature.floatValue()));

			var chatCompletion = completionEntity.getBody();
			if (chatCompletion == null) {
				logger.warn("No chat completion returned for request: {}", chatCompletionMessages);
				return new ChatResponse(List.of());
			}

			RateLimit rateLimits = OpenAiResponseHeaderExtractor.extractAiResponseHeaders(completionEntity);

			List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
				return new Generation(choice.message().content(), Map.of("role", choice.message().role().name()))
					.withChoiceMetadata(ChoiceMetadata.from(choice.finishReason().name(), null));
			}).toList();

			return new ChatResponse(generations,
					OpenAiGenerationMetadata.from(completionEntity.getBody()).withRateLimit(rateLimits));
		});
	}

	@Override
	public Flux<ChatResponse> generateStream(Prompt prompt) {
		return this.retryTemplate.execute(ctx -> {
			List<Message> messages = prompt.getMessages();

			List<ChatCompletionMessage> chatCompletionMessages = messages.stream()
				.map(m -> new ChatCompletionMessage(m.getContent(),
						ChatCompletionMessage.Role.valueOf(m.getMessageType().getValue())))
				.toList();

			Flux<OpenAiApi.ChatCompletionChunk> completionChunks = this.openAiApi
				.chatCompletionStream(new OpenAiApi.ChatCompletionRequest(chatCompletionMessages, this.model,
						this.temperature.floatValue(), true));

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			// An alternative implementation that returns Flux<Generation> instead of
			// Flux<ChatResponse>.
			// Flux<Generation> generationFlux = completionChunks.map(chunk -> {
			// String chunkId = chunk.id();
			// return chunk.choices().stream()
			// .map(choice -> {
			// if (choice.delta().role() != null) {
			// roleMap.putIfAbsent(chunkId, choice.delta().role().name());
			// }
			// return new Generation(choice.delta().content(),
			// Map.of("role", roleMap.get(chunkId)));
			// })
			// .toList();
			// }).flatMapIterable(generations -> generations);
			// return generationFlux;

			return completionChunks.map(chunk -> {
				String chunkId = chunk.id();
				List<Generation> generations = chunk.choices().stream().map(choice -> {
					if (choice.delta().role() != null) {
						roleMap.putIfAbsent(chunkId, choice.delta().role().name());
					}
					var generation = new Generation(choice.delta().content(), Map.of("role", roleMap.get(chunkId)));
					if (choice.finishReason() != null) {
						generation = generation
							.withChoiceMetadata(ChoiceMetadata.from(choice.finishReason().name(), null));
					}
					return generation;
				}).toList();
				return new ChatResponse(generations);
			});
		});
	}

}
