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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.FunctionCall;
import com.google.cloud.vertexai.api.FunctionDeclaration;
import com.google.cloud.vertexai.api.FunctionResponse;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Tool;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.PartMaker;
import com.google.cloud.vertexai.generativeai.ResponseStream;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
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
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackContext;
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

	private final static boolean IS_RUNTIME_CALL = true;

	private final VertexAI vertexAI;

	private final VertexAiGeminiChatOptions defaultOptions;

	private final GenerationConfig generationConfig;

	private GenerativeModel generativeModel;

	/**
	 * The function callback register is used to resolve the function callbacks by name.
	 */
	private Map<String, FunctionCallback> functionCallbackRegister = new ConcurrentHashMap<>();

	/**
	 * The function callback context is used to resolve the function callbacks by name
	 * from the Spring context. It is optional and usually used with Spring
	 * auto-configuration.
	 */
	private FunctionCallbackContext functionCallbackContext;

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
		this(vertexAI,
				VertexAiGeminiChatOptions.builder().withModelName("gemini-pro-vision").withTemperature(0.8f).build());
	}

	public VertexAiGeminiChatClient(VertexAI vertexAI, VertexAiGeminiChatOptions options) {
		this(vertexAI, options, null);
	}

	public VertexAiGeminiChatClient(VertexAI vertexAI, VertexAiGeminiChatOptions options,
			FunctionCallbackContext functionCallbackContext) {

		Assert.notNull(vertexAI, "VertexAI must not be null");
		Assert.notNull(options, "VertexAiGeminiChatOptions must not be null");
		Assert.notNull(options.getModelName(), "VertexAiGeminiChatOptions.modelName must not be null");

		this.vertexAI = vertexAI;
		this.defaultOptions = options;
		this.generationConfig = toGenerationConfig(options);
		this.generativeModel = new GenerativeModel(options.getModelName(), vertexAI);
		this.functionCallbackContext = functionCallbackContext;
	}

	// https://cloud.google.com/vertex-ai/docs/generative-ai/model-reference/gemini
	@Override
	public ChatResponse call(Prompt prompt) {

		var geminiRequest = createGeminiRequest(prompt);

		GenerateContentResponse response = this.chatCompletionWithTools(geminiRequest);

		List<Generation> generations = response.getCandidatesList()
			.stream()
			.map(candidate -> candidate.getContent().getPartsList())
			.flatMap(List::stream)
			.map(Part::getText)
			.map(t -> new Generation(t.toString()))
			.toList();

		return new ChatResponse(generations, toChatResponseMetadata(response));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		try {

			var request = createGeminiRequest(prompt);

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

	private GeminiRequest createGeminiRequest(Prompt prompt) {

		Set<String> functionsForThisRequest = new HashSet<>();

		GenerationConfig generationConfig = this.generationConfig;
		GenerativeModel generativeModel = this.generativeModel;

		VertexAiGeminiChatOptions updatedRuntimeOptions = null;

		if (prompt.getOptions() != null) {
			if (prompt.getOptions() instanceof ChatOptions runtimeOptions) {
				updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(runtimeOptions, ChatOptions.class,
						VertexAiGeminiChatOptions.class);

				functionsForThisRequest
					.addAll(handleFunctionCallbackConfigurations(updatedRuntimeOptions, IS_RUNTIME_CALL));
			}
			else {
				throw new IllegalArgumentException("Prompt options are not of type ChatOptions: "
						+ prompt.getOptions().getClass().getSimpleName());
			}
		}

		if (this.defaultOptions != null) {

			functionsForThisRequest.addAll(handleFunctionCallbackConfigurations(this.defaultOptions, !IS_RUNTIME_CALL));

			if (updatedRuntimeOptions == null) {
				updatedRuntimeOptions = VertexAiGeminiChatOptions.builder().build();
			}

			updatedRuntimeOptions = ModelOptionsUtils.merge(updatedRuntimeOptions, this.defaultOptions,
					VertexAiGeminiChatOptions.class);

		}

		if (updatedRuntimeOptions != null) {

			if (StringUtils.hasText(updatedRuntimeOptions.getModelName())
					&& !updatedRuntimeOptions.getModelName().equals(this.defaultOptions.getModelName())) {
				generativeModel = new GenerativeModel(updatedRuntimeOptions.getModelName(), vertexAI);
			}

			if (updatedRuntimeOptions.getTransportType() != null) {
				Transport transport = (updatedRuntimeOptions
					.getTransportType() == VertexAiGeminiChatOptions.TransportType.GRPC) ? Transport.GRPC
							: Transport.REST;
				generativeModel.setTransport(transport);
			}

			generationConfig = toGenerationConfig(updatedRuntimeOptions);
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {
			List<Tool> tools = this.getFunctionTools(functionsForThisRequest);
			generativeModel.setTools(tools);
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
		if (options.getCandidateCount() != null) {
			generationConfigBuilder.setCandidateCount(options.getCandidateCount());
		}
		if (options.getStopSequences() != null) {
			generationConfigBuilder.addAllStopSequences(options.getStopSequences());
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

	// Function Calling Support
	private Set<String> handleFunctionCallbackConfigurations(VertexAiGeminiChatOptions options, boolean isRuntimeCall) {

		Set<String> functionToCall = new HashSet<>();

		if (options != null) {
			if (!CollectionUtils.isEmpty(options.getFunctionCallbacks())) {
				options.getFunctionCallbacks().stream().forEach(functionCallback -> {

					// Register the tool callback.
					if (isRuntimeCall) {
						this.functionCallbackRegister.put(functionCallback.getName(), functionCallback);
					}
					else {
						this.functionCallbackRegister.putIfAbsent(functionCallback.getName(), functionCallback);
					}

					// Automatically enable the function, usually from prompt callback.
					if (isRuntimeCall) {
						functionToCall.add(functionCallback.getName());
					}
				});
			}

			// Add the explicitly enabled functions.
			if (!CollectionUtils.isEmpty(options.getFunctions())) {
				functionToCall.addAll(options.getFunctions());
			}
		}

		return functionToCall;
	}

	private List<Tool> getFunctionTools(Set<String> functionNames) {

		List<Tool> functionTools = new ArrayList<>();

		for (String functionName : functionNames) {
			if (!this.functionCallbackRegister.containsKey(functionName)) {

				if (this.functionCallbackContext != null) {
					FunctionCallback functionCallback = this.functionCallbackContext.getFunctionCallback(functionName,
							null);
					if (functionCallback != null) {
						this.functionCallbackRegister.put(functionName, functionCallback);
					}
					else {
						throw new IllegalStateException(
								"No function callback [" + functionName + "] fund in tht FunctionCallbackContext");
					}
				}
				else {
					throw new IllegalStateException("No function callback found for name: " + functionName);
				}
			}
			FunctionCallback functionCallback = this.functionCallbackRegister.get(functionName);

			FunctionDeclaration functionDeclaration = FunctionDeclaration.newBuilder()
				.setName(functionCallback.getName())
				.setDescription(functionCallback.getDescription())
				.setParameters(jsonToSchema(functionCallback.getInputTypeSchema()))
				// .setParameters(toOpenApiSchema(functionCallback.getInputTypeSchema()))
				.build();

			Tool tool = Tool.newBuilder().addFunctionDeclarations(functionDeclaration).build();

			functionTools.add(tool);
		}

		return functionTools;
	}

	GenerateContentResponse chatCompletionWithTools(GeminiRequest request) {
		try {
			GenerateContentResponse response = request.model.generateContent(request.contents, request.config);

			if (!isToolCall(response)) {
				return response;
			}

			// message conversation history.
			List<Content> messageConversation = new ArrayList<>(request.contents);

			Content responseContent = response.getCandidatesList().get(0).getContent();

			// Add the assistant response to the message conversation history.
			messageConversation.add(responseContent);

			FunctionCall functionCall = responseContent.getPartsList().iterator().next().getFunctionCall();

			var functionName = functionCall.getName();
			String functionArguments = structToJson(functionCall.getArgs());

			if (!this.functionCallbackRegister.containsKey(functionName)) {
				throw new IllegalStateException("No function callback found for function name: " + functionName);
			}

			String functionResponse = this.functionCallbackRegister.get(functionName).call(functionArguments);

			Content contentFnResp = Content.newBuilder()
				.addParts(Part.newBuilder()
					.setFunctionResponse(FunctionResponse.newBuilder()
						.setName(functionCall.getName())
						.setResponse(jsonToStruct(functionResponse))
						.build())
					.build())
				.build();

			messageConversation.add(contentFnResp);

			return this
				.chatCompletionWithTools(new GeminiRequest(messageConversation, request.model(), request.config()));
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to generate content", e);
		}
	}

	private static String structToJson(Struct struct) {
		try {
			String json = JsonFormat.printer().print(struct);
			// Map<String, Object> metadata = new ObjectMapper() .readValue(json,
			// Map.class);
			return json;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static Struct jsonToStruct(String json) {
		try {
			var structBuilder = Struct.newBuilder();
			JsonFormat.parser().ignoringUnknownFields().merge(json, structBuilder);
			var filterStruct = structBuilder.build();
			return filterStruct;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static Schema jsonToSchema(String json) {
		try {
			var schemaBuilder = Schema.newBuilder();
			JsonFormat.parser().ignoringUnknownFields().merge(json, schemaBuilder);
			var filterStruct = schemaBuilder.build();
			return filterStruct;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Boolean isToolCall(GenerateContentResponse response) {

		if (response == null || CollectionUtils.isEmpty(response.getCandidatesList())
				|| response.getCandidatesList().get(0).getContent() == null
				|| CollectionUtils.isEmpty(response.getCandidatesList().get(0).getContent().getPartsList())) {
			return false;
		}
		return response.getCandidatesList().get(0).getContent().getPartsList().get(0).hasFunctionCall();
	}

	@Override
	public void destroy() throws Exception {
		if (this.vertexAI != null) {
			this.vertexAI.close();
		}
	}

}
