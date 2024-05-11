/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.openai.chat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.TypeReferenceOutputConverter;
import org.springframework.ai.openai.OpenAiTestConfiguration;
import org.springframework.ai.openai.testutils.AbstractIT;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OpenAiTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiChatClientTypeRefOutputConverterIT extends AbstractIT {

	private static final Logger logger = LoggerFactory.getLogger(OpenAiChatClientTypeRefOutputConverterIT.class);

	record ActorsFilmsRecord(String actor, List<String> movies) {
	}

	@Test
	void typeRefOutputConverterRecords() {

		TypeReferenceOutputConverter<List<ActorsFilmsRecord>> outputConverter = new TypeReferenceOutputConverter<>(
				new TypeReference<List<ActorsFilmsRecord>>() {
				});

		String format = outputConverter.getFormat();
		String template = """
				Generate the filmography of 5 movies for Tom Hanks and Bill Murray.
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template, Map.of("format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());
		Generation generation = chatClient.call(prompt).getResult();

		List<ActorsFilmsRecord> actorsFilms = outputConverter.convert(generation.getOutput().getContent());
		logger.info("" + actorsFilms);
		assertThat(actorsFilms).hasSize(2);
		assertThat(actorsFilms.get(0).actor()).isEqualTo("Tom Hanks");
		assertThat(actorsFilms.get(0).movies()).hasSize(5);
		assertThat(actorsFilms.get(1).actor()).isEqualTo("Bill Murray");
		assertThat(actorsFilms.get(1).movies()).hasSize(5);
	}

	@Test
	void typeRefStreamOutputConverterRecords() {

		TypeReferenceOutputConverter<List<ActorsFilmsRecord>> outputConverter = new TypeReferenceOutputConverter<>(
				new TypeReference<List<ActorsFilmsRecord>>() {
				});

		String format = outputConverter.getFormat();
		String template = """
				Generate the filmography of 5 movies for Tom Hanks and Bill Murray.
					{format}
					""";
		PromptTemplate promptTemplate = new PromptTemplate(template, Map.of("format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());

		String generationTextFromStream = streamingChatClient.stream(prompt)
			.collectList()
			.block()
			.stream()
			.map(ChatResponse::getResults)
			.flatMap(List::stream)
			.map(Generation::getOutput)
			.map(AssistantMessage::getContent)
			.collect(Collectors.joining());

		List<ActorsFilmsRecord> actorsFilms = outputConverter.convert(generationTextFromStream);
		logger.info("" + actorsFilms);
		assertThat(actorsFilms).hasSize(2);
		assertThat(actorsFilms.get(0).actor()).isEqualTo("Tom Hanks");
		assertThat(actorsFilms.get(0).movies()).hasSize(5);
		assertThat(actorsFilms.get(1).actor()).isEqualTo("Bill Murray");
		assertThat(actorsFilms.get(1).movies()).hasSize(5);
	}

}