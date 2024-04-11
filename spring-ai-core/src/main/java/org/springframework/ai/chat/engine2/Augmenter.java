package org.springframework.ai.chat.engine2;

public interface Augmenter {

	AugmentResponse augment(AugmentRequest augmentRequest);

}
