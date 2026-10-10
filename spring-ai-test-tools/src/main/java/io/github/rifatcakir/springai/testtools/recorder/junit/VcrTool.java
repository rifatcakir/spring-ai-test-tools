package io.github.rifatcakir.springai.testtools.recorder.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolMode;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolModeOverride;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Overrides the effective {@link VcrToolMode} for one test method, or every test in one
 * class, regardless of what {@code VcrToolCallingManager} was otherwise configured with
 * — restored automatically once each test completes. The tool-isolation counterpart of
 * {@link Vcr}, kept as its own annotation (mirroring {@link VcrToolMode} being its own
 * enum, independent of {@code VcrMode}) rather than a second attribute on {@code @Vcr},
 * so a test can select one, the other, or both without an "unset" sentinel value.
 *
 * <p>The escape hatch this exists for: the default, {@link VcrToolMode#REPLAY_FROM_CASSETTE},
 * means a real {@code @Tool} method never runs on a cassette hit — right up until one
 * test specifically wants to assert that a tool <em>was</em> actually invoked, with the
 * right arguments, the right number of times. {@code @VcrTool(mode =
 * VcrToolMode.EXECUTE_REAL)} lets that one test opt into real invocation without
 * weakening isolation for every other test in the same run:
 *
 * <pre>{@code
 * @Test
 * @VcrTool(mode = VcrToolMode.EXECUTE_REAL)
 * void assertsTheRealToolRan() {
 *     // the real @Tool method executes on every call, even on a replay
 * }
 * }</pre>
 *
 * <p>Applies to whichever thread runs the annotated test method, via {@link
 * VcrToolModeOverride} — the same blocking-call constraint {@link Vcr} already has.
 *
 * <p>A method-level {@code @VcrTool} overrides a class-level one. Neither is required: a
 * test with no {@code @VcrTool} anywhere runs under whatever mode {@code
 * VcrToolCallingManager} was actually configured with.
 *
 * @author Rifat Cakir
 * @see VcrToolModeExtension
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(VcrToolModeExtension.class)
public @interface VcrTool {

	/**
	 * The tool mode to use for the annotated test, or every test in the annotated class,
	 * instead of whatever {@code VcrToolCallingManager} was otherwise configured with.
	 */
	VcrToolMode mode();

}
