package io.github.rifatcakir.springai.testtools.recorder.track;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureSizeWarning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the large-fixture warning is actually wired into the write path, against real
 * Logback output rather than a mock — the decision logic itself is covered separately in
 * {@code VcrFixtureSizeWarningTests}, so what these tests exist to catch is the wiring
 * being silently absent.
 *
 * <p>Also pins the property that makes this feature safe to ship at all: the warning is
 * advisory, so a fixture above the threshold must still be written and still replay
 * byte-for-byte. A check that quietly truncated or refused a large recording would be
 * worse than no check.
 *
 * @author Rifat Cakir
 */
class VcrTrackStoreSizeWarningTests {

	private static final String HASH = "a".repeat(64);

	@TempDir
	Path cacheDirectory;

	private ch.qos.logback.classic.Logger storeLogger;

	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void attachAppender() {
		this.storeLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(VcrTrackStore.class);
		this.appender = new ListAppender<>();
		this.appender.start();
		this.storeLogger.addAppender(this.appender);
	}

	@AfterEach
	void detachAppender() {
		this.storeLogger.detachAppender(this.appender);
		this.appender.stop();
	}

	private VcrTrack trackWithTextOfSize(int characters) {
		VcrTrack.MessageSnapshot message = new VcrTrack.MessageSnapshot("user", "x".repeat(characters), List.of(),
				List.of(), List.of());
		VcrTrack.RequestSnapshot request = new VcrTrack.RequestSnapshot("llama3.2", null, null, null, null, List.of(),
				List.of(message), List.of(), null);
		VcrTrack.ResponseSnapshot response = new VcrTrack.ResponseSnapshot("id-1", "llama3.2",
				List.of(new VcrTrack.GenerationSnapshot("ok", "STOP", List.of())), null, Map.of());
		return new VcrTrack(VcrTrack.CURRENT_SCHEMA_VERSION, HASH, "2026-07-28T10:00:00Z", "canonical", request,
				response);
	}

	private List<String> warnings() {
		return this.appender.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	@Test
	@DisplayName("a fixture at or above the threshold is written and warned about")
	void largeFixtureWarns() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper(), 2048);

		store.write(trackWithTextOfSize(4096));

		assertThat(warnings()).hasSize(1);
		assertThat(warnings().get(0)).contains("LARGE FIXTURE")
			.contains("not readably diffable")
			.contains("Advisory only")
			.contains(HASH + ".json");
		// Locale-independent rendering, and ASCII-only. The exact fixture size is not
		// pinned (JSON framing makes it a few hundred bytes larger than the message text,
		// and pinning it would make this test brittle for no gain) -- what is pinned is
		// that sizes render with a dot decimal separator, which a tr_TR JVM would
		// otherwise render as "4,8 KB": a thousands separator to every other reader, and
		// not the syntax the property itself accepts. The threshold, being exactly 2048
		// bytes, is checked literally.
		assertThat(warnings().get(0)).contains("(threshold 2.0 KB)").containsPattern("is \\d+\\.\\d KB \\(threshold");
		assertThat(warnings().get(0)).matches("\\p{ASCII}+");
	}

	@Test
	@DisplayName("an ordinary fixture stays silent")
	void smallFixtureIsSilent() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper(), 65536);

		store.write(trackWithTextOfSize(64));

		assertThat(warnings()).isEmpty();
	}

	@Test
	@DisplayName("a realistically-sized fixture stays silent at the shipped default")
	void realisticFixtureIsSilentAtTheDefault() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper());

		// Comfortably larger than any fixture either repo commits today (largest chat
		// fixture: ~3.6 KB), and still silent — enabling this must not make an existing
		// healthy suite start warning.
		store.write(trackWithTextOfSize(30_000));

		assertThat(warnings()).isEmpty();
	}

	@Test
	@DisplayName("the warning never blocks the recording — an oversized fixture still replays intact")
	void oversizedFixtureStillReplaysIntact() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper(), 1024);
		VcrTrack written = trackWithTextOfSize(8192);

		store.write(written);

		assertThat(warnings()).hasSize(1);
		assertThat(store.read(HASH)).hasValueSatisfying(read -> {
			assertThat(read.request().messages().get(0).text()).isEqualTo(written.request().messages().get(0).text());
			assertThat(read.response().generations().get(0).text()).isEqualTo("ok");
		});
	}

	@Test
	@DisplayName("threshold 0 disables the warning entirely, however big the fixture gets")
	void thresholdZeroDisables() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper(), 0);

		store.write(trackWithTextOfSize(200_000));

		assertThat(warnings()).isEmpty();
	}

	@Test
	@DisplayName("the two-arg constructor keeps the documented default rather than silently disabling")
	void twoArgConstructorUsesTheDefaultThreshold() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory, VcrTrackStore.defaultJsonMapper());

		store.write(trackWithTextOfSize((int) VcrFixtureSizeWarning.DEFAULT_THRESHOLD_BYTES + 1));

		assertThat(warnings()).hasSize(1);
	}

}
