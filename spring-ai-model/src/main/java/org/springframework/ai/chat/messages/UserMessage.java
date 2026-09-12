/*
 * Copyright 2023-present the original author or authors.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A message of the type 'user' passed as input Messages with the user role are from the
 * end-user or developer. They represent questions, prompts, or any input that you want
 * the generative to respond to.
 * <p>
 * The message content is an ordered list of {@link MessagePart}s, so text and media can
 * interleave. {@link #getText()} joins the {@link TextPart}s and {@link #getMedia()}
 * selects the {@link MediaPart}s, in part order. Messages built through the legacy
 * constructors or the builder's {@link Builder#text(String)} and
 * {@link Builder#media(List)} setters get their parts in the legacy order: text, then
 * media.
 */
public class UserMessage extends AbstractMessage implements MediaContent {

	private final List<MessagePart> parts;

	/**
	 * The media of this message, derived from its {@link MediaPart}s.
	 * @deprecated since 2.1.0 in favor of {@link #getMedia()}; kept so existing
	 * subclasses that read the field keep compiling. Will be removed in a future release.
	 */
	@Deprecated(since = "2.1.0", forRemoval = true)
	protected final List<Media> media;

	public UserMessage(@Nullable String textContent) {
		this(textContent, new ArrayList<>(), Map.of());
	}

	private UserMessage(@Nullable String textContent, Collection<Media> media, Map<String, Object> metadata) {
		this(legacyParts(textContent, media), metadata);
	}

	public UserMessage(Resource resource) {
		this(MessageUtils.readResource(resource));
	}

	/**
	 * Creates a message from an ordered list of parts. A message without a
	 * {@link TextPart} (for example media only) has an empty text.
	 * @param parts the parts, in order
	 * @param metadata the message metadata
	 * @since 2.1.0
	 */
	protected UserMessage(List<MessagePart> parts, Map<String, Object> metadata) {
		super(MessageType.USER, joinText(parts), metadata);
		this.parts = List.copyOf(parts);
		this.media = this.parts.stream()
			.filter(MediaPart.class::isInstance)
			.map(MediaPart.class::cast)
			.map(MediaPart::media)
			.toList();
	}

	private static List<MessagePart> legacyParts(@Nullable String textContent, Collection<Media> media) {
		Assert.notNull(media, "media cannot be null");
		Assert.noNullElements(media, "media cannot have null elements");
		List<MessagePart> parts = new ArrayList<>();
		if (textContent != null) {
			parts.add(TextPart.of(textContent));
		}
		for (Media medium : media) {
			parts.add(MediaPart.of(medium));
		}
		return parts;
	}

	/**
	 * Joins the text parts without a separator. A single text part hands back its own
	 * string instance. Without any text part the text is {@code ""} when the message has
	 * other parts, and {@code null} for an empty part list so the legacy null-text check
	 * of {@link AbstractMessage} still applies.
	 */
	private static @Nullable String joinText(List<MessagePart> parts) {
		Assert.notNull(parts, "Parts must not be null");
		Assert.noNullElements(parts, "Parts must not contain null elements");
		String single = null;
		StringBuilder sb = null;
		for (MessagePart part : parts) {
			if (part instanceof TextPart textPart) {
				if (single == null && sb == null) {
					single = textPart.text();
				}
				else {
					if (sb == null) {
						sb = new StringBuilder(single);
						single = null;
					}
					sb.append(textPart.text());
				}
			}
		}
		if (sb != null) {
			return sb.toString();
		}
		if (single != null) {
			return single;
		}
		return parts.isEmpty() ? null : "";
	}

	@Override
	public String toString() {
		return "UserMessage{" + "content='" + getText() + '\'' + ", parts=" + this.parts + ", metadata=" + this.metadata
				+ ", messageType=" + this.messageType + '}';
	}

	/**
	 * The ordered parts of this message.
	 * @return an unmodifiable list of parts
	 * @since 2.1.0
	 */
	public List<MessagePart> getParts() {
		return this.parts;
	}

	@Override
	public List<Media> getMedia() {
		return this.media;
	}

	public UserMessage copy() {
		return mutate().build();
	}

	/**
	 * A builder pre-populated with this message's parts and metadata. Calling
	 * {@link Builder#text(String)} or {@link Builder#media(List)} on it replaces the
	 * corresponding parts, so rewriting the question keeps the media and vice versa.
	 */
	public Builder mutate() {
		return new Builder().parts(getParts()).metadata(Map.copyOf(getMetadata()));
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof UserMessage that)) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		return Objects.equals(this.parts, that.parts);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), this.parts);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link UserMessage}.
	 * <p>
	 * Parts added through {@link #part(MessagePart)} and {@link #parts(List)} keep their
	 * order. {@link #text(String)} and {@link #text(Resource)} replace the text parts:
	 * the new text takes the place of the first text part, or comes first when there is
	 * none. {@link #media(List)} replaces the media parts: the new media go where the
	 * first media part was, or last when there is none. A builder without explicit parts
	 * therefore produces the legacy order, text then media.
	 */
	public static final class Builder {

		private final List<MessagePart> explicitParts = new ArrayList<>();

		private @Nullable String textContent;

		private boolean textSet;

		private @Nullable Resource resource;

		private @Nullable List<Media> media;

		private Map<String, Object> metadata = new HashMap<>();

		/**
		 * Adds a part.
		 * @param part the part
		 * @return this builder
		 * @since 2.1.0
		 */
		public Builder part(MessagePart part) {
			Assert.notNull(part, "part cannot be null");
			this.explicitParts.add(part);
			return this;
		}

		/**
		 * Adds parts, in order.
		 * @param parts the parts
		 * @return this builder
		 * @since 2.1.0
		 */
		public Builder parts(List<MessagePart> parts) {
			Assert.notNull(parts, "parts cannot be null");
			Assert.noNullElements(parts, "parts cannot have null elements");
			this.explicitParts.addAll(parts);
			return this;
		}

		public Builder text(String textContent) {
			this.textContent = textContent;
			this.textSet = true;
			return this;
		}

		public Builder text(Resource resource) {
			this.resource = resource;
			return this;
		}

		public Builder media(List<Media> media) {
			this.media = media;
			return this;
		}

		public Builder media(Media... media) {
			this.media = Arrays.asList(media);
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public UserMessage build() {
			if (StringUtils.hasText(this.textContent) && this.resource != null) {
				throw new IllegalArgumentException("textContent and resource cannot be set at the same time");
			}
			String text = this.textContent;
			if (this.resource != null) {
				text = MessageUtils.readResource(this.resource);
			}
			if (this.explicitParts.isEmpty()) {
				// Legacy path: text then media; a null text fails as it always has
				return new UserMessage(text, this.media != null ? this.media : List.of(), this.metadata);
			}
			List<MessagePart> parts = new ArrayList<>(this.explicitParts);
			if (this.textSet || this.resource != null) {
				Assert.notNull(text, "Content must not be null for SYSTEM or USER messages");
				replace(parts, TextPart.class, List.of(TextPart.of(text)), 0);
			}
			if (this.media != null) {
				Assert.noNullElements(this.media, "media cannot have null elements");
				List<MessagePart> mediaParts = this.media.stream()
					.map(MediaPart::of)
					.map(MessagePart.class::cast)
					.toList();
				replace(parts, MediaPart.class, mediaParts, parts.size());
			}
			return new UserMessage(parts, this.metadata);
		}

		/**
		 * Removes every part of the given type and inserts the replacements where the
		 * first one was, or at {@code fallbackIndex} when there was none.
		 */
		private static void replace(List<MessagePart> parts, Class<? extends MessagePart> type,
				List<MessagePart> replacements, int fallbackIndex) {
			int insertAt = -1;
			for (int i = parts.size() - 1; i >= 0; i--) {
				if (type.isInstance(parts.get(i))) {
					parts.remove(i);
					insertAt = i;
				}
			}
			parts.addAll(insertAt >= 0 ? insertAt : Math.min(fallbackIndex, parts.size()), replacements);
		}

	}

}
