/*
* Copyright 2025 - 2025 the original author or authors.
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
package org.springframework.ai.anthropic.api.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.codec.ServerSentEvent;

/**
 * @author Christian Tzolov
 */
public class DefaultServerSentEventParser {

	private static final Logger logger = LoggerFactory.getLogger(DefaultServerSentEventParser.class);

	public void parse(InputStream httpResponseBody, ServerSentEventListener listener) {

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpResponseBody))) {

			String event = null;
			StringBuilder data = new StringBuilder();

			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					if (!data.isEmpty()) {
						ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
							.event(event)
							.data(data.toString())
							.build();

						ignoringExceptions(() -> listener.onEvent(sse));
						event = null;
						data.setLength(0);
					}
					continue;
				}

				if (line.startsWith("event:")) {
					event = line.substring("event:".length()).trim();
				}
				else if (line.startsWith("data:")) {
					String content = line.substring("data:".length());
					if (!data.isEmpty()) {
						data.append("\n");
					}
					data.append(content.trim());
				}
			}

			if (!data.isEmpty()) {

				ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
					.event(event)
					.data(data.toString())
					.build();
				ignoringExceptions(() -> listener.onEvent(sse));
			}
		}
		catch (IOException e) {
			ignoringExceptions(() -> listener.onError(e));
		}
	}

	public static void ignoringExceptions(Runnable runnable) {
		try {
			runnable.run();
		}
		catch (Exception e) {
			logger.warn("An exception occurred during the invocation of the SSE listener. "
					+ "This exception has been ignored.", e);
		}
	}

}