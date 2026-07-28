package io.github.rifatcakir.springai.testtools.recorder.tool;

/**
 * The result of hashing one tool invocation (name + exact argument string) — the
 * tool-execution counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey} and {@code
 * io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKey}, kept
 * as its own type for the same reason those two are separate from each other: a tool
 * invocation is not a chat request or an embedding request, and the three hash families
 * must never collide by construction (see {@link VcrToolExecutionCacheKeyGenerator}'s own
 * canonical-form header).
 *
 * @param hash lowercase SHA-256 hex digest, 64 characters
 * @param canonicalRequest the exact string the digest was computed over
 * @author Rifat Cakir
 */
public record VcrToolExecutionCacheKey(String hash, String canonicalRequest) {
}
