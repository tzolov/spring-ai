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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransport;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;

@AutoConfiguration
@ConditionalOnClass({ WebFluxSseServerTransport.class, RouterFunction.class })
@ConditionalOnProperty(prefix = McpClientProperties.CONFIG_PREFIX, name = "transport", havingValue = "WEBFLUX")
public class MpcWebFluxSseClientAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public WebClient.Builder webClientBuilder(McpClientProperties properties) {
		return WebClient.builder().baseUrl(properties.getBaseUrl());
	}

	@Bean
	@ConditionalOnMissingBean
	public WebFluxSseClientTransport webFluxTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
		return new WebFluxSseClientTransport(webClientBuilder, objectMapper);
	}

}
