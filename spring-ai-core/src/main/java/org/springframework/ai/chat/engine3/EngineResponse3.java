package org.springframework.ai.chat.engine3;

import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.history.ChatExchange;

import java.util.List;

public class EngineResponse3 {

	private final EngineRequest3 engineRequest2;

	private final RetrievalResponse retrievalResponse;

	private final AugmentResponse augmentResponse;

	private final ChatResponse generateResponse;

	private final List<ChatExchange> exchangedMessages;

	public EngineResponse3(EngineRequest3 engineRequest2, ChatResponse retrievalResponse,
			AugmentResponse augmentResponse, GenerateResponse generateResponse, List<ChatExchange> exchangedMessages) {
		this.engineRequest2 = engineRequest2;
		this.retrievalResponse = retrievalResponse;
		this.augmentResponse = augmentResponse;
		this.generateResponse = generateResponse;
		this.exchangedMessages = exchangedMessages;
	}

	public EngineRequest3 getEngineRequest2() {
		return engineRequest2;
	}

	public RetrievalResponse getRetrievalResponse() {
		return retrievalResponse;
	}

	public AugmentResponse getAugmentResponse() {
		return augmentResponse;
	}

	public GenerateResponse getGenerateResponse() {
		return generateResponse;
	}

	public List<ChatExchange> getExchangedMessages() {
		return exchangedMessages;
	}

	@Override
	public String toString() {
		return "EngineResponse2{" + "engineRequest2=" + engineRequest2 + ", retrievalResponse=" + retrievalResponse
				+ ", augmentResponse=" + augmentResponse + ", generateResponse=" + generateResponse
				+ ", retrievedMessages=" + exchangedMessages + '}';
	}

}
