/*
 * Copyright 2023 - 2024 the original author or authors.
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
package org.springframework.ai.autoconfigure.anthropic;

import org.springframework.ai.anthropic.AnthropicChatClient;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.autoconfigure.retry.SpringAiRetryAutoConfiguration;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.model.function.FunctionCallbackWrapper.Builder.SchemaType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */
@AutoConfiguration(after = { RestClientAutoConfiguration.class, SpringAiRetryAutoConfiguration.class })
@EnableConfigurationProperties({ AnthropicChatProperties.class, AnthropicConnectionProperties.class })
@ConditionalOnClass(AnthropicApi.class)
@ConditionalOnProperty(prefix = AnthropicChatProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class AnthropicAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public AnthropicApi anthropicApi(AnthropicConnectionProperties connectionProperties,
			RestClient.Builder restClientBuilder, ResponseErrorHandler responseErrorHandler) {

		return new AnthropicApi(connectionProperties.getBaseUrl(), connectionProperties.getApiKey(),
				connectionProperties.getVersion(), restClientBuilder, responseErrorHandler);
	}

	@Bean
	@ConditionalOnMissingBean
	public AnthropicChatClient anthropicChatClient(AnthropicApi anthropicApi, AnthropicChatProperties chatProperties,
			RetryTemplate retryTemplate, ApplicationContext context) {
		FunctionCallbackContext functionCallbackContext = springAiFunctionManager(context);
		return new AnthropicChatClient(anthropicApi, chatProperties.getOptions(), retryTemplate,
				functionCallbackContext);
	}

	/**
	 * Because of the ANTHROPIC_XML_SCHEMA type, the FunctionCallbackContext instance must
	 * different from the other JSON schema types.
	 */
	private FunctionCallbackContext springAiFunctionManager(ApplicationContext context) {
		FunctionCallbackContext manager = new FunctionCallbackContext();
		manager.setSchemaType(SchemaType.ANTHROPIC_XML_SCHEMA);
		manager.setApplicationContext(context);
		return manager;
	}

}
