package io.github.rifatcakir.springai.testtools.recorder;

import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;

import org.springframework.util.Assert;

/**
 * Applies {@link VcrFixtureRedactor}s on every write path: chat, stream, tool execution and
 * embedding.
 *
 * <p>One place for the loop so that the guarantee every path relies on is enforced the same
 * way four times: redactors run in order, each sees the previous one's output, a {@code null}
 * result fails loudly, and {@code hash} and {@code schemaVersion} are put back from the
 * original after every step. A redactor cannot file a fixture under a different key (design
 * rule #1), whichever family it is redacting.
 *
 * @author Rifat Cakir
 */
public final class VcrFixtureRedaction {

	private VcrFixtureRedaction() {
	}

	public static VcrTrack apply(List<VcrFixtureRedactor> redactors, VcrTrack track) {
		Assert.notNull(redactors, "redactors must not be null");
		Assert.notNull(track, "track must not be null");
		VcrTrack redacted = track;
		for (VcrFixtureRedactor redactor : redactors) {
			VcrTrack candidate = redactor.redact(redacted);
			Assert.notNull(candidate, "VcrFixtureRedactor must not return null");
			redacted = new VcrTrack(track.schemaVersion(), track.hash(), candidate.recordedAt(),
					candidate.canonicalRequest(), candidate.request(), candidate.response());
		}
		return redacted;
	}

	public static VcrStreamTrack apply(List<VcrFixtureRedactor> redactors, VcrStreamTrack track) {
		Assert.notNull(redactors, "redactors must not be null");
		Assert.notNull(track, "track must not be null");
		VcrStreamTrack redacted = track;
		for (VcrFixtureRedactor redactor : redactors) {
			VcrStreamTrack candidate = redactor.redactStream(redacted);
			Assert.notNull(candidate, "VcrFixtureRedactor must not return null");
			redacted = new VcrStreamTrack(track.schemaVersion(), track.hash(), candidate.recordedAt(),
					candidate.canonicalRequest(), candidate.request(), candidate.response());
		}
		return redacted;
	}

	public static VcrToolExecutionTrack apply(List<VcrFixtureRedactor> redactors, VcrToolExecutionTrack track) {
		Assert.notNull(redactors, "redactors must not be null");
		Assert.notNull(track, "track must not be null");
		VcrToolExecutionTrack redacted = track;
		for (VcrFixtureRedactor redactor : redactors) {
			VcrToolExecutionTrack candidate = redactor.redactToolExecution(redacted);
			Assert.notNull(candidate, "VcrFixtureRedactor must not return null");
			redacted = new VcrToolExecutionTrack(track.schemaVersion(), track.hash(), candidate.recordedAt(),
					candidate.canonicalRequest(), candidate.toolName(), candidate.arguments(), candidate.result(),
					candidate.returnDirect());
		}
		return redacted;
	}

	public static VcrEmbeddingTrack apply(List<VcrFixtureRedactor> redactors, VcrEmbeddingTrack track) {
		Assert.notNull(redactors, "redactors must not be null");
		Assert.notNull(track, "track must not be null");
		VcrEmbeddingTrack redacted = track;
		for (VcrFixtureRedactor redactor : redactors) {
			VcrEmbeddingTrack candidate = redactor.redactEmbedding(redacted);
			Assert.notNull(candidate, "VcrFixtureRedactor must not return null");
			redacted = new VcrEmbeddingTrack(track.schemaVersion(), track.hash(), candidate.recordedAt(),
					candidate.canonicalRequest(), candidate.request(), candidate.response());
		}
		return redacted;
	}

	/**
	 * The redactors that keep the pass-through default for tool executions, so whoever wraps
	 * the {@code ToolCallingManager} can say so. Best effort by design: it reads which class
	 * declares the method, which is exact for a class or lambda, while a composite from {@link
	 * VcrFixtureRedactor#andThen} always counts as implementing it.
	 */
	public static List<VcrFixtureRedactor> passingToolExecutionsThrough(List<VcrFixtureRedactor> redactors) {
		return redactors.stream()
			.filter(redactor -> keepsDefault(redactor, "redactToolExecution", VcrToolExecutionTrack.class))
			.toList();
	}

	/**
	 * The redactors that keep the pass-through default for embeddings; see {@link
	 * #passingToolExecutionsThrough(List)}.
	 */
	public static List<VcrFixtureRedactor> passingEmbeddingsThrough(List<VcrFixtureRedactor> redactors) {
		return redactors.stream()
			.filter(redactor -> keepsDefault(redactor, "redactEmbedding", VcrEmbeddingTrack.class))
			.toList();
	}

	private static boolean keepsDefault(VcrFixtureRedactor redactor, String method, Class<?> parameter) {
		try {
			return redactor.getClass().getMethod(method, parameter).getDeclaringClass() == VcrFixtureRedactor.class;
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * The chat-shaped view {@link VcrFixtureRedactor#redactStream(VcrStreamTrack)} hands to
	 * {@link VcrFixtureRedactor#redact(VcrTrack)} by default: the stream's own request side,
	 * and its review-only aggregate as the single generation, carrying the last model and usage
	 * any chunk reported.
	 */
	static VcrTrack chatViewOf(VcrStreamTrack track) {
		VcrStreamTrack.ResponseSnapshot response = track.response();
		String model = null;
		VcrTrack.UsageSnapshot usage = null;
		List<VcrTrack.GenerationSnapshot> generations = List.of();
		if (response != null) {
			if (response.chunks() != null) {
				for (VcrStreamTrack.ChunkSnapshot chunk : response.chunks()) {
					model = (chunk.model() != null) ? chunk.model() : model;
					usage = (chunk.usage() != null) ? chunk.usage() : usage;
				}
			}
			generations = List.of(new VcrTrack.GenerationSnapshot(response.aggregateText(),
					response.aggregateFinishReason(),
					(response.aggregateToolCalls() != null) ? response.aggregateToolCalls() : List.of()));
		}
		return new VcrTrack(VcrTrack.CURRENT_SCHEMA_VERSION, track.hash(), track.recordedAt(),
				track.canonicalRequest(), track.request(),
				new VcrTrack.ResponseSnapshot(null, model, generations, usage, Map.of()));
	}

}
