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

package org.springframework.ai.chat.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

@ExtendWith({ SpringExtension.class, MockitoExtension.class })
@ComponentScan(basePackageClasses = DefaultChatClient.class)
@EnableAutoConfiguration
// @Import(ObservedAspectConfiguration.class)
@AutoConfigureObservability
public class ChatClientObservabilityTests {

	@Captor
	static ArgumentCaptor<Prompt> promptCaptor;

	@Autowired
	TestObservationRegistry registry;

	@Autowired
	ChatClient chatClient;

	@Test
	public void testChatClientObservability() {
		assertThat(registry).isNotNull();
		assertThat(chatClient).isNotNull();

		var content = chatClient.prompt().user("my name is John").call().content();

		assertThat(content).isEqualTo("Hello John");

		TestObservationRegistryAssert.assertThat(registry)
			.doesNotHaveAnyRemainingCurrentObservation()
			.hasObservationWithNameEqualTo("chatClient")
			.that()
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@TestConfiguration
	static class ObservationTestConfiguration {

		@Bean
		TestObservationRegistry observationRegistry() {
			return TestObservationRegistry.create();
		}

		@Bean
		ChatClient chatClient(ObservationRegistry observationRegistry) {

			ChatModel chatModel = Mockito.mock(ChatModel.class);

			when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation("Hello John"))))
				.thenReturn(new ChatResponse(List.of(new Generation("Your name is John"))));

			return ChatClient.builder(chatModel, observationRegistry).build();
		}

	}

}
