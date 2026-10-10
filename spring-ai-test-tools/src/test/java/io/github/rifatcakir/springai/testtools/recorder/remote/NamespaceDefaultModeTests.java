package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrModeOverride;
import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingModel;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * PRD fork (b): the client decides. A mode configured in the application always wins, in
 * either direction, and never asks the server; only an application with no mode of its own
 * follows its namespace's default, and it re-reads that default on every call.
 *
 * @author Rifat Cakir
 */
class NamespaceDefaultModeTests {

	private StubCassetteServer server;

	private final AtomicInteger modelCalls = new AtomicInteger();

	private final ChatClientRequest request = new ChatClientRequest(new Prompt(new UserMessage("Capital of France?")),
			Map.of());

	@BeforeEach
	void start() {
		this.server = StubCassetteServer.start();
	}

	@AfterEach
	void stop() {
		VcrModeOverride.clear();
		this.server.close();
	}

	private DeterministicVcrAdvisor followingTheServer() {
		CassetteServerClient client = this.server.client("qa");
		return advisor(new NamespaceDefaultMode(client), client);
	}

	private DeterministicVcrAdvisor configured(VcrMode mode) {
		CassetteServerClient client = this.server.client("qa");
		return advisor(() -> mode, client);
	}

	private DeterministicVcrAdvisor advisor(Supplier<VcrMode> mode, CassetteServerClient client) {
		return new DeterministicVcrAdvisor(new VcrCacheKeyGenerator(), new RemoteChatFixtureStore(client),
				new VcrTrackMapper(), mode, DeterministicVcrAdvisor.ORDER_OUTSIDE_TOOL_LOOP, List.of(), null, null);
	}

	private CallAdvisorChain model(String answer) {
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			this.modelCalls.incrementAndGet();
			return new ChatClientResponse(
					ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(answer)))).build(),
					Map.of());
		});
		return chain;
	}

	private long configRequests() {
		return this.server.requests().stream().filter(request -> request.path().endsWith("/config")).count();
	}

	private static String text(ChatClientResponse response) {
		return response.chatResponse().getResult().getOutput().getText();
	}

	@Test
	@DisplayName("no client mode: a REPLAY_ONLY namespace seals the application — miss fails, zero model calls")
	void namespaceReplayOnlyApplies() {
		this.server.namespaceMode("qa", "REPLAY_ONLY");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> followingTheServer().adviseCall(this.request, model("x")));
		assertThat(this.modelCalls).hasValue(0);
		assertThat(this.server.countFixtureRequests("PUT")).isZero();
		assertThat(configRequests()).isEqualTo(1);
	}

	@Test
	@DisplayName("no client mode: an admin's change applies to the very next call, without a restart")
	void namespaceChangeAppliesToTheNextCall() {
		DeterministicVcrAdvisor advisor = followingTheServer();
		advisor.adviseCall(this.request, model("first"));

		this.server.namespaceMode("qa", "RECORD_ALWAYS");
		assertThat(text(advisor.adviseCall(this.request, model("second")))).isEqualTo("second");
		assertThat(this.server.requests().get(this.server.requests().size() - 1).query()).isEqualTo("overwrite=true");

		this.server.namespaceMode("qa", "REPLAY_ONLY");
		assertThat(text(advisor.adviseCall(this.request, model("third")))).isEqualTo("second");

		assertThat(this.modelCalls).hasValue(2);
		assertThat(configRequests()).isEqualTo(3);
	}

	@Test
	@DisplayName("no client mode: a BYPASS namespace calls the model and touches no fixture")
	void namespaceBypass() {
		this.server.namespaceMode("qa", "BYPASS");

		followingTheServer().adviseCall(this.request, model("live"));

		assertThat(this.modelCalls).hasValue(1);
		assertThat(this.server.countFixtureRequests("GET")).isZero();
		assertThat(this.server.countFixtureRequests("PUT")).isZero();
	}

	@Test
	@DisplayName("client REPLAY_ONLY wins over a RECORD_ALWAYS namespace, and the server is never asked")
	void clientStricterModeWins() {
		this.server.namespaceMode("qa", "RECORD_ALWAYS");

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> configured(VcrMode.REPLAY_ONLY).adviseCall(this.request, model("x")));
		assertThat(this.modelCalls).hasValue(0);
		assertThat(configRequests()).isZero();
	}

	@Test
	@DisplayName("client RECORD_OR_REPLAY wins over a REPLAY_ONLY namespace too: the client decides in either direction")
	void clientLooserModeWinsToo() {
		this.server.namespaceMode("qa", "REPLAY_ONLY");

		configured(VcrMode.RECORD_OR_REPLAY).adviseCall(this.request, model("recorded"));

		assertThat(this.modelCalls).hasValue(1);
		assertThat(this.server.countFixtureRequests("PUT")).isEqualTo(1);
		assertThat(configRequests()).isZero();
	}

	@Test
	@DisplayName("a @Vcr override wins without asking the server")
	void vcrOverrideNeverAsks() {
		this.server.namespaceMode("qa", "REPLAY_ONLY");
		VcrModeOverride.set(VcrMode.BYPASS);

		followingTheServer().adviseCall(this.request, model("live"));

		assertThat(this.modelCalls).hasValue(1);
		assertThat(configRequests()).isZero();
	}

	@Test
	@DisplayName("server unreachable when asked for the mode: loud failure, zero model calls")
	void unreachableServerFailsLoudly() {
		DeterministicVcrAdvisor advisor = followingTheServer();
		this.server.close();

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> advisor.adviseCall(this.request, model("x")))
			.withMessageContaining("/api/v1/ns/qa/config");
		assertThat(this.modelCalls).hasValue(0);
	}

	@Test
	@DisplayName("a mode name this library does not know fails loudly instead of guessing")
	void unknownModeFailsLoudly() {
		this.server.namespaceMode("qa", "REPLAY_SOMETIMES");

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> followingTheServer().adviseCall(this.request, model("x")))
			.withMessageContaining("REPLAY_SOMETIMES")
			.withMessageContaining("spring.ai.test.vcr.mode");
		assertThat(this.modelCalls).hasValue(0);
	}

	@Test
	@DisplayName("embeddings follow the namespace default the same way")
	void embeddingsFollowTheNamespace() {
		this.server.namespaceMode("qa", "REPLAY_ONLY");
		CassetteServerClient client = this.server.client("qa");
		EmbeddingModel real = mock(EmbeddingModel.class);
		given(real.call(any())).willAnswer(invocation -> {
			this.modelCalls.incrementAndGet();
			return new EmbeddingResponse(List.of(new Embedding(new float[] { 1f }, 0)));
		});
		VcrEmbeddingModel model = new VcrEmbeddingModel(real, new VcrEmbeddingCacheKeyGenerator(),
				new RemoteEmbeddingFixtureStore(client), new VcrEmbeddingTrackMapper(), new NamespaceDefaultMode(client),
				List.of());

		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> model.call(new EmbeddingRequest(List.of("hello"), null)));
		assertThat(this.modelCalls).hasValue(0);
	}

}
