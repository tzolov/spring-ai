/*
* Copyright 2024 - 2024 the original author or authors.
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
package org.springframework.ai.chat.client.observation;

import org.springframework.ai.chat.model.ChatResponse;

import io.micrometer.observation.Observation;

/**
 * @author Christian Tzolov
 * @since 1.0.0
 */

public class ChatClientObservationContext extends Observation.Context {

	private String modelClassName;

	private ChatResponse chatResponse;

	private boolean stream;

	private String model;

	public void setModelClassName(String chatModelName) {
		this.modelClassName = chatModelName;
	}

	public String getModelClassName() {
		return this.modelClassName;
	}

	public ChatResponse getChatResponse() {
		return this.chatResponse;
	}

	public void setChatResponse(ChatResponse chatResponse) {
		this.chatResponse = chatResponse;
	}

	public boolean isStream() {
		return this.stream;
	}

	public void setStream(boolean stream) {
		this.stream = stream;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getModel() {
		return this.model;
	}

}
