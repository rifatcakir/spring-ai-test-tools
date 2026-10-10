package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.tool.ToolFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps {@link VcrToolExecutionTrack} tool-execution fixtures on a Cassette Server instead of local disk
 * ({@code spring.ai.test.vcr.store=remote}), under kind {@code tool}.
 *
 * <p>The tool-execution counterpart of {@link RemoteChatFixtureStore}, with the same guarantees:
 * the document sent is exactly what {@link VcrToolExecutionTrackStore} would have written, an
 * unreadable document is a miss, and a server failure is a {@link
 * CassetteServerException}, never a miss. With this store a remote hit isolates the real {@code @Tool} method exactly as a local hit does: tool isolation stays in {@code VcrToolCallingManager}, only the fixture moves.
 *
 * @author Rifat Cakir
 */
public class RemoteToolFixtureStore implements ToolFixtureStore {

	private final RemoteFixtureDocuments<VcrToolExecutionTrack> documents;

	public RemoteToolFixtureStore(CassetteServerClient client) {
		this(client, VcrToolExecutionTrackStore.defaultJsonMapper());
	}

	public RemoteToolFixtureStore(CassetteServerClient client, JsonMapper jsonMapper) {
		this.documents = new RemoteFixtureDocuments<>(client, CassetteServerClient.Kind.TOOL, VcrToolExecutionTrack.class,
				VcrToolExecutionTrack::hash, jsonMapper, LoggerFactory.getLogger(RemoteToolFixtureStore.class), "VCR TOOL");
	}

	@Override
	public Optional<VcrToolExecutionTrack> read(String hash) {
		return this.documents.read(hash);
	}

	@Override
	public void write(VcrToolExecutionTrack track) {
		this.documents.put(track, false);
	}

	@Override
	public void replace(VcrToolExecutionTrack track) {
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
