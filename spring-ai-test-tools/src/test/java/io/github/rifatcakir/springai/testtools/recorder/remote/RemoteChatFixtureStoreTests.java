package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link RemoteChatFixtureStore} against a stub server: what goes over the wire, and how
 * each server answer maps onto a hit, a miss or a loud failure.
 *
 * @author Rifat Cakir
 */
class RemoteChatFixtureStoreTests {

	private StubCassetteServer server;

	@BeforeEach
	void start() {
		this.server = StubCassetteServer.start();
	}

	@AfterEach
	void stop() {
		this.server.close();
	}

	private static VcrTrack track(String answer) {
		Prompt prompt = new Prompt(List.of(new UserMessage("Capital of France?")));
		VcrCacheKey key = new VcrCacheKeyGenerator().generate(prompt);
		ChatResponse response = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage(answer))))
			.build();
		return new VcrTrackMapper().toTrack(key, prompt, Map.of(), response);
	}

	@Test
	@DisplayName("a write sends exactly the bytes the file store would have written")
	void writeSendsTheFileStoresBytes(@TempDir Path directory) throws Exception {
		VcrTrack track = track("Paris.");
		new VcrTrackStore(directory).write(track);

		new RemoteChatFixtureStore(this.server.client("svc")).write(track);

		assertThat(this.server.count("PUT")).isEqualTo(1);
		StubCassetteServer.Request put = this.server.requests().get(0);
		assertThat(put.path()).isEqualTo("svc/chat/" + track.hash());
		assertThat(put.query()).isNull();
		assertThat(put.body()).isEqualTo(Files.readString(directory.resolve(track.hash() + ".json"), StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("a stored document reads back as the same track")
	void readRoundTrips() {
		RemoteChatFixtureStore store = new RemoteChatFixtureStore(this.server.client("svc"));
		VcrTrack track = track("Paris.");
		store.write(track);

		assertThat(store.read(track.hash())).hasValueSatisfying(read -> {
			assertThat(read.hash()).isEqualTo(track.hash());
			assertThat(read.response().generations().get(0).text()).isEqualTo("Paris.");
		});
		assertThat(this.server.count("GET")).isEqualTo(1);
	}

	@Test
	@DisplayName("404 is a miss")
	void notFoundIsAMiss() {
		assertThat(new RemoteChatFixtureStore(this.server.client("svc")).read(track("x").hash())).isEmpty();
		assertThat(this.server.count("GET")).isEqualTo(1);
	}

	@Test
	@DisplayName("a document the library cannot read degrades to a miss, like a corrupt local file")
	void malformedDocumentIsAMiss() {
		String hash = track("x").hash();
		this.server.store("svc", "chat", hash, "{ \"hash\": [ not json");

		assertThat(new RemoteChatFixtureStore(this.server.client("svc")).read(hash)).isEmpty();
	}

	@Test
	@DisplayName("a 5xx fails loudly, naming the server URL and namespace")
	void serverErrorFailsLoudly() {
		this.server.failWith(503);

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> new RemoteChatFixtureStore(this.server.client("svc")).read(track("x").hash()))
			.withMessageContaining("HTTP 503")
			.withMessageContaining(this.server.url())
			.withMessageContaining("namespace 'svc'")
			.withMessageContaining("Not calling the real model");
	}

	@Test
	@DisplayName("an unreachable server fails loudly instead of reading as a miss")
	void unreachableServerFailsLoudly() {
		CassetteServerClient client = this.server.client("svc");
		this.server.close();

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> new RemoteChatFixtureStore(client).read(track("x").hash()))
			.withMessageContaining("unreachable");
	}

	@Test
	@DisplayName("a slow server times out and fails loudly")
	void readTimeoutFailsLoudly() {
		this.server.delayResponses(Duration.ofMillis(800));

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> new RemoteChatFixtureStore(this.server.client("svc", null, Duration.ofMillis(100)))
				.read(track("x").hash()))
			.withMessageContaining("timed out");
	}

	@Test
	@DisplayName("409 on write: no error, and the server keeps the document that was there first")
	void conflictKeepsTheExistingDocument() {
		VcrTrack track = track("second");
		this.server.store("svc", "chat", track.hash(), "first-recording");

		new RemoteChatFixtureStore(this.server.client("svc")).write(track);

		assertThat(this.server.count("PUT")).isEqualTo(1);
		assertThat(this.server.document("svc", "chat", track.hash())).isEqualTo("first-recording");
	}

	@Test
	@DisplayName("replace() asks the server to overwrite")
	void replaceOverwrites() {
		VcrTrack track = track("second");
		this.server.store("svc", "chat", track.hash(), "first-recording");

		new RemoteChatFixtureStore(this.server.client("svc")).replace(track);

		assertThat(this.server.requests().get(0).query()).isEqualTo("overwrite=true");
		assertThat(this.server.document("svc", "chat", track.hash())).contains("\"second\"");
	}

	@Test
	@DisplayName("the token travels as a bearer token, and nothing is sent without one")
	void tokenIsSentAsBearer() {
		String hash = track("x").hash();
		new RemoteChatFixtureStore(this.server.client("svc", "s3cret", Duration.ofSeconds(5))).read(hash);
		new RemoteChatFixtureStore(this.server.client("svc")).read(hash);

		assertThat(this.server.requests()).extracting(StubCassetteServer.Request::authorization)
			.containsExactly("Bearer s3cret", null);
	}

	@Test
	@DisplayName("401 fails loudly and points at the token property")
	void unauthorizedPointsAtTheToken() {
		this.server.failWith(401);

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> new RemoteChatFixtureStore(this.server.client("svc")).write(track("x")))
			.withMessageContaining("spring.ai.test.vcr.remote.token");
	}

	@Test
	@DisplayName("a refused fixture (400) fails loudly with the server's reason")
	void refusedFixtureFailsLoudly() {
		this.server.failWith(400);

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> new RemoteChatFixtureStore(this.server.client("svc")).write(track("x")))
			.withMessageContaining("HTTP 400")
			.withMessageContaining("forced by test");
	}

	@Test
	@DisplayName("the location is the fixture's URL; the short name is namespace/kind/hash")
	void locationIsTheUrl() {
		RemoteChatFixtureStore store = new RemoteChatFixtureStore(this.server.client("svc"));
		String hash = track("x").hash();

		assertThat(store.locationOf(hash)).isEqualTo(this.server.url() + "/api/v1/ns/svc/chat/" + hash);
		assertThat(store.fixtureNameOf(hash)).isEqualTo("svc/chat/" + hash);
		assertThat(this.server.requests()).isEmpty();
	}

	@Test
	@DisplayName("a namespace that could escape the server's storage is refused up front")
	void invalidNamespaceIsRefused() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> this.server.client("../etc"));
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> new CassetteServerClient("cassette:9090", "svc", null, Duration.ofSeconds(1),
					Duration.ofSeconds(1)))
			.withMessageContaining("http://");
	}

}
