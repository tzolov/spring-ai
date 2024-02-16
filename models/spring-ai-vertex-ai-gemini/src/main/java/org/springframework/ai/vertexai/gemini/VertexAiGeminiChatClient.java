/*
 * Copyright 2024-2024 the original author or authors.
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

package org.springframework.ai.vertexai.gemini;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.preview.GenerativeModel;
import com.google.cloud.vertexai.generativeai.preview.PartMaker;
import com.google.cloud.vertexai.generativeai.preview.ResponseStream;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatOptions;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.vertexai.gemini.metadata.VertexAiChatResponseMetadata;
import org.springframework.ai.vertexai.gemini.metadata.VertexAiUsage;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * @author Christian Tzolov
 * @since 0.8.1
 */
public class VertexAiGeminiChatClient implements ChatClient, StreamingChatClient, DisposableBean {

	private final VertexAI vertexAI;

	private final VertexAiGeminiChatOptions defaultOptions;

	private final GenerationConfig generationConfig;

	private GenerativeModel generativeModel;

	public enum GeminiMessageType {

		USER("user"),

		MODEL("model");

		GeminiMessageType(String value) {
			this.value = value;
		}

		public final String value;

		public String getValue() {
			return this.value;
		}

	}

	public VertexAiGeminiChatClient(VertexAI vertexAI) {
		this(vertexAI, VertexAiGeminiChatOptions.builder().build());
	}

	public VertexAiGeminiChatClient(VertexAI vertexAI, VertexAiGeminiChatOptions options) {
		this.vertexAI = vertexAI;
		this.defaultOptions = options;
		this.generationConfig = toGenerationConfig(options);
		this.generativeModel = new GenerativeModel(options.getModelName(), vertexAI);
	}

	// https://cloud.google.com/vertex-ai/docs/generative-ai/model-reference/gemini
	@Override
	public ChatResponse call(Prompt prompt) {

		try {
			var geminiRequest = toGeminiRequest(prompt);

			GenerateContentResponse response = geminiRequest.model.generateContent(geminiRequest.contents,
					geminiRequest.config);

			List<Generation> generations = response.getCandidatesList()
				.stream()
				.map(candidate -> candidate.getContent().getPartsList())
				.flatMap(List::stream)
				.map(Part::getText)
				.map(t -> new Generation(t.toString()))
				.toList();

			return new ChatResponse(generations, toChatResponseMetadata(response));
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to generate content", e);
		}
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		try {

			var request = toGeminiRequest(prompt);

			ResponseStream<GenerateContentResponse> responseStream = request.model
				.generateContentStream(request.contents, request.config);

			return Flux.fromStream(responseStream.stream()).map(response -> {
				List<Generation> generations = response.getCandidatesList()
					.stream()
					.map(candidate -> candidate.getContent().getPartsList())
					.flatMap(List::stream)
					.map(Part::getText)
					.map(t -> new Generation(t.toString()))
					.toList();

				return new ChatResponse(generations, toChatResponseMetadata(response));
			});
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to generate content", e);
		}
	}

	private VertexAiChatResponseMetadata toChatResponseMetadata(GenerateContentResponse response) {
		return new VertexAiChatResponseMetadata(new VertexAiUsage(response.getUsageMetadata()));
	}

	private record GeminiRequest(List<Content> contents, GenerativeModel model, GenerationConfig config) {
	}

	private GeminiRequest toGeminiRequest(Prompt prompt) {
		GenerationConfig generationConfig = this.generationConfig;
		GenerativeModel generativeModel = this.generativeModel;

		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ChatOptions runtimeOptions) {
				VertexAiGeminiChatOptions updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(runtimeOptions,
						ChatOptions.class, VertexAiGeminiChatOptions.class);

				if (this.defaultOptions != null) {
					updatedRuntimeOptions = ModelOptionsUtils.merge(updatedRuntimeOptions, this.defaultOptions,
							VertexAiGeminiChatOptions.class);

					if (StringUtils.hasText(updatedRuntimeOptions.getModelName())
							&& !updatedRuntimeOptions.getModelName().equals(this.defaultOptions.getModelName())) {
						generativeModel = new GenerativeModel(updatedRuntimeOptions.getModelName(), vertexAI);
					}
				}

				generationConfig = toGenerationConfig(updatedRuntimeOptions);

			}
			else {
				throw new IllegalArgumentException("Prompt options are not of type ChatOptions: "
						+ prompt.getOptions().getClass().getSimpleName());
			}
		}

		return new GeminiRequest(toGeminiContent(prompt), generativeModel, generationConfig);
	}

	private GenerationConfig toGenerationConfig(VertexAiGeminiChatOptions options) {

		GenerationConfig.Builder generationConfigBuilder = GenerationConfig.newBuilder();

		if (options.getTemperature() != null) {
			generationConfigBuilder.setTemperature(options.getTemperature());
		}
		if (options.getMaxOutputTokens() != null) {
			generationConfigBuilder.setMaxOutputTokens(options.getMaxOutputTokens());
		}
		if (options.getTopK() != null) {
			generationConfigBuilder.setTopK(options.getTopK());
		}
		if (options.getTopP() != null) {
			generationConfigBuilder.setTopP(options.getTopP());
		}
		if (options.getEcho() != null) {
			generationConfigBuilder.setEcho(options.getEcho());
		}
		if (options.getCandidateCount() != null) {
			generationConfigBuilder.setCandidateCount(options.getCandidateCount());
		}
		if (options.getLogprobs() != null) {
			generationConfigBuilder.setLogprobs(options.getLogprobs());
		}
		if (options.getStopSequences() != null) {
			generationConfigBuilder.addAllStopSequences(options.getStopSequences());
		}
		if (options.getFrequencyPenalty() != null) {
			generationConfigBuilder.setFrequencyPenalty(options.getFrequencyPenalty());
		}
		if (options.getPresencePenalty() != null) {
			generationConfigBuilder.setPresencePenalty(options.getPresencePenalty());
		}

		return generationConfigBuilder.build();
	}

	private List<Content> toGeminiContent(Prompt prompt) {

		String systemContext = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.SYSTEM)
			.map(m -> m.getContent())
			.collect(Collectors.joining("\n"));

		List<Content> contents = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
			.map(message -> Content.newBuilder()
				.setRole(toGeminiMessageType(message.getMessageType()).getValue())
				.addAllParts(messageToGeminiParts(message, systemContext))
				.build())
			.toList();

		return contents;
	}

	private static GeminiMessageType toGeminiMessageType(@NonNull MessageType type) {

		Assert.notNull(type, "Message type must not be null");

		switch (type) {
			case USER:
				return GeminiMessageType.USER;
			case ASSISTANT:
				return GeminiMessageType.MODEL;
			default:
				throw new IllegalArgumentException("Unsupported message type: " + type);
		}
	}

	static List<Part> messageToGeminiParts(Message message, String systemContext) {

		if (message instanceof UserMessage userMessage) {

			String messageTextContent = (userMessage.getContent() == null) ? "null" : userMessage.getContent();
			if (!StringUtils.hasText(systemContext)) {
				messageTextContent = systemContext + "\n\n" + messageTextContent;
			}
			Part textPart = Part.newBuilder().setText(messageTextContent).build();

			List<Part> parts = new ArrayList<>(List.of(textPart));

			List<Part> mediaParts = userMessage.getMediaData()
				.stream()
				.map(mediaData -> PartMaker.fromMimeTypeAndData(mediaData.getMimeType().toString(),
						mediaData.getData()))
				.toList();

			if (!CollectionUtils.isEmpty(mediaParts)) {
				parts.addAll(mediaParts);
			}

			return parts;
		}
		else if (message instanceof AssistantMessage assistantMessage) {
			return List.of(Part.newBuilder().setText(assistantMessage.getContent()).build());
		}
		else {
			throw new IllegalArgumentException("Gemini doesn't support message type: " + message.getClass());
		}
	}

	@Override
	public void destroy() throws Exception {
		if (this.vertexAI != null) {
			this.vertexAI.close();
		}
	}

}
