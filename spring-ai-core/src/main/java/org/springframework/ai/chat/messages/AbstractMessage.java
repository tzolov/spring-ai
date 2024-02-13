/*
 * Copyright 2023 the original author or authors.
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

package org.springframework.ai.chat.messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StreamUtils;

public abstract class AbstractMessage implements Message {

	protected List<MediaData> contentParts = new ArrayList<>();

	/**
	 * Additional options for the message to influence the response, not a generative map.
	 */
	protected Map<String, Object> properties = new HashMap<>();

	protected MessageType messageType;

	protected AbstractMessage() {

	}

	protected AbstractMessage(MessageType messageType, String content) {
		this(messageType, content, Collections.emptyMap());
	}

	protected AbstractMessage(MessageType messageType, String content, Map<String, Object> messageProperties) {
		this.messageType = messageType;
		this.contentParts.add(new MediaData(MimeTypeUtils.TEXT_PLAIN, content));
		this.properties = messageProperties;
	}

	protected AbstractMessage(MessageType messageType, List<MediaData> contentParts) {
		this(messageType, contentParts, Map.of());
	}

	protected AbstractMessage(MessageType messageType, List<MediaData> contentParts,
			Map<String, Object> messageProperties) {
		this.messageType = messageType;

		if (!CollectionUtils.isEmpty(contentParts)) {
			this.contentParts.addAll(contentParts);
		}
		this.properties = messageProperties;
	}

	protected AbstractMessage(MessageType messageType, Resource resource) {
		this(messageType, resource, Collections.emptyMap());
	}

	protected AbstractMessage(MessageType messageType, Resource resource, Map<String, Object> messageProperties) {
		this.messageType = messageType;
		this.properties = messageProperties;
		try (InputStream inputStream = resource.getInputStream()) {
			String textContent = StreamUtils.copyToString(inputStream, Charset.defaultCharset());
			this.contentParts.add(new MediaData(MimeTypeUtils.TEXT_PLAIN, textContent));
		}
		catch (IOException ex) {
			throw new RuntimeException("Failed to read resource", ex);
		}
	}

	@Override
	public String getContent() {
		Object data = null;
		if (CollectionUtils.isEmpty(this.contentParts)) {
			MediaData part = this.contentParts.stream()
					.filter(c -> c.getMimeType() == MimeTypeUtils.TEXT_PLAIN)
					.findFirst().get();

			if (part != null) {
				data = part.getData();
			}
		}

		if (data == null) {
			return null;
		}
		return (data instanceof String) ? (String) data : new String((byte[]) data, Charset.defaultCharset());
	}

	public List<MediaData> getMedia() {
		return this.contentParts;
	}

	@Override
	public Map<String, Object> getProperties() {
		return this.properties;
	}

	@Override
	public MessageType getMessageType() {
		return this.messageType;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((contentParts == null) ? 0 : contentParts.hashCode());
		result = prime * result + ((properties == null) ? 0 : properties.hashCode());
		result = prime * result + ((messageType == null) ? 0 : messageType.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AbstractMessage other = (AbstractMessage) obj;
		if (contentParts == null) {
			if (other.contentParts != null)
				return false;
		}
		else if (!contentParts.equals(other.contentParts))
			return false;
		if (properties == null) {
			if (other.properties != null)
				return false;
		}
		else if (!properties.equals(other.properties))
			return false;
		if (messageType != other.messageType)
			return false;
		return true;
	}

}
