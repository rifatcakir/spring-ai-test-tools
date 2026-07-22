package io.github.rifatcakir.springai.testtools.recorder.advisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same proof as {@code OllamaStructuredOutputEndToEndTests}, but fast and Docker-free —
 * a real {@link ChatClient} with a real {@link org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor}
 * (Spring AI's own terminal advisor) and a real {@code BeanOutputConverter}, wired to a fake
 * {@link ChatModel} instead of a live one. Exercising the real {@code entity()} code path
 * matters here: a test that only called {@link VcrCacheKeyGenerator#generate(Prompt, java.util.Map)}
 * directly would prove the formula is sensitive to a hand-built context map, not that the
 * whole {@code ChatClient...entity(Class)} pipeline actually reaches this advisor with that
 * information present.
 *
 * @author Rifat Cakir
 */
class DeterministicVcrAdvisorStructuredOutputTests {

	@TempDir
	Path cacheDirectory;

	record CityWeather(String city, Integer temperatureCelsius) {
	}

	record Reminder(String title, Boolean done) {
	}

	/**
	 * Always answers the same bare JSON object, regardless of what was asked — deliberately
	 * shape-agnostic, since every {@code entity()} target type here has only nullable
	 * fields and Jackson leaves them {@code null} for an object with no matching properties.
	 * What's under test is which fixture gets replayed, not what the model would really say.
	 */
	static class FakeChatModel implements ChatModel {

		final AtomicInteger invocations = new AtomicInteger();

		@Override
		public ChatResponse call(Prompt prompt) {
			this.invocations.incrementAndGet();
			return ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage("{}"))))
				.metadata(ChatResponseMetadata.builder().model("fake").build())
				.build();
		}

	}

	@Test
	@DisplayName("BUG FIXED: two different entity() types sharing identical prompt text no longer collide on one "
			+ "fixture")
	void differentEntityTypesWithIdenticalPromptTextNoLongerCollide() throws IOException {
		FakeChatModel fakeModel = new FakeChatModel();
		VcrTrackStore store = new VcrTrackStore(this.cacheDirectory);
		DeterministicVcrAdvisor advisor = new DeterministicVcrAdvisor(new VcrCacheKeyGenerator(), store,
				new VcrTrackMapper(), VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP);

		ChatClient chatClient = ChatClient.builder(fakeModel).defaultAdvisors(advisor).build();
		String prompt = "Give me an example. Make up any reasonable data.";

		CityWeather weather = chatClient.prompt().user(prompt).call().entity(CityWeather.class);
		assertThat(weather).isNotNull();
		assertThat(fakeModel.invocations).as("recording the first entity() call must reach the model").hasValue(1);

		Reminder reminder = chatClient.prompt().user(prompt).call().entity(Reminder.class);
		assertThat(reminder).isNotNull();
		assertThat(fakeModel.invocations)
			.as("BUG FIXED: a structurally different entity() type, even with identical prompt text, must reach "
					+ "the model again rather than replaying the CityWeather fixture")
			.hasValue(2);

		try (Stream<Path> fixtures = Files.list(this.cacheDirectory)) {
			assertThat(fixtures)
				.as("two meaningfully different structured-output requests must now produce two fixtures, not one")
				.hasSize(2);
		}

		// The same call repeated must still replay correctly — the fix must not have
		// turned every entity() call into a permanent cache miss.
		Reminder replayedReminder = chatClient.prompt().user(prompt).call().entity(Reminder.class);
		assertThat(fakeModel.invocations).as("a genuine repeat of the same entity() call must still hit the cache")
			.hasValue(2);
		assertThat(replayedReminder).isEqualTo(reminder);
	}

}
