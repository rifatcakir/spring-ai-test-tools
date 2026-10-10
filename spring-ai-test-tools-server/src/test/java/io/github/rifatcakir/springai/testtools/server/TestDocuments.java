package io.github.rifatcakir.springai.testtools.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Minimal, hand-written fixture documents of each kind, shaped like the library's records
 * but no richer than the server needs. The real, library-written samples live in {@code
 * fixture-contract/} and are exercised by {@link FixtureContractTests}.
 */
final class TestDocuments {

	private TestDocuments() {
	}

	static String hash(String seed) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static byte[] chat(String hash) {
		return chat(hash, "Paris.");
	}

	static byte[] chat(String hash, String answer) {
		return """
				{
				  "schemaVersion" : "6",
				  "hash" : "%s",
				  "recordedAt" : "2026-10-10T00:00:00Z",
				  "canonicalRequest" : "vcr-canonical-form/v1\\nmessage[user]=Capital of France?\\n",
				  "request" : {
				    "model" : "llama3.2:1b",
				    "messages" : [ { "type" : "system", "text" : "Be brief." }, { "type" : "user", "text" : "Capital of France?" } ]
				  },
				  "response" : {
				    "model" : "llama3.2:1b",
				    "generations" : [ { "text" : "%s", "finishReason" : "STOP", "toolCalls" : [ ] } ]
				  }
				}
				""".formatted(hash, answer).getBytes(StandardCharsets.UTF_8);
	}

	static byte[] stream(String hash) {
		return """
				{
				  "schemaVersion" : "1",
				  "hash" : "%s",
				  "recordedAt" : "2026-10-10T00:00:00Z",
				  "canonicalRequest" : "vcr-stream-canonical-form/v1\\n",
				  "request" : { "model" : "llama3.2:1b", "messages" : [ { "type" : "user", "text" : "Stream it" } ] },
				  "response" : { "chunks" : [ { "text" : "Par" }, { "text" : "is." } ], "aggregateText" : "Paris." }
				}
				""".formatted(hash).getBytes(StandardCharsets.UTF_8);
	}

	static byte[] embedding(String hash) {
		return """
				{
				  "schemaVersion" : "1",
				  "hash" : "%s",
				  "recordedAt" : "2026-10-10T00:00:00Z",
				  "canonicalRequest" : "vcr-embedding-canonical-form/v1\\n",
				  "request" : { "model" : "nomic-embed-text", "inputs" : [ "hello world" ] },
				  "response" : { "model" : "nomic-embed-text", "embeddings" : [ { "index" : 0, "vector" : [ 0.013228971, -0.043682855 ] } ] }
				}
				""".formatted(hash).getBytes(StandardCharsets.UTF_8);
	}

	static byte[] tool(String hash) {
		return """
				{
				  "schemaVersion" : "1",
				  "hash" : "%s",
				  "recordedAt" : "2026-10-10T00:00:00Z",
				  "canonicalRequest" : "vcr-tool-canonical-form/v1\\n",
				  "toolName" : "currentWeather",
				  "arguments" : "{\\"city\\":\\"Paris\\"}",
				  "result" : "18C",
				  "returnDirect" : false
				}
				""".formatted(hash).getBytes(StandardCharsets.UTF_8);
	}

	static byte[] of(FixtureKind kind, String hash) {
		return switch (kind) {
			case CHAT -> chat(hash);
			case STREAM -> stream(hash);
			case EMBEDDING -> embedding(hash);
			case TOOL -> tool(hash);
		};
	}

}
