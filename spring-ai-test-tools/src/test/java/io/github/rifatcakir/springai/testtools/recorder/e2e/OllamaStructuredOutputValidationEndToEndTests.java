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
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaModel;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import io.micrometer.observation.ObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Empirically verifies {@code docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md} against a real
 * model: how {@code DeterministicVcrAdvisor} interacts with Spring AI 2.0's new recursive
 * {@code StructuredOutputValidationAdvisor} (self-correcting structured output).
 *
 * <p><strong>Why the validation advisor is attached by hand, not via {@code
 * .entity(Class, spec -> spec.validateSchema())}:</strong> that convenience path derives
 * its JSON schema from {@code JsonSchemaGenerator}, which (confirmed by reading its
 * source, {@code spring-ai-model}'s {@code JsonSchemaGenerator.java}) only ever emits
 * {@code type}/{@code properties}/{@code required} — no {@code const}, {@code pattern} or
 * {@code minLength}. Combined with the fact that the model is shown the exact schema as
 * part of its structured-output format instructions, that makes a *reliable, deterministic*
 * validation failure impossible to engineer through {@code .entity()}: a schema built only
 * from required fields is trivially satisfiable by a model that can literally read what is
 * required. {@code StructuredOutputValidationAdvisor.builder().outputJsonSchema(String)}
 * takes a raw schema string, validated by {@code com.networknt.schema} (which does support
 * {@code const}) — and, crucially, confirmed by reading {@code
 * StructuredOutputValidationAdvisor}'s source, this advisor never injects its schema into
 * the prompt itself (only a validation *error message* on retry). So a hand-attached
 * instance with a schema requiring an unguessable {@code const} the model is never shown
 * is guaranteed to fail every attempt, every run — a deterministic, non-flaky forcing
 * technique instead of hoping a small model happens to misbehave.
 *
 * @author Rifat Cakir
 */
@Tag("integration")
class OllamaStructuredOutputValidationEndToEndTests {

	private static final String OLLAMA_MODEL_TAG = "llama3.2:1b";

	private static final DockerImageName OLLAMA_BASE_IMAGE = DockerImageName.parse("ollama/ollama:latest");

	private static final String OLLAMA_BAKED_IMAGE = "tc-ollama-llama3-2-1b-vcr-test";

	/**
	 * A {@code const} the model is never shown (see the class Javadoc) — every attempt is
	 * therefore guaranteed to fail validation, deterministically, regardless of what the
	 * model actually produces. {@code maxRepeatAttempts(1)} below caps the loop at exactly
	 * two real attempts per live call: fast enough for CI, still a genuine multi-attempt
	 * loop.
	 */
	private static final String IMPOSSIBLE_SCHEMA = """
			{
			  "$schema": "https://json-schema.org/draft/2020-12/schema",
			  "type": "object",
			  "properties": {
			    "verificationCode": { "const": "R5-PROOF-UNGUESSABLE-8f3c1a9d" }
			  },
			  "required": ["verificationCode"]
			}
			""";

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
	 * Builds a real {@code ChatClient} the same way every other e2e test in this project
	 * does — {@code ChatClient.builder(chatModel)} plus this library's own
	 * {@code ChatClientBuilderCustomizer} beans — with an optional {@code order} property
	 * override so each test can place {@code DeterministicVcrAdvisor} either above or below
	 * {@code StructuredOutputValidationAdvisor}'s default order
	 * ({@code LOWEST_PRECEDENCE - 2000}).
	 */
	private ChatClient buildChatClient(AtomicInteger httpRequestCount, Path cacheDirectory, Integer vcrOrder) {
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

		List<String> propertyValues = new java.util.ArrayList<>(List.of("spring.ai.test.vcr.enabled=true",
				"spring.ai.test.vcr.cache-directory=" + cacheDirectory, "spring.ai.test.vcr.mode=RECORD_OR_REPLAY"));
		if (vcrOrder != null) {
			propertyValues.add("spring.ai.test.vcr.order=" + vcrOrder);
		}

		ChatClient[] holder = new ChatClient[1];
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class))
			.withPropertyValues(propertyValues.toArray(String[]::new))
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

	private static StructuredOutputValidationAdvisor guaranteedFailValidationAdvisor() {
		return StructuredOutputValidationAdvisor.builder().outputJsonSchema(IMPOSSIBLE_SCHEMA).maxRepeatAttempts(1).build();
	}

	@Test
	@DisplayName("R5, correctness (default order, VCR above the validation advisor): a self-correcting call's "
			+ "*entire* multi-attempt retry loop records as ONE fixture, and replays with zero additional network "
			+ "calls — matching R5's 'no bug' verdict against a real, forced multi-attempt loop, not just a "
			+ "single-shot call")
	void wholeValidationLoopRecordsAsOneFixtureAtDefaultOrder() throws IOException {
		AtomicInteger httpRequestCount = new AtomicInteger();
		// No order override: DeterministicVcrAdvisor keeps its default (HIGHEST_PRECEDENCE
		// + 250), which R5 §1 confirmed is always above StructuredOutputValidationAdvisor's
		// default (LOWEST_PRECEDENCE - 2000).
		ChatClient chatClient = buildChatClient(httpRequestCount, this.cacheDirectory, null);

		String prompt = "Reply with a short JSON object containing a single string field of your choosing.";

		String first = chatClient.prompt()
			.advisors(guaranteedFailValidationAdvisor())
			.user(prompt)
			.call()
			.content();

		assertThat(first).as("the model must have replied with something, even though it never satisfies the "
				+ "hidden schema").isNotBlank();
		// maxRepeatAttempts(1) forces exactly two real attempts (repeatCounter 1 and 2)
		// every single run, since the const requirement is never shown to the model.
		assertThat(httpRequestCount.get())
			.as("recording this scenario takes exactly two real HTTP calls to Ollama — one per forced attempt")
			.isEqualTo(2);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures)
				.as("VCR sits above the validation advisor, so the whole two-attempt loop is invisible to it -- "
						+ "one fixture wraps the entire loop, not one per attempt")
				.hasSize(1);
		}

		String second = chatClient.prompt().advisors(guaranteedFailValidationAdvisor()).user(prompt).call().content();

		assertThat(second).as("a replay must return exactly what was recorded, retry loop included")
			.isEqualTo(first);
		assertThat(httpRequestCount.get())
			.as("replaying the whole loop from its single fixture must make zero additional HTTP requests to "
					+ "Ollama, even though the validation advisor still \"runs\" the same do-while loop around it")
			.isEqualTo(requestsAfterFirstCall);
	}

	@Test
	@DisplayName("R5 §6, capability extension (order override, VCR BELOW the validation advisor): setting "
			+ "spring.ai.test.vcr.order below LOWEST_PRECEDENCE - 2000 places VCR inside the retry loop and "
			+ "records/replays each attempt as its own fixture -- confirmed empirically, not just by the ordering "
			+ "arithmetic")
	void eachValidationAttemptRecordsItsOwnFixtureWhenOrderPlacesVcrInsideTheLoop() throws IOException {
		AtomicInteger httpRequestCount = new AtomicInteger();
		// R5 §4's suggested escape hatch: an order numerically between the validation
		// advisor's default (LOWEST_PRECEDENCE - 2000) and the terminal ChatModelCallAdvisor
		// (LOWEST_PRECEDENCE) places DeterministicVcrAdvisor *below* the validation advisor
		// in the sorted advisor chain, so each `chain.copy(this).nextCall(...)` re-entry
		// the validation advisor makes on a failed attempt reaches VCR fresh.
		int belowValidationAdvisorOrder = Ordered.LOWEST_PRECEDENCE - 1000;
		ChatClient chatClient = buildChatClient(httpRequestCount, this.cacheDirectory, belowValidationAdvisorOrder);

		String prompt = "Reply with a short JSON object containing a single string field of your choosing.";

		String first = chatClient.prompt()
			.advisors(guaranteedFailValidationAdvisor())
			.user(prompt)
			.call()
			.content();

		assertThat(first).isNotBlank();
		assertThat(httpRequestCount.get()).as("still exactly two real attempts -- the order change does not alter "
				+ "how many times the validation advisor itself loops, only where VCR sits relative to it")
			.isEqualTo(2);
		int requestsAfterFirstCall = httpRequestCount.get();

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures)
				.as("R5 §6's hypothesis, now confirmed empirically: VCR positioned below the validation advisor "
						+ "sees each retry attempt's differently-augmented prompt as its own cache miss -- two "
						+ "attempts, two fixtures, not one")
				.hasSize(2);
		}

		String second = chatClient.prompt().advisors(guaranteedFailValidationAdvisor()).user(prompt).call().content();

		assertThat(second).as("a replay of both per-attempt fixtures must reproduce the same final answer")
			.isEqualTo(first);
		assertThat(httpRequestCount.get())
			.as("both attempts replaying from their own fixtures must together make zero additional HTTP requests")
			.isEqualTo(requestsAfterFirstCall);
	}

}
