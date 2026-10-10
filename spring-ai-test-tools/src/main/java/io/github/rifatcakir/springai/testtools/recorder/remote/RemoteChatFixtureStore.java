package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.track.ChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link VcrTrack} chat fixtures on a Cassette Server instead of local disk
 * ({@code spring.ai.test.vcr.store=remote}).
 *
 * <p>Only where fixtures live changes. The hash, hit/miss decision, model call and
 * redaction all stay in {@code DeterministicVcrAdvisor}, so the server receives exactly
 * the document the file store would have written: serialized by the same {@link
 * VcrTrackStore#defaultJsonMapper()}, byte for byte. That is what lets a server namespace
 * be exported into {@code src/test/resources} and replay with {@code store: file}.
 *
 * <p>A document the server returns but this library cannot read degrades to a miss, as a
 * corrupt local file does (design rule #7). Everything else that goes wrong with the
 * server is a {@link CassetteServerException} from {@link CassetteServerClient}: an
 * outage is never mistaken for a miss, so the real model is never called because the
 * server blipped.
 *
 * @author Rifat Cakir
 */
public class RemoteChatFixtureStore implements ChatFixtureStore {

	private final RemoteFixtureDocuments<VcrTrack> documents;

	public RemoteChatFixtureStore(CassetteServerClient client) {
		this(client, VcrTrackStore.defaultJsonMapper());
	}

	public RemoteChatFixtureStore(CassetteServerClient client, JsonMapper jsonMapper) {
		this.documents = new RemoteFixtureDocuments<>(client, CassetteServerClient.Kind.CHAT, VcrTrack.class,
				VcrTrack::hash, jsonMapper, LoggerFactory.getLogger(RemoteChatFixtureStore.class), "VCR");
	}

	@Override
	public Optional<VcrTrack> read(String hash) {
		return this.documents.read(hash);
	}

	@Override
	public void write(VcrTrack track) {
		this.documents.put(track, false);
	}

	@Override
	public void replace(VcrTrack track) {
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
