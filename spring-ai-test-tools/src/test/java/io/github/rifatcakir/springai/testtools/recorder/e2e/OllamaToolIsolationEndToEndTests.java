package io.github.rifatcakir.springai.testtools.recorder.e2e;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves tool isolation (docs/TOOL-ISOLATION-PRD.md) end to end, against a real model and
 * through Spring AI's <em>own</em> {@code ToolCallingAutoConfiguration}/{@code
 * ChatClientAutoConfiguration} — the one thing {@link OllamaToolCallingEndToEndTests}
 * structurally cannot exercise, because it builds its {@code ChatClient} via the plain
 * {@code ChatClient.builder(chatModel)} static factory (see {@code
 * docs/TOOL-ISOLATION-PRD.md} section 1.5): that path never touches a Spring {@code
 * ToolCallingManager} bean at all, so it could never prove {@link
 * io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManagerBeanPostProcessor}
 * actually intercepts anything.
 *
 * <p>This test instead loads Spring AI's real autoconfiguration classes — the same ones a
 * consuming Spring Boot application gets transitively via {@code
 * spring-ai-starter-model-ollama} (confirmed via {@code mvn dependency:tree} on the
 * sibling {@code spring-ai-test-tools-example} project) — so the {@code ChatClient.Builder}
 * bean under test is wired exactly the way this library's own README documents as the
 * main quick-start path.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaToolIsolationEndToEndTests {

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

	@TempDir
	Path toolCacheDirectory;

	/**
	 * A real {@code @Tool} method, not a mock — the same class {@link
	 * OllamaToolCallingEndToEndTests} uses, so the only variable between the two tests is
	 * the interception mechanism, not the tool itself.
	 */
	static class WeatherTool {

		final AtomicInteger invocations = new AtomicInteger();

		@Tool(description = "Get the current weather for a named city")
		String getWeather(String city) {
			this.invocations.incrementAndGet();
			return "sunny, 22 degrees Celsius";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RealOllamaChatModelConfiguration {

		@Bean
		ChatModel chatModel(AtomicInteger httpRequestCount) {
			ClientHttpRequestInterceptor countingInterceptor = (request, body, execution) -> {
				httpRequestCount.incrementAndGet();
				return execution.execute(request, body);
			};

			OllamaApi ollamaApi = OllamaApi.builder()
				.baseUrl(ollama.getEndpoint())
				.restClientBuilder(RestClient.builder().requestInterceptor(countingInterceptor))
				.build();

			OllamaChatOptions options = OllamaChatOptions.builder().model(OllamaModel.LLAMA3_2_1B).temperature(0.0).build();

			return OllamaChatModel.builder()
				.ollamaApi(ollamaApi)
				.options(options)
				.toolCallingManager(ToolCallingManager.builder().build())
				.modelManagementOptions(ModelManagementOptions.defaults())
				.observationRegistry(ObservationRegistry.NOOP)
				.build();
		}

		@Bean
		AtomicInteger httpRequestCount() {
			return new AtomicInteger();
		}

	}

	@Test
	@DisplayName("REPLAY_FROM_CASSETTE (default): the real @Tool method runs on record, then NEVER runs again on replay, "
			+ "through Spring AI's own ToolCallingAutoConfiguration/ChatClientAutoConfiguration")
	void toolIsolationHoldsThroughRealAutoconfiguration() {
		WeatherTool weatherTool = new WeatherTool();

		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class, ToolCallingAutoConfiguration.class,
					ChatClientAutoConfiguration.class))
			.withUserConfiguration(RealOllamaChatModelConfiguration.class)
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.cache-directory=" + this.cacheDirectory,
					"spring.ai.test.vcr.mode=RECORD_OR_REPLAY", "spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP",
					"spring.ai.test.vcr.tool.cache-directory=" + this.toolCacheDirectory)
			.run(context -> {
				assertThat(context).as("the real autoconfiguration graph must resolve with no wiring conflict")
					.hasNotFailed();

				ToolCallingManager wrapped = context.getBean(ToolCallingManager.class);
				assertThat(wrapped)
					.as("VcrToolCallingManagerBeanPostProcessor must have wrapped Spring AI's own autoconfigured bean")
					.isInstanceOf(io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManager.class);

				ChatClient.Builder chatClientBuilder = context.getBean(ChatClient.Builder.class);
				ChatClient chatClient = chatClientBuilder.build();
				AtomicInteger httpRequestCount = context.getBean(AtomicInteger.class);

				String prompt = "What is the weather in Ankara? Use the tool to find out, do not guess.";

				// --- first call: records. The real tool runs once, exactly like today. ---
				String firstResponse = chatClient.prompt().user(prompt).tools(weatherTool).call().content();

				assertThat(firstResponse).isNotBlank();
				assertThat(weatherTool.invocations).as("the real tool must have actually run once on the live call")
					.hasValue(1);
				int requestsAfterFirstCall = httpRequestCount.get();
				assertThat(requestsAfterFirstCall)
					.as("recording this scenario takes at least two real HTTP calls to Ollama — one per model turn")
					.isGreaterThanOrEqualTo(2);
				assertThat(this.toolCacheDirectory.toFile().listFiles())
					.as("the tool invocation must have been recorded to its own cassette").hasSize(1);

				// --- second call: identical prompt. Model turns replay (INSIDE_TOOL_LOOP,
				// as before) AND the tool invocation itself now ALSO replays -- this is the
				// behaviour change this test exists to prove. ---
				String secondResponse = chatClient.prompt().user(prompt).tools(weatherTool).call().content();

				assertThat(secondResponse).as("a replay must return exactly what was recorded").isEqualTo(firstResponse);
				assertThat(httpRequestCount.get())
					.as("both replayed model turns together must make zero additional HTTP requests to Ollama")
					.isEqualTo(requestsAfterFirstCall);
				assertThat(weatherTool.invocations)
					.as("the whole point of this test: under the new default, the real @Tool method is NEVER "
							+ "invoked again on replay -- full isolation, not just a replayed model answer")
					.hasValue(1);
			});
	}

}
