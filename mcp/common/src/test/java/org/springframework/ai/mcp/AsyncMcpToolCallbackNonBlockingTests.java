/*
 * Copyright 2025-2025 the original author or authors.
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

package org.springframework.ai.mcp;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMcpToolCallbackNonBlockingTests {

	@Mock
	private McpAsyncClient asyncMcpClient;

	@Mock
	private Tool tool;

	/**
	 * This test verifies that the AsyncMcpToolCallback's call method doesn't block when
	 * used in a reactive context. It simulates a reactive environment by using a parallel
	 * scheduler and verifies that the operation completes successfully.
	 */
	@Test
	void callShouldNotBlockInReactiveContext() throws Exception {

		when(tool.name()).thenReturn("testAsyncTool");

		// Create a delayed response to simulate a slow operation
		CallToolResult callResult = mock(CallToolResult.class);
		TextContent textContent = mock(TextContent.class);
		when(textContent.text()).thenReturn("delayed result");
		when(callResult.content()).thenReturn(List.of(textContent));

		// Simulate a slow operation that takes 500ms to complete
		when(asyncMcpClient.callTool(any(CallToolRequest.class)))
			.thenReturn(Mono.delay(Duration.ofMillis(100)).then(Mono.just(callResult)));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		// Use a CountDownLatch to wait for the operation to complete
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();

		// Execute the call on a parallel scheduler to simulate a reactive environment
		Mono.fromCallable(() -> callback.call("{\"param\":\"value\"}"))
			.subscribeOn(Schedulers.parallel())
			.subscribe(r -> {
				result.set(r);
				latch.countDown();
			}, e -> {
				error.set(e);
				latch.countDown();
			});

		// Wait for the operation to complete (with a timeout)
		boolean completed = latch.await(2, TimeUnit.SECONDS);

		// Verify that the operation completed successfully
		assertThat(completed).isTrue();
		assertThat(error.get()).isNull();
		assertThat(result.get()).isNotNull();
		assertThat(result.get()).contains("delayed result");
	}

	/**
	 * This test verifies that the AsyncMcpToolCallback's call method properly handles
	 * errors in a reactive context without blocking.
	 */
	@Test
	void callShouldHandleErrorsInReactiveContextWithoutBlocking() throws Exception {
		// Arrange
		when(tool.name()).thenReturn("testAsyncTool");

		// Simulate an operation that fails after a delay
		when(asyncMcpClient.callTool(any(CallToolRequest.class)))
			.thenReturn(Mono.delay(Duration.ofMillis(500)).then(Mono.error(new RuntimeException("Test error"))));

		AsyncMcpToolCallback callback = new AsyncMcpToolCallback(asyncMcpClient, tool);

		// Act & Assert
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();

		Mono.fromCallable(() -> callback.call("{\"param\":\"value\"}"))
			.subscribeOn(Schedulers.parallel())
			.subscribe(r -> {
				result.set(r);
				latch.countDown();
			}, e -> {
				error.set(e);
				latch.countDown();
			});

		boolean completed = latch.await(2, TimeUnit.SECONDS);

		assertThat(completed).isTrue();
		assertThat(error.get()).isNotNull();
		assertThat(error.get()).isInstanceOf(RuntimeException.class);
		assertThat(error.get()).hasMessageContaining("Test error");
	}

}
