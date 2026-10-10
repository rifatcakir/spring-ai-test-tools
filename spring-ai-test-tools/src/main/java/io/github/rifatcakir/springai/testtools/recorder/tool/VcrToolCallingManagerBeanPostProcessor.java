package io.github.rifatcakir.springai.testtools.recorder.tool;

import java.util.List;
import java.util.function.Supplier;

import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedaction;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.Assert;

/**
 * Wraps the {@link ToolCallingManager} bean in the context with {@link
 * VcrToolCallingManager}, transparently — the tool-isolation counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModelBeanPostProcessor},
 * same mechanism, different bean type.
 *
 * <p>{@code ToolCallingManager} is a singleton bean registered by Spring AI's own {@code
 * ToolCallingAutoConfiguration} and consumed by {@code
 * ChatClientAutoConfiguration}'s {@code toolCallingAdvisorBuilder}, which threads it into
 * the auto-configured {@code ChatClient.Builder}'s {@code ToolCallingAdvisor} — see {@code
 * docs/TOOL-ISOLATION-PRD.md} section 1.3 for the bytecode confirming this is the one
 * bean every tool invocation passes through, and section 1.4 for the diagnostic probe
 * that confirmed a {@code BeanPostProcessor} here survives that autoconfiguration graph
 * intact. Section 1.5 for the one thing this cannot reach: a plain, non-Spring {@code
 * ChatClient.builder(model)} call builds its own private {@code ToolCallingManager}
 * inline, with no bean to post-process — the same Spring-context-only scope the rest of
 * this library's mechanisms already have.
 *
 * @author Rifat Cakir
 */
public class VcrToolCallingManagerBeanPostProcessor implements BeanPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(VcrToolCallingManagerBeanPostProcessor.class);

	private final VcrToolExecutionCacheKeyGenerator keyGenerator;

	private final ToolFixtureStore store;

	private final VcrToolMode mode;

	private final Supplier<List<VcrFixtureRedactor>> redactors;

	public VcrToolCallingManagerBeanPostProcessor(VcrToolExecutionCacheKeyGenerator keyGenerator,
			ToolFixtureStore store, VcrToolMode mode) {
		this(keyGenerator, store, mode, List::of);
	}

	/**
	 * @param redactors resolved when the {@code ToolCallingManager} is wrapped, not when this
	 * post-processor is created: a {@code BeanPostProcessor} is instantiated before ordinary
	 * beans, and resolving the user's redactor beans that early would make them ineligible
	 * for post-processing themselves
	 */
	public VcrToolCallingManagerBeanPostProcessor(VcrToolExecutionCacheKeyGenerator keyGenerator,
			ToolFixtureStore store, VcrToolMode mode, Supplier<List<VcrFixtureRedactor>> redactors) {
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mode, "mode must not be null");
		Assert.notNull(redactors, "redactors must not be null");
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mode = mode;
		this.redactors = redactors;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (!(bean instanceof ToolCallingManager toolCallingManager) || bean instanceof VcrToolCallingManager) {
			return bean;
		}
		logger.info("VCR TOOL wrapping bean '{}' ({})", beanName, toolCallingManager.getClass().getSimpleName());
		List<VcrFixtureRedactor> redactors = this.redactors.get();
		for (VcrFixtureRedactor redactor : VcrFixtureRedaction.passingToolExecutionsThrough(redactors)) {
			logger.warn("VCR TOOL fixture redactor {} does not implement redactToolExecution, so tool arguments "
					+ "and results are written without its redaction. Implement it, even as a pass-through, "
					+ "to make that explicit.", redactor);
		}
		return new VcrToolCallingManager(toolCallingManager, this.keyGenerator, this.store, this.mode, redactors);
	}

}
