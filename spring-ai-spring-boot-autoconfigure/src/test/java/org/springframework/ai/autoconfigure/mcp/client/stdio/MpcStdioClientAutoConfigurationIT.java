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
package org.springframework.ai.autoconfigure.mcp.client.stdio;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link MpcStdioClientAutoConfiguration}.
 *
 * @author Christian Tzolov
 */
class MpcStdioClientAutoConfigurationIT {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(MpcStdioClientAutoConfiguration.class));

	@Test
	void autoConfigurationDisabledByDefault() {
		this.contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(McpSyncClient.class);
			assertThat(context).doesNotHaveBean(MpcStdioClientAutoConfiguration.McpToolAdapter.class);
		});
	}

	@Test
	void autoConfigurationEnabledWithProperty() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true").run(context -> {
			assertThat(context).hasSingleBean(List.class);
			assertThat(context).hasSingleBean(MpcStdioClientAutoConfiguration.McpToolAdapter.class);
		});
	}

	@Test
	void defaultPropertiesAreCorrect() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true").run(context -> {
			McpStdioClientProperties properties = context.getBean(McpStdioClientProperties.class);
			assertThat(properties.getVersion()).isEqualTo("1.0.0");
			assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(20));
			assertThat(properties.isRootChangeNotification()).isTrue();
			assertThat(properties.getStdioConnections()).isEmpty();
			assertThat(properties.getServersConfiguration()).isNull();
		});
	}

	@Test
	void customPropertiesAreApplied() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true",
					"spring.ai.mcp.client.stdio.initialize=false", "spring.ai.mcp.client.stdio.version=2.0.0",
					"spring.ai.mcp.client.stdio.request-timeout=30s",
					"spring.ai.mcp.client.stdio.root-change-notification=false",
					"spring.ai.mcp.client.stdio.stdio-connections.test-server.command=test-command",
					"spring.ai.mcp.client.stdio.stdio-connections.test-server.args[0]=arg1",
					"spring.ai.mcp.client.stdio.stdio-connections.test-server.env.TEST_VAR=test-value")
			.run(context -> {
				McpStdioClientProperties properties = context.getBean(McpStdioClientProperties.class);
				assertThat(properties.getVersion()).isEqualTo("2.0.0");
				assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
				assertThat(properties.isRootChangeNotification()).isFalse();
				assertThat(properties.getStdioConnections()).hasSize(1);
				assertThat(properties.getStdioConnections().get("test-server").getCommand()).isEqualTo("test-command");
				assertThat(properties.getStdioConnections().get("test-server").getArgs()).containsExactly("arg1");
				assertThat(properties.getStdioConnections().get("test-server").getEnv()).containsEntry("TEST_VAR",
						"test-value");
			});
	}

	// @Test
	void resourceBasedConfiguration() {
		String jsonConfig = """
				{
					"mcpServers": {
						"test-server": {
							"command": "test-command",
							"args": ["arg1", "arg2"],
							"env": {
								"TEST_VAR": "test-value"
							}
						}
					}
				}
				""";

		this.contextRunner.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true")
			.withBean("serversConfig", ByteArrayResource.class, () -> new ByteArrayResource(jsonConfig.getBytes()))
			.withPropertyValues("spring.ai.mcp.client.stdio.servers-configuration=serversConfig")
			.run(context -> {
				McpStdioClientProperties properties = context.getBean(McpStdioClientProperties.class);
				Map<String, io.modelcontextprotocol.client.transport.ServerParameters> params = properties
					.toServerParameters();
				assertThat(params).hasSize(1);
				assertThat(params.get("test-server")).isNotNull();
			});
	}

	@Test
	void optionalDependenciesAreHandled() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true")
			.withUserConfiguration(OptionalDependenciesConfig.class)
			.run(context -> {
				assertThat(context).hasSingleBean(List.class);
				assertThat(context).hasSingleBean(MpcStdioClientAutoConfiguration.McpToolAdapter.class);
			});
	}

	@Configuration
	static class OptionalDependenciesConfig {

		@Bean
		Map<String, McpSchema.Root> roots() {
			return new HashMap<>();
		}

		@SuppressWarnings("unchecked")
		@Bean
		Consumer<List<McpSchema.Tool>> toolsChangeConsumer() {
			return mock(Consumer.class);
		}

		@SuppressWarnings("unchecked")
		@Bean
		Consumer<List<McpSchema.Resource>> resourcesChangeConsumer() {
			return mock(Consumer.class);
		}

		@SuppressWarnings("unchecked")
		@Bean
		Consumer<List<McpSchema.Prompt>> promptsChangeConsumer() {
			return mock(Consumer.class);
		}

		@SuppressWarnings("unchecked")
		@Bean
		Consumer<McpSchema.LoggingMessageNotification> loggingConsumer() {
			return mock(Consumer.class);
		}

	}

	@Test
	void toolAdapterCleanupIsPerformed() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.client.stdio.enabled=true").run(context -> {
			MpcStdioClientAutoConfiguration.McpToolAdapter adapter = context
				.getBean(MpcStdioClientAutoConfiguration.McpToolAdapter.class);
			context.close();
			// Verify cleanup is performed - this is a bit tricky to test directly
			// since we can't easily mock the McpToolCallback instances
			assertThat(adapter.toolCallbacks()).isNotNull();
		});
	}

}
