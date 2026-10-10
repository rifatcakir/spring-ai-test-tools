package io.github.rifatcakir.springai.testtools.recorder.junit;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolModeOverride;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Sets and clears {@link VcrToolModeOverride} around each test that carries a {@link
 * VcrTool} annotation, directly or via its enclosing class — the tool-isolation
 * counterpart of {@link VcrModeExtension}.
 *
 * <p>Package-private on purpose, same reasoning as {@link VcrModeExtension}: no code
 * ever names this class directly.
 *
 * @author Rifat Cakir
 */
class VcrToolModeExtension implements BeforeEachCallback, AfterEachCallback {

	@Override
	public void beforeEach(ExtensionContext context) {
		findVcrTool(context).ifPresent(vcrTool -> VcrToolModeOverride.set(vcrTool.mode()));
	}

	@Override
	public void afterEach(ExtensionContext context) {
		VcrToolModeOverride.clear();
	}

	/**
	 * A method-level {@link VcrTool} takes precedence over a class-level one.
	 */
	private Optional<VcrTool> findVcrTool(ExtensionContext context) {
		return AnnotationSupport.findAnnotation(context.getTestMethod(), VcrTool.class)
			.or(() -> AnnotationSupport.findAnnotation(context.getTestClass(), VcrTool.class));
	}

}
