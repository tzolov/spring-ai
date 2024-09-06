package org.springframework.ai.chat.client.advisor.api;

import org.springframework.ai.chat.client.AdvisedRequest;

public interface AroundAdvisorChain {

	AdvisedResponse nextAroundCall(AdvisedRequest advisedRequest);

	StreamAdvisedResponse nextAroundStream(AdvisedRequest advisedRequest);

}