package org.springframework.ai.chat.engine2;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class UserMessageAugmentor implements Augmenter {

	private static final String DEFAULT_USER_PROMPT_TEXT = """
			   "Context information is below.\\n"
			   "---------------------\\n"
			   "{context}\\n"
			   "---------------------\\n"
			   "Given the context information and not prior knowledge, "
			   "answer the question. If the answer is not in the context, inform "
			   "the user that you can't answer the question.\\n"
			   "Question: {question}\\n"
			   "Answer: "
			""";

	@Override
	public AugmentResponse augment(AugmentRequest augmentRequest) {
		String context = doCreateContext(augmentRequest.getRetrievalResponse().getRetrievedDocuments());
		Map<String, Object> contextMap = doCreateContextMap(augmentRequest.getEngineRequest2().getPrompt(), context);
		Prompt prompt = doCreatePrompt(augmentRequest.getEngineRequest2().getPrompt(), contextMap);
		AugmentResponse augmentResponse = new AugmentResponse(prompt);
		return augmentResponse;
	}

	protected String doCreateContext(List<Document> similarDocuments) {
		return similarDocuments.stream().map(entry -> entry.getContent()).collect(Collectors.joining("\n"));
	}

	private Map<String, Object> doCreateContextMap(Prompt prompt, String context) {
		String question = prompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() == MessageType.USER)
			.map(m -> m.getContent())
			.collect(Collectors.joining(System.lineSeparator()));
		Map<String, Object> contextMap = Map.of("context", context, "question", question);
		return contextMap;
	}

	private Prompt doCreatePrompt(Prompt originalPrompt, Map<String, Object> contextMap) {
		PromptTemplate promptTemplate = new PromptTemplate(DEFAULT_USER_PROMPT_TEXT);
		Message augmentedUserMessage = promptTemplate.createMessage(contextMap);

		// TODO improve copy of prompt
		// public Prompt(List<Message> messages, ChatOptions modelOptions) {
		List<Message> messageList = originalPrompt.getInstructions()
			.stream()
			.filter(m -> m.getMessageType() != MessageType.USER)
			.collect(Collectors.toList());
		messageList.add(augmentedUserMessage);
		Prompt augmentedPrompt = new Prompt(messageList, (ChatOptions) originalPrompt.getOptions()); // TODO
																										// investigate
																										// cast
		return augmentedPrompt;
	}

}
