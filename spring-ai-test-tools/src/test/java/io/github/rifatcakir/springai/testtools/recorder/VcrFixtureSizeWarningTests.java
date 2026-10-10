package io.github.rifatcakir.springai.testtools.recorder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the decision logic behind the large-fixture warning.
 *
 * <p>A mocked {@link Logger} rather than a captured logging backend: what is being
 * asserted here is <i>whether</i> a warning is emitted for a given size and threshold,
 * which is a pure decision, and mocking keeps that independent of whichever logging
 * implementation happens to be on a consumer's classpath. The store-level counterpart —
 * that a store actually calls this on the write path — is asserted against real Logback
 * output in {@code VcrTrackStoreSizeWarningTests}.
 *
 * @author Rifat Cakir
 */
class VcrFixtureSizeWarningTests {

	@TempDir
	Path directory;

	private final Logger logger = mock(Logger.class);

	private Path fileOfSize(int bytes) throws Exception {
		Path path = this.directory.resolve("fixture.json");
		Files.writeString(path, "x".repeat(bytes), StandardCharsets.UTF_8);
		return path;
	}

	@Test
	@DisplayName("warns when the fixture is at or above the threshold")
	void warnsAtOrAboveThreshold() throws Exception {
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(2048), 1024);

		verify(this.logger).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("the threshold is inclusive — exactly at the limit still warns")
	void warnsExactlyAtThreshold() throws Exception {
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(1024), 1024);

		verify(this.logger).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("stays silent below the threshold")
	void silentBelowThreshold() throws Exception {
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(1023), 1024);

		verify(this.logger, never()).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("a zero threshold disables the check without even stat-ing the file")
	void zeroThresholdDisables() throws Exception {
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(1_000_000), 0);

		verify(this.logger, never()).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("a negative threshold disables the check too")
	void negativeThresholdDisables() throws Exception {
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(1_000_000), -1);

		verify(this.logger, never()).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("a missing file is not an error — the fixture was already written, so this must never throw")
	void missingFileNeverThrows() {
		Path absent = this.directory.resolve("does-not-exist.json");

		assertThatCode(() -> VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", absent, 1024))
			.doesNotThrowAnyException();

		verify(this.logger, never()).warn(anyString(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("the documented default is 256 KiB")
	void defaultThresholdIs256KiB() {
		assertThat(VcrFixtureSizeWarning.DEFAULT_THRESHOLD_BYTES).isEqualTo(262144L);
	}

	@Test
	@DisplayName("no fixture this project has ever committed comes close to the default threshold")
	void everyExistingFixtureStaysUnderTheDefault() throws Exception {
		// The largest committed fixture in the sibling example project is an embedding
		// vector at roughly 28 KB. Pinned as a test rather than left as a claim in a
		// comment: the point of the default is that enabling this feature cannot make an
		// existing, healthy suite start warning, and that is only true while this holds.
		VcrFixtureSizeWarning.warnIfLarge(this.logger, "VCR", fileOfSize(28_500),
				VcrFixtureSizeWarning.DEFAULT_THRESHOLD_BYTES);

		verify(this.logger, never()).warn(anyString(), any(), any(), any(), any(), any());
	}

}
