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

package org.springframework.ai.autoconfigure.mcp.client;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.Root;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.log.LogAccessor;

@AutoConfiguration
@ConditionalOnClass({ McpSchema.class, McpSyncClient.class })
@EnableConfigurationProperties(McpClientProperties.class)
@ConditionalOnProperty(prefix = McpClientProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
public class MpcClientAutoConfiguration {

	private static final LogAccessor logger = new LogAccessor(MpcClientAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = McpClientProperties.CONFIG_PREFIX, name = "transport", havingValue = "STDIO",
			matchIfMissing = true)
	public ClientMcpTransport stdioClientTransport(McpClientProperties properties) {
		return new StdioClientTransport(properties.getStdioConnection().toServerParameters());
	}

	@Bean
	@ConditionalOnMissingBean
	public McpSchema.ClientCapabilities.Builder capabilitiesBuilder() {
		return McpSchema.ClientCapabilities.builder();
	}

	@Bean
	@ConditionalOnProperty(prefix = McpClientProperties.CONFIG_PREFIX, name = "type", havingValue = "SYNC",
			matchIfMissing = true)
	public McpSyncClient mcpSyncClient(ClientMcpTransport transport,
			McpSchema.ClientCapabilities.Builder capabilitiesBuilder, ObjectProvider<Map<String, Root>> roots,
			ObjectProvider<Function<CreateMessageRequest, CreateMessageResult>> samplingHandler,
			ObjectProvider<List<Consumer<List<McpSchema.Tool>>>> toolsChangeConsumers,
			ObjectProvider<List<Consumer<List<McpSchema.Resource>>>> resourcesChangeConsumers,
			ObjectProvider<List<Consumer<List<McpSchema.Prompt>>>> promptsChangeConsumers,
			ObjectProvider<List<Consumer<McpSchema.LoggingMessageNotification>>> loggingConsumers,
			McpClientProperties clientProperties) {

		McpSchema.Implementation clientInfo = new McpSchema.Implementation(clientProperties.getName(),
				clientProperties.getVersion());

		McpClient.SyncSpec clientBilder = McpClient.sync(transport)
			.clientInfo(clientInfo)
			.requestTimeout(clientProperties.getRequestTimeout());

		roots.ifAvailable(rootEntries -> {
			rootEntries.values().stream().forEach(clientBilder::roots);
			capabilitiesBuilder.roots(clientProperties.isRootChangeNotification());
			logger.info("Registered roots: " + rootEntries.keySet().toString());
		});

		samplingHandler.ifAvailable(handler -> {
			clientBilder.sampling(handler);
			capabilitiesBuilder.sampling();
			logger.info("Registered sampling handler");
		});

		toolsChangeConsumers.ifAvailable(consumers -> {
			consumers.stream().forEach(clientBilder::toolsChangeConsumer);
			logger.info("Registered tools change consumers: " + consumers.size());
		});

		resourcesChangeConsumers.ifAvailable(consumers -> {
			consumers.stream().forEach(clientBilder::resourcesChangeConsumer);
			logger.info("Registered resources change consumers: " + consumers.size());
		});

		promptsChangeConsumers.ifAvailable(consumers -> {
			consumers.stream().forEach(clientBilder::promptsChangeConsumer);
			logger.info("Registered prompts change consumers: " + consumers.size());
		});

		loggingConsumers.ifAvailable(consumers -> {
			consumers.stream().forEach(clientBilder::loggingConsumer);
			logger.info("Registered logging consumers: " + consumers.size());
		});

		clientBilder.loggingConsumer(loggingNotification -> {
			logger.debug("Received logging notification: " + loggingNotification);
		});

		clientBilder.capabilities(capabilitiesBuilder.build());

		return clientBilder.build();

	}

}
