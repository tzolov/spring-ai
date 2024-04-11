package org.springframework.ai.chat.engine2;

import org.springframework.ai.chat.history.ChatExchange;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ChatExchangeAugmenter implements Augmenter {

	public static final String HISTORY_PROMPT = """
			Use the conversation history from the HISTORY section to provide accurate answers.

			HISTORY:
			{history}
				""";

	@Override
	public AugmentResponse augment(AugmentRequest augmentRequest) {
		Prompt originalPrompt = augmentRequest.getEngineRequest2().getPrompt();
		List<Message> systemMessages = (originalPrompt.getInstructions() != null) ? originalPrompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.SYSTEM)
			.toList() : List.of();

		List<Message> nonSystemMessages = (originalPrompt.getInstructions() != null) ? originalPrompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() != MessageType.SYSTEM)
			.toList() : List.of();

		SystemMessage originalSystemMessage = (!systemMessages.isEmpty()) ? (SystemMessage) systemMessages.get(0)
				: new SystemMessage("");

		List<ChatExchange> chatExchangeList = augmentRequest.getRetrievalResponse().getChatExchanges();
		String historyContext = chatExchangeList.stream()
			.map(ce -> ce.getMessages())
			.flatMap(List::stream)
			.map(msg -> msg.getMessageType() + ": " + msg.getContent())
			.collect(Collectors.joining(System.lineSeparator()));

		SystemMessage newSystemMessage = new SystemMessage(originalSystemMessage.getContent() + System.lineSeparator()
				+ HISTORY_PROMPT.replace("{history}", historyContext));

		System.out.println(newSystemMessage.getContent());

		List<Message> newPromptMessages = new ArrayList<>();
		newPromptMessages.add(newSystemMessage);
		newPromptMessages.addAll(nonSystemMessages);

		Prompt augmentedPrompt = new Prompt(newPromptMessages, (ChatOptions) originalPrompt.getOptions());
		return new AugmentResponse(augmentedPrompt);
	}

}
