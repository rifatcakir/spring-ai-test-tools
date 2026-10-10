package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedaction;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.util.Assert;

/**
 * Intercepts {@code EmbeddingModel.call(EmbeddingRequest)} and serves it from a local
 * JSON fixture when one exists — the embedding counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor}.
 *
 * <h2>Why a decorator and not an advisor</h2>
 *
 * <p>Unlike {@code ChatClient}, {@code EmbeddingModel} has no advisor chain at all — it
 * is a bare {@code Model<EmbeddingRequest, EmbeddingResponse>} with one {@code call()}
 * method, and Spring AI 2.0 has no {@code EmbeddingModelBuilderCustomizer} equivalent to
 * {@code ChatClientBuilderCustomizer} (confirmed: no such type exists). So the only
 * provider-agnostic interception point is the {@code EmbeddingModel} bean itself — this
 * class wraps a delegate and is installed by a {@code BeanPostProcessor}
 * (see the autoconfigure package), not attached to a builder.
 *
 * <h2>Scope: {@code call(EmbeddingRequest)} only</h2>
 *
 * <p>{@code embed(String)}, {@code embed(List<String>)} and {@code embedForResponse
 * (List<String>)} are all default methods on {@code EmbeddingModel} that route through
 * {@code call(EmbeddingRequest)}, so overriding just that one method caches all of them
 * for free. {@code embed(Document)} is separately abstract — not a template method —
 * and is deliberately left as a pure pass-through to the delegate, uncached: it is a
 * RAG/vector-store ingestion concern with its own id/metadata shape, out of scope for
 * what this class exists for (a text-in, vector-out call a test can assert on
 * deterministically).
 *
 * <h2>Fixture schema</h2>
 *
 * <p>Uses {@link VcrEmbeddingTrack}, a type independent of {@code VcrTrack} — see that
 * class's Javadoc and {@code docs/R4-EMBEDDING-INTERCEPTION.md} for why, including the
 * explicitly accepted tradeoff that an embedding fixture's vector is not meaningfully
 * human-reviewable the way every other fixture type in this project is.
 *
 * <h2>Redaction</h2>
 *
 * <p>Registered {@link VcrFixtureRedactor}s run on every recording through {@link
 * VcrFixtureRedactor#redactEmbedding}, after the hash is computed, exactly as on the chat
 * path. Embedding inputs are the user's text verbatim, and with a remote store the fixture
 * leaves the process, so this path cannot be the one that skips redaction.
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingModel implements EmbeddingModel {

	private static final Logger logger = LoggerFactory.getLogger(VcrEmbeddingModel.class);

	private final EmbeddingModel delegate;

	private final VcrEmbeddingCacheKeyGenerator keyGenerator;

	private final EmbeddingFixtureStore store;

	private final VcrEmbeddingTrackMapper mapper;

	/** Asked once per call; see {@code DeterministicVcrAdvisor}'s {@code Supplier} constructor. */
	private final Supplier<VcrMode> mode;

	private final List<VcrFixtureRedactor> redactors;

	public VcrEmbeddingModel(EmbeddingModel delegate, VcrEmbeddingCacheKeyGenerator keyGenerator,
			EmbeddingFixtureStore store, VcrEmbeddingTrackMapper mapper, VcrMode mode) {
		this(delegate, keyGenerator, store, mapper, mode, List.of());
	}

	/**
	 * @param redactors applied in list order to every fixture before it is written; never
	 * affects the hash or what a replay returns, see {@link VcrFixtureRedactor}
	 */
	public VcrEmbeddingModel(EmbeddingModel delegate, VcrEmbeddingCacheKeyGenerator keyGenerator,
			EmbeddingFixtureStore store, VcrEmbeddingTrackMapper mapper, VcrMode mode,
			List<VcrFixtureRedactor> redactors) {
		this(delegate, keyGenerator, store, mapper, constant(mode), redactors);
	}

	/**
	 * @param mode asked once per call, so an application without its own embedding mode
	 * follows its Cassette Server namespace's default (PRD fork b); may throw, which fails
	 * the call before the model is reached
	 */
	public VcrEmbeddingModel(EmbeddingModel delegate, VcrEmbeddingCacheKeyGenerator keyGenerator,
			EmbeddingFixtureStore store, VcrEmbeddingTrackMapper mapper, Supplier<VcrMode> mode,
			List<VcrFixtureRedactor> redactors) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mapper, "mapper must not be null");
		Assert.notNull(mode, "mode must not be null");
		Assert.notNull(redactors, "redactors must not be null");
		this.redactors = List.copyOf(redactors);
		this.delegate = delegate;
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mapper = mapper;
		this.mode = mode;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		Assert.notNull(request, "request must not be null");

		VcrMode mode = this.mode.get();
		if (mode == VcrMode.BYPASS) {
			logger.debug("VCR EMBEDDING BYPASS — delegating to the real model");
			return this.delegate.call(request);
		}

		VcrEmbeddingCacheKey key = this.keyGenerator.generate(request);
		String shortHash = VcrEmbeddingTrackStore.shortHash(key.hash());

		if (mode == VcrMode.RECORD_ALWAYS) {
			logger.info("VCR EMBEDDING RE-RECORD [{}] — mode is RECORD_ALWAYS, ignoring any existing fixture",
					shortHash);
			return recordAndReturn(request, key, true);
		}

		Optional<VcrEmbeddingTrack> existing = this.store.read(key.hash());

		if (existing.isPresent()) {
			logger.info("VCR EMBEDDING CACHE HIT  [{}] replaying {}", shortHash,
					this.store.fixtureNameOf(key.hash()));
			return this.mapper.toEmbeddingResponse(existing.get());
		}

		if (mode == VcrMode.REPLAY_ONLY) {
			logger.error("VCR EMBEDDING CACHE MISS [{}] in REPLAY_ONLY — refusing to call the real model", shortHash);
			throw cacheMiss(key);
		}

		logger.info("VCR EMBEDDING CACHE MISS [{}] invoking the real model and recording", shortHash);
		return recordAndReturn(request, key, false);
	}

	/**
	 * @param replaceExisting {@code true} under {@code RECORD_ALWAYS}, the one mode where
	 * the last write wins; a plain miss lets a shared store keep a fixture another instance
	 * recorded first (PRD 5.3)
	 */
	private EmbeddingResponse recordAndReturn(EmbeddingRequest request, VcrEmbeddingCacheKey key,
			boolean replaceExisting) {
		EmbeddingResponse response = this.delegate.call(request);
		VcrEmbeddingTrack track = VcrFixtureRedaction.apply(this.redactors, this.mapper.toTrack(key, request, response));
		if (replaceExisting) {
			this.store.replace(track);
		}
		else {
			this.store.write(track);
		}
		return response;
	}

	private static Supplier<VcrMode> constant(VcrMode mode) {
		Assert.notNull(mode, "mode must not be null");
		return () -> mode;
	}

	/**
	 * Uncached pass-through — see this class's Javadoc for why {@code embed(Document)}
	 * is out of scope for record/replay.
	 */
	@Override
	public float[] embed(Document document) {
		return this.delegate.embed(document);
	}

	/**
	 * The file store keeps handing {@link VcrCacheMissException} a real {@code Path}, so
	 * {@code getExpectedPath()} keeps working; any other store reports its
	 * {@code locationOf} string instead.
	 */
	private VcrCacheMissException cacheMiss(VcrEmbeddingCacheKey key) {
		if (this.store instanceof VcrEmbeddingTrackStore files) {
			return new VcrCacheMissException(key.hash(), files.pathFor(key.hash()), key.canonicalRequest());
		}
		return new VcrCacheMissException(key.hash(), this.store.locationOf(key.hash()), key.canonicalRequest());
	}

}
