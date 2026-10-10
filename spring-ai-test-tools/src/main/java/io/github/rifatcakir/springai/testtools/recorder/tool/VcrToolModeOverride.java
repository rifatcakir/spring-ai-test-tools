package io.github.rifatcakir.springai.testtools.recorder.tool;

import java.util.Optional;

/**
 * A thread-scoped override for the {@link VcrToolMode} {@link VcrToolCallingManager}
 * would otherwise use — the tool-isolation counterpart of {@code
 * io.github.rifatcakir.springai.testtools.recorder.VcrModeOverride}, kept as a separate
 * type for the same reason {@link VcrToolMode} is a separate enum from {@code VcrMode}:
 * the two answer different questions and are set independently.
 *
 * <p>Deliberately a plain {@link ThreadLocal}, not a {@code Map} keyed by test identity —
 * same reasoning as {@code VcrModeOverride}: the manager is only ever invoked
 * synchronously, on whatever thread calls {@code ChatClient.call()}, so the override only
 * needs to follow that one thread for the duration of one test.
 *
 * @author Rifat Cakir
 */
public final class VcrToolModeOverride {

	private static final ThreadLocal<VcrToolMode> OVERRIDE = new ThreadLocal<>();

	private VcrToolModeOverride() {
	}

	/**
	 * Set the tool-mode override for the calling thread. Cleared with {@link #clear()}; a
	 * JUnit extension should always pair a {@code set} in {@code beforeEach} with a
	 * {@code clear} in {@code afterEach}, regardless of test outcome.
	 * @param mode the mode to use instead of whatever {@link VcrToolCallingManager} was
	 * configured with
	 */
	public static void set(VcrToolMode mode) {
		OVERRIDE.set(mode);
	}

	/**
	 * Remove any override for the calling thread. Safe to call even when no override is
	 * active.
	 */
	public static void clear() {
		OVERRIDE.remove();
	}

	/**
	 * The override active for the calling thread, if any.
	 * @return the overridden mode, or empty if the calling thread has none set
	 */
	public static Optional<VcrToolMode> current() {
		return Optional.ofNullable(OVERRIDE.get());
	}

}
