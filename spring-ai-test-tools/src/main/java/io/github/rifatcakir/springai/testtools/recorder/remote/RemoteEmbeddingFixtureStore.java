package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.embedding.EmbeddingFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link VcrEmbeddingTrack} embedding fixtures on a Cassette Server instead of local disk
 * ({@code spring.ai.test.vcr.store=remote}), under kind {@code embedding}.
 *
 * <p>The embedding counterpart of {@link RemoteChatFixtureStore}, with the same guarantees:
 * the document sent is exactly what {@link VcrEmbeddingTrackStore} would have written, an
 * unreadable document is a miss, and a server failure is a {@link
 * CassetteServerException}, never a miss. The server stores the document byte for byte, which matters most here: vectors are floats, and any re-serialization on the way would change their digits.
 *
 * @author Rifat Cakir
 */
public class RemoteEmbeddingFixtureStore implements EmbeddingFixtureStore {

	private final RemoteFixtureDocuments<VcrEmbeddingTrack> documents;

	public RemoteEmbeddingFixtureStore(CassetteServerClient client) {
		this(client, VcrEmbeddingTrackStore.defaultJsonMapper());
	}

	public RemoteEmbeddingFixtureStore(CassetteServerClient client, JsonMapper jsonMapper) {
		this.documents = new RemoteFixtureDocuments<>(client, CassetteServerClient.Kind.EMBEDDING, VcrEmbeddingTrack.class,
				VcrEmbeddingTrack::hash, jsonMapper, LoggerFactory.getLogger(RemoteEmbeddingFixtureStore.class), "VCR EMBEDDING");
	}

	@Override
	public Optional<VcrEmbeddingTrack> read(String hash) {
		return this.documents.read(hash);
	}

	@Override
	public void write(VcrEmbeddingTrack track) {
		this.documents.put(track, false);
	}

	@Override
	public void replace(VcrEmbeddingTrack track) {
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
