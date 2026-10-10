package io.github.rifatcakir.springai.testtools.server;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One line of {@code GET /__admin/namespaces/{ns}/fixtures}: enough to recognize a
 * fixture without opening it.
 *
 * <p>The only place the server reads past {@code schemaVersion}, {@code hash} and kind,
 * and only for display: every field is optional, and a document that lacks one simply
 * shows {@code null}. Nothing here influences what is stored or served.
 *
 * @param kind the fixture family
 * @param hash the SHA-256 the fixture is filed under
 * @param recordedAt when the client recorded it, as the client wrote it
 * @param model the requested model, when the document names one
 * @param summary the first user message (chat, stream), the first input (embedding) or
 * {@code toolName(arguments)} (tool), truncated
 * @author Rifat Cakir
 */
public record FixtureSummary(FixtureKind kind, String hash, @Nullable String recordedAt, @Nullable String model,
		@Nullable String summary) {

	static FixtureSummary of(FixtureKind kind, String hash, JsonNode document, int maxLength) {
		JsonNode request = document.path("request");
		String summary = switch (kind) {
			case CHAT, STREAM -> firstUserMessage(request.path("messages"));
			case EMBEDDING -> text(request.path("inputs").path(0));
			case TOOL -> text(document.path("toolName")) + "(" + text(document.path("arguments"), "") + ")";
		};
		String model = text(request.path("model"));
		if (model == null) {
			model = text(document.path("response").path("model"));
		}
		return new FixtureSummary(kind, hash, text(document.path("recordedAt")), model, truncate(summary, maxLength));
	}

	private static @Nullable String firstUserMessage(JsonNode messages) {
		for (JsonNode message : messages) {
			if ("user".equalsIgnoreCase(text(message.path("type"), ""))) {
				return text(message.path("text"));
			}
		}
		return null;
	}

	private static @Nullable String text(JsonNode node) {
		return node.isString() ? node.stringValue() : null;
	}

	private static String text(JsonNode node, String fallback) {
		return node.isString() ? node.stringValue() : fallback;
	}

	private static @Nullable String truncate(@Nullable String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength - 1) + "…";
	}

}
