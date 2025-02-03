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

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties(McpClientProperties.CONFIG_PREFIX)
public class McpClientProperties {

	public static final String CONFIG_PREFIX = "spring.ai.mcp.client";

	/**
	 * Enable/disable the MCP client.
	 * <p>
	 * When set to false, the MCP client and all its components will not be initialized.
	 */
	private boolean enabled = false;

	/**
	 * The name of the MCP client instance.
	 * <p>
	 * This name is used to identify the server in logs and monitoring.
	 */
	private String name = "mcp-client";

	/**
	 * The version of the MCP client instance.
	 * <p>
	 * This version is reported to clients and used for compatibility checks.
	 */
	private String version = "1.0.0";

	private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

	private boolean rootChangeNotification = true;

	/**
	 * The transport type to use for MCP server communication.
	 * <p>
	 * Supported types are:
	 * <ul>
	 * <li>STDIO - Standard input/output transport (default)</li>
	 * <li>WEBMVC - Spring MVC Server-Sent Events transport</li>
	 * <li>WEBFLUX - Spring WebFlux Server-Sent Events transport</li>
	 * </ul>
	 */
	private Transport transport = Transport.STDIO;

	private String baseUrl;

	private McpStdioConnection stdioConnection = new McpStdioConnection();

	/**
	 * The type of server to use for MCP server communication.
	 * <p>
	 * Supported types are:
	 * <ul>
	 * <li>SYNC - Standard synchronous server (default)</li>
	 * <li>ASYNC - Asynchronous server</li>
	 * </ul>
	 */
	private ClientType type = ClientType.SYNC;

	/**
	 * Transport types supported by the MCP server.
	 */
	public enum Transport {

		/**
		 * Standard input/output transport, suitable for command-line tools and local
		 * development.
		 */
		STDIO,

		/**
		 * Spring MVC Server-Sent Events transport, requires spring-boot-starter-web and
		 * mcp-spring-webmvc.
		 */
		WEBMVC,

		/**
		 * Spring WebFlux Server-Sent Events transport, requires
		 * spring-boot-starter-webflux and mcp-spring-webflux.
		 */
		WEBFLUX

	}

	/**
	 * Server types supported by the MCP client.
	 */
	public enum ClientType {

		/**
		 * Synchronous (McpSyncServer) server
		 */
		SYNC,
		/**
		 * Asynchronous (McpAsyncServer) server
		 */
		ASYNC

	}

	public McpStdioConnection getStdioConnection() {
		return this.stdioConnection;
	}

	public boolean isRootChangeNotification() {
		return this.rootChangeNotification;
	}

	public void setRootChangeNotification(boolean rootChangeNotification) {
		this.rootChangeNotification = rootChangeNotification;
	}

	public Duration getRequestTimeout() {
		return this.requestTimeout;
	}

	public void setRequestTimeout(Duration requestTimeout) {
		Assert.notNull(requestTimeout, "Request timeout must not be null");
		this.requestTimeout = requestTimeout;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		Assert.hasText(name, "Name must not be empty");
		this.name = name;
	}

	public String getVersion() {
		return this.version;
	}

	public void setVersion(String version) {
		Assert.hasText(version, "Version must not be empty");
		this.version = version;
	}

	public Transport getTransport() {
		return this.transport;
	}

	public void setTransport(Transport transport) {
		Assert.notNull(transport, "Transport must not be null");
		this.transport = transport;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String sseUrl) {
		this.baseUrl = sseUrl;
	}

	public ClientType getType() {
		return this.type;
	}

	public void setType(ClientType serverType) {
		Assert.notNull(serverType, "Server type must not be null");
		this.type = serverType;
	}

}
