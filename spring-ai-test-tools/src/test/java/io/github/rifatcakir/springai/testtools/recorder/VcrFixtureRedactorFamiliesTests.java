package io.github.rifatcakir.springai.testtools.recorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModelBeanPostProcessor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManager;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolCallingManagerBeanPostProcessor;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolMode;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Redaction on the stream, tool and embedding write paths (PRD 6.6), which before the
 * remote store existed ran on the blocking chat path only. Each test reads what actually
 * reached the store, because that is what would leave the process with {@code
 * store=remote}.
 *
 * @author Rifat Cakir
 */
@ExtendWith(OutputCaptureExtension.class)
class VcrFixtureRedactorFamiliesTests {

	@TempDir
	Path directory;

	/** A chat-only redactor, written the way users wrote them before this change: a lambda. */
	private static final VcrFixtureRedactor CARD_IN_PROMPT = track -> new VcrTrack(track.schemaVersion(),
			"forged-hash", track.recordedAt(), track.canonicalRequest().replace("4111-1111", "[CARD]"),
			new VcrTrack.RequestSnapshot(track.request().model(), track.request().temperature(), track.request().topP(),
					track.request().topK(), track.request().maxTokens(), track.request().stopSequences(),
					track.request().messages()
						.stream()
						.map(message -> new VcrTrack.MessageSnapshot(message.type(),
								message.text().replace("4111-1111", "[CARD]"), message.toolCalls(),
								message.toolResponses(), message.media()))
						.toList(),
					track.request().tools(), track.request().structuredOutput()),
			track.response());

	private static ChatClientRequest request(String text) {
		return new ChatClientRequest(new Prompt(List.of(new UserMessage(text)),
				ChatOptions.builder().model("llama3.2:1b").build()), Map.of());
	}

	private static StreamAdvisorChain streamOf(String... chunks) {
		StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
		given(chain.nextStream(any())).willAnswer(invocation -> Flux.fromArray(chunks)
			.map(text -> new ChatClientResponse(
					ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build(),
					Map.of())));
		return chain;
	}

	private DeterministicVcrAdvisor streamingAdvisor(VcrFixtureRedactor redactor) {
		return new DeterministicVcrAdvisor(new VcrCacheKeyGenerator(), new VcrTrackStore(this.directory),
				new VcrTrackMapper(), VcrMode.RECORD_OR_REPLAY, VcrScope.OUTSIDE_TOOL_LOOP, List.of(redactor),
				new VcrStreamTrackStore(this.directory), new VcrStreamTrackMapper());
	}

	private VcrStreamTrack onlyStreamFixture() throws Exception {
		try (var files = Files.list(this.directory)) {
			Path file = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
			return VcrStreamTrackStore.defaultJsonMapper().readValue(Files.readString(file), VcrStreamTrack.class);
		}
	}

	@Test
	@DisplayName("stream: an existing chat-only redactor now redacts a streamed fixture's request side, and cannot move its hash")
	void chatRedactorCoversTheStreamRequestSide() throws Exception {
		ChatClientRequest request = request("Refund card 4111-1111");
		String expectedHash = new VcrCacheKeyGenerator().generateForStream(request.prompt(), request.context()).hash();

		streamingAdvisor(CARD_IN_PROMPT).adviseStream(request, streamOf("Do", "ne.")).blockLast();

		VcrStreamTrack written = onlyStreamFixture();
		assertThat(written.hash()).isEqualTo(expectedHash);
		assertThat(written.canonicalRequest()).contains("[CARD]").doesNotContain("4111-1111");
		assertThat(written.request().messages().get(0).text()).isEqualTo("Refund card [CARD]");
		assertThat(written.response().chunks()).extracting(VcrStreamTrack.ChunkSnapshot::text)
			.containsExactly("Do", "ne.");
	}

	@Test
	@DisplayName("stream: a chat redactor that changes the answer fails loudly instead of writing unredacted chunks")
	void chatRedactorChangingTheResponseFailsLoudly() throws Exception {
		VcrFixtureRedactor answerRedactor = track -> new VcrTrack(track.schemaVersion(), track.hash(),
				track.recordedAt(), track.canonicalRequest(), track.request(),
				new VcrTrack.ResponseSnapshot(null, null, List.of(), null, Map.of()));

		assertThatIllegalStateException()
			.isThrownBy(() -> streamingAdvisor(answerRedactor).adviseStream(request("hi"), streamOf("a", "b"))
				.blockLast())
			.withMessageContaining("Override VcrFixtureRedactor#redactStream");
		try (var files = Files.list(this.directory)) {
			assertThat(files).as("nothing written").isEmpty();
		}
	}

	@Test
	@DisplayName("stream: overriding redactStream redacts the chunks themselves")
	void redactStreamCanRedactChunks() throws Exception {
		VcrFixtureRedactor chunkRedactor = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrStreamTrack redactStream(VcrStreamTrack track) {
				List<VcrStreamTrack.ChunkSnapshot> chunks = track.response()
					.chunks()
					.stream()
					.map(chunk -> new VcrStreamTrack.ChunkSnapshot(chunk.id(), chunk.model(),
							chunk.text().replace("secret", "[X]"), chunk.finishReason(), chunk.toolCalls(), chunk.usage()))
					.toList();
				return new VcrStreamTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
						track.canonicalRequest(), track.request(),
						new VcrStreamTrack.ResponseSnapshot(chunks, "[X]", track.response().aggregateFinishReason(),
								track.response().aggregateToolCalls()));
			}

		};

		streamingAdvisor(chunkRedactor).adviseStream(request("hi"), streamOf("secret", "!")).blockLast();

		assertThat(onlyStreamFixture().response().chunks()).extracting(VcrStreamTrack.ChunkSnapshot::text)
			.containsExactly("[X]", "!");
	}

	@Test
	@DisplayName("tool: redactToolExecution reaches the written fixture and cannot move its hash")
	void toolExecutionIsRedacted() {
		VcrToolExecutionTrackStore store = new VcrToolExecutionTrackStore(this.directory);
		VcrFixtureRedactor redactor = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
				return new VcrToolExecutionTrack(track.schemaVersion(), "forged", track.recordedAt(),
						track.canonicalRequest(), track.toolName(), track.arguments(),
						track.result().replace("jane@example.com", "[EMAIL]"), track.returnDirect());
			}

		};
		AtomicInteger realToolCalls = new AtomicInteger();
		VcrToolCallingManager manager = new VcrToolCallingManager(tool(realToolCalls, "owner: jane@example.com"),
				new VcrToolExecutionCacheKeyGenerator(), store, VcrToolMode.REPLAY_FROM_CASSETTE, List.of(redactor));

		manager.executeToolCalls(prompt(), toolCallResponse());

		String hash = new VcrToolExecutionCacheKeyGenerator().generate("lookupOrder", "{\"id\":7}").hash();
		assertThat(store.read(hash)).hasValueSatisfying(track -> assertThat(track.result()).isEqualTo("owner: [EMAIL]"));
	}

	@Test
	@DisplayName("tool: under EXECUTE_REAL an unchanged, redacted result is not rewritten on every run")
	void redactedToolFixtureIsNotRewrittenEveryRun() throws Exception {
		VcrToolExecutionTrackStore store = new VcrToolExecutionTrackStore(this.directory);
		VcrFixtureRedactor redactor = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
				return new VcrToolExecutionTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
						track.canonicalRequest(), track.toolName(), track.arguments(), "[REDACTED]",
						track.returnDirect());
			}

		};
		AtomicInteger realToolCalls = new AtomicInteger();
		VcrToolCallingManager manager = new VcrToolCallingManager(tool(realToolCalls, "secret"),
				new VcrToolExecutionCacheKeyGenerator(), store, VcrToolMode.EXECUTE_REAL, List.of(redactor));
		String hash = new VcrToolExecutionCacheKeyGenerator().generate("lookupOrder", "{\"id\":7}").hash();

		manager.executeToolCalls(prompt(), toolCallResponse());
		String first = Files.readString(store.pathFor(hash));
		manager.executeToolCalls(prompt(), toolCallResponse());

		assertThat(realToolCalls).hasValue(2);
		assertThat(Files.readString(store.pathFor(hash))).as("same recordedAt, untouched").isEqualTo(first);
	}

	@Test
	@DisplayName("embedding: redactEmbedding reaches the written fixture and cannot move its hash")
	void embeddingIsRedacted() {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(this.directory);
		VcrFixtureRedactor redactor = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrEmbeddingTrack redactEmbedding(VcrEmbeddingTrack track) {
				return new VcrEmbeddingTrack(track.schemaVersion(), "forged", track.recordedAt(),
						track.canonicalRequest().replace("4111-1111", "[CARD]"),
						new VcrEmbeddingTrack.RequestSnapshot(track.request().model(), track.request().dimensions(),
								List.of("[CARD]")),
						track.response());
			}

		};
		EmbeddingModel real = mock(EmbeddingModel.class);
		given(real.call(any())).willReturn(new EmbeddingResponse(List.of(new Embedding(new float[] { 0.5f }, 0))));
		EmbeddingRequest request = new EmbeddingRequest(List.of("card 4111-1111"),
				EmbeddingOptions.builder().model("nomic-embed-text").build());

		new VcrEmbeddingModel(real, new VcrEmbeddingCacheKeyGenerator(), store, new VcrEmbeddingTrackMapper(),
				VcrMode.RECORD_OR_REPLAY, List.of(redactor))
			.call(request);

		String hash = new VcrEmbeddingCacheKeyGenerator().generate(request).hash();
		assertThat(store.read(hash)).hasValueSatisfying(track -> {
			assertThat(track.canonicalRequest()).doesNotContain("4111-1111");
			assertThat(track.request().inputs()).containsExactly("[CARD]");
			assertThat(track.response().embeddings().get(0).vector()).containsExactly(0.5f);
		});
	}

	@Test
	@DisplayName("andThen composes every family, not just chat")
	void andThenComposesEveryFamily() {
		VcrFixtureRedactor first = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
				return withResult(track, track.result() + "+first");
			}

		};
		VcrFixtureRedactor second = new VcrFixtureRedactor() {

			@Override
			public VcrTrack redact(VcrTrack track) {
				return track;
			}

			@Override
			public VcrToolExecutionTrack redactToolExecution(VcrToolExecutionTrack track) {
				return withResult(track, track.result() + "+second");
			}

		};
		VcrToolExecutionTrack track = new VcrToolExecutionTrack("1", "h", "t", "c", "tool", "{}", "r", false);

		assertThat(first.andThen(second).redactToolExecution(track).result()).isEqualTo("r+first+second");
	}

	@Test
	@DisplayName("a redactor that keeps the tool or embedding pass-through default is reported once with a WARN")
	void passThroughDefaultsAreReported(CapturedOutput output) {
		new VcrToolCallingManagerBeanPostProcessor(new VcrToolExecutionCacheKeyGenerator(),
				new VcrToolExecutionTrackStore(this.directory), VcrToolMode.REPLAY_FROM_CASSETTE,
				() -> List.of(CARD_IN_PROMPT))
			.postProcessAfterInitialization(mock(ToolCallingManager.class), "toolCallingManager");
		new VcrEmbeddingModelBeanPostProcessor(new VcrEmbeddingCacheKeyGenerator(),
				new VcrEmbeddingTrackStore(this.directory), new VcrEmbeddingTrackMapper(), VcrMode.RECORD_OR_REPLAY,
				() -> List.of(CARD_IN_PROMPT))
			.postProcessAfterInitialization(mock(EmbeddingModel.class), "embeddingModel");

		assertThat(output.getAll()).contains("does not implement redactToolExecution")
			.contains("does not implement redactEmbedding");
	}

	private static VcrToolExecutionTrack withResult(VcrToolExecutionTrack track, String result) {
		return new VcrToolExecutionTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
				track.canonicalRequest(), track.toolName(), track.arguments(), result, track.returnDirect());
	}

	private static Prompt prompt() {
		return new Prompt(List.of(new UserMessage("Who owns order 7?")), ChatOptions.builder().build());
	}

	private static ChatResponse toolCallResponse() {
		AssistantMessage message = AssistantMessage.builder()
			.content("")
			.properties(Map.of())
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookupOrder", "{\"id\":7}")))
			.media(List.of())
			.build();
		return ChatResponse.builder().generations(List.of(new Generation(message))).build();
	}

	static ToolCallingManager tool(AtomicInteger calls, String result) {
		ToolCallingManager delegate = mock(ToolCallingManager.class);
		given(delegate.executeToolCalls(any(), any())).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			ChatResponse response = invocation.getArgument(1);
			AssistantMessage assistant = response.getResult().getOutput();
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
				calls.incrementAndGet();
				responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
			}
			List<Message> history = new ArrayList<>(prompt.getInstructions());
			history.add(assistant);
			history.add(ToolResponseMessage.builder().responses(responses).build());
			return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
		});
		return delegate;
	}

}
