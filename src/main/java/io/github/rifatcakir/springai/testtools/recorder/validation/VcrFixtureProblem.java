package io.github.rifatcakir.springai.testtools.recorder.validation;

import java.nio.file.Path;

/**
 * One thing wrong with one committed fixture, found by {@link VcrFixtureValidator}.
 *
 * <p>Never thrown, never logged by the validator itself — a list of these is the return
 * value, so a caller (a CI test, a build step) decides what "found any problems" means
 * for its own build: fail loudly, log a warning, whatever fits.
 *
 * @param file the fixture file this problem was found in
 * @param reason a human-readable description of what is wrong, specific enough to act on
 * without re-deriving the check
 * @author Rifat Cakir
 */
public record VcrFixtureProblem(Path file, String reason) {

	@Override
	public String toString() {
		return this.file + ": " + this.reason;
	}

}
