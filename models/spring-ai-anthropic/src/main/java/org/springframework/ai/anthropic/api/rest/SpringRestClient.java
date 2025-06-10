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

import java.io.InputStream;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * @author Christian Tzolov
 */
public class SpringRestClient {

	private static final Logger logger = LoggerFactory.getLogger(SpringRestClient.class);

	private final RestClient restClient;

	private final AsyncTaskExecutor streamingRequestExecutor;

	private DefaultServerSentEventParser sseParser = new DefaultServerSentEventParser();

	public SpringRestClient(RestClient.Builder restClientBuilder) {
		this(restClientBuilder, createDefaultStreamingRequestExecutor());
	}

	public SpringRestClient(RestClient.Builder restClientBuilder, AsyncTaskExecutor streamingRequestExecutor) {

		Assert.notNull(restClientBuilder, "RestClient.Builder must not be null");
		Assert.notNull(streamingRequestExecutor, "StreamingRequestExecutor must not be null");

		this.restClient = restClientBuilder
			// .requestFactory(clientHttpRequestFactory)
			.build();

		this.streamingRequestExecutor = streamingRequestExecutor;
	}

	private static AsyncTaskExecutor createDefaultStreamingRequestExecutor() {
		ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
		taskExecutor.initialize();
		return taskExecutor;
	}

	public void execute(HttpMethod method, String url, HttpHeaders headers, String requestBody,
			ServerSentEventListener listener) {

		RestClient.RequestBodySpec requestBodySpec = this.restClient.method(method)
			.uri(url)
			.headers(httpHeaders -> httpHeaders.putAll(headers));

		if (requestBody != null) {
			requestBodySpec.body(requestBody);
		}

		this.streamingRequestExecutor.execute(() -> {
			try {
				requestBodySpec.exchange((springRequest, springResponse) -> {

					int statusCode = springResponse.getStatusCode().value();

					if (!springResponse.getStatusCode().is2xxSuccessful()) {
						String body = springResponse.bodyTo(String.class);

						RuntimeException exception = new RuntimeException(statusCode + ":" + body);
						ignoringExceptions(() -> listener.onError(exception));
						return null;
					}

					try (InputStream inputStream = springResponse.getBody()) {
						this.sseParser.parse(inputStream, listener);
						ignoringExceptions(listener::onClose);
					}

					return null;
				});
			}
			catch (Exception e) {
				if (e.getCause() instanceof SocketTimeoutException) {
					ignoringExceptions(() -> listener.onError(new RuntimeException(e)));
				}
				else {
					ignoringExceptions(() -> listener.onError(e));
				}
			}
		});
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
