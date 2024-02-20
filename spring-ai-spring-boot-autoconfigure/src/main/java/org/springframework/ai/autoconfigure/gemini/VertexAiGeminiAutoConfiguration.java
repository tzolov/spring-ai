/*
 * Copyright 2024-20424 the original author or authors.
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

package org.springframework.ai.autoconfigure.gemini;

import java.util.List;

import com.google.cloud.vertexai.VertexAI;

import org.springframework.ai.autoconfigure.NativeHints;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.util.CollectionUtils;

/**
 * Auto-configuration for Vertex AI Gemini Chat.
 *
 * @author Christian Tzolov
 * @since 0.8.0
 */
@ConditionalOnClass(VertexAI.class)
@ImportRuntimeHints(NativeHints.class)
@EnableConfigurationProperties({ VertexAiGeminiChatProperties.class, VertexAiGeminiConnectionProperties.class })
public class VertexAiGeminiAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public VertexAI vertexAi(VertexAiGeminiConnectionProperties connectionProperties) {
		return new VertexAI(connectionProperties.getProjectId(), connectionProperties.getLocation());
	}

	@Bean
	@ConditionalOnMissingBean
	public VertexAiGeminiChatClient vertexAiGeminiChat(VertexAI vertexAi, VertexAiGeminiChatProperties chatProperties,
			List<FunctionCallback> toolFunctionCallbacks, FunctionCallbackContext functionCallbackContext) {

		if (!CollectionUtils.isEmpty(toolFunctionCallbacks)) {
			chatProperties.getOptions().getFunctionCallbacks().addAll(toolFunctionCallbacks);
		}

		return new VertexAiGeminiChatClient(vertexAi, chatProperties.getOptions(), functionCallbackContext);
	}

	@Bean
	@ConditionalOnMissingBean
	public FunctionCallbackContext springAiFunctionManager(ApplicationContext context) {
		FunctionCallbackContext manager = new FunctionCallbackContext();
		manager.setVertexAiGemini(true);
		manager.setApplicationContext(context);
		return manager;
	}

}
