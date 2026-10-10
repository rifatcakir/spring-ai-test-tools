package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import okhttp3.Interceptor;
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
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Empirical validation of the "provider independent" design claim {@code docs/VISION.md}
 * has, until now, only ever proven against {@code spring-ai-ollama}'s native
 * {@code OllamaChatModel}. This class exercises a genuinely different {@link
 * org.springframework.ai.chat.model.ChatModel} implementation instead:
 * {@link OpenAiChatModel}, which in Spring AI 2.0 is built on the official OpenAI Java SDK
 * ({@code com.openai.client.OpenAIClient}, OkHttp-based) rather than {@code OllamaChatModel}'s
 * {@code RestClient}-based {@code OllamaApi} — a different HTTP client stack entirely, not
 * merely a different Spring AI wrapper class. Pointed at Ollama's own OpenAI-compatible
 * endpoint ({@code /v1/chat/completions}) rather than a real OpenAI account, so this needs
 * no paid API key and no new model — the same already-pulled {@code llama3.2:1b} answers
 * both this class's calls and every other e2e test's.
 *
 * <p><strong>The question this class exists to answer, decided before writing the fix (there
 * turned out to be none needed) rather than after:</strong> should the same prompt, sent
 * through two different {@code ChatModel} implementations, resolve to the same fixture or a
 * different one? {@link VcrCacheKeyGenerator#canonicalize(org.springframework.ai.chat.prompt.Prompt, java.util.Map)}
 * reads only {@code ChatOptions} interface getters (model name, temperature, etc.) and message
 * content — never {@code instanceof OllamaChatOptions}/{@code instanceof OpenAiChatOptions},
 * never anything identifying which Java class or HTTP stack is being used. This is deliberate,
 * not an oversight: which Spring AI class or wire protocol reaches a model is a transport
 * detail that cannot change what the model computes, given the same model name and
 * parameters — so it must not be able to bust the cache. If it did, switching a project from
 * one {@code ChatModel} implementation to another (a real thing that happens, e.g. migrating
 * off a deprecated provider integration) would force re-recording every fixture for no reason
 * related to prompt correctness. {@link #sameFixtureReplaysAcrossBothProviderImplementations()}
 * proves this empirically: a fixture recorded through the native Ollama client replays,
 * unchanged, through the OpenAI-SDK client too. Confirmed manually first, outside any test,
 * by sending the identical prompt to both of Ollama's endpoints directly and observing
 * byte-identical model output ({@code "Yes."} from both, at {@code temperature=0}).
 *
 * <p><strong>Result: no production code changed for this item.</strong> {@code
 * DeterministicVcrAdvisor}, {@code VcrCacheKeyGenerator} and {@code VcrTrackMapper} needed
 * nothing new — confirming the advisor-layer design is, empirically and not just by
 * intention, as provider-agnostic as {@code docs/VISION.md} claimed. The only change in this
 * repository is this test file and the {@code spring-ai-openai} test-scope dependency it
 * needs.
 *
 * <p>Tagged {@code integration} and excluded from the default {@code mvn test} run — see
 * {@link OllamaEndToEndTests} for why and how to run it explicitly.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OpenAiViaOllamaEndToEndTests {

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

	/**
	 * Builds a real {@link OpenAiChatModel} against Ollama's OpenAI-compatible endpoint,
	 * with an OkHttp {@link Interceptor} counting every HTTP request actually made — the
	 * OpenAI SDK's own HTTP stack, not Spring's {@code RestClient}/{@code
	 * ClientHttpRequestInterceptor} that the native-Ollama e2e tests use, since {@code
	 * OpenAiChatModel} does not go through that stack at all in Spring AI 2.0.
	 */
	private OpenAiChatModel buildOpenAiChatModelViaOllama(AtomicInteger httpRequestCount) {
		Interceptor countingInterceptor = chain -> {
			httpRequestCount.incrementAndGet();
			return chain.proceed(chain.request());
		};

		SpringAiOpenAiHttpClient httpClient = SpringAiOpenAiHttpClient.builder().interceptor(countingInterceptor).build();

		ClientOptions clientOptions = ClientOptions.builder()
			.httpClient(httpClient)
			.baseUrl(ollama.getEndpoint() + "/v1")
			// Ollama's OpenAI-compatible endpoint does not validate this key at all, but
			// the OpenAI SDK requires a non-null value to construct a client.
			.apiKey("ollama-does-not-check-this-key")
			.build();
		OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);

		OpenAiChatOptions options = OpenAiChatOptions.builder().model(OLLAMA_MODEL_TAG).temperature(0.0).build();

		return OpenAiChatModel.builder()
			.openAiClient(openAiClient)
			// Without this, OpenAiChatModel.Builder.build() constructs its own default
			// async client, which reads credentials from the environment and throws
			// since none are set here -- deriving it from the sync client we already
			// built keeps both views backed by the same ClientOptions/credentials.
			.openAiClientAsync(openAiClient.async())
			.options(options)
			.observationRegistry(ObservationRegistry.NOOP)
			.build();
	}

	private OllamaChatModel buildNativeOllamaChatModel(AtomicInteger httpRequestCount) {
		Interceptor countingInterceptor = chain -> {
			httpRequestCount.incrementAndGet();
			return chain.proceed(chain.request());
		};

		OllamaApi ollamaApi = OllamaApi.builder()
			.baseUrl(ollama.getEndpoint())
			.restClientBuilder(RestClient.builder().requestInterceptor((request, body, execution) -> {
				httpRequestCount.incrementAndGet();
				return execution.execute(request, body);
			}))
			.build();

		OllamaChatOptions options = OllamaChatOptions.builder().model(OLLAMA_MODEL_TAG).temperature(0.0).build();

		return OllamaChatModel.builder()
			.ollamaApi(ollamaApi)
			.options(options)
			.toolCallingManager(ToolCallingManager.builder().build())
			.modelManagementOptions(ModelManagementOptions.defaults())
			.observationRegistry(ObservationRegistry.NOOP)
			.build();
	}

	@Test
	@DisplayName("OpenAiChatModel (via Ollama's OpenAI-compatible endpoint) records once and replays identically, "
			+ "with zero additional requests through the OpenAI SDK's own HTTP client")
	void openAiChatModelRecordsOnceAndReplaysWithZeroNetworkCallsOnTheHit() throws IOException {
		AtomicInteger httpRequestCount = new AtomicInteger();
		OpenAiChatModel chatModel = buildOpenAiChatModelViaOllama(httpRequestCount);

		VcrCacheKeyGenerator keyGenerator = new VcrCacheKeyGenerator();
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		DeterministicVcrAdvisor advisor = new DeterministicVcrAdvisor(keyGenerator, store, new VcrTrackMapper(),
				VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP);

		ChatClient chatClient = ChatClient.builder(chatModel).defaultAdvisors(advisor).build();
		String prompt = "Reply with exactly the single word: PONG";

		String firstResponse = chatClient.prompt().user(prompt).call().content();
		assertThat(firstResponse).as("the live call must produce some real answer").isNotBlank();
		assertThat(httpRequestCount.get()).as("the live call must reach Ollama's OpenAI-compatible endpoint at "
				+ "least once, through the OpenAI SDK's own HTTP client").isGreaterThanOrEqualTo(1);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures).as("exactly one fixture for this one distinct prompt").hasSize(1);
		}

		String secondResponse = chatClient.prompt().user(prompt).call().content();

		assertThat(secondResponse).as("a replay must return exactly what was recorded, not a fresh live answer")
			.isEqualTo(firstResponse);
		assertThat(httpRequestCount.get())
			.as("a replay must make zero additional requests through the OpenAI SDK's HTTP client")
			.isEqualTo(requestsAfterFirstCall);
	}

	@Test
	@DisplayName("CRITICAL: a fixture recorded through native OllamaChatModel replays identically through "
			+ "OpenAiChatModel too -- the cache key must not (and does not) encode which ChatModel "
			+ "implementation is used")
	void sameFixtureReplaysAcrossBothProviderImplementations() throws IOException {
		VcrCacheKeyGenerator keyGenerator = new VcrCacheKeyGenerator();
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		VcrTrackMapper mapper = new VcrTrackMapper();
		String prompt = "Reply with exactly the single word: PONG";

		// --- record once, through the native Ollama client ---
		AtomicInteger nativeHttpRequestCount = new AtomicInteger();
		OllamaChatModel nativeModel = buildNativeOllamaChatModel(nativeHttpRequestCount);
		DeterministicVcrAdvisor recordingAdvisor = new DeterministicVcrAdvisor(keyGenerator, store, mapper,
				VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP);
		ChatClient nativeChatClient = ChatClient.builder(nativeModel).defaultAdvisors(recordingAdvisor).build();

		String recordedResponse = nativeChatClient.prompt().user(prompt).call().content();
		assertThat(recordedResponse).as("the live recording call must produce some real answer").isNotBlank();
		assertThat(nativeHttpRequestCount.get()).as("recording must have actually reached Ollama's native API")
			.isGreaterThanOrEqualTo(1);

		Path fixtureFile;
		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			List<Path> written = fixtures.toList();
			assertThat(written).as("exactly one fixture recorded so far").hasSize(1);
			fixtureFile = written.get(0);
		}

		// --- replay the SAME prompt, but through OpenAiChatModel, sealed to REPLAY_ONLY so
		// any miss (which would mean the cache key differs by provider) fails loudly rather
		// than silently reaching the network and masking the very thing under test ---
		AtomicInteger openAiHttpRequestCount = new AtomicInteger();
		OpenAiChatModel openAiModel = buildOpenAiChatModelViaOllama(openAiHttpRequestCount);
		DeterministicVcrAdvisor replayingAdvisor = new DeterministicVcrAdvisor(keyGenerator, store, mapper,
				VcrMode.REPLAY_ONLY, VcrScope.OUTSIDE_TOOL_LOOP);
		ChatClient openAiChatClient = ChatClient.builder(openAiModel).defaultAdvisors(replayingAdvisor).build();

		String replayedResponse = openAiChatClient.prompt().user(prompt).call().content();

		assertThat(replayedResponse)
			.as("CRITICAL: a fixture recorded via the native Ollama client must replay identically through "
					+ "OpenAiChatModel -- the cache key does not encode provider identity")
			.isEqualTo(recordedResponse);
		assertThat(openAiHttpRequestCount.get())
			.as("REPLAY_ONLY must serve this from disk without OpenAiChatModel ever reaching the network -- a "
					+ "miss here would have thrown VcrCacheMissException instead of returning normally")
			.isZero();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures)
				.as("still exactly one fixture -- the OpenAI-client replay did not create a second, separate one")
				.hasSize(1)
				.containsExactly(fixtureFile);
		}
	}

}
