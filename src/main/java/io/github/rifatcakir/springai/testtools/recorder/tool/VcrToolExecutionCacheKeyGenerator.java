package io.github.rifatcakir.springai.testtools.recorder.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.util.Assert;

/**
 * Computes the deterministic SHA-256 cache key for one tool invocation — the
 * tool-execution counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator} and {@code
 * io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator}.
 *
 * <p><strong>Hashed exactly as received, never re-serialized or re-canonicalized</strong>
 * — the same design rule #1 discipline (exact match, no fuzzy matching, ever) already
 * governing every other hash in this project. Two argument strings that are semantically
 * equal but syntactically different (key order, whitespace) are deliberately treated as
 * different invocations and get two fixtures — this mirrors {@code
 * VcrTrack.ToolCallSnapshot#arguments()}'s own documented behaviour ("as the model
 * returned them — not parsed or validated by this library"), applied here to the
 * argument string used as a cache key rather than just as a stored field.
 *
 * <p>This also means a genuinely non-idempotent tool (a random number generator, a
 * counter) called twice with identical arguments and expecting two different results is
 * not supported — the same accepted limitation this project already documents for a
 * model call recorded at {@code temperature > 0} (see {@code record-replay.md}'s
 * Limitations section): a fixture freezes one sample, not the underlying behaviour.
 *
 * @author Rifat Cakir
 */
public class VcrToolExecutionCacheKeyGenerator {

	private static final String FIELD_SEPARATOR = "\n";

	private static final String NULL_TOKEN = " null";

	/**
	 * Compute the cache key for one tool invocation.
	 * @param toolName the tool's name, as the model addressed it
	 * @param arguments the exact argument JSON string the model produced, or {@code null}
	 * if the model supplied none
	 * @return the digest and the canonical string it was derived from
	 */
	public VcrToolExecutionCacheKey generate(String toolName, String arguments) {
		Assert.hasText(toolName, "toolName must not be empty");
		String canonical = canonicalize(toolName, arguments);
		return new VcrToolExecutionCacheKey(sha256Hex(canonical), canonical);
	}

	/**
	 * Build the canonical, line-oriented representation of one tool invocation. Exposed
	 * as {@code protected} so a project with an exotic requirement can override the
	 * contract, but overriding it changes every existing tool-execution hash.
	 */
	protected String canonicalize(String toolName, String arguments) {
		StringBuilder sb = new StringBuilder(128);
		sb.append("vcr-tool-canonical-form/v1").append(FIELD_SEPARATOR);
		sb.append("name=").append(escape(toolName)).append(FIELD_SEPARATOR);
		sb.append("arguments=").append(escape(arguments == null ? NULL_TOKEN : arguments)).append(FIELD_SEPARATOR);
		return sb.toString();
	}

	/**
	 * Prevent a newline inside a tool name or argument string from forging an extra
	 * canonical field — same reasoning as {@code VcrCacheKeyGenerator#escape}.
	 */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 is mandated by the JLS for every conforming JRE.
			throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
		}
	}

}
