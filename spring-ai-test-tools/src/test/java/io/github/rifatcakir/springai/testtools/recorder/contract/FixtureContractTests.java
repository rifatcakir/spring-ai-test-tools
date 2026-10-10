package io.github.rifatcakir.springai.testtools.recorder.contract;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionCacheKey;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The library's half of the fixture contract with the Cassette Server (PRD fork d).
 *
 * <p>There is deliberately no shared fixture-format module: the server treats documents
 * as JSON and checks only {@code schemaVersion}, {@code hash} and kind. What keeps the two
 * sides honest is {@code fixture-contract/} at the repository root, a namespace directory
 * in the library's own layout holding one real fixture per family, plus the latest schema
 * version of each. This test proves the library's side: the samples are what the library
 * writes today (same field structure, same hash for the same input, same schema
 * versions), and every sample replays through the file stores unchanged. The server's
 * {@code FixtureContractTests} proves the other side: it accepts and serves the same
 * files byte for byte. Together they are the "copy a server namespace into {@code
 * src/test/resources} and it replays with {@code store: file}" guarantee.
 *
 * <p>When this fails because the library changed a fixture format on purpose, regenerate
 * the samples and commit them with the change, so the server's test then runs against the
 * new shape:
 *
 * <pre>mvn -pl spring-ai-test-tools test -Dtest=FixtureContractTests -Dfixture-contract.regenerate=true</pre>
 *
 * @author Rifat Cakir
 */
class FixtureContractTests {

	static final Path CONTRACT = Path.of("..", "fixture-contract").toAbsolutePath().normalize();

	private static final JsonMapper JSON = VcrTrackStore.defaultJsonMapper();

	@BeforeAll
	static void regenerateWhenAsked() throws IOException {
		if (!Boolean.getBoolean("fixture-contract.regenerate")) {
			return;
		}
		for (String directory : List.of("llm-cache", "llm-cache-embedding", "llm-cache-tool")) {
			FileSystemUtils.deleteRecursively(CONTRACT.resolve(directory));
		}
		new VcrTrackStore(CONTRACT.resolve("llm-cache")).write(chatFixture());
		new VcrStreamTrackStore(CONTRACT.resolve("llm-cache")).write(streamFixture());
		new VcrEmbeddingTrackStore(CONTRACT.resolve("llm-cache-embedding")).write(embeddingFixture());
		new VcrToolExecutionTrackStore(CONTRACT.resolve("llm-cache-tool")).write(toolFixture());
		Properties versions = new Properties();
		versions.setProperty("chat", VcrTrack.CURRENT_SCHEMA_VERSION);
		versions.setProperty("stream", VcrStreamTrack.CURRENT_SCHEMA_VERSION);
		versions.setProperty("embedding", VcrEmbeddingTrack.CURRENT_SCHEMA_VERSION);
		versions.setProperty("tool", VcrToolExecutionTrack.CURRENT_SCHEMA_VERSION);
		try (var writer = Files.newBufferedWriter(CONTRACT.resolve("schema-versions.properties"))) {
			writer.write("# Latest schemaVersion per fixture kind. Written by the library's FixtureContractTests;\n"
					+ "# the server's FixtureKind must match. See fixture-contract/README.md.\n");
			for (String kind : List.of("chat", "stream", "embedding", "tool")) {
				writer.write(kind + "=" + versions.getProperty(kind) + "\n");
			}
		}
	}

	@Test
	@DisplayName("schema-versions.properties matches every fixture family's CURRENT_SCHEMA_VERSION")
	void schemaVersionsMatchTheLibrary() throws IOException {
		Properties versions = new Properties();
		try (Reader reader = Files.newBufferedReader(CONTRACT.resolve("schema-versions.properties"))) {
			versions.load(reader);
		}
		assertThat(versions).containsExactlyInAnyOrderEntriesOf(Map.of("chat", VcrTrack.CURRENT_SCHEMA_VERSION,
				"stream", VcrStreamTrack.CURRENT_SCHEMA_VERSION, "embedding", VcrEmbeddingTrack.CURRENT_SCHEMA_VERSION,
				"tool", VcrToolExecutionTrack.CURRENT_SCHEMA_VERSION));
	}

	@Test
	@DisplayName("the committed samples have exactly the field structure the library writes today")
	void samplesHaveTheCurrentStructure(@TempDir Path fresh) throws IOException {
		new VcrTrackStore(fresh.resolve("chat")).write(chatFixture());
		new VcrStreamTrackStore(fresh.resolve("stream")).write(streamFixture());
		new VcrEmbeddingTrackStore(fresh.resolve("embedding")).write(embeddingFixture());
		new VcrToolExecutionTrackStore(fresh.resolve("tool")).write(toolFixture());

		assertSameStructure(fresh.resolve("chat").resolve(chatFixture().hash() + ".json"),
				CONTRACT.resolve("llm-cache").resolve(chatFixture().hash() + ".json"));
		assertSameStructure(fresh.resolve("stream").resolve(streamFixture().hash() + ".json"),
				CONTRACT.resolve("llm-cache").resolve(streamFixture().hash() + ".json"));
		assertSameStructure(fresh.resolve("embedding").resolve(embeddingFixture().hash() + ".json"),
				CONTRACT.resolve("llm-cache-embedding").resolve(embeddingFixture().hash() + ".json"));
		assertSameStructure(fresh.resolve("tool").resolve(toolFixture().hash() + ".json"),
				CONTRACT.resolve("llm-cache-tool").resolve(toolFixture().hash() + ".json"));
	}

	@Test
	@DisplayName("every sample replays through the file stores, read straight from the contract directory")
	void samplesReplayWithTheFileStores() {
		VcrTrack chat = new VcrTrackStore(CONTRACT.resolve("llm-cache")).read(chatFixture().hash()).orElseThrow();
		assertThat(new VcrTrackMapper(List.of()).toChatResponse(chat).getResult().getOutput().getText())
			.isEqualTo("Paris.");

		VcrStreamTrack stream = new VcrStreamTrackStore(CONTRACT.resolve("llm-cache")).read(streamFixture().hash())
			.orElseThrow();
		assertThat(new VcrStreamTrackMapper(List.of()).toChatResponseChunks(stream)).hasSize(2);

		VcrEmbeddingTrack embedding = new VcrEmbeddingTrackStore(CONTRACT.resolve("llm-cache-embedding"))
			.read(embeddingFixture().hash())
			.orElseThrow();
		assertThat(new VcrEmbeddingTrackMapper().toEmbeddingResponse(embedding).getResult().getOutput())
			.containsExactly(0.013228971f, -0.043682855f, 3.14159265f);

		VcrToolExecutionTrack tool = new VcrToolExecutionTrackStore(CONTRACT.resolve("llm-cache-tool"))
			.read(toolFixture().hash())
			.orElseThrow();
		assertThat(tool.result()).isEqualTo("{\"temperatureC\":18}");
	}

	@Test
	@DisplayName("the contract directory holds the four samples and nothing else")
	void noStraySamples() throws IOException {
		assertThat(jsonFiles("llm-cache")).containsExactlyInAnyOrder(chatFixture().hash() + ".json",
				streamFixture().hash() + ".json");
		assertThat(jsonFiles("llm-cache-embedding")).containsExactly(embeddingFixture().hash() + ".json");
		assertThat(jsonFiles("llm-cache-tool")).containsExactly(toolFixture().hash() + ".json");
	}

	private static List<String> jsonFiles(String directory) throws IOException {
		try (Stream<Path> files = Files.list(CONTRACT.resolve(directory))) {
			return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json")).toList();
		}
	}

	private static void assertSameStructure(Path fresh, Path sample) throws IOException {
		assertThat(sample).as("committed sample (regenerate with -Dfixture-contract.regenerate=true)").exists();
		assertThat(fieldPaths(JSON.readTree(Files.readString(sample, StandardCharsets.UTF_8))))
			.as("field structure of %s", sample.getFileName())
			.isEqualTo(fieldPaths(JSON.readTree(Files.readString(fresh, StandardCharsets.UTF_8))));
	}

	private static TreeSet<String> fieldPaths(JsonNode node) {
		TreeSet<String> paths = new TreeSet<>();
		collect(node, "", paths);
		return paths;
	}

	private static void collect(JsonNode node, String prefix, TreeSet<String> paths) {
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				String path = prefix + "/" + field.getKey();
				paths.add(path);
				collect(field.getValue(), path, paths);
			}
		}
		else if (node.isArray()) {
			for (JsonNode element : node) {
				collect(element, prefix + "[]", paths);
			}
		}
	}

	// The samples: fixed inputs, so the same library version always computes the same hashes.

	private static Prompt prompt() {
		return new Prompt(List.of(new SystemMessage("Answer in one word."), new UserMessage("Capital of France?")),
				ChatOptions.builder().model("llama3.2:1b").temperature(0.0).build());
	}

	static VcrTrack chatFixture() {
		VcrCacheKey key = new VcrCacheKeyGenerator().generate(prompt());
		ChatResponse response = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("Paris."),
					ChatGenerationMetadata.builder().finishReason("STOP").build())))
			.metadata(ChatResponseMetadata.builder()
				.id("chatcmpl-1")
				.model("llama3.2:1b")
				.usage(new DefaultUsage(12, 2, 14))
				.build())
			.build();
		return withFixedTime(new VcrTrackMapper(List.of()).toTrack(key, prompt(), Map.of(), response));
	}

	static VcrStreamTrack streamFixture() {
		VcrCacheKey key = new VcrCacheKeyGenerator().generateForStream(prompt());
		ChatResponse first = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("Par"))))
			.build();
		ChatResponse last = ChatResponse.builder()
			.generations(List.of(new Generation(new AssistantMessage("is."),
					ChatGenerationMetadata.builder().finishReason("STOP").build())))
			.metadata(ChatResponseMetadata.builder().model("llama3.2:1b").usage(new DefaultUsage(12, 2, 14)).build())
			.build();
		VcrStreamTrack track = new VcrStreamTrackMapper(List.of()).toTrack(key, prompt(), Map.of(),
				List.of(first, last));
		return new VcrStreamTrack(track.schemaVersion(), track.hash(), FIXED_TIME, track.canonicalRequest(),
				track.request(), track.response());
	}

	static VcrEmbeddingTrack embeddingFixture() {
		EmbeddingRequest request = new EmbeddingRequest(List.of("hello world"),
				EmbeddingOptions.builder().model("nomic-embed-text").build());
		VcrEmbeddingCacheKey key = new VcrEmbeddingCacheKeyGenerator().generate(request);
		EmbeddingResponse response = new EmbeddingResponse(
				List.of(new Embedding(new float[] { 0.013228971f, -0.043682855f, 3.14159265f }, 0)),
				new EmbeddingResponseMetadata("nomic-embed-text", new DefaultUsage(3, null, 3)));
		VcrEmbeddingTrack track = new VcrEmbeddingTrackMapper().toTrack(key, request, response);
		return new VcrEmbeddingTrack(track.schemaVersion(), track.hash(), FIXED_TIME, track.canonicalRequest(),
				track.request(), track.response());
	}

	static VcrToolExecutionTrack toolFixture() {
		VcrToolExecutionCacheKey key = new VcrToolExecutionCacheKeyGenerator().generate("currentWeather",
				"{\"city\":\"Paris\"}");
		return new VcrToolExecutionTrack(VcrToolExecutionTrack.CURRENT_SCHEMA_VERSION, key.hash(), FIXED_TIME,
				key.canonicalRequest(), "currentWeather", "{\"city\":\"Paris\"}", "{\"temperatureC\":18}", false);
	}

	private static final String FIXED_TIME = "2026-10-10T00:00:00Z";

	private static VcrTrack withFixedTime(VcrTrack track) {
		return new VcrTrack(track.schemaVersion(), track.hash(), FIXED_TIME, track.canonicalRequest(),
				track.request(), track.response());
	}

}
