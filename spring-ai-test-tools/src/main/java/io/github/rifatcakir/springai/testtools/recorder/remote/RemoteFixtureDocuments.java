package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * What the four remote stores have in common: turning a fixture into the document the file
 * store would have written, and a server answer back into a fixture or a miss.
 *
 * <p>Package-private on purpose. The public types stay one store per family (PRD fork e),
 * matching the file stores, which were deliberately never generalized; this only keeps the
 * four from carrying four copies of the same miss-versus-corrupt-versus-conflict logic.
 *
 * @param <T> the fixture record
 * @author Rifat Cakir
 */
final class RemoteFixtureDocuments<T> {

	private final CassetteServerClient client;

	private final CassetteServerClient.Kind kind;

	private final Class<T> type;

	private final Function<T, String> hashOf;

	private final JsonMapper jsonMapper;

	private final Logger logger;

	private final String logPrefix;

	RemoteFixtureDocuments(CassetteServerClient client, CassetteServerClient.Kind kind, Class<T> type,
			Function<T, String> hashOf, JsonMapper jsonMapper, Logger logger, String logPrefix) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.client = client;
		this.kind = kind;
		this.type = type;
		this.hashOf = hashOf;
		this.jsonMapper = jsonMapper;
		this.logger = logger;
		this.logPrefix = logPrefix;
	}

	/**
	 * The fixture for {@code hash}; empty on {@code 404} and on a document this library
	 * cannot read (design rule #7). Any server failure propagates as {@link
	 * CassetteServerException}.
	 */
	Optional<T> read(String hash) {
		Assert.hasText(hash, "hash must not be empty");
		Optional<String> json = this.client.get(this.kind, hash);
		if (json.isEmpty()) {
			this.logger.debug("{} no remote fixture at {}", this.logPrefix, locationOf(hash));
			return Optional.empty();
		}
		try {
			return Optional.of(this.jsonMapper.readValue(json.get(), this.type));
		}
		catch (RuntimeException ex) {
			this.logger.warn("{} remote fixture {} is malformed; treating as a cache miss. Delete it and re-record.",
					this.logPrefix, locationOf(hash), ex);
			return Optional.empty();
		}
	}

	void put(T track, boolean overwrite) {
		Assert.notNull(track, "track must not be null");
		String hash = this.hashOf.apply(track);
		String json = this.jsonMapper.writeValueAsString(track);
		CassetteServerClient.WriteOutcome outcome = this.client.put(this.kind, hash, json, overwrite);
		String shortHash = (hash.length() < 12) ? hash : hash.substring(0, 12);
		switch (outcome) {
			case STORED -> this.logger.info("{} RECORDED  [{}] -> {}", this.logPrefix, shortHash, locationOf(hash));
			case REPLACED -> this.logger.info("{} RE-RECORDED [{}] -> {}", this.logPrefix, shortHash,
					locationOf(hash));
			case KEPT_EXISTING -> this.logger.info("{} NOT RECORDED [{}] another instance recorded {} first; the "
					+ "server kept that fixture and this one was discarded", this.logPrefix, shortHash,
					locationOf(hash));
		}
	}

	String locationOf(String hash) {
		return this.client.locationOf(this.kind, hash);
	}

	String fixtureNameOf(String hash) {
		return this.client.fixtureNameOf(this.kind, hash);
	}

}
