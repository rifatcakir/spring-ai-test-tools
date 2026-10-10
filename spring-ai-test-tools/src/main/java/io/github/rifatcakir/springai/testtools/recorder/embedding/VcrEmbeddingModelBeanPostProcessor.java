package io.github.rifatcakir.springai.testtools.recorder.embedding;

import java.util.List;
import java.util.function.Supplier;

import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedaction;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.Assert;

/**
 * Wraps every {@link EmbeddingModel} bean in the context with {@link VcrEmbeddingModel},
 * transparently, the same "application code never has a test-only branch" outcome
 * {@code ChatClientBuilderCustomizer} achieves for chat — just via a
 * {@link BeanPostProcessor} instead, because Spring AI gives {@code EmbeddingModel} no
 * builder to customize (see {@link VcrEmbeddingModel}'s Javadoc for the full reasoning).
 *
 * @author Rifat Cakir
 */
public class VcrEmbeddingModelBeanPostProcessor implements BeanPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(VcrEmbeddingModelBeanPostProcessor.class);

	private final VcrEmbeddingCacheKeyGenerator keyGenerator;

	private final EmbeddingFixtureStore store;

	private final VcrEmbeddingTrackMapper mapper;

	private final VcrMode mode;

	private final Supplier<List<VcrFixtureRedactor>> redactors;

	public VcrEmbeddingModelBeanPostProcessor(VcrEmbeddingCacheKeyGenerator keyGenerator, EmbeddingFixtureStore store,
			VcrEmbeddingTrackMapper mapper, VcrMode mode) {
		this(keyGenerator, store, mapper, mode, List::of);
	}

	/**
	 * @param redactors resolved when an {@code EmbeddingModel} is wrapped, not when this
	 * post-processor is created; see {@code VcrToolCallingManagerBeanPostProcessor} for why
	 */
	public VcrEmbeddingModelBeanPostProcessor(VcrEmbeddingCacheKeyGenerator keyGenerator, EmbeddingFixtureStore store,
			VcrEmbeddingTrackMapper mapper, VcrMode mode, Supplier<List<VcrFixtureRedactor>> redactors) {
		Assert.notNull(redactors, "redactors must not be null");
		this.redactors = redactors;
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mapper, "mapper must not be null");
		Assert.notNull(mode, "mode must not be null");
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mapper = mapper;
		this.mode = mode;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (!(bean instanceof EmbeddingModel embeddingModel) || bean instanceof VcrEmbeddingModel) {
			return bean;
		}
		logger.info("VCR EMBEDDING wrapping bean '{}' ({})", beanName, embeddingModel.getClass().getSimpleName());
		List<VcrFixtureRedactor> redactors = this.redactors.get();
		for (VcrFixtureRedactor redactor : VcrFixtureRedaction.passingEmbeddingsThrough(redactors)) {
			logger.warn("VCR EMBEDDING fixture redactor {} does not implement redactEmbedding, so embedding inputs "
					+ "are written without its redaction. Implement it, even as a pass-through, to make that "
					+ "explicit.", redactor);
		}
		return new VcrEmbeddingModel(embeddingModel, this.keyGenerator, this.store, this.mapper, this.mode,
				redactors);
	}

}
