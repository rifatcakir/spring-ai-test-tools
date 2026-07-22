package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.autoconfigure.SpringAiVcrAutoConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Diagnoses, against a real model, whether {@code ChatClient...call().entity(Class)}
 * (Spring AI's structured-output / {@code BeanOutputConverter} path) round-trips
 * correctly through Recorder — the same "prove it, don't assume it" discipline that
 * surfaced a real gap for tool calling.
 *
 * <p>Two separate findings, not one:
 *
 * <ol>
 * <li>The single-DTO case genuinely already works with zero new code —
 * {@link #structuredOutputRecordsOnceAndReplaysTheSamePojo()} proves it. POJO conversion
 * (parsing the response text into a bean) happens entirely client-side, after the advisor
 * chain returns, so a replayed {@code ChatResponse} converts exactly the same way a live
 * one would.</li>
 * <li>There <em>was</em> a real, separate cache-key blind spot, now fixed —
 * {@link #differentEntityTypesWithIdenticalPromptTextNoLongerCollide()} proves the fix
 * against a real model. {@code ChatClient}'s structured-output format instructions (the
 * JSON schema description an {@code entity()} call adds) are only spliced into the actual
 * message text by {@code ChatModelCallAdvisor} — the terminal advisor, {@code getOrder()
 * == Integer.MAX_VALUE} — which always runs <em>after</em> {@code DeterministicVcrAdvisor}.
 * {@code VcrCacheKeyGenerator.generate(Prompt)} alone only ever saw a {@link
 * org.springframework.ai.chat.prompt.Prompt}, never {@code ChatClientRequest.context()}
 * (where the format instructions/schema actually live before that splice happens), so two
 * structurally different entity types sharing the same user prompt text used to hash
 * identically and collide on one fixture — confirmed by reading {@code
 * ChatModelCallAdvisor}'s and {@code DefaultChatClient$DefaultCallResponseSpec}'s bytecode
 * directly, not guessed. {@code VcrTrack} schema version {@code "3"} added a {@code
 * generate(Prompt, Map)} overload that also canonicalizes {@code OUTPUT_FORMAT}/
 * {@code STRUCTURED_OUTPUT_SCHEMA} from the request context, closing the gap.</li>
 * </ol>
 *
 * <p>Tagged {@code integration} and excluded from the default {@code mvn test} run — see
 * {@link OllamaEndToEndTests} for why and how to run it explicitly. See
 * {@code DeterministicVcrAdvisorStructuredOutputTests} for the same collision-fix proof,
 * fast and Docker-free, against a fake {@code ChatModel} instead of a real one.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaStructuredOutputEndToEndTests {

	private static final String OLLAMA_MODEL_TAG = "llama3.2:1b";

	private static final DockerImageName OLLAMA_BASE_IMAGE = DockerImageName.parse("ollama/ollama:latest");

	private static final String OLLAMA_BAKED_IMAGE = "tc-ollama-llama3-2-1b-vcr-test";

	private static OllamaContainer ollama;

	@BeforeAll
	static void startOllama() throws IOException, InterruptedException {
		assumeTrue(dockerIsAvailable(), "Docker is not available — skipping the end-to-end test");

		boolean bakedImageExists = !DockerClientFactory.instance()
			.client()
			.listImagesCmd()
			.withImageNameFilter(OLLAMA_BAKED_IMAGE)
			.exec()
			.isEmpty();

		ollama = bakedImageExists
				? new OllamaContainer(DockerImageName.parse(OLLAMA_BAKED_IMAGE).asCompatibleSubstituteFor("ollama/ollama"))
				: new OllamaContainer(OLLAMA_BASE_IMAGE);
		ollama.withStartupTimeout(Duration.ofMinutes(2));
		ollama.start();

		if (!bakedImageExists) {
			ExecResult pull = ollama.execInContainer("ollama", "pull", OLLAMA_MODEL_TAG);
			if (pull.getExitCode() != 0) {
				throw new IllegalStateException(
						"Failed to pull " + OLLAMA_MODEL_TAG + " inside the Ollama container: " + pull.getStderr());
			}
			ollama.commitToImage(OLLAMA_BAKED_IMAGE);
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

	@AfterAll
	static void stopOllama() {
		if (ollama != null) {
			ollama.stop();
		}
	}

	@TempDir
	Path cacheDirectory;

	record CityWeather(String city, Integer temperatureCelsius) {
	}

	record Reminder(String title, Boolean done) {
	}

	private ChatClient buildChatClient(AtomicInteger httpRequestCount, Path cacheDirectory) {
		ClientHttpRequestInterceptor countingInterceptor = (request, body, execution) -> {
			httpRequestCount.incrementAndGet();
			return execution.execute(request, body);
		};

		OllamaApi ollamaApi = OllamaApi.builder()
			.baseUrl(ollama.getEndpoint())
			.restClientBuilder(RestClient.builder().requestInterceptor(countingInterceptor))
			.build();

		OllamaChatOptions options = OllamaChatOptions.builder().model(OllamaModel.LLAMA3_2_1B).temperature(0.0).build();

		OllamaChatModel chatModel = OllamaChatModel.builder()
			.ollamaApi(ollamaApi)
			.options(options)
			.toolCallingManager(ToolCallingManager.builder().build())
			.modelManagementOptions(ModelManagementOptions.defaults())
			.observationRegistry(ObservationRegistry.NOOP)
			.build();

		ChatClient[] holder = new ChatClient[1];
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.cache-directory=" + cacheDirectory,
					"spring.ai.test.vcr.mode=RECORD_OR_REPLAY")
			.run(context -> {
				ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel);
				List<ChatClientBuilderCustomizer> customizers = context.getBeanProvider(ChatClientBuilderCustomizer.class)
					.orderedStream()
					.toList();
				assertThat(customizers).as("the VCR auto-configuration must have registered a customizer").isNotEmpty();
				customizers.forEach(customizer -> customizer.customize(chatClientBuilder));
				holder[0] = chatClientBuilder.build();
			});
		return holder[0];
	}

	@Test
	@DisplayName("a structured-output (.entity()) call records once and replays the identical POJO with zero "
			+ "additional network calls")
	void structuredOutputRecordsOnceAndReplaysTheSamePojo() throws IOException {
		AtomicInteger httpRequestCount = new AtomicInteger();
		ChatClient chatClient = buildChatClient(httpRequestCount, this.cacheDirectory);

		String prompt = "Invent a plausible city and a temperature in Celsius for it right now.";

		CityWeather first = chatClient.prompt().user(prompt).call().entity(CityWeather.class);
		assertThat(first).isNotNull();
		assertThat(httpRequestCount.get()).as("the live call must reach Ollama at least once").isGreaterThanOrEqualTo(1);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("one fixture for one structured-output call").hasSize(1);
		}

		CityWeather second = chatClient.prompt().user(prompt).call().entity(CityWeather.class);

		assertThat(httpRequestCount.get()).as("a replay must make zero additional HTTP requests")
			.isEqualTo(requestsAfterFirstCall);
		assertThat(second).as("a replayed structured-output call must convert to an identical POJO").isEqualTo(first);
	}

	@Test
	@DisplayName("BUG FIXED: two different entity() types sharing the same prompt text now record two separate "
			+ "fixtures against a real model, instead of colliding on one")
	void differentEntityTypesWithIdenticalPromptTextNoLongerCollide() throws IOException {
		AtomicInteger httpRequestCount = new AtomicInteger();
		ChatClient chatClient = buildChatClient(httpRequestCount, this.cacheDirectory);

		// Deliberately schema-agnostic prompt text: nothing here hints at either DTO
		// shape, so any difference in behaviour between the two calls below can only
		// come from the entity type itself — which is exactly what used to be invisible
		// to the cache key before this fix.
		String prompt = "Give me an example. Make up any reasonable data.";

		CityWeather weather = chatClient.prompt().user(prompt).call().entity(CityWeather.class);
		assertThat(weather).isNotNull();
		int requestsAfterFirstCall = httpRequestCount.get();
		assertThat(requestsAfterFirstCall).as("recording the first entity() call must reach the real model")
			.isGreaterThanOrEqualTo(1);

		Path fixtureAfterFirstCall;
		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			List<Path> written = fixtures.toList();
			assertThat(written).as("one fixture for the first (CityWeather) call").hasSize(1);
			fixtureAfterFirstCall = written.get(0);
		}
		String fixtureContentAfterFirstCall = Files.readString(fixtureAfterFirstCall);
		assertThat(fixtureContentAfterFirstCall)
			.as("sanity check: the committed fixture genuinely records the CityWeather schema")
			.contains("temperatureCelsius");

		// A structurally different entity type, but byte-for-byte the same prompt text.
		Reminder reminder = chatClient.prompt().user(prompt).call().entity(Reminder.class);
		assertThat(reminder).isNotNull();

		assertThat(httpRequestCount.get())
			.as("BUG FIXED: the second call — a structurally different entity type — must reach the real model "
					+ "again, because it no longer resolves to the same cache key as the first call")
			.isGreaterThan(requestsAfterFirstCall);

		Path fixtureAfterSecondCall;
		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			List<Path> written = fixtures.toList();
			assertThat(written)
				.as("BUG FIXED: two meaningfully different requests (different entity/schema) now produce two "
						+ "separate fixtures instead of colliding on one")
				.hasSize(2);
			fixtureAfterSecondCall = written.stream().filter(path -> !path.equals(fixtureAfterFirstCall)).findFirst()
				.orElseThrow();
		}
		assertThat(fixtureAfterSecondCall).as("the second fixture must be a different file from the first")
			.isNotEqualTo(fixtureAfterFirstCall);
		assertThat(Files.readString(fixtureAfterSecondCall))
			.as("the second fixture must carry the Reminder schema, not the CityWeather one")
			.contains("done")
			.doesNotContain("temperatureCelsius");

		// A genuine repeat of the second call must still replay from its own fixture.
		int requestsAfterSecondCall = httpRequestCount.get();
		Reminder replayedReminder = chatClient.prompt().user(prompt).call().entity(Reminder.class);
		assertThat(httpRequestCount.get()).as("a real replay of the Reminder call must make zero additional requests")
			.isEqualTo(requestsAfterSecondCall);
		assertThat(replayedReminder).isEqualTo(reminder);
	}

}
