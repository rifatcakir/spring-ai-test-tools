package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.stream.StreamFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link VcrStreamTrack} streaming fixtures on a Cassette Server instead of local disk
 * ({@code spring.ai.test.vcr.store=remote}), under kind {@code stream}.
 *
 * <p>The streaming counterpart of {@link RemoteChatFixtureStore}, with the same guarantees:
 * the document sent is exactly what {@link VcrStreamTrackStore} would have written, an
 * unreadable document is a miss, and a server failure is a {@link
 * CassetteServerException}, never a miss. Stored under its own kind even though the file store shares a directory with chat fixtures: the server keeps that same shared directory on disk, but a lookup must never return a chat document for a stream hash.
 *
 * @author Rifat Cakir
 */
public class RemoteStreamFixtureStore implements StreamFixtureStore {

	private final RemoteFixtureDocuments<VcrStreamTrack> documents;

	public RemoteStreamFixtureStore(CassetteServerClient client) {
		this(client, VcrStreamTrackStore.defaultJsonMapper());
	}

	public RemoteStreamFixtureStore(CassetteServerClient client, JsonMapper jsonMapper) {
		this.documents = new RemoteFixtureDocuments<>(client, CassetteServerClient.Kind.STREAM, VcrStreamTrack.class,
				VcrStreamTrack::hash, jsonMapper, LoggerFactory.getLogger(RemoteStreamFixtureStore.class), "VCR STREAM");
	}

	@Override
	public Optional<VcrStreamTrack> read(String hash) {
		return this.documents.read(hash);
	}

	@Override
	public void write(VcrStreamTrack track) {
		this.documents.put(track, false);
	}

	@Override
	public void replace(VcrStreamTrack track) {
		this.documents.put(track, true);
	}

	@Override
	public String locationOf(String hash) {
		return this.documents.locationOf(hash);
	}

	@Override
	public String fixtureNameOf(String hash) {
		return this.documents.fixtureNameOf(hash);
	}

}
