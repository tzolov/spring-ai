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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class AsyncMcpToolCallbackProviderTests {

	@Mock
	private McpAsyncClient asyncMcpClient;

	@Test
	void getToolCallbacksShouldReturnEmptyArrayWhenNoTools() {

		ListToolsResult listToolsResult = mock(ListToolsResult.class);
		when(asyncMcpClient.initialize()).thenReturn(Mono.just(new InitializeResult(null, null, null, null)));
		when(listToolsResult.tools()).thenReturn(List.of());
		when(asyncMcpClient.listTools()).thenReturn(Mono.just(listToolsResult));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(asyncMcpClient);

		var callbacks = provider.getToolCallbacks();

		assertThat(callbacks).isEmpty();
	}

	@Test
	void getToolCallbacksShouldReturnCallbacksForEachTool() {

		Tool tool1 = mock(Tool.class);
		when(tool1.name()).thenReturn("asyncTool1");

		Tool tool2 = mock(Tool.class);
		when(tool2.name()).thenReturn("asyncTool2");

		when(asyncMcpClient.initialize()).thenReturn(Mono.just(new InitializeResult(null, null, null, null)));

		ListToolsResult listToolsResult = mock(ListToolsResult.class);
		when(listToolsResult.tools()).thenReturn(List.of(tool1, tool2));
		when(asyncMcpClient.listTools()).thenReturn(Mono.just(listToolsResult));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(asyncMcpClient);

		var callbacks = provider.getToolCallbacks();

		assertThat(callbacks).hasSize(2);
	}

	@Test
	void getToolCallbacksShouldThrowExceptionForDuplicateToolNames() {
		Tool tool1 = mock(Tool.class);
		when(tool1.name()).thenReturn("sameName");

		Tool tool2 = mock(Tool.class);
		when(tool2.name()).thenReturn("sameName");

		when(asyncMcpClient.initialize()).thenReturn(Mono.just(new InitializeResult(null, null, null, null)));

		ListToolsResult listToolsResult = mock(ListToolsResult.class);
		when(listToolsResult.tools()).thenReturn(List.of(tool1, tool2));
		when(asyncMcpClient.listTools()).thenReturn(Mono.just(listToolsResult));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(asyncMcpClient);

		assertThatThrownBy(() -> provider.getToolCallbacks()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Multiple tools with the same name");
	}

	@Test
	void constructorShouldAcceptVarargs() {
		McpAsyncClient client1 = mock(McpAsyncClient.class);
		McpAsyncClient client2 = mock(McpAsyncClient.class);

		when(client1.initialize()).thenReturn(Mono.just(new InitializeResult("protocol",
				ServerCapabilities.builder().build(), new McpSchema.Implementation("", ""), null)));
		when(client2.initialize()).thenReturn(Mono.just(new InitializeResult("protocol",
				ServerCapabilities.builder().build(), new McpSchema.Implementation("", ""), null)));

		ListToolsResult emptyResult = mock(ListToolsResult.class);
		when(emptyResult.tools()).thenReturn(List.of());
		when(client1.listTools()).thenReturn(Mono.just(emptyResult));
		when(client2.listTools()).thenReturn(Mono.just(emptyResult));

		AsyncMcpToolCallbackProvider provider = new AsyncMcpToolCallbackProvider(client1, client2);

		assertThat(provider.getToolCallbacks()).isEmpty();
	}

}
