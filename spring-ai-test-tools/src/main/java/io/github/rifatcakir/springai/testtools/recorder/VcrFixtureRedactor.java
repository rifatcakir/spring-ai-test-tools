package io.github.rifatcakir.springai.testtools.recorder;

import java.util.Objects;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;

/**
 * Rewrites a fixture immediately before it is written to disk, and never anywhere else.
 *
 * <p>{@link VcrPromptNormalizer} already lets a prompt be rewritten before hashing, but it does
 * double duty: the same rewrite both decides whether two requests share a cache entry <em>and</em>
 * is the only tool available for keeping something out of a committed fixture. Those are
 * different jobs. Normalizing away a value that the model genuinely conditions on — to keep it out
 * of version control — makes two requests that should be treated as different collide on one
 * fixture, which is precisely the failure mode design rule #1 (exact match only) exists to
 * prevent. A {@code VcrFixtureRedactor} is the second, narrower hook: it can change what a
 * reviewer sees in the committed JSON without being able to change whether a request hits the
 * cache.
 *
 * <p><strong>What is redacted is fixture content, not the cache key.</strong> A value this
 * redactor removes from {@link VcrTrack#request()}, {@link VcrTrack#response()} or
 * {@link VcrTrack#canonicalRequest()} still determines which fixture a prompt resolves to — it is
 * simply never written down. Two requests that differ only in a field a redactor strips will still
 * be treated as different requests and get different fixtures; redacting a field does not merge
 * it away the way a {@link VcrPromptNormalizer} would. This is the opposite trade-off from
 * normalizing: safe to use on real secrets or PII precisely because it cannot accidentally cause a
 * cache collision, at the cost of not being able to collapse volatile-but-harmless values the way a
 * normalizer can.
 *
 * <h2>A partial redaction is a silent leak, not a smaller one</h2>
 *
 * <p>{@link VcrTrack#canonicalRequest()} is a real, previously-shipped mistake, not a hypothetical
 * one: it is a top-level field, separate from {@link VcrTrack#request()}, that <em>also</em>
 * embeds the full message text a redactor might already believe it has scrubbed from
 * {@code request().messages()}. Redacting only the messages and leaving {@code canonicalRequest()}
 * untouched writes the exact same secret right back into the committed fixture through a different
 * field — the fixture looks redacted at a glance (the message text reads {@code [REDACTED]}) while
 * the raw value is still sitting a few lines further down in the same file. There is no field this
 * interface hides from a redactor to make this safe by default; every field capable of holding the
 * value being redacted has to be redacted, deliberately, by the implementation. When in doubt,
 * verify by reading the committed JSON itself, not by trusting that redacting one field was enough.
 *
 * <h2>Called only on the write path</h2>
 *
 * <p>Invoked once, on a cache miss, after the real model has already answered and after the hash
 * has already been computed from the un-redacted canonical request. It never runs on a replay: a
 * cache hit must return exactly what was recorded, or the cache stops meaning anything, so a
 * redactor cannot see or influence what a test observes — only what a reviewer sees in the
 * committed file.
 *
 * <h2>Cannot forge a different cache key</h2>
 *
 * <p>{@link VcrTrack#hash()} and {@link VcrTrack#schemaVersion()} on the track this method returns
 * are ignored: the caller always persists the hash it already computed and the current schema
 * version, regardless of what a redactor returns for those two fields. Rule #1 (exact SHA-256
 * match, no exceptions) cannot depend on every redactor implementation being trusted to leave the
 * hash alone, so the guarantee is enforced by the caller rather than merely documented here.
 *
 * <h2>Every fixture family</h2>
 *
 * <p>Chat fixtures are only one of four things this library writes. Streamed calls, tool
 * executions and embeddings carry the same prompts, tool arguments and results, and with {@code
 * spring.ai.test.vcr.store=remote} every one of them leaves the process. So each family has its
 * own method, applied on its own write path, with the same hash and schema-version guarantee:
 * <ul>
 * <li>{@link #redactStream(VcrStreamTrack)} defaults to {@link #redact(VcrTrack)}: a stream's
 * request side has exactly a chat fixture's shape, so an existing redactor covers it unchanged. Its
 * response is a chunk sequence, which cannot be derived back from a redacted whole; if the default
 * sees {@code redact} change the response, it fails rather than write chunks that still carry the
 * original text. Override it to redact chunks.</li>
 * <li>{@link #redactToolExecution(VcrToolExecutionTrack)} and {@link
 * #redactEmbedding(VcrEmbeddingTrack)} default to returning the fixture unchanged, because neither
 * shares a shape with a chat fixture that a chat redactor could be trusted to handle. A registered
 * redactor that keeps either default is reported with a {@code WARN} when tool isolation or
 * embedding caching wraps its bean, so the gap is visible rather than silent.</li>
 * </ul>
 *
 * <h2>Ordering and composition</h2>
 *
 * <p>Multiple redactors may be registered; they are applied in sequence, each seeing the previous
 * one's output — in {@link org.springframework.core.Ordered} sequence when collected by the
 * auto-configuration, or in whatever order a caller supplies when wired by hand. Use
 * {@link #andThen(VcrFixtureRedactor)} to compose two into one bean.
 *
 * <h2>A redactor that throws</h2>
 *
 * <p>Is not swallowed. {@link io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore} already lets
 * a genuine write failure (an {@code IOException}) propagate rather than silently drop a fixture,
 * and a throwing redactor is treated the same way for consistency: the exception propagates out of
 * the advisor call, so a redaction bug fails loudly instead of quietly shipping an unredacted — or
 * half-redacted — fixture.
 *
 * @author Rifat Cakir
 * @see VcrPromptNormalizer
 */
@FunctionalInterface
public interface VcrFixtureRedactor {

	/**
	 * Transform a fixture immediately before it is written.
	 * @param track the fixture as it would be written with no redaction applied; never
	 * {@code null}
	 * @return the fixture to actually write; never {@code null}. {@link VcrTrack#hash()} and
	 * {@link VcrTrack#schemaVersion()} are ignored by the caller
	 */
	VcrTrack redact(VcrTrack track);

	/**
	 * Transform a streamed-call fixture immediately before it is written.
	 *
	 * <p>The default runs {@link #redact(VcrTrack)} over a chat-shaped view of the stream (its
	 * canonical request, its request snapshot, and its review-only aggregate as the single
	 * generation) and keeps the redacted request side. If {@code redact} changes that view's
	 * response, the default throws: the replayed chunks would still hold the unredacted text, a
	 * partial redaction this interface treats as a leak. Override this method to redact {@link
	 * VcrStreamTrack.ResponseSnapshot#chunks()} as well.
	 * @param track the fixture as it would be written with no redaction applied; never {@code null}
	 * @return the fixture to actually write; never {@code null}. {@code hash()} and {@code
	 * schemaVersion()} are ignored by the caller
	 * @throws IllegalStateException if the default cannot redact the response faithfully
	 */
	default VcrStreamTrack redactStream(VcrStreamTrack track) {
		VcrTrack view = VcrFixtureRedaction.chatViewOf(track);
		VcrTrack redacted = redact(view);
		Objects.requireNonNull(redacted, "VcrFixtureRedactor must not return null");
		if (!Objects.equals(view.response(), redacted.response())) {
			throw new IllegalStateException("VcrFixtureRedactor " + this + " changed the response of streamed "
					+ "fixture " + track.hash() + ", but a stream replays its chunks, which cannot be derived from "
					+ "a redacted whole. Override VcrFixtureRedactor#redactStream to redact the chunks too; "
					+ "nothing was written.");
		}
		return new VcrStreamTrack(track.schemaVersion(), track.hash(), redacted.recordedAt(),
				redacted.canonicalRequest(), redacted.request(), track.response());
	}

	/**
	 * Transform a tool-execution fixture immediately before it is written. Returns it unchanged
	 * unless overridden; see "Every fixture family" above.
	 *
	 * <p>Mind what replays: a redacted {@code result} is what a later replay hands back to the
	 * model in place of the real tool's answer. Under {@code INSIDE_TOOL_LOOP} the next model turn
	 * is keyed on a prompt that contains that tool response, so redacting a value the recorded turn
	 * saw makes the replayed turn a different request and a cache miss. Redact what must not be
	 * stored, knowing the model's next turn will see the redacted form on replay.
	 * @param track the fixture as it would be written with no redaction applied; never {@code null}
	 * @return the fixture to actually write; never {@code null}. {@code hash()} and {@code
	 * schemaVersion()} are ignored by the caller
	 */
	default VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
		return track;
	}

	/**
	 * Transform an embedding fixture immediately before it is written. Returns it unchanged
	 * unless overridden; see "Every fixture family" above.
	 * @param track the fixture as it would be written with no redaction applied; never {@code null}
	 * @return the fixture to actually write; never {@code null}. {@code hash()} and {@code
	 * schemaVersion()} are ignored by the caller
	 */
	default VcrEmbeddingTrack redactEmbedding(VcrEmbeddingTrack track) {
		return track;
	}

	/**
	 * Compose this redactor with another, applying this one first, for every fixture family.
	 * @param next the redactor to apply to this one's output
	 * @return a composed redactor
	 */
	default VcrFixtureRedactor andThen(VcrFixtureRedactor next) {
		VcrFixtureRedactor first = this;
		return new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return next.redact(first.redact(track));
			}

			@Override
			public VcrStreamTrack redactStream(VcrStreamTrack track) {
				return next.redactStream(first.redactStream(track));
			}

			@Override
			public VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
				return next.redactToolExecution(first.redactToolExecution(track));
			}

			@Override
			public VcrEmbeddingTrack redactEmbedding(VcrEmbeddingTrack track) {
				return next.redactEmbedding(first.redactEmbedding(track));
			}

			@Override
			public String toString() {
				return first + " andThen " + next;
			}

		};
	}

}
