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

package org.springframework.ai.deepseek;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.content.Media;
import org.springframework.util.StringUtils;

/**
 * DeepSeek-specific {@link AssistantMessage} that exposes the reasoning content and the
 * prefix-completion flag through dedicated accessors.
 * <p>
 * Both are now carried by the plain {@link AssistantMessage}: the reasoning content is a
 * {@link ReasoningPart} in {@link #getParts()} (see {@link #getReasoning()}), and the
 * prefix flag is the {@link DeepSeekChatModel#PREFIX_METADATA_KEY} metadata entry. This
 * class keeps the old accessors working on top of those and remains the type returned by
 * {@link DeepSeekChatModel} for one release, so existing casts keep working.
 *
 * @author Mark Pollack
 * @author Soby Chacko
 * @author Sun Yuhan
 * @author guan xu
 * @deprecated since 2.1.0 for removal; read the reasoning through
 * {@link AssistantMessage#getReasoning()} and build prefix messages with
 * {@code AssistantMessage.builder().content(prefix).properties(Map.of(DeepSeekChatModel.PREFIX_METADATA_KEY, true))}.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
public class DeepSeekAssistantMessage extends AssistantMessage {

	private @Nullable Boolean prefix;

	private @Nullable String reasoningContent;

	protected DeepSeekAssistantMessage(@Nullable String content, @Nullable String reasoningContent,
			@Nullable Boolean prefix, Map<String, Object> properties, List<ToolCall> toolCalls, List<Media> media) {
		this(legacyParts(content, reasoningContent, toolCalls, media), withPrefix(properties, prefix));
		// Keep the exact value that was set, even when empty and therefore not a part.
		this.reasoningContent = reasoningContent;
	}

	/**
	 * Creates a message from an ordered list of parts. The reasoning content is derived
	 * from the {@link ReasoningPart}s without payload and the prefix flag from the
	 * {@link DeepSeekChatModel#PREFIX_METADATA_KEY} metadata entry.
	 * @param parts the parts, in order
	 * @param properties the message metadata
	 * @since 2.1.0
	 */
	protected DeepSeekAssistantMessage(List<MessagePart> parts, Map<String, Object> properties) {
		super(parts, properties);
		this.reasoningContent = joinReasoning(getReasoning());
		this.prefix = prefixFrom(getMetadata());
	}

	private static List<MessagePart> legacyParts(@Nullable String content, @Nullable String reasoningContent,
			List<ToolCall> toolCalls, List<Media> media) {
		List<MessagePart> parts = new ArrayList<>();
		if (StringUtils.hasLength(reasoningContent)) {
			parts.add(ReasoningPart.of(reasoningContent));
		}
		if (content != null) {
			parts.add(TextPart.of(content));
		}
		for (ToolCall toolCall : toolCalls) {
			parts.add(ToolCallPart.of(toolCall));
		}
		for (Media medium : media) {
			parts.add(MediaPart.of(medium));
		}
		return parts;
	}

	private static Map<String, Object> withPrefix(Map<String, Object> properties, @Nullable Boolean prefix) {
		if (prefix == null) {
			return properties;
		}
		Map<String, Object> result = new LinkedHashMap<>(properties);
		result.put(DeepSeekChatModel.PREFIX_METADATA_KEY, prefix);
		return result;
	}

	private static @Nullable Boolean prefixFrom(Map<String, Object> metadata) {
		return metadata.get(DeepSeekChatModel.PREFIX_METADATA_KEY) instanceof Boolean prefix ? prefix : null;
	}

	private static @Nullable String joinReasoning(List<ReasoningPart> reasoning) {
		StringBuilder text = null;
		for (ReasoningPart part : reasoning) {
			if (part.payload() == null && part.text() != null) {
				text = (text != null ? text : new StringBuilder()).append(part.text());
			}
		}
		return text != null ? text.toString() : null;
	}

	@Deprecated(forRemoval = true, since = "2.0.0")
	public static DeepSeekAssistantMessage prefixAssistantMessage(@Nullable String content) {
		return prefixAssistantMessage(content, null);
	}

	@Deprecated(forRemoval = true, since = "2.0.0")
	public static DeepSeekAssistantMessage prefixAssistantMessage(@Nullable String content,
			@Nullable String reasoningContent) {
		return new Builder().content(content).prefix(true).reasoningContent(reasoningContent).build();
	}

	/**
	 * The prefix-completion flag, read from the
	 * {@link DeepSeekChatModel#PREFIX_METADATA_KEY} metadata entry.
	 * @return the flag, or {@code null} when not set
	 */
	public @Nullable Boolean getPrefix() {
		return this.prefix;
	}

	/**
	 * Sets the prefix-completion flag, also updating the
	 * {@link DeepSeekChatModel#PREFIX_METADATA_KEY} metadata entry.
	 * @param prefix the flag
	 */
	public void setPrefix(Boolean prefix) {
		this.prefix = prefix;
		getMetadata().put(DeepSeekChatModel.PREFIX_METADATA_KEY, prefix);
	}

	/**
	 * The reasoning content: the joined text of the {@link ReasoningPart}s without
	 * payload, unless overridden through {@link #setReasoningContent(String)}.
	 * @return the reasoning content, or {@code null} when there is none
	 */
	public @Nullable String getReasoningContent() {
		return this.reasoningContent;
	}

	/**
	 * Overrides the reasoning content returned by {@link #getReasoningContent()}. The
	 * parts of this message are immutable and are not changed; build a new message to
	 * change the {@link ReasoningPart}s.
	 * @param reasoningContent the reasoning content
	 */
	public void setReasoningContent(@Nullable String reasoningContent) {
		this.reasoningContent = reasoningContent;
	}

	/**
	 * A builder pre-populated with this message's parts and metadata. A reasoning content
	 * set through {@link #setReasoningContent(String)} is carried over only when the
	 * message has no {@link ReasoningPart}; the parts are the source of truth otherwise.
	 */
	@Override
	public Builder mutate() {
		Builder builder = builder().parts(getParts()).properties(getMetadata()).prefix(getPrefix());
		if (getReasoning().isEmpty()) {
			builder.reasoningContent(getReasoningContent());
		}
		return builder;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DeepSeekAssistantMessage that)) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		return Objects.equals(this.reasoningContent, that.reasoningContent) && Objects.equals(this.prefix, that.prefix);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), this.prefix, this.reasoningContent);
	}

	@Override
	public String toString() {
		return "DeepSeekAssistantMessage [messageType=" + this.messageType + ", parts=" + getParts() + ", textContent="
				+ this.textContent + ", reasoningContent=" + this.reasoningContent + ", prefix=" + this.prefix
				+ ", metadata=" + this.metadata + "]";
	}

	public static Builder builder() {
		return new Builder();
	}

	// public Builder class exposed to users. Avoids having to deal with noisy generic
	// parameters.
	public static class Builder extends AbstractBuilder<Builder> {

	}

	public static class AbstractBuilder<B extends AbstractBuilder<B>> extends AssistantMessage.Builder<B> {

		protected @Nullable Boolean prefix;

		protected @Nullable String reasoningContent;

		public B prefix(@Nullable Boolean prefix) {
			this.prefix = prefix;
			return self();
		}

		public B reasoningContent(@Nullable String reasoningContent) {
			this.reasoningContent = reasoningContent;
			return self();
		}

		@Override
		protected boolean supportsParts() {
			return true;
		}

		/**
		 * Explicit parts first, then the reasoning content as a {@link ReasoningPart},
		 * then the legacy text, tool calls and media.
		 */
		@Override
		protected List<MessagePart> buildParts() {
			List<MessagePart> parts = super.buildParts();
			if (StringUtils.hasLength(this.reasoningContent)) {
				parts.add(this.explicitParts.size(), ReasoningPart.of(this.reasoningContent));
			}
			return parts;
		}

		@Override
		public DeepSeekAssistantMessage build() {
			DeepSeekAssistantMessage message = new DeepSeekAssistantMessage(buildParts(),
					withPrefix(this.properties, this.prefix));
			if (this.reasoningContent != null && message.getReasoning().isEmpty()) {
				// An empty reasoning content is not a part but is still reported as set.
				message.setReasoningContent(this.reasoningContent);
			}
			return message;
		}

	}

}
