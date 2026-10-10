package io.github.rifatcakir.springai.testtools.recorder.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator's two checks (parses, filename matches its own recorded hash) proven
 * against real fixtures written through this project's own stores/mapper, not
 * hand-crafted JSON pretending to be a fixture — the same discipline
 * {@code VcrTrackStoreRoundTripTests} already uses.
 *
 * @author Rifat Cakir
 */
class VcrFixtureValidatorTests {

	@TempDir
	Path cacheDirectory;

	private final VcrTrackMapper mapper = new VcrTrackMapper();

	private final VcrCacheKeyGenerator keyGenerator = new VcrCacheKeyGenerator();

	private VcrCacheKey key() {
		return this.keyGenerator.generate(new Prompt(List.of(new UserMessage("hello")),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build()));
	}

	private void writeOneValidChatFixture() {
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		VcrCacheKey key = key();
		store.write(this.mapper.toTrack(key, new Prompt(List.of(new UserMessage("hello")),
				ChatOptions.builder().model("llama3.2").temperature(0.0).build()),
				ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("hi")))).build()));
	}

	@Test
	@DisplayName("a clean directory of real, unmodified fixtures has no problems")
	void cleanDirectoryHasNoProblems() {
		writeOneValidChatFixture();

		assertThat(VcrFixtureValidator.validateChatFixtures(this.cacheDirectory)).isEmpty();
	}

	@Test
	@DisplayName("a directory that was never recorded to is not itself a problem")
	void missingDirectoryYieldsNoProblems() {
		Path neverCreated = this.cacheDirectory.resolve("never-recorded");

		assertThat(Files.exists(neverCreated)).isFalse();
		assertThat(VcrFixtureValidator.validateChatFixtures(neverCreated)).isEmpty();
	}

	@Test
	@DisplayName("an empty, existing directory has no problems")
	void emptyDirectoryYieldsNoProblems() {
		assertThat(VcrFixtureValidator.validateChatFixtures(this.cacheDirectory)).isEmpty();
	}

	@Test
	@DisplayName("malformed JSON is reported, not thrown")
	void malformedJsonIsReported() throws IOException {
		String hash = "a".repeat(64);
		Files.writeString(this.cacheDirectory.resolve(hash + ".json"), "{ this is not valid json",
				StandardCharsets.UTF_8);

		List<VcrFixtureProblem> problems = VcrFixtureValidator.validateChatFixtures(this.cacheDirectory);

		assertThat(problems).singleElement().satisfies(problem -> {
			assertThat(problem.file().getFileName().toString()).isEqualTo(hash + ".json");
			assertThat(problem.reason()).contains("does not parse");
		});
	}

	@Test
	@DisplayName("BUG this closes: VcrTrackStore#read never checks the filename against the fixture's own hash "
			+ "field -- a renamed or hand-edited fixture would replay in silence without this check")
	void filenameHashMismatchIsReported() throws IOException {
		String filenameHash = "b".repeat(64);
		String recordedHash = "c".repeat(64);
		VcrTrack mismatched = new VcrTrack(VcrTrack.CURRENT_SCHEMA_VERSION, recordedHash, "2026-08-02T10:00:00Z",
				"irrelevant",
				new VcrTrack.RequestSnapshot("llama3.2", null, null, null, null, List.of(), List.of(), List.of(),
						null),
				new VcrTrack.ResponseSnapshot("id", "llama3.2",
						List.of(new VcrTrack.GenerationSnapshot("hi", "stop", List.of())), null, Map.of()));
		String json = VcrTrackStore.defaultJsonMapper().writeValueAsString(mismatched);
		// Written under a DIFFERENT hash than the one the fixture itself records --
		// exactly what a rename, a bad merge, or a hand-edited "hash" field produces.
		Files.writeString(this.cacheDirectory.resolve(filenameHash + ".json"), json, StandardCharsets.UTF_8);

		List<VcrFixtureProblem> problems = VcrFixtureValidator.validateChatFixtures(this.cacheDirectory);

		assertThat(problems).singleElement().satisfies(problem -> {
			assertThat(problem.file().getFileName().toString()).isEqualTo(filenameHash + ".json");
			assertThat(problem.reason()).contains("does not match").contains(recordedHash);
		});
	}

	@Test
	@DisplayName("a file whose name is not a 64-character hex hash is reported, not silently ignored")
	void nonHashFilenameIsReported() throws IOException {
		Files.writeString(this.cacheDirectory.resolve("README.json"), "{}", StandardCharsets.UTF_8);

		List<VcrFixtureProblem> problems = VcrFixtureValidator.validateChatFixtures(this.cacheDirectory);

		assertThat(problems).singleElement()
			.satisfies(problem -> assertThat(problem.reason()).contains("not a 64-character lowercase hex hash"));
	}

	@Test
	@DisplayName("problems in one file don't stop the rest of the directory from being checked")
	void oneBadFixtureDoesNotStopTheRest() throws IOException {
		writeOneValidChatFixture();
		Files.writeString(this.cacheDirectory.resolve("d".repeat(64) + ".json"), "not json at all",
				StandardCharsets.UTF_8);

		List<VcrFixtureProblem> problems = VcrFixtureValidator.validateChatFixtures(this.cacheDirectory);

		assertThat(problems).hasSize(1);
	}

	@Test
	@DisplayName("wired correctly for streaming, embedding and tool-execution fixtures too, not just chat")
	void wiredForEveryFixtureFamily() throws IOException {
		Path streamDirectory = this.cacheDirectory.resolve("stream");
		Files.createDirectories(streamDirectory);
		String streamHash = "e".repeat(64);
		Files.writeString(streamDirectory.resolve(streamHash + ".json"),
				"{\"schemaVersion\":\"1\",\"hash\":\"" + streamHash + "\",\"recordedAt\":\"x\","
						+ "\"canonicalRequest\":\"x\",\"request\":null,\"response\":null}",
				StandardCharsets.UTF_8);
		assertThat(VcrFixtureValidator.validateStreamFixtures(streamDirectory)).isEmpty();

		Path embeddingDirectory = this.cacheDirectory.resolve("embedding");
		Files.createDirectories(embeddingDirectory);
		String embeddingHash = "f".repeat(64);
		Files.writeString(embeddingDirectory.resolve(embeddingHash + ".json"),
				"{\"schemaVersion\":\"1\",\"hash\":\"" + embeddingHash + "\",\"recordedAt\":\"x\","
						+ "\"canonicalRequest\":\"x\",\"request\":null,\"response\":null}",
				StandardCharsets.UTF_8);
		assertThat(VcrFixtureValidator.validateEmbeddingFixtures(embeddingDirectory)).isEmpty();

		Path toolDirectory = this.cacheDirectory.resolve("tool");
		Files.createDirectories(toolDirectory);
		String toolHash = "0".repeat(64);
		Files.writeString(toolDirectory.resolve(toolHash + ".json"),
				"{\"schemaVersion\":\"1\",\"hash\":\"" + toolHash + "\",\"recordedAt\":\"x\","
						+ "\"canonicalRequest\":\"x\",\"toolName\":\"x\",\"arguments\":\"{}\","
						+ "\"result\":\"ok\",\"returnDirect\":false}",
				StandardCharsets.UTF_8);
		assertThat(VcrFixtureValidator.validateToolFixtures(toolDirectory)).isEmpty();
	}

}
