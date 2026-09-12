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

package org.springframework.ai.bedrock.converse.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;

import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;

/**
 * Amazon Bedrock Converse API utils.
 *
 * @author Wei Jiang
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @since 1.0.0
 */
public final class ConverseApiUtils {

	/**
	 * Provider tag of the {@link OpaquePayload} on parts produced by the Bedrock Converse
	 * chat model.
	 * @since 2.1.0
	 */
	public static final String BEDROCK_PROVIDER = "bedrock";

	/**
	 * Payload kind of a signed {@link ReasoningPart}: the data is the Bedrock signature
	 * of the reasoning text, replayed unmodified.
	 * @since 2.1.0
	 */
	public static final String PAYLOAD_SIGNATURE = "signature";

	/**
	 * Payload kind of a redacted {@link ReasoningPart}: the data is the Base64 encoded
	 * redacted content Bedrock returned, replayed unmodified.
	 * @since 2.1.0
	 */
	public static final String PAYLOAD_REDACTED_CONTENT = "redacted_content";

	private ConverseApiUtils() {
	}

	/**
	 * Map a Bedrock {@code reasoningContent} block to a {@link ReasoningPart}: signed
	 * text becomes a text part whose payload is the signature; redacted content becomes a
	 * redacted part whose payload is the Base64 encoded bytes. Both replay unmodified
	 * through {@link #toReasoningContentBlock(ReasoningPart)}.
	 * @param reasoningContent the Bedrock reasoning block
	 * @return the reasoning part
	 * @since 2.1.0
	 */
	public static ReasoningPart toReasoningPart(ReasoningContentBlock reasoningContent) {
		SdkBytes redacted = reasoningContent.redactedContent();
		if (redacted != null) {
			return new ReasoningPart(null, null, true, new OpaquePayload(BEDROCK_PROVIDER, PAYLOAD_REDACTED_CONTENT,
					Base64.getEncoder().encodeToString(redacted.asByteArray())), Map.of());
		}
		ReasoningTextBlock reasoningText = reasoningContent.reasoningText();
		if (reasoningText != null) {
			OpaquePayload payload = reasoningText.signature() != null
					? new OpaquePayload(BEDROCK_PROVIDER, PAYLOAD_SIGNATURE, reasoningText.signature()) : null;
			return new ReasoningPart(reasoningText.text(), null, false, payload, Map.of());
		}
		throw new IllegalStateException(
				"Unexpected reasoningContent block: neither reasoningText nor redactedContent is set");
	}

	/**
	 * Map a {@link ReasoningPart} back to the Bedrock {@code reasoningContent} block it
	 * came from. Only parts with a Bedrock payload are replayable: Bedrock rejects
	 * unsigned reasoning, and reasoning signed by another provider is skipped.
	 * @param reasoningPart the reasoning part
	 * @return the content block, or {@code null} when the part has no Bedrock payload
	 * @since 2.1.0
	 */
	public static @Nullable ContentBlock toReasoningContentBlock(ReasoningPart reasoningPart) {
		OpaquePayload payload = reasoningPart.payload();
		if (payload == null || !BEDROCK_PROVIDER.equals(payload.provider())) {
			return null;
		}
		ReasoningContentBlock.Builder builder = ReasoningContentBlock.builder();
		if (PAYLOAD_REDACTED_CONTENT.equals(payload.kind())) {
			builder.redactedContent(SdkBytes.fromByteArray(Base64.getDecoder().decode(payload.data())));
		}
		else if (PAYLOAD_SIGNATURE.equals(payload.kind())) {
			builder.reasoningText(
					ReasoningTextBlock.builder().text(reasoningPart.text()).signature(payload.data()).build());
		}
		else {
			return null;
		}
		return ContentBlock.fromReasoningContent(builder.build());
	}

	@SuppressWarnings("unchecked")
	public static Document convertObjectToDocument(Object value) {
		if (value == null) {
			return Document.fromNull();
		}
		else if (value instanceof String stringValue) {
			return Document.fromString(stringValue);
		}
		else if (value instanceof Boolean booleanValue) {
			return Document.fromBoolean(booleanValue);
		}
		else if (value instanceof Integer integerValue) {
			return Document.fromNumber(integerValue);
		}
		else if (value instanceof Long longValue) {
			return Document.fromNumber(longValue);
		}
		else if (value instanceof Float floatValue) {
			return Document.fromNumber(floatValue);
		}
		else if (value instanceof Double doubleValue) {
			return Document.fromNumber(doubleValue);
		}
		else if (value instanceof BigDecimal bigDecimalValue) {
			return Document.fromNumber(bigDecimalValue);
		}
		else if (value instanceof BigInteger bigIntegerValue) {
			return Document.fromNumber(bigIntegerValue);
		}
		else if (value instanceof List listValue) {
			return Document.fromList(listValue.stream().map(v -> convertObjectToDocument(v)).toList());
		}
		else if (value instanceof Map mapValue) {
			return convertMapToDocument(mapValue);
		}
		else {
			throw new IllegalArgumentException("Unsupported value type:" + value.getClass().getSimpleName());
		}
	}

	public static Map<String, String> getRequestMetadata(Map<String, Object> metadata) {

		if (metadata.isEmpty()) {
			return Map.of();
		}

		Map<String, String> result = new HashMap<>();
		for (Map.Entry<String, Object> entry : metadata.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();

			if (key != null && value != null) {
				result.put(key, value.toString());
			}
		}

		return result;
	}

	private static Document convertMapToDocument(Map<String, Object> value) {
		Map<String, Document> attr = value.entrySet()
			.stream()
			.collect(Collectors.toMap(e -> e.getKey(), e -> convertObjectToDocument(e.getValue())));

		return Document.fromMap(attr);
	}

}
