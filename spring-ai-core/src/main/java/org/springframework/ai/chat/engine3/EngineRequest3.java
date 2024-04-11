package org.springframework.ai.chat.engine3;

import org.springframework.ai.chat.prompt.Prompt;

public class EngineRequest3 {

	private String conversationId;

	private final Prompt prompt;

	public EngineRequest3(String conversationId, Prompt prompt) {
		this.conversationId = conversationId;
		this.prompt = prompt;
	}

	public Prompt getPrompt() {
		return prompt;
	}

	public String getConversationId() {
		return conversationId;
	}

	@Override
	public String toString() {
		return "EngineRequest2{" + "conversationId='" + conversationId + '\'' + ", prompt=" + prompt + '}';
	}

}
