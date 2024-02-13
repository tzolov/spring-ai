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
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.preview.GenerativeModel;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatOptions;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MediaData;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

/**
 *
 * @author Christian Tzolov
 */
public class VertexAiGeminiChatClient implements ChatClient, DisposableBean {

	private final VertexAI vertexAI;

	private final VertexAiGeminiChatOptions defaultOptions;

	private final GenerationConfig generationConfig;

	private GenerativeModel generativeModel;

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


		String vertexContext = prompt.getInstructions()
				.stream()
				.filter(m -> m.getMessageType() == MessageType.SYSTEM)
				.map(m -> m.getContent())
				.collect(Collectors.joining("\n"));

		List<Content> contents = prompt.getInstructions()
				.stream()
				.filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
				.map(message -> Content.newBuilder()
						.setRole(messageTypeMapping(message.getMessageType()))
						.addAllParts(PartsMapper.map(message, vertexContext))
						.build())
				.toList();

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

		try {
			GenerateContentResponse result = generativeModel.generateContent(contents, generationConfig);

			List<Generation> generations = result.getCandidatesList().stream()
					.map(candidate -> candidate.getContent().getPartsList())
					.flatMap(List::stream)
					.map(Part::getText)
					.map(t -> new Generation(t.toString()))
					.toList();

			return new ChatResponse(generations);
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to generate content", e);
		}
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

	private static String messageTypeMapping(MessageType type) {
		switch (type) {
		case USER:
			return "user";
		case ASSISTANT:
			return "model";
		}
		throw new IllegalArgumentException("Unsupported message type: " + type);
	}

	static class PartsMapper {

		private static final Map<String, String> EXTENSION_TO_MIME_TYPE = new HashMap<>();

		static {
			EXTENSION_TO_MIME_TYPE.put("avif", "image/avif");
			EXTENSION_TO_MIME_TYPE.put("bmp", "image/bmp");
			EXTENSION_TO_MIME_TYPE.put("gif", "image/gif");
			EXTENSION_TO_MIME_TYPE.put("jpe", "image/jpeg");
			EXTENSION_TO_MIME_TYPE.put("jpeg", "image/jpeg");
			EXTENSION_TO_MIME_TYPE.put("jpg", "image/jpeg");
			EXTENSION_TO_MIME_TYPE.put("png", "image/png");
			EXTENSION_TO_MIME_TYPE.put("svg", "image/svg+xml");
			EXTENSION_TO_MIME_TYPE.put("tif", "image/tiff");
			EXTENSION_TO_MIME_TYPE.put("tiff", "image/tiff");
			EXTENSION_TO_MIME_TYPE.put("webp", "image/webp");
		}

		static List<Part> map(Message message, String context) {
			if (message instanceof UserMessage) {
				return ((UserMessage) message).getMedia().stream()
						.map(part -> PartsMapper.convertToParts(part, context))
						.toList();
			}
			else if (message instanceof AssistantMessage) {
				return Collections.singletonList(Part.newBuilder()
						.setText(((AssistantMessage) message).getContent())
						.build());
			}
			else {
				throw new IllegalArgumentException("Gemini doesn't support message type: " + message.getClass());
			}
		}

		// private static Part map(Content content) {
		// if (content instanceof TextContent) {
		// return map((TextContent) content);
		// }
		// else if (content instanceof ImageContent) {
		// return map((ImageContent) content);
		// }
		// else {
		// throw illegalArgument("Unknown content type: " + content);
		// }
		// }

		private static Part convertToParts(MediaData contentPart, String context) {
			String data = (contentPart.getData() == null) ? "null" : contentPart.getData().toString();

			return Part.newBuilder()
					.setText(context + "\n" + data)
					.build();
		}
		// private static Part map(TextContent content) {
		// return Part.newBuilder()
		// .setText(content.text())
		// .build();
		// }

		// static Part map(ImageContent content) {
		// Image image = content.image();
		// if (image.url() != null) {
		// String mimeType = getOrDefault(image.mimeType(), () -> detectMimeType(image.url()));
		// if (image.url().getScheme().equals("gs")) {
		// return fromMimeTypeAndData(mimeType, image.url());
		// }
		// else {
		// return fromMimeTypeAndData(mimeType, readBytes(image.url().toString()));
		// }
		// }
		// return fromMimeTypeAndData(image.mimeType(), Base64.getDecoder().decode(image.base64Data()));
		// }

		static String detectMimeType(URI url) {
			String[] pathParts = url.getPath().split("\\.");
			if (pathParts.length > 1) {
				String extension = pathParts[pathParts.length - 1].toLowerCase();
				String mimeType = EXTENSION_TO_MIME_TYPE.get(extension);
				if (mimeType != null) {
					return mimeType;
				}
			}
			throw new IllegalArgumentException(
					String.format("Unable to detect the MIME type of '%s'. Please provide it explicitly.", url));
		}
	}

	@Override
	public void destroy() throws Exception {
		if (this.vertexAI != null) {
			this.vertexAI.close();
		}
	}
}
