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

 package org.springframework.ai.chat.ra.tokenizer;

 import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;

 /**
  * Estimates the number of tokens in a given text or message.
  *
  * @author Christian Tzolov
  * @since 1.0.0
  */
 public interface TokenCountEstimator {

	 /**
	  * Estimates the number of tokens in the given text.
	  * @param text the text to estimate the number of tokens for.
	  * @return the estimated number of tokens.
	  */
	 int estimate(String text);

	 /**
	  * Estimates the number of tokens in the given message.
	  * @param message the message to estimate the number of tokens for.
	  * @return the estimated number of tokens.
	  */
	 int estimate(Message message);

	 /**
	  * Estimates the number of tokens in the given messages.
	  * @param messages the messages to estimate the number of tokens for.
	  * @return the estimated number of tokens.
	  */
	 int estimate(Iterable<Message> messages);

	 int estimate(Document document);

	 int estimateDocuments(Iterable<Document> document);

 }