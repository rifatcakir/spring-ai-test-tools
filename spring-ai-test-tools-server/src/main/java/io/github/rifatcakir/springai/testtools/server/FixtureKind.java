package io.github.rifatcakir.springai.testtools.server;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The four fixture families the library records, as the server sees them.
 *
 * <p>{@link #directory()} is the library's own default cache directory for that family
 * ({@code VcrProperties}: {@code llm-cache}, {@code llm-cache-embedding}, {@code
 * llm-cache-tool}). Chat and stream fixtures share {@code llm-cache} on purpose, because
 * that is where the library's file store keeps both: their hashes are computed over
 * different canonical-form headers, so they can never collide on one file. Keeping the
 * library's layout exactly is what lets a namespace directory be copied into {@code
 * src/test/resources} and replay with {@code store: file}, and the reverse.
 *
 * <p>{@link #latestSchemaVersion()} mirrors each family's {@code CURRENT_SCHEMA_VERSION}
 * in the library. A document with a newer version is refused rather than stored: the
 * server cannot know what a future format means, and a loud {@code 400} that says
 * "upgrade the server" is better than silently storing something it then mislabels in
 * listings and exports. {@code fixture-contract/schema-versions.properties} at the
 * repository root pins these numbers, and both modules test against it.
 *
 * @author Rifat Cakir
 */
public enum FixtureKind {

	CHAT("chat", "llm-cache", 6),

	STREAM("stream", "llm-cache", 1),

	EMBEDDING("embedding", "llm-cache-embedding", 1),

	TOOL("tool", "llm-cache-tool", 1);

	private final String pathSegment;

	private final String directory;

	private final int latestSchemaVersion;

	FixtureKind(String pathSegment, String directory, int latestSchemaVersion) {
		this.pathSegment = pathSegment;
		this.directory = directory;
		this.latestSchemaVersion = latestSchemaVersion;
	}

	/**
	 * The {@code {kind}} segment in {@code /api/v1/ns/{ns}/{kind}/{hash}}.
	 */
	public String pathSegment() {
		return this.pathSegment;
	}

	/**
	 * The directory, under a namespace, this family's files live in.
	 */
	public String directory() {
		return this.directory;
	}

	public int latestSchemaVersion() {
		return this.latestSchemaVersion;
	}

	/**
	 * The kind for a URL path segment, or {@code null} for an unknown one.
	 */
	public static @Nullable FixtureKind fromPathSegment(String segment) {
		String normalized = segment.toLowerCase(Locale.ROOT);
		for (FixtureKind kind : values()) {
			if (kind.pathSegment.equals(normalized)) {
				return kind;
			}
		}
		return null;
	}

	/**
	 * Which family a document belongs to, judged by its shape alone.
	 *
	 * <p>Structural rather than by reading {@code canonicalRequest}'s header line: a
	 * {@code VcrFixtureRedactor} in the client is allowed to rewrite {@code
	 * canonicalRequest}, but none of these four markers is something a redactor has any
	 * reason to remove. Each marker is a field only that family's record has: {@code
	 * toolName} ({@code VcrToolExecutionTrack}), {@code response.chunks} ({@code
	 * VcrStreamTrack}), {@code response.embeddings} ({@code VcrEmbeddingTrack}) and
	 * {@code response.generations} ({@code VcrTrack}).
	 * @return the kind, or {@code null} if the document matches none
	 */
	public static @Nullable FixtureKind detect(JsonNode document) {
		if (document.path("toolName").isString()) {
			return TOOL;
		}
		JsonNode response = document.path("response");
		if (response.path("chunks").isArray()) {
			return STREAM;
		}
		if (response.path("embeddings").isArray()) {
			return EMBEDDING;
		}
		if (response.path("generations").isArray()) {
			return CHAT;
		}
		return null;
	}

}
