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

import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;

import org.springframework.ai.bedrock.converse.api.ConverseApiUtils;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.util.Assert;

/**
 * Internal Spring AI Bedrock value object that preserves a single Bedrock Converse
 * {@code reasoningContent} block so it can be replayed, unmodified, on a later request.
 * <p>
 * Amazon Bedrock requires that the assistant turn containing a {@code toolUse} block also
 * replays the preceding signed {@code reasoningContent} block when the matching
 * {@code toolResult} is sent. To honor that replay invariant this object captures the
 * reasoning text, its signature and any redacted content without modification and can
 * convert itself back into an AWS SDK {@link ContentBlock} via {@link #toContentBlock()}.
 * <p>
 * The reasoning text and signature are intentionally redacted from {@link #toString()}:
 * the signature is an opaque token used only for replay verification and has no reason to
 * leak into logs or assertion output.
 *
 * @author Jewoo Shin
 * @author Soby Chacko
 * @see BedrockAssistantMessage
 * @deprecated since 2.1.0, for removal; the reasoning is a {@link ReasoningPart} of the
 * assistant message whose {@link OpaquePayload} carries the signature
 * ({@link BedrockProxyChatModel#PAYLOAD_SIGNATURE}) or the redacted content
 * ({@link BedrockProxyChatModel#PAYLOAD_REDACTED_CONTENT}) under the
 * {@link BedrockProxyChatModel#BEDROCK_PROVIDER} provider. This class is now a view over
 * such a part.
 */
@Deprecated(since = "2.1.0", forRemoval = true)
final class BedrockReasoningContent {

	private final @Nullable String text;

	private final @Nullable String signature;

	private final byte @Nullable [] redactedContent;

	private BedrockReasoningContent(@Nullable String text, @Nullable String signature,
			byte @Nullable [] redactedContent) {
		this.text = text;
		this.signature = signature;
		this.redactedContent = redactedContent != null ? redactedContent.clone() : null;
	}

	/**
	 * Capture a Bedrock {@link ReasoningContentBlock} as a Spring AI value object,
	 * preserving its union member (reasoning text and signature, or redacted content)
	 * without modification.
	 * @param reasoningContentBlock the AWS SDK reasoning content block
	 * @return a value object that can replay the same reasoning block
	 */
	public static BedrockReasoningContent from(ReasoningContentBlock reasoningContentBlock) {
		Assert.notNull(reasoningContentBlock, "reasoningContentBlock must not be null");
		SdkBytes redacted = reasoningContentBlock.redactedContent();
		if (redacted != null) {
			return new BedrockReasoningContent(null, null, redacted.asByteArray());
		}
		ReasoningTextBlock reasoningText = reasoningContentBlock.reasoningText();
		if (reasoningText != null) {
			return new BedrockReasoningContent(reasoningText.text(), reasoningText.signature(), null);
		}
		throw new IllegalStateException(
				"Unexpected reasoningContent block: neither reasoningText nor redactedContent is set");
	}

	/**
	 * The view of a {@link ReasoningPart} that carries a Bedrock payload.
	 * @param reasoningPart the reasoning part
	 * @return the value object, or {@code null} when the part has no Bedrock payload
	 * (plain or foreign reasoning is not Bedrock reasoning content)
	 */
	static @Nullable BedrockReasoningContent from(ReasoningPart reasoningPart) {
		ContentBlock contentBlock = ConverseApiUtils.toReasoningContentBlock(reasoningPart);
		return contentBlock != null ? from(contentBlock.reasoningContent()) : null;
	}

	/**
	 * Convert this value object back into an AWS SDK {@link ContentBlock} carrying a
	 * {@code reasoningContent} block. The reasoning text and signature are replayed
	 * unmodified; signatures are never regenerated.
	 * @return the reasoning content block ready to be added to a Bedrock request
	 */
	public ContentBlock toContentBlock() {
		ReasoningContentBlock.Builder builder = ReasoningContentBlock.builder();
		if (this.redactedContent != null) {
			builder.redactedContent(SdkBytes.fromByteArray(this.redactedContent));
		}
		else {
			builder.reasoningText(ReasoningTextBlock.builder().text(this.text).signature(this.signature).build());
		}
		return ContentBlock.fromReasoningContent(builder.build());
	}

	/**
	 * The {@link ReasoningPart} equivalent of this value object: a signed text part, or a
	 * redacted part whose payload holds the Base64 encoded redacted content.
	 * @return the reasoning part
	 */
	ReasoningPart toReasoningPart() {
		return ConverseApiUtils.toReasoningPart(toContentBlock().reasoningContent());
	}

	public @Nullable String getText() {
		return this.text;
	}

	public @Nullable String getSignature() {
		return this.signature;
	}

	public byte @Nullable [] getRedactedContent() {
		return this.redactedContent != null ? this.redactedContent.clone() : null;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof BedrockReasoningContent that)) {
			return false;
		}
		return Objects.equals(this.text, that.text) && Objects.equals(this.signature, that.signature)
				&& Arrays.equals(this.redactedContent, that.redactedContent);
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(this.text, this.signature) + Arrays.hashCode(this.redactedContent);
	}

	@Override
	public String toString() {
		return "BedrockReasoningContent [text=" + (this.text != null ? "***redacted***" : "null") + ", signature="
				+ (this.signature != null ? "***redacted***" : "null") + ", redactedContent="
				+ (this.redactedContent != null ? this.redactedContent.length + " bytes" : "null") + "]";
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String text;

		private @Nullable String signature;

		private byte @Nullable [] redactedContent;

		private Builder() {
		}

		public Builder text(@Nullable String text) {
			this.text = text;
			return this;
		}

		public Builder signature(@Nullable String signature) {
			this.signature = signature;
			return this;
		}

		public Builder redactedContent(byte @Nullable [] redactedContent) {
			this.redactedContent = redactedContent != null ? redactedContent.clone() : null;
			return this;
		}

		public BedrockReasoningContent build() {
			if (this.text != null && this.signature == null) {
				throw new IllegalStateException("signature must be set when text is set");
			}
			return new BedrockReasoningContent(this.text, this.signature, this.redactedContent);
		}

	}

}
