package org.springframework.ai.azure.openai.dto;

import com.azure.ai.openai.models.AzureChatEnhancements;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatChoiceLogProbabilityInfo;
import com.azure.ai.openai.models.ChatFinishDetails;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.ContentFilterResultsForChoice;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AccessibleChatChoice {

	@JsonProperty(value = "message")
	public AccessibleChatResponseMessage message;

	@JsonProperty(value = "index")
	public int index;

	@JsonProperty(value = "finish_reason")
	public CompletionsFinishReason finishReason;

	@JsonProperty(value = "delta")
	public AccessibleChatResponseMessage delta;

	@JsonProperty(value = "content_filter_results")
	public ContentFilterResultsForChoice contentFilterResults;

	@JsonProperty(value = "finish_details")
	public ChatFinishDetails finishDetails;

	@JsonProperty(value = "enhancements")
	public AzureChatEnhancements enhancements;

	@JsonProperty(value = "logprobs")
	public ChatChoiceLogProbabilityInfo logprobs;

	public static AccessibleChatChoice from(ChatChoice cc) {
		final var acc = new AccessibleChatChoice();
		acc.message = AccessibleChatResponseMessage.from(cc.getMessage());
		acc.index = cc.getIndex();
		acc.finishReason = cc.getFinishReason();
		acc.delta = AccessibleChatResponseMessage.from(cc.getDelta());
		acc.contentFilterResults = cc.getContentFilterResults();
		acc.finishDetails = cc.getFinishDetails();
		acc.enhancements = cc.getEnhancements();
		acc.logprobs = cc.getLogprobs();
		return acc;
	}

	public AccessibleChatResponseMessage getMessage() {
		return message;
	}

	public int getIndex() {
		return index;
	}

	public CompletionsFinishReason getFinishReason() {
		return finishReason;
	}

	public AccessibleChatResponseMessage getDelta() {
		return delta;
	}

	public ContentFilterResultsForChoice getContentFilterResults() {
		return contentFilterResults;
	}

	public ChatFinishDetails getFinishDetails() {
		return finishDetails;
	}

	public AzureChatEnhancements getEnhancements() {
		return enhancements;
	}

	public ChatChoiceLogProbabilityInfo getLogprobs() {
		return logprobs;
	}

	public static AccessibleChatChoice merge(AccessibleChatChoice left, AccessibleChatChoice right) {
		final var instance = new AccessibleChatChoice();
		if (left.message == null) {
			instance.message = right.message;
		}
		else {
			instance.message = AccessibleChatResponseMessage.merge(left.message, right.message);
		}
		instance.index = Math.max(left.index, right.index);
		instance.finishReason = left.finishReason != null ? left.finishReason : right.finishReason;
		if (left.delta == null) {
			instance.delta = right.delta;
		}
		else {
			instance.delta = AccessibleChatResponseMessage.merge(left.delta, right.delta);
		}

		instance.contentFilterResults = left.contentFilterResults != null ? left.contentFilterResults
				: right.contentFilterResults;
		instance.finishDetails = left.finishDetails != null ? left.finishDetails : right.finishDetails;
		instance.enhancements = left.enhancements != null ? left.enhancements : right.enhancements;
		instance.logprobs = left.logprobs != null ? left.logprobs : right.logprobs;
		return instance;
	}

}
