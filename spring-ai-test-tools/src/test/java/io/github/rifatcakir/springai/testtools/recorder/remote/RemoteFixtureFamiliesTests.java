package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManager;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolMode;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The remote stream, embedding and tool stores: each under its own kind, each sending
 * exactly the bytes its file store would write, and each wired so that a remote hit
 * reaches neither the model nor the real tool. Every test counts the calls it cares about.
 *
 * @author Rifat Cakir
 */
class RemoteFixtureFamiliesTests {

	private StubCassetteServer server;

	@BeforeEach
	void start() {
		this.server = StubCassetteServer.start();
	}

	@AfterEach
	void stop() {
		this.server.close();
	}

	@Nested
	@DisplayName("streaming")
	class Streaming {

		private final AtomicInteger modelSubscriptions = new AtomicInteger();

		private DeterministicVcrAdvisor advisor(VcrMode mode) {
			CassetteServerClient client = RemoteFixtureFamiliesTests.this.server.client("svc");
			return new DeterministicVcrAdvisor(new VcrCacheKeyGenerator(), new RemoteChatFixtureStore(client),
					new VcrTrackMapper(), mode, VcrScope.OUTSIDE_TOOL_LOOP, List.of(),
					new RemoteStreamFixtureStore(client), new VcrStreamTrackMapper());
		}

		private StreamAdvisorChain model(String... chunks) {
			StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
			given(chain.nextStream(any())).willAnswer(invocation -> Flux.fromArray(chunks)
				.map(text -> new ChatClientResponse(
						ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build(),
						Map.of()))
				.doOnSubscribe(subscription -> this.modelSubscriptions.incrementAndGet()));
			return chain;
		}

		private List<String> chunks(Flux<ChatClientResponse> flux) {
			return flux.map(response -> response.chatResponse().getResult().getOutput().getText()).collectList().block();
		}

		private final ChatClientRequest request = new ChatClientRequest(
				new Prompt(List.of(new UserMessage("Stream it")), ChatOptions.builder().model("llama3.2:1b").build()),
				Map.of());

		@Test
		@DisplayName("miss records once under kind 'stream'; the replay is chunk-for-chunk with zero model subscriptions")
		void recordThenReplay() {
			assertThat(chunks(advisor(VcrMode.RECORD_OR_REPLAY).adviseStream(this.request, model("Par", "is", "."))))
				.containsExactly("Par", "is", ".");
			assertThat(this.modelSubscriptions).hasValue(1);
			assertThat(RemoteFixtureFamiliesTests.this.server.requests()).filteredOn(r -> r.method().equals("PUT"))
				.singleElement()
				.satisfies(put -> assertThat(put.path()).startsWith("svc/stream/"));

			assertThat(chunks(advisor(VcrMode.REPLAY_ONLY).adviseStream(this.request, model("live"))))
				.containsExactly("Par", "is", ".");
			assertThat(this.modelSubscriptions).hasValue(1);
			assertThat(RemoteFixtureFamiliesTests.this.server.count("PUT")).isEqualTo(1);
		}

		@Test
		@DisplayName("miss in REPLAY_ONLY fails with the remote location; zero model subscriptions, zero PUTs")
		void missInReplayOnly() {
			assertThatExceptionOfType(VcrCacheMissException.class)
				.isThrownBy(() -> advisor(VcrMode.REPLAY_ONLY).adviseStream(this.request, model("x")))
				.satisfies(ex -> assertThat(ex.getExpectedLocation()).contains("/api/v1/ns/svc/stream/"));
			assertThat(this.modelSubscriptions).hasValue(0);
			assertThat(RemoteFixtureFamiliesTests.this.server.count("PUT")).isZero();
		}

		@Test
		@DisplayName("RECORD_ALWAYS overwrites the server's stream fixture")
		void recordAlwaysOverwrites() {
			chunks(advisor(VcrMode.RECORD_OR_REPLAY).adviseStream(this.request, model("old")));
			chunks(advisor(VcrMode.RECORD_ALWAYS).adviseStream(this.request, model("new")));

			List<StubCassetteServer.Request> puts = RemoteFixtureFamiliesTests.this.server.requests()
				.stream()
				.filter(r -> r.method().equals("PUT"))
				.toList();
			assertThat(puts).extracting(StubCassetteServer.Request::query).containsExactly(null, "overwrite=true");
			assertThat(chunks(advisor(VcrMode.REPLAY_ONLY).adviseStream(this.request, model("live"))))
				.containsExactly("new");
		}

		@Test
		@DisplayName("server down: the stream fails loudly and the model is never subscribed")
		void serverDown() {
			DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
			RemoteFixtureFamiliesTests.this.server.close();

			assertThatExceptionOfType(CassetteServerException.class)
				.isThrownBy(() -> chunks(advisor.adviseStream(this.request, model("x"))));
			assertThat(this.modelSubscriptions).hasValue(0);
		}

	}

	@Nested
	@DisplayName("embeddings")
	class Embeddings {

		private final AtomicInteger modelCalls = new AtomicInteger();

		private final EmbeddingRequest request = new EmbeddingRequest(List.of("hello world"),
				EmbeddingOptions.builder().model("nomic-embed-text").build());

		private VcrEmbeddingModel wrap(VcrMode mode) {
			EmbeddingModel real = mock(EmbeddingModel.class);
			given(real.call(any())).willAnswer(invocation -> {
				this.modelCalls.incrementAndGet();
				return new EmbeddingResponse(List.of(new Embedding(new float[] { 0.013228971f, -0.043682855f }, 0)));
			});
			return new VcrEmbeddingModel(real, new VcrEmbeddingCacheKeyGenerator(),
					new RemoteEmbeddingFixtureStore(RemoteFixtureFamiliesTests.this.server.client("svc")),
					new VcrEmbeddingTrackMapper(), mode);
		}

		@Test
		@DisplayName("miss records once under kind 'embedding'; the replay is the exact vector with zero model calls")
		void recordThenReplay() {
			wrap(VcrMode.RECORD_OR_REPLAY).call(this.request);
			float[] replayed = wrap(VcrMode.REPLAY_ONLY).call(this.request).getResult().getOutput();

			assertThat(replayed).containsExactly(0.013228971f, -0.043682855f);
			assertThat(this.modelCalls).hasValue(1);
			assertThat(RemoteFixtureFamiliesTests.this.server.requests()).filteredOn(r -> r.method().equals("PUT"))
				.singleElement()
				.satisfies(put -> assertThat(put.path()).startsWith("svc/embedding/"));
		}

		@Test
		@DisplayName("miss in REPLAY_ONLY fails; server down fails; neither calls the model")
		void failuresNeverCallTheModel() {
			assertThatExceptionOfType(VcrCacheMissException.class)
				.isThrownBy(() -> wrap(VcrMode.REPLAY_ONLY).call(this.request));
			VcrEmbeddingModel recordOrReplay = wrap(VcrMode.RECORD_OR_REPLAY);
			RemoteFixtureFamiliesTests.this.server.close();
			assertThatExceptionOfType(CassetteServerException.class).isThrownBy(() -> recordOrReplay.call(this.request));

			assertThat(this.modelCalls).hasValue(0);
		}

		@Test
		@DisplayName("RECORD_ALWAYS overwrites")
		void recordAlwaysOverwrites() {
			wrap(VcrMode.RECORD_OR_REPLAY).call(this.request);
			wrap(VcrMode.RECORD_ALWAYS).call(this.request);

			assertThat(RemoteFixtureFamiliesTests.this.server.requests()).filteredOn(r -> r.method().equals("PUT"))
				.extracting(StubCassetteServer.Request::query)
				.containsExactly(null, "overwrite=true");
		}

	}

	@Nested
	@DisplayName("tool isolation")
	class Tools {

		private final AtomicInteger realToolRuns = new AtomicInteger();

		private VcrToolCallingManager manager(VcrToolMode mode) {
			return new VcrToolCallingManager(realTool(), new VcrToolExecutionCacheKeyGenerator(),
					new RemoteToolFixtureStore(RemoteFixtureFamiliesTests.this.server.client("svc")), mode);
		}

		private ToolCallingManager realTool() {
			ToolCallingManager delegate = mock(ToolCallingManager.class);
			given(delegate.executeToolCalls(any(), any())).willAnswer(invocation -> {
				Prompt prompt = invocation.getArgument(0);
				AssistantMessage assistant = ((ChatResponse) invocation.getArgument(1)).getResult().getOutput();
				List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
				for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
					this.realToolRuns.incrementAndGet();
					responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "Sunny, 28C"));
				}
				List<Message> history = new ArrayList<>(prompt.getInstructions());
				history.add(assistant);
				history.add(ToolResponseMessage.builder().responses(responses).build());
				return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
			});
			return delegate;
		}

		private ChatResponse toolCall() {
			AssistantMessage message = AssistantMessage.builder()
				.content("")
				.properties(Map.of())
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getWeather",
						"{\"city\":\"Istanbul\"}")))
				.media(List.of())
				.build();
			return ChatResponse.builder().generations(List.of(new Generation(message))).build();
		}

		private String result(ToolExecutionResult result) {
			Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
			return ((ToolResponseMessage) last).getResponses().get(0).responseData();
		}

		private final Prompt prompt = new Prompt(List.of(new UserMessage("weather?")), ChatOptions.builder().build());

		@Test
		@DisplayName("the real @Tool runs once to record and zero times on a remote hit")
		void remoteHitIsolatesTheRealTool() {
			manager(VcrToolMode.REPLAY_FROM_CASSETTE).executeToolCalls(this.prompt, toolCall());
			assertThat(this.realToolRuns).hasValue(1);
			assertThat(RemoteFixtureFamiliesTests.this.server.requests()).filteredOn(r -> r.method().equals("PUT"))
				.singleElement()
				.satisfies(put -> assertThat(put.path()).startsWith("svc/tool/"));

			ToolExecutionResult replayed = manager(VcrToolMode.REPLAY_FROM_CASSETTE).executeToolCalls(this.prompt,
					toolCall());

			assertThat(result(replayed)).isEqualTo("Sunny, 28C");
			assertThat(this.realToolRuns).as("isolated on the remote hit").hasValue(1);
			assertThat(RemoteFixtureFamiliesTests.this.server.count("PUT")).isEqualTo(1);
		}

		@Test
		@DisplayName("server down: the tool call fails loudly and the real @Tool never runs")
		void serverDownNeverRunsTheTool() {
			VcrToolCallingManager manager = manager(VcrToolMode.REPLAY_FROM_CASSETTE);
			RemoteFixtureFamiliesTests.this.server.close();

			assertThatExceptionOfType(CassetteServerException.class)
				.isThrownBy(() -> manager.executeToolCalls(this.prompt, toolCall()));
			assertThat(this.realToolRuns).hasValue(0);
		}

		@Test
		@DisplayName("EXECUTE_REAL with an unchanged result reads but does not rewrite; a changed one overwrites")
		void executeRealOnlyRewritesChangedResults() {
			manager(VcrToolMode.EXECUTE_REAL).executeToolCalls(this.prompt, toolCall());
			manager(VcrToolMode.EXECUTE_REAL).executeToolCalls(this.prompt, toolCall());
			assertThat(RemoteFixtureFamiliesTests.this.server.count("PUT")).isEqualTo(1);

			String hash = new VcrToolExecutionCacheKeyGenerator().generate("getWeather", "{\"city\":\"Istanbul\"}")
				.hash();
			RemoteFixtureFamiliesTests.this.server.store("svc", "tool", hash, VcrToolExecutionTrackStore
				.defaultJsonMapper()
				.writeValueAsString(new VcrToolExecutionTrack("1", hash, "t", "c", "getWeather", "{}", "Rainy", false)));
			manager(VcrToolMode.EXECUTE_REAL).executeToolCalls(this.prompt, toolCall());

			StubCassetteServer.Request last = RemoteFixtureFamiliesTests.this.server.requests()
				.get(RemoteFixtureFamiliesTests.this.server.requests().size() - 1);
			assertThat(last.method()).isEqualTo("PUT");
			assertThat(last.query()).isEqualTo("overwrite=true");
			assertThat(this.realToolRuns).hasValue(3);
		}

	}

	@Nested
	@DisplayName("byte-for-byte parity with the file stores")
	class Parity {

		@TempDir
		Path directory;

		@Test
		@DisplayName("each remote store sends exactly what its file store writes")
		void sameBytes() throws Exception {
			CassetteServerClient client = RemoteFixtureFamiliesTests.this.server.client("svc");

			VcrEmbeddingTrack embedding = new VcrEmbeddingTrackMapper().toTrack(
					new VcrEmbeddingCacheKeyGenerator().generate(new EmbeddingRequest(List.of("x"), null)),
					new EmbeddingRequest(List.of("x"), null),
					new EmbeddingResponse(List.of(new Embedding(new float[] { 3.14159265f, 1.0E-7f }, 0))));
			new VcrEmbeddingTrackStore(this.directory.resolve("embedding")).write(embedding);
			new RemoteEmbeddingFixtureStore(client).write(embedding);

			VcrToolExecutionTrack tool = new VcrToolExecutionTrack("1", "a".repeat(64), "t", "c", "tool", "{}", "r",
					false);
			new VcrToolExecutionTrackStore(this.directory.resolve("tool")).write(tool);
			new RemoteToolFixtureStore(client).write(tool);

			assertThat(RemoteFixtureFamiliesTests.this.server.document("svc", "embedding", embedding.hash()))
				.isEqualTo(Files.readString(this.directory.resolve("embedding").resolve(embedding.hash() + ".json"),
						StandardCharsets.UTF_8));
			assertThat(RemoteFixtureFamiliesTests.this.server.document("svc", "tool", tool.hash()))
				.isEqualTo(Files.readString(this.directory.resolve("tool").resolve(tool.hash() + ".json"),
						StandardCharsets.UTF_8));
		}

	}

}
