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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.Root;

import org.springframework.ai.mcp.McpToolCallback;
import org.springframework.ai.mcp.McpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.log.LogAccessor;
import org.springframework.util.CollectionUtils;

/**
 * Auto-configuration for Model Context Protocol (MCP) STDIO clients.
 *
 * <p>
 * This configuration is responsible for setting up MCP clients that communicate with MCP
 * servers through standard input/output (STDIO). It creates and configures
 * {@link McpSyncClient} instances based on the provided configuration properties.
 *
 * <p>
 * The configuration is conditionally enabled when:
 * <ul>
 * <li>Required classes ({@link McpSchema} and {@link McpSyncClient}) are present on the
 * classpath</li>
 * <li>The 'spring.ai.mcp.client.stdio.enabled' property is set to 'true'</li>
 * </ul>
 *
 * <p>
 * This auto-configuration provides:
 * <ul>
 * <li>A list of {@link McpSyncClient} instances configured for STDIO communication</li>
 * <li>An {@link McpToolAdapter} that manages tool callbacks for the configured
 * clients</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @since 1.0.0
 * @see McpStdioClientProperties
 * @see McpSyncClient
 * @see McpToolCallback
 */
@AutoConfiguration
@ConditionalOnClass({ McpSchema.class, McpSyncClient.class })
@EnableConfigurationProperties(McpStdioClientProperties.class)
@ConditionalOnProperty(prefix = McpStdioClientProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true")
public class MpcStdioClientAutoConfiguration {

	private static final LogAccessor logger = new LogAccessor(MpcStdioClientAutoConfiguration.class);

	/**
	 * Creates and configures a list of {@link McpSyncClient} instances based on the
	 * provided configuration.
	 *
	 * <p>
	 * Each client is configured with:
	 * <ul>
	 * <li>STDIO transport</li>
	 * <li>Client capabilities and information</li>
	 * <li>Request timeout settings</li>
	 * <li>Optional root configurations</li>
	 * <li>Optional sampling handlers</li>
	 * <li>Optional change consumers for tools, resources, and prompts</li>
	 * <li>Logging consumers</li>
	 * </ul>
	 * @param roots Root configurations provider
	 * @param samplingHandler Sampling handler provider
	 * @param toolsChangeConsumers Tools change consumers provider
	 * @param resourcesChangeConsumers Resources change consumers provider
	 * @param promptsChangeConsumers Prompts change consumers provider
	 * @param loggingConsumers Logging consumers provider
	 * @param clientProperties Client configuration properties
	 * @return List of configured {@link McpSyncClient} instances
	 */
	@Bean
	public List<McpSyncClient> mcpSyncClients(ObjectProvider<Map<String, Root>> roots,
			ObjectProvider<Function<CreateMessageRequest, CreateMessageResult>> samplingHandler,
			ObjectProvider<List<Consumer<List<McpSchema.Tool>>>> toolsChangeConsumers,
			ObjectProvider<List<Consumer<List<McpSchema.Resource>>>> resourcesChangeConsumers,
			ObjectProvider<List<Consumer<List<McpSchema.Prompt>>>> promptsChangeConsumers,
			ObjectProvider<List<Consumer<McpSchema.LoggingMessageNotification>>> loggingConsumers,
			McpStdioClientProperties clientProperties) {

		List<McpSyncClient> clients = new ArrayList<>();

		for (Map.Entry<String, ServerParameters> serverParameters : clientProperties.toServerParameters().entrySet()) {

			var transport = new StdioClientTransport(serverParameters.getValue());

			McpSchema.ClientCapabilities.Builder capabilitiesBuilder = McpSchema.ClientCapabilities.builder();

			McpSchema.Implementation clientInfo = new McpSchema.Implementation(serverParameters.getKey(),
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

			McpSyncClient mcpClient = clientBilder.build();

			if (clientProperties.isInitialize()) {
				mcpClient.initialize();
			}

			clients.add(mcpClient);
		}

		return clients;
	}

	/**
	 * A record that encapsulates an array of MCP tool callbacks and provides cleanup
	 * functionality.
	 *
	 * <p>
	 * This adapter is responsible for:
	 * <ul>
	 * <li>Holding references to tool callbacks from MCP clients</li>
	 * <li>Providing a cleanup mechanism through the close method</li>
	 * </ul>
	 */
	public static record McpToolAdapter(McpToolCallback[] toolCallbacks) {
		/**
		 * Closes all tool callbacks held by this adapter.
		 *
		 * <p>
		 * This method is called automatically when the Spring application context is
		 * closed, ensuring proper cleanup of resources.
		 */
		public void close() {
			Arrays.stream(toolCallbacks).forEach(McpToolCallback::close);
		}
	}

	/**
	 * Creates an adapter that manages tool callbacks for the configured MCP clients.
	 *
	 * <p>
	 * This bean is configured with a destroy method that ensures proper cleanup of all
	 * tool callbacks when the application context is closed.
	 *
	 * <p>
	 * The adapter:
	 * <ul>
	 * <li>Collects tool callbacks from all configured MCP clients</li>
	 * <li>Provides a mechanism to close all callbacks during shutdown</li>
	 * <li>Returns an empty adapter if no clients are configured</li>
	 * </ul>
	 * @param mcpClients List of configured MCP clients
	 * @return An adapter containing tool callbacks from all clients
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(prefix = McpStdioClientProperties.CONFIG_PREFIX, name = "initialize", havingValue = "true",
			matchIfMissing = true)
	public McpToolAdapter mcpToolAdapter(List<McpSyncClient> mcpClients) {
		if (CollectionUtils.isEmpty(mcpClients)) {
			return new McpToolAdapter(new McpToolCallback[0]);
		}
		var toolCallbacks = mcpClients.stream()
			.map(mcpClient -> List.of((new McpToolCallbackProvider(mcpClient).getToolCallbacks())))
			.flatMap(List::stream)
			.toList()
			.toArray(McpToolCallback[]::new);

		return new McpToolAdapter(toolCallbacks);
	}

}
