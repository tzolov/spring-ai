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

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncMcpToolCallbackProviderNonBlockingTests {

	@Mock
	private McpAsyncClient asyncMcpClient;

	/**
	 * This test verifies that the AsyncMcpToolCallbackProvider's getToolCallbacks method
	 * doesn't block when used in a reactive context. It simulates a reactive environment
	 * by using a parallel scheduler and verifies that the operation completes
	 * successfully.
	 */
	@Test
	void getToolCallbacksShouldNotBlockInReactiveContext() throws Exception {

		Tool tool1 = mock(Tool.class);
		when(tool1.name()).thenReturn("asyncTool1");

		Tool tool2 = mock(Tool.class);
		when(tool2.name()).thenReturn("asyncTool2");

		ListToolsResult listToolsResult = mock(ListToolsResult.class);
		when(listToolsResult.tools()).thenReturn(List.of(tool1, tool2));

		// Simulate a slow operation that takes 500ms to complete
		when(asyncMcpClient.initialize()).thenReturn(Mono.just(new InitializeResult(null, null, null, null)));
		when(asyncMcpClient.listTools())
			.thenReturn(Mono.delay(Duration.ofMillis(500)).then(Mono.just(listToolsResult)));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(asyncMcpClient);

		// Use a CountDownLatch to wait for the operation to complete
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<ToolCallback[]> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();

		// Execute the getToolCallbacks on a parallel scheduler to simulate a reactive
		// environment
		Mono.fromCallable(() -> provider.getToolCallbacks()).subscribeOn(Schedulers.parallel()).subscribe(r -> {
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
		assertThat(result.get()).hasSize(2);
	}

	/**
	 * This test verifies that the AsyncMcpToolCallbackProvider's getToolCallbacks method
	 * properly handles errors in a reactive context without blocking.
	 */
	@Test
	void getToolCallbacksShouldHandleErrorsInReactiveContextWithoutBlocking() throws Exception {

		when(asyncMcpClient.initialize()).thenReturn(Mono.just(new InitializeResult(null, null, null, null)));
		// Simulate an operation that fails after a delay
		when(asyncMcpClient.listTools())
			.thenReturn(Mono.delay(Duration.ofMillis(500)).then(Mono.error(new RuntimeException("Test error"))));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(asyncMcpClient);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<ToolCallback[]> result = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();

		Mono.fromCallable(() -> provider.getToolCallbacks()).subscribeOn(Schedulers.parallel()).subscribe(r -> {
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
