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

package org.springframework.ai.bedrock.converse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.util.CollectionUtils;

/**
 * Internal Bedrock-specific {@link AssistantMessage} that exposes the signed
 * {@code reasoningContent} blocks of an assistant turn as {@link BedrockReasoningContent}
 * objects.
 * <p>
 * The reasoning lives in the message's {@link ReasoningPart}s, whose payload carries the
 * Bedrock signature or redacted content; {@link #getReasoningContents()} is a view over
 * those parts and the builder's {@link Builder#reasoningContents(List)} materializes its
 * argument as such parts, placed before the text.
 *
 * @author Jewoo Shin
 * @author Soby Chacko
 * @author guan xu
 * @see BedrockReasoningContent
 * @deprecated since 2.1.0, for removal; the adapter reads and replays the
 * {@link ReasoningPart}s of any {@link AssistantMessage}, so a Bedrock-specific subclass
 * is no longer needed. Use {@link AssistantMessage#getReasoning()}.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
final class BedrockAssistantMessage extends AssistantMessage {

	private final List<BedrockReasoningContent> reasoningContents;

	private BedrockAssistantMessage(List<MessagePart> parts, Map<String, Object> properties) {
		super(parts, properties);
		List<BedrockReasoningContent> contents = new ArrayList<>();
		for (ReasoningPart reasoningPart : getReasoning()) {
			BedrockReasoningContent content = BedrockReasoningContent.from(reasoningPart);
			if (content != null) {
				contents.add(content);
			}
		}
		this.reasoningContents = List.copyOf(contents);
	}

	/**
	 * The Bedrock reasoning of this message, derived from its {@link ReasoningPart}s that
	 * carry a Bedrock payload.
	 * @return the reasoning contents, in part order
	 */
	List<BedrockReasoningContent> getReasoningContents() {
		return this.reasoningContents;
	}

	boolean hasReasoningContents() {
		return !CollectionUtils.isEmpty(this.reasoningContents);
	}

	@Override
	public Builder mutate() {
		return builder().parts(getParts()).properties(getMetadata());
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BedrockAssistantMessage that)) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}
		return Objects.equals(this.reasoningContents, that.reasoningContents);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), this.reasoningContents);
	}

	@Override
	public String toString() {
		return "BedrockAssistantMessage [messageType=" + this.messageType + ", parts=" + getParts() + ", textContent="
				+ this.textContent + ", reasoningContents=" + this.reasoningContents + ", metadata=" + this.metadata
				+ "]";
	}

	public static Builder builder() {
		return new Builder();
	}

	static final class Builder extends AssistantMessage.Builder<Builder> {

		private List<BedrockReasoningContent> reasoningContents = List.of();

		private Builder() {
		}

		Builder reasoningContents(List<BedrockReasoningContent> reasoningContents) {
			this.reasoningContents = reasoningContents;
			return self();
		}

		@Override
		protected boolean supportsParts() {
			return true;
		}

		/**
		 * The legacy reasoning contents come first, then the explicit parts, then the
		 * legacy text, tool calls and media.
		 */
		@Override
		protected List<MessagePart> buildParts() {
			List<MessagePart> parts = new ArrayList<>();
			for (BedrockReasoningContent reasoningContent : this.reasoningContents) {
				parts.add(reasoningContent.toReasoningPart());
			}
			parts.addAll(super.buildParts());
			return parts;
		}

		@Override
		public BedrockAssistantMessage build() {
			return new BedrockAssistantMessage(buildParts(), this.properties);
		}

	}

}
