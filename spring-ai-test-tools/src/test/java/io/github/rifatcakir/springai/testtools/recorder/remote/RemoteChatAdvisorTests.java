package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.VcrFixtureRedactor;
import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import io.github.rifatcakir.springai.testtools.recorder.VcrScope;
import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.key.VcrCacheKeyGenerator;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackMapper;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@code DeterministicVcrAdvisor} with the remote store: the PRD section 8 client cases.
 *
 * <p>Every test counts three things, because a payload check alone passes even when the
 * cache calls the model every time: model calls (through a mocked {@link
 * CallAdvisorChain}), lookups ({@code GET}) and writes ({@code PUT}) that reached the
 * server.
 *
 * @author Rifat Cakir
 */
class RemoteChatAdvisorTests {

	private StubCassetteServer server;

	private final AtomicInteger modelCalls = new AtomicInteger();

	@BeforeEach
	void start() {
		this.server = StubCassetteServer.start();
	}

	@AfterEach
	void stop() {
		this.server.close();
	}

	private DeterministicVcrAdvisor advisor(VcrMode mode) {
		return advisor(mode, List.of());
	}

	private DeterministicVcrAdvisor advisor(VcrMode mode, List<VcrFixtureRedactor> redactors) {
		return new DeterministicVcrAdvisor(new VcrCacheKeyGenerator(),
				new RemoteChatFixtureStore(this.server.client("order-service")), new VcrTrackMapper(), mode,
				VcrScope.OUTSIDE_TOOL_LOOP, redactors);
	}

	private static ChatClientRequest request(String text) {
		return new ChatClientRequest(new Prompt(List.of(new UserMessage(text)),
				ChatOptions.builder().model("llama3.2:1b").build()), Map.of());
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

	private static String text(ChatClientResponse response) {
		return response.chatResponse().getResult().getOutput().getText();
	}

	@Test
	@DisplayName("miss in RECORD_OR_REPLAY: one model call and one PUT; the repeat is a hit with no model call")
	void missRecordsThenHits() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = model("Paris.");

		assertThat(text(advisor.adviseCall(request("Capital of France?"), chain))).isEqualTo("Paris.");
		assertThat(this.modelCalls).hasValue(1);
		assertThat(this.server.count("PUT")).isEqualTo(1);

		assertThat(text(advisor.adviseCall(request("Capital of France?"), chain))).isEqualTo("Paris.");
		assertThat(this.modelCalls).as("the hit must not reach the model").hasValue(1);
		assertThat(this.server.count("PUT")).isEqualTo(1);
		assertThat(this.server.count("GET")).isEqualTo(2);
	}

	@Test
	@DisplayName("hit: zero model calls and zero PUTs, even in REPLAY_ONLY")
	void hitNeverCallsTheModel() {
		advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("Capital of France?"), model("Paris."));
		this.modelCalls.set(0);
		long putsBefore = this.server.count("PUT");

		ChatClientResponse replayed = advisor(VcrMode.REPLAY_ONLY).adviseCall(request("Capital of France?"),
				model("a different, live answer"));

		assertThat(text(replayed)).isEqualTo("Paris.");
		assertThat(this.modelCalls).hasValue(0);
		assertThat(this.server.count("PUT")).isEqualTo(putsBefore);
	}

	@Test
	@DisplayName("miss in REPLAY_ONLY: VcrCacheMissException naming the remote location, zero model calls, zero PUTs")
	void missInReplayOnlyFails() {
		assertThatExceptionOfType(VcrCacheMissException.class)
			.isThrownBy(() -> advisor(VcrMode.REPLAY_ONLY).adviseCall(request("never recorded"), model("x")))
			.satisfies(ex -> {
				assertThat(ex.getExpectedPath()).isNull();
				assertThat(ex.getExpectedLocation())
					.startsWith(this.server.url() + "/api/v1/ns/order-service/chat/")
					.endsWith(ex.getHash());
			});
		assertThat(this.modelCalls).hasValue(0);
		assertThat(this.server.count("PUT")).isZero();
		assertThat(this.server.count("GET")).isEqualTo(1);
	}

	@Test
	@DisplayName("server down: loud failure and zero model calls, in RECORD_OR_REPLAY too")
	void serverDownNeverCallsTheModel() {
		DeterministicVcrAdvisor recordOrReplay = advisor(VcrMode.RECORD_OR_REPLAY);
		DeterministicVcrAdvisor replayOnly = advisor(VcrMode.REPLAY_ONLY);
		this.server.close();

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> recordOrReplay.adviseCall(request("Capital of France?"), model("x")));
		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> replayOnly.adviseCall(request("Capital of France?"), model("x")));
		assertThat(this.modelCalls).hasValue(0);
	}

	@Test
	@DisplayName("server answering 5xx: loud failure and zero model calls")
	void serverErrorNeverCallsTheModel() {
		this.server.failWith(500);

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("Capital of France?"), model("x")));
		assertThat(this.modelCalls).hasValue(0);
		assertThat(this.server.count("PUT")).isZero();
	}

	@Test
	@DisplayName("409 on PUT (another instance recorded first): no error, live answer returned, existing fixture kept")
	void conflictIsNotAnError() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			this.modelCalls.incrementAndGet();
			// While this instance waits for the model, another one records the same prompt.
			String hash = this.server.requests().get(0).path().substring("order-service/chat/".length());
			this.server.store("order-service", "chat", hash, "{\"recordedBy\":\"the other instance\"}");
			return new ChatClientResponse(
					ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("mine")))).build(),
					Map.of());
		});

		ChatClientResponse response = advisor.adviseCall(request("Capital of France?"), chain);

		assertThat(text(response)).isEqualTo("mine");
		assertThat(this.modelCalls).hasValue(1);
		assertThat(this.server.count("PUT")).isEqualTo(1);
		String hash = this.server.requests().get(0).path().substring("order-service/chat/".length());
		assertThat(this.server.document("order-service", "chat", hash)).contains("the other instance");
	}

	@Test
	@DisplayName("RECORD_ALWAYS: calls the model and overwrites the server's fixture")
	void recordAlwaysOverwrites() {
		advisor(VcrMode.RECORD_OR_REPLAY).adviseCall(request("Capital of France?"), model("old"));

		ChatClientResponse response = advisor(VcrMode.RECORD_ALWAYS).adviseCall(request("Capital of France?"),
				model("new"));

		assertThat(text(response)).isEqualTo("new");
		assertThat(this.modelCalls).hasValue(2);
		StubCassetteServer.Request lastPut = this.server.requests().get(this.server.requests().size() - 1);
		assertThat(lastPut.method()).isEqualTo("PUT");
		assertThat(lastPut.query()).isEqualTo("overwrite=true");
		this.modelCalls.set(0);
		assertThat(text(advisor(VcrMode.REPLAY_ONLY).adviseCall(request("Capital of France?"), model("x"))))
			.isEqualTo("new");
		assertThat(this.modelCalls).hasValue(0);
	}

	@Test
	@DisplayName("a failed PUT after the model answered fails the call loudly instead of dropping the recording")
	void failedWriteFailsLoudly() {
		DeterministicVcrAdvisor advisor = advisor(VcrMode.RECORD_OR_REPLAY);
		CallAdvisorChain chain = mock(CallAdvisorChain.class);
		given(chain.nextCall(any())).willAnswer(invocation -> {
			this.modelCalls.incrementAndGet();
			this.server.failWith(503);
			return new ChatClientResponse(
					ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("x")))).build(),
					Map.of());
		});

		assertThatExceptionOfType(CassetteServerException.class)
			.isThrownBy(() -> advisor.adviseCall(request("Capital of France?"), chain))
			.withMessageContaining("PUT");
		assertThat(this.modelCalls).hasValue(1);
	}

	@Test
	@DisplayName("redaction runs before the PUT: the server never receives the unredacted text")
	void serverReceivesOnlyRedactedContent() {
		VcrFixtureRedactor redactor = track -> new VcrTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
				track.canonicalRequest().replace("4111-1111", "[CARD]"), track.request(), track.response());

		advisor(VcrMode.RECORD_OR_REPLAY, List.of(redactor)).adviseCall(request("Refund card 4111-1111"),
				model("Done."));

		StubCassetteServer.Request put = this.server.requests().get(1);
		assertThat(put.method()).isEqualTo("PUT");
		String sentCanonicalRequest = VcrTrackStore.defaultJsonMapper().readTree(put.body()).path("canonicalRequest")
			.stringValue();
		assertThat(sentCanonicalRequest).contains("[CARD]").doesNotContain("4111-1111");
	}

}
