package org.springframework.ai.chat.client.advisor.api;

import org.springframework.ai.chat.client.AdvisedRequest;
import org.springframework.ai.chat.model.ChatResponse;

import reactor.core.publisher.Flux;

public interface AroundAdvisorChain {

	ChatResponse nextAroundCall(AdvisedRequest advisedRequest);

	Flux<ChatResponse> nextAroundStream(AdvisedRequest advisedRequest);

}