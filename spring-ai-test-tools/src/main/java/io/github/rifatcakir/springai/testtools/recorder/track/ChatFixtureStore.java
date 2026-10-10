package io.github.rifatcakir.springai.testtools.recorder.track;

import java.util.Optional;

/**
 * Where {@link VcrTrack} fixtures for a {@code ChatClient} call are read from and written to.
 *
 * <p>The record/replay logic never depends on <em>where</em> a fixture lives, only on
 * looking one up by its hash and storing a new one. {@link VcrTrackStore} keeps them as files
 * under a cache directory, the default and the only implementation the library has had
 * so far; a remote implementation backed by the Cassette Server ({@code docs/SERVER-PRD.md})
 * stores them over HTTP instead. Keeping this to the operations the recorder actually
 * performs is deliberate: anything file-specific ({@code pathFor}, {@code
 * getCacheDirectory}) stays on the file implementation, where callers that genuinely need
 * a path can still reach it.
 *
 * <p>One interface per fixture family rather than one generic {@code FixtureStore<T>},
 * matching the decision recorded on the stores themselves not to generalize them.
 *
 * @author Rifat Cakir
 */
public interface ChatFixtureStore {

	/**
	 * The fixture stored under {@code hash}, or empty if there is none. A fixture that
	 * exists but cannot be read is reported as empty, not thrown: a corrupt fixture
	 * degrades to a cache miss, and in {@code REPLAY_ONLY} the caller turns that absence
	 * into a loud failure anyway.
	 */
	Optional<VcrTrack> read(String hash);

	/**
	 * Store {@code track} under its own hash, after a cache miss. A shared store may keep
	 * a fixture another instance recorded for the same hash in the meantime instead
	 * (PRD 5.3); the file store always writes.
	 */
	void write(VcrTrack track);

	/**
	 * Store {@code track} under its own hash, replacing any existing fixture: the write
	 * {@code RECORD_ALWAYS} makes, the one mode where the last write wins. A separate
	 * method rather than a mode on the store, because the effective mode is decided per
	 * call ({@code @Vcr} can override it for one test) and only the caller knows it.
	 * Defaults to {@link #write(VcrTrack)}, which for the file store already replaces.
	 */
	default void replace(VcrTrack track) {
		write(track);
	}

	/**
	 * A human-readable description of where the fixture for {@code hash} lives or would
	 * live, for log lines and {@link
	 * io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException} messages.
	 * Never parsed.
	 */
	String locationOf(String hash);

	/**
	 * A short name for the fixture of {@code hash}, for the {@code CACHE HIT} log line.
	 * Defaults to {@link #locationOf(String)}; the file store reports just the file name,
	 * as it always has.
	 */
	default String fixtureNameOf(String hash) {
		return locationOf(hash);
	}

}
