package io.github.rifatcakir.springai.testtools.it;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.github.rifatcakir.springai.testtools.recorder.autoconfigure.SpringAiVcrAutoConfiguration;
import io.github.rifatcakir.springai.testtools.recorder.autoconfigure.SpringAiVcrEmbeddingAutoConfiguration;
import io.github.rifatcakir.springai.testtools.recorder.embedding.EmbeddingFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteEmbeddingFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteStreamFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteToolFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.stream.StreamFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.ToolFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManager;
import io.github.rifatcakir.springai.testtools.recorder.track.ChatFixtureStore;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Cassette Server end to end (PRD section 8): the server image built from its own
 * {@code Dockerfile}, Spring contexts wired exactly as an application with {@code
 * store: remote} would be, and a real Ollama model.
 *
 * <p>The sequence is the claim. Record a plain call, a stream, an embedding and a two-turn
 * tool call through the server; <strong>stop Ollama</strong>; switch the namespace default to
 * {@code REPLAY_ONLY} through the admin API; then replay each one in a fresh context that
 * sets no mode of its own. Every replay must return identical content with zero requests to
 * the model (counted on the HTTP clients underneath {@code OllamaApi}, both the blocking and
 * the streaming one) and zero runs of the real {@code @Tool}. Fresh contexts matter: nothing
 * can be served from memory, so the fixtures demonstrably came back from the server. Finally
 * the namespace is exported and the same replays run against the export with {@code
 * store: file}, which is the "a server export replays locally" guarantee.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CassetteServerEndToEndTests {

	private static final Path SERVER_MODULE = Path.of("..", "spring-ai-test-tools-server").toAbsolutePath().normalize();

	private static final String OLLAMA_MODEL_TAG = "llama3.2:1b";

	private static final String OLLAMA_BAKED_IMAGE = "tc-ollama-llama3-2-1b-vcr-test";

	private static final String NAMESPACE = "order-service";

	private static final String ADMIN_TOKEN = "it-admin-token";

	private static final String CHAT_PROMPT = "In one short sentence: what is the capital of France?";

	private static final String STREAM_PROMPT = "Count from one to five in words, separated by commas.";

	private static final String TOOL_PROMPT = "What is the weather in Ankara? Use the tool to find out, do not guess.";

	private static final List<String> EMBEDDING_INPUTS = List.of("hello world");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** Read by the model beans; still points at the stopped container during replay. */
	private static String ollamaEndpoint;

	private GenericContainer<?> server;

	private OllamaContainer ollama;

	private final HttpClient http = HttpClient.newHttpClient();

	private String recordedChat;

	private List<String> recordedStream;

	private float[] recordedVector;

	private String recordedToolAnswer;

	private int missesAfterRecording;

	@BeforeAll
	void startContainers() throws Exception {
		assumeTrue(dockerIsAvailable(), "Docker is not available — skipping the Cassette Server end-to-end test");
		assertThat(serverJarExists()).as("the server jar must be packaged first; run `mvn -Pintegration-test verify` "
				+ "from the repository root, which packages spring-ai-test-tools-server before this module")
			.isTrue();

		this.server = new GenericContainer<>(new ImageFromDockerfile("spring-ai-test-tools-server-it", true)
			.withDockerfile(SERVER_MODULE.resolve("Dockerfile")))
			.withExposedPorts(9090)
			.withEnv("CASSETTE_ADMIN_TOKEN", ADMIN_TOKEN)
			.waitingFor(Wait.forHttp("/actuator/health").forPort(9090).forStatusCode(200))
			.withStartupTimeout(Duration.ofMinutes(3));
		this.server.start();

		boolean bakedImageExists = !DockerClientFactory.instance()
			.client()
			.listImagesCmd()
			.withImageNameFilter(OLLAMA_BAKED_IMAGE)
			.exec()
			.isEmpty();
		this.ollama = bakedImageExists
				? new OllamaContainer(DockerImageName.parse(OLLAMA_BAKED_IMAGE).asCompatibleSubstituteFor("ollama/ollama"))
				: new OllamaContainer(DockerImageName.parse("ollama/ollama:latest"));
		this.ollama.withStartupTimeout(Duration.ofMinutes(2));
		this.ollama.start();
		if (!bakedImageExists) {
			ExecResult pull = this.ollama.execInContainer("ollama", "pull", OLLAMA_MODEL_TAG);
			if (pull.getExitCode() != 0) {
				throw new IllegalStateException("Failed to pull " + OLLAMA_MODEL_TAG + ": " + pull.getStderr());
			}
			this.ollama.commitToImage(OLLAMA_BAKED_IMAGE);
		}
		ollamaEndpoint = this.ollama.getEndpoint();
	}

	@AfterAll
	void stopContainers() {
		if (this.ollama != null) {
			this.ollama.stop();
		}
		if (this.server != null) {
			this.server.stop();
		}
	}

	// ---------------------------------------------------------------- recording

	@Test
	@Order(1)
	@DisplayName("record: a plain call goes to the model once and is stored on the server")
	void recordChat() {
		remoteApp("RECORD_OR_REPLAY").run(context -> {
			assertThat(context).hasNotFailed();
			assertRemoteStores(context);
			this.recordedChat = chatClient(context).prompt().user(CHAT_PROMPT).call().content();

			assertThat(this.recordedChat).isNotBlank();
			assertThat(context.getBean(ModelRequests.class).count()).isEqualTo(1);
		});
	}

	@Test
	@Order(2)
	@DisplayName("record: a stream goes to the model once and is stored chunk by chunk")
	void recordStream() {
		remoteApp("RECORD_OR_REPLAY").run(context -> {
			assertRemoteStores(context);
			this.recordedStream = chatClient(context).prompt().user(STREAM_PROMPT).stream().content().collectList().block();

			assertThat(this.recordedStream).isNotEmpty();
			assertThat(context.getBean(ModelRequests.class).count()).isEqualTo(1);
		});
	}

	@Test
	@Order(3)
	@DisplayName("record: an embedding goes to the model once and is stored")
	void recordEmbedding() {
		remoteApp("RECORD_OR_REPLAY").run(context -> {
			EmbeddingModel embeddingModel = context.getBean(EmbeddingModel.class);
			assertThat(embeddingModel).isInstanceOf(VcrEmbeddingModel.class);

			this.recordedVector = embeddingModel.embed(EMBEDDING_INPUTS).get(0);

			assertThat(this.recordedVector).isNotEmpty();
			assertThat(context.getBean(ModelRequests.class).count()).isEqualTo(1);
		});
	}

	@Test
	@Order(4)
	@DisplayName("record: a two-turn tool call runs the real @Tool once; both model turns and the tool result are stored")
	void recordToolCall() {
		WeatherTool tool = new WeatherTool();
		remoteApp("RECORD_OR_REPLAY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP").run(context -> {
			assertThat(context.getBean(ToolCallingManager.class)).isInstanceOf(VcrToolCallingManager.class);

			this.recordedToolAnswer = chatClient(context).prompt().user(TOOL_PROMPT).tools(tool).call().content();

			assertThat(this.recordedToolAnswer).isNotBlank();
			assertThat(tool.invocations).as("the real tool runs on the recording").hasValue(1);
			assertThat(context.getBean(ModelRequests.class).count()).as("one request per model turn")
				.isGreaterThanOrEqualTo(2);
		});
	}

	@Test
	@Order(5)
	@DisplayName("the server holds every kind; then Ollama is stopped and the namespace default becomes REPLAY_ONLY")
	void serverHoldsEverythingThenOllamaStops() throws Exception {
		List<Map<String, Object>> namespaces = adminGet("/__admin/namespaces", new TypeReference<>() {
		});
		assertThat(namespaces).singleElement().satisfies(namespace -> {
			assertThat(namespace).containsEntry("namespace", NAMESPACE);
			@SuppressWarnings("unchecked")
			Map<String, Integer> fixtures = (Map<String, Integer>) namespace.get("fixtures");
			assertThat(fixtures.get("chat")).as("the plain call plus both tool-loop turns").isGreaterThanOrEqualTo(3);
			assertThat(fixtures).containsEntry("stream", 1).containsEntry("embedding", 1).containsEntry("tool", 1);
		});
		this.missesAfterRecording = misses().size();

		this.ollama.stop();
		assertThat(this.ollama.isRunning()).isFalse();

		HttpResponse<String> mode = this.http.send(HttpRequest.newBuilder(URI.create(serverUrl()
				+ "/__admin/namespaces/" + NAMESPACE + "/mode"))
			.header("Authorization", "Bearer " + ADMIN_TOKEN)
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString("{\"mode\":\"REPLAY_ONLY\"}"))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(mode.statusCode()).isEqualTo(200);
	}

	// ---------------------------------------------------------------- replay from the server

	@Test
	@Order(6)
	@DisplayName("replay: the plain call, identical, zero model requests, in a fresh context with no mode of its own")
	void replayChat() {
		remoteApp(null).run(context -> {
			assertRemoteStores(context);
			assertThat(chatClient(context).prompt().user(CHAT_PROMPT).call().content()).isEqualTo(this.recordedChat);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
	}

	@Test
	@Order(7)
	@DisplayName("replay: the stream, chunk for chunk, zero model requests")
	void replayStream() {
		remoteApp(null).run(context -> {
			assertThat(chatClient(context).prompt().user(STREAM_PROMPT).stream().content().collectList().block())
				.isEqualTo(this.recordedStream);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
	}

	@Test
	@Order(8)
	@DisplayName("replay: the embedding, the exact vector, zero model requests")
	void replayEmbedding() {
		remoteApp(null).run(context -> {
			assertThat(context.getBean(EmbeddingModel.class).embed(EMBEDDING_INPUTS).get(0))
				.containsExactly(this.recordedVector);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
	}

	@Test
	@Order(9)
	@DisplayName("replay: the two-turn tool call, identical, zero model requests, and the real @Tool runs zero times")
	void replayToolCall() {
		WeatherTool tool = new WeatherTool();
		remoteApp(null, "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP").run(context -> {
			assertThat(chatClient(context).prompt().user(TOOL_PROMPT).tools(tool).call().content())
				.isEqualTo(this.recordedToolAnswer);
			assertThat(tool.invocations).as("tool isolation holds with the remote store").hasValue(0);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
	}

	@Test
	@Order(10)
	@DisplayName("no replay missed: the server's misses log did not grow")
	void noReplayMissed() throws Exception {
		assertThat(misses()).hasSize(this.missesAfterRecording);
	}

	// ---------------------------------------------------------------- the export, with store: file

	@Test
	@Order(11)
	@DisplayName("export: the namespace zip, unzipped, replays everything with store=file and zero model requests")
	void exportReplaysWithTheFileStore(@TempDir Path resources) throws Exception {
		HttpResponse<byte[]> export = this.http.send(HttpRequest.newBuilder(URI.create(serverUrl()
				+ "/__admin/namespaces/" + NAMESPACE + "/export"))
			.header("Authorization", "Bearer " + ADMIN_TOKEN)
			.GET()
			.build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(export.statusCode()).isEqualTo(200);
		unzip(export.body(), resources);
		try (Stream<Path> files = Files.walk(resources)) {
			assertThat(files.filter(Files::isRegularFile).map(path -> resources.relativize(path).getName(0).toString()))
				.containsOnly("llm-cache", "llm-cache-embedding", "llm-cache-tool");
		}

		WeatherTool tool = new WeatherTool();
		fileApp(resources).run(context -> {
			ChatClient chatClient = chatClient(context);
			assertThat(chatClient.prompt().user(CHAT_PROMPT).call().content()).isEqualTo(this.recordedChat);
			assertThat(chatClient.prompt().user(STREAM_PROMPT).stream().content().collectList().block())
				.isEqualTo(this.recordedStream);
			assertThat(context.getBean(EmbeddingModel.class).embed(EMBEDDING_INPUTS).get(0))
				.containsExactly(this.recordedVector);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
		fileApp(resources, "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP").run(context -> {
			assertThat(chatClient(context).prompt().user(TOOL_PROMPT).tools(tool).call().content())
				.isEqualTo(this.recordedToolAnswer);
			assertThat(tool.invocations).hasValue(0);
			assertThat(context.getBean(ModelRequests.class).count()).isZero();
		});
	}

	// ---------------------------------------------------------------- wiring

	/**
	 * An application with the library and {@code store: remote}, wired through the same
	 * auto-configurations a Spring Boot application gets from its starters.
	 * @param mode the configured mode, or {@code null} to set none and follow the namespace
	 */
	private ApplicationContextRunner remoteApp(String mode, String... extra) {
		List<String> properties = new ArrayList<>(List.of("spring.ai.test.vcr.enabled=true",
				"spring.ai.test.vcr.embedding.enabled=true", "spring.ai.test.vcr.store=remote",
				"spring.ai.test.vcr.remote.url=" + serverUrl(), "spring.ai.test.vcr.remote.namespace=" + NAMESPACE));
		if (mode != null) {
			properties.add("spring.ai.test.vcr.mode=" + mode);
			properties.add("spring.ai.test.vcr.embedding.mode=" + mode);
		}
		properties.addAll(List.of(extra));
		return app().withPropertyValues(properties.toArray(String[]::new));
	}

	private ApplicationContextRunner fileApp(Path resources, String... extra) {
		List<String> properties = new ArrayList<>(List.of("spring.ai.test.vcr.enabled=true",
				"spring.ai.test.vcr.embedding.enabled=true", "spring.ai.test.vcr.store=file",
				"spring.ai.test.vcr.mode=REPLAY_ONLY", "spring.ai.test.vcr.embedding.mode=REPLAY_ONLY",
				"spring.ai.test.vcr.cache-directory=" + resources.resolve("llm-cache"),
				"spring.ai.test.vcr.embedding.cache-directory=" + resources.resolve("llm-cache-embedding"),
				"spring.ai.test.vcr.tool.cache-directory=" + resources.resolve("llm-cache-tool")));
		properties.addAll(List.of(extra));
		return app().withPropertyValues(properties.toArray(String[]::new));
	}

	private static ApplicationContextRunner app() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class,
					SpringAiVcrEmbeddingAutoConfiguration.class, ToolCallingAutoConfiguration.class,
					ChatClientAutoConfiguration.class))
			.withUserConfiguration(OllamaModels.class);
	}

	/**
	 * Guards the whole test against passing for the wrong reason: run against a library jar
	 * without the remote store (for example a stale one from the local repository), {@code
	 * store=remote} is silently ignored, fixtures go to local files, and every record/replay
	 * step above still passes. Found exactly that way.
	 */
	private static void assertRemoteStores(org.springframework.context.ApplicationContext context) {
		assertThat(context.getBean(ChatFixtureStore.class)).isInstanceOf(RemoteChatFixtureStore.class);
		assertThat(context.getBean(StreamFixtureStore.class)).isInstanceOf(RemoteStreamFixtureStore.class);
		assertThat(context.getBean(ToolFixtureStore.class)).isInstanceOf(RemoteToolFixtureStore.class);
		assertThat(context.getBean(EmbeddingFixtureStore.class)).isInstanceOf(RemoteEmbeddingFixtureStore.class);
	}

	private static ChatClient chatClient(org.springframework.context.ApplicationContext context) {
		return context.getBean(ChatClient.Builder.class).build();
	}

	private String serverUrl() {
		return "http://" + this.server.getHost() + ":" + this.server.getMappedPort(9090);
	}

	private List<Map<String, Object>> misses() throws Exception {
		return adminGet("/__admin/namespaces/" + NAMESPACE + "/misses", new TypeReference<>() {
		});
	}

	private <T> T adminGet(String path, TypeReference<T> type) throws Exception {
		HttpResponse<String> response = this.http.send(HttpRequest.newBuilder(URI.create(serverUrl() + path))
			.header("Authorization", "Bearer " + ADMIN_TOKEN)
			.GET()
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(path).isEqualTo(200);
		return JSON.readValue(response.body(), type);
	}

	private static void unzip(byte[] zip, Path target) throws IOException {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				Path file = target.resolve(entry.getName()).normalize();
				assertThat(file.startsWith(target)).as("zip entry %s stays inside the target", entry.getName()).isTrue();
				Files.createDirectories(file.getParent());
				Files.write(file, in.readAllBytes());
			}
		}
	}

	private static boolean serverJarExists() throws IOException {
		Path target = SERVER_MODULE.resolve("target");
		if (!Files.isDirectory(target)) {
			return false;
		}
		try (Stream<Path> files = Files.list(target)) {
			return files.map(path -> path.getFileName().toString())
				.anyMatch(name -> name.startsWith("spring-ai-test-tools-server-") && name.endsWith(".jar"));
		}
	}

	private static boolean dockerIsAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/** Every request that reaches Ollama, blocking or streaming. */
	record ModelRequests(AtomicInteger counter) {

		int count() {
			return this.counter.get();
		}

	}

	/** A real {@code @Tool}, counting how often it actually runs. */
	static class WeatherTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(description = "Get the current weather for a named city")
		String getWeather(String city) {
			this.invocations.incrementAndGet();
			return "sunny, 22 degrees Celsius";
		}

	}

	/**
	 * Real Ollama chat and embedding models whose HTTP clients count every request. The
	 * streaming path uses {@code OllamaApi}'s reactive {@code WebClient}, not its {@code
	 * RestClient}, so both are counted; counting one would hide a streamed replay that still
	 * reached the network.
	 */
	@Configuration(proxyBeanMethods = false)
	static class OllamaModels {

		@Bean
		ModelRequests modelRequests() {
			return new ModelRequests(new AtomicInteger());
		}

		@Bean
		OllamaApi ollamaApi(ModelRequests requests) {
			ClientHttpRequestInterceptor counting = (request, body, execution) -> {
				requests.counter().incrementAndGet();
				return execution.execute(request, body);
			};
			ExchangeFilterFunction countingStreams = ExchangeFilterFunction.ofRequestProcessor(request -> {
				requests.counter().incrementAndGet();
				return Mono.just(request);
			});
			return OllamaApi.builder()
				.baseUrl(ollamaEndpoint)
				.restClientBuilder(RestClient.builder().requestInterceptor(counting))
				.webClientBuilder(WebClient.builder().filter(countingStreams))
				.build();
		}

		@Bean
		ChatModel chatModel(OllamaApi ollamaApi) {
			return OllamaChatModel.builder()
				.ollamaApi(ollamaApi)
				.options(OllamaChatOptions.builder().model(OllamaModel.LLAMA3_2_1B).temperature(0.0).build())
				.toolCallingManager(ToolCallingManager.builder().build())
				.modelManagementOptions(ModelManagementOptions.defaults())
				.observationRegistry(ObservationRegistry.NOOP)
				.build();
		}

		@Bean
		EmbeddingModel embeddingModel(OllamaApi ollamaApi) {
			return OllamaEmbeddingModel.builder()
				.ollamaApi(ollamaApi)
				.options(OllamaEmbeddingOptions.builder().model(OLLAMA_MODEL_TAG).build())
				.modelManagementOptions(ModelManagementOptions.defaults())
				.observationRegistry(ObservationRegistry.NOOP)
				.build();
		}

	}

}
