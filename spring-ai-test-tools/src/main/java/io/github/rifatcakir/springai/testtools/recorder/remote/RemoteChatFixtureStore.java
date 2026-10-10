package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;

import io.github.rifatcakir.springai.testtools.recorder.track.ChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

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

	private static final Logger logger = LoggerFactory.getLogger(RemoteChatFixtureStore.class);

	private static final CassetteServerClient.Kind KIND = CassetteServerClient.Kind.CHAT;

	private final CassetteServerClient client;

	private final JsonMapper jsonMapper;

	public RemoteChatFixtureStore(CassetteServerClient client) {
		this(client, VcrTrackStore.defaultJsonMapper());
	}

	public RemoteChatFixtureStore(CassetteServerClient client, JsonMapper jsonMapper) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.client = client;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public Optional<VcrTrack> read(String hash) {
		Assert.hasText(hash, "hash must not be empty");
		Optional<String> json = this.client.get(KIND, hash);
		if (json.isEmpty()) {
			logger.debug("VCR no remote fixture at {}", locationOf(hash));
			return Optional.empty();
		}
		try {
			return Optional.of(this.jsonMapper.readValue(json.get(), VcrTrack.class));
		}
		catch (RuntimeException ex) {
			logger.warn("VCR remote fixture {} is malformed; treating as a cache miss. Delete it and re-record.",
					locationOf(hash), ex);
			return Optional.empty();
		}
	}

	@Override
	public void write(VcrTrack track) {
		put(track, false);
	}

	@Override
	public void replace(VcrTrack track) {
		put(track, true);
	}

	private void put(VcrTrack track, boolean overwrite) {
		Assert.notNull(track, "track must not be null");
		String json = this.jsonMapper.writeValueAsString(track);
		CassetteServerClient.WriteOutcome outcome = this.client.put(KIND, track.hash(), json, overwrite);
		String shortHash = VcrTrackStore.shortHash(track.hash());
		switch (outcome) {
			case STORED -> logger.info("VCR RECORDED  [{}] -> {}", shortHash, locationOf(track.hash()));
			case REPLACED -> logger.info("VCR RE-RECORDED [{}] -> {}", shortHash, locationOf(track.hash()));
			case KEPT_EXISTING -> logger.info("VCR NOT RECORDED [{}] another instance recorded {} first; the server "
					+ "kept that fixture and this one was discarded", shortHash, locationOf(track.hash()));
		}
	}

	@Override
	public String locationOf(String hash) {
		return this.client.locationOf(KIND, hash);
	}

	@Override
	public String fixtureNameOf(String hash) {
		return this.client.fixtureNameOf(KIND, hash);
	}

}
