package io.github.rifatcakir.springai.testtools.recorder.autoconfigure;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rifatcakir.springai.testtools.recorder.VcrCacheMissException;
import io.github.rifatcakir.springai.testtools.recorder.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakir.springai.testtools.recorder.remote.CassetteServerClient;
import io.github.rifatcakir.springai.testtools.recorder.embedding.EmbeddingFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteEmbeddingFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteStreamFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.RemoteToolFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.remote.StubCassetteServer;
import io.github.rifatcakir.springai.testtools.recorder.stream.StreamFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.ToolFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.track.ChatFixtureStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Store selection from {@code spring.ai.test.vcr.store}: file by default, the Cassette
 * Server with {@code remote}, a clear startup failure when {@code remote} is missing its
 * URL or namespace, and the one {@code WARN} line that says AI calls go through a server.
 *
 * @author Rifat Cakir
 */
@ExtendWith(OutputCaptureExtension.class)
class SpringAiVcrRemoteStoreAutoConfigurationTests {

	@TempDir
	Path cacheDirectory;

	private StubCassetteServer server;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class));

	@BeforeEach
	void start() {
		this.server = StubCassetteServer.start();
	}

	@AfterEach
	void stop() {
		this.server.close();
	}

	private String[] remote() {
		return new String[] { "spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.store=remote",
				"spring.ai.test.vcr.remote.url=" + this.server.url(), "spring.ai.test.vcr.remote.namespace=order-service",
				"spring.ai.test.vcr.cache-directory=" + this.cacheDirectory };
	}

	@Test
	@DisplayName("default: the file store, and no Cassette Server client at all")
	void fileStoreByDefault() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.cache-directory=" + this.cacheDirectory)
			.run(context -> {
				assertThat(context).hasSingleBean(VcrTrackStore.class);
				assertThat(context).doesNotHaveBean(CassetteServerClient.class);
				assertThat(context).doesNotHaveBean(RemoteChatFixtureStore.class);
			});
	}

	@Test
	@DisplayName("store=remote: the chat store is remote and no file store exists")
	void remoteStoreWhenSelected() {
		this.contextRunner.withPropertyValues(remote()).run(context -> {
			assertThat(context).hasSingleBean(CassetteServerClient.class);
			assertThat(context.getBean(ChatFixtureStore.class)).isInstanceOf(RemoteChatFixtureStore.class);
			assertThat(context).doesNotHaveBean(VcrTrackStore.class);
			assertThat(context.getBean(CassetteServerClient.class).getNamespace()).isEqualTo("order-service");
		});
	}

	@Test
	@DisplayName("store=remote: stream and tool fixtures are remote too, so nothing stays on local disk")
	void streamAndToolStoresAreRemote() {
		this.contextRunner.withPropertyValues(remote()).run(context -> {
			assertThat(context.getBean(StreamFixtureStore.class)).isInstanceOf(RemoteStreamFixtureStore.class);
			assertThat(context.getBean(ToolFixtureStore.class)).isInstanceOf(RemoteToolFixtureStore.class);
			assertThat(context).doesNotHaveBean(VcrStreamTrackStore.class);
			assertThat(context).doesNotHaveBean(VcrToolExecutionTrackStore.class);
		});
	}

	@Test
	@DisplayName("store=remote with embeddings: the embedding store is remote, sharing one client and one WARN line")
	void embeddingStoreIsRemoteAndShareTheClient(CapturedOutput output) {
		this.contextRunner
			.withConfiguration(AutoConfigurations.of(SpringAiVcrEmbeddingAutoConfiguration.class))
			.withPropertyValues(remote())
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true")
			.run(context -> {
				assertThat(context.getBean(EmbeddingFixtureStore.class)).isInstanceOf(RemoteEmbeddingFixtureStore.class);
				assertThat(context).doesNotHaveBean(VcrEmbeddingTrackStore.class);
				assertThat(context).hasSingleBean(CassetteServerClient.class);
			});
		assertThat(output.getAll().lines().filter(line -> line.contains("VCR store=remote"))).hasSize(1);
	}

	@Test
	@DisplayName("embedding caching alone (chat off) with store=remote still gets its client")
	void embeddingOnlyGetsAClient() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SpringAiVcrEmbeddingAutoConfiguration.class))
			.withPropertyValues("spring.ai.test.vcr.embedding.enabled=true", "spring.ai.test.vcr.store=remote",
					"spring.ai.test.vcr.remote.url=" + this.server.url(), "spring.ai.test.vcr.remote.namespace=search")
			.run(context -> {
				assertThat(context.getBean(EmbeddingFixtureStore.class)).isInstanceOf(RemoteEmbeddingFixtureStore.class);
				assertThat(context.getBean(CassetteServerClient.class).getNamespace()).isEqualTo("search");
			});
	}

	@Test
	@DisplayName("store=remote is case-insensitive, like every other enum property")
	void remoteIsCaseInsensitive() {
		this.contextRunner.withPropertyValues(remote())
			.withPropertyValues("spring.ai.test.vcr.store=REMOTE")
			.run(context -> assertThat(context.getBean(ChatFixtureStore.class)).isInstanceOf(RemoteChatFixtureStore.class));
	}

	@Test
	@DisplayName("the auto-configured advisor really reads and writes through the server")
	void advisorUsesTheServer() {
		this.contextRunner.withPropertyValues(remote()).run(context -> {
			DeterministicVcrAdvisor advisor = context.getBean(DeterministicVcrAdvisor.class);
			AtomicInteger modelCalls = new AtomicInteger();
			CallAdvisorChain chain = mock(CallAdvisorChain.class);
			given(chain.nextCall(any())).willAnswer(invocation -> {
				modelCalls.incrementAndGet();
				return new ChatClientResponse(
						ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("Paris.")))).build(),
						Map.of());
			});
			ChatClientRequest request = new ChatClientRequest(new Prompt(new UserMessage("Capital of France?")), Map.of());

			advisor.adviseCall(request, chain);
			advisor.adviseCall(request, chain);

			assertThat(modelCalls).hasValue(1);
			assertThat(this.server.countFixtureRequests("PUT")).isEqualTo(1);
			assertThat(this.server.countFixtureRequests("GET")).isEqualTo(2);
			assertThat(this.cacheDirectory).isEmptyDirectory();
		});
	}

	@Test
	@DisplayName("store=remote, no mode configured: the auto-configured advisor follows the namespace default")
	void unsetModeFollowsTheNamespace() {
		this.server.namespaceMode("order-service", "REPLAY_ONLY");
		this.contextRunner.withPropertyValues(remote()).run(context -> {
			CallAdvisorChain chain = mock(CallAdvisorChain.class);
			ChatClientRequest request = new ChatClientRequest(new Prompt(new UserMessage("unrecorded")), Map.of());

			assertThatExceptionOfType(VcrCacheMissException.class)
				.isThrownBy(() -> context.getBean(DeterministicVcrAdvisor.class).adviseCall(request, chain));
			verify(chain, never()).nextCall(any());
			assertThat(this.server.requests()).anySatisfy(r -> assertThat(r.path()).isEqualTo("order-service/config"));
		});
	}

	@Test
	@DisplayName("store=remote with a configured mode: the server's namespace mode is never asked for")
	void configuredModeNeverAsks() {
		this.server.namespaceMode("order-service", "REPLAY_ONLY");
		this.contextRunner.withPropertyValues(remote())
			.withPropertyValues("spring.ai.test.vcr.mode=RECORD_OR_REPLAY")
			.run(context -> {
				CallAdvisorChain chain = mock(CallAdvisorChain.class);
				given(chain.nextCall(any())).willReturn(new ChatClientResponse(
						ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage("ok")))).build(),
						Map.of()));
				context.getBean(DeterministicVcrAdvisor.class)
					.adviseCall(new ChatClientRequest(new Prompt(new UserMessage("q")), Map.of()), chain);

				verify(chain).nextCall(any());
				assertThat(this.server.requests()).noneSatisfy(r -> assertThat(r.path()).endsWith("/config"));
			});
	}

	@Test
	@DisplayName("store=remote without a URL fails at startup and says which property is missing")
	void missingUrlFailsAtStartup() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.store=remote",
					"spring.ai.test.vcr.remote.namespace=order-service")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("spring.ai.test.vcr.remote.url must be set"));
	}

	@Test
	@DisplayName("store=remote without a namespace fails at startup and says which property is missing")
	void missingNamespaceFailsAtStartup() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", "spring.ai.test.vcr.store=remote",
					"spring.ai.test.vcr.remote.url=" + this.server.url())
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("spring.ai.test.vcr.remote.namespace must be set"));
	}

	@Test
	@DisplayName("store=remote logs exactly one WARN line naming the server and namespace")
	void oneWarnLine(CapturedOutput output) {
		this.contextRunner.withPropertyValues(remote()).run(context -> assertThat(context).hasNotFailed());

		assertThat(output.getAll().lines().filter(line -> line.contains("VCR store=remote"))).singleElement()
			.satisfies(line -> assertThat(line).contains("WARN")
				.contains(this.server.url())
				.contains("order-service")
				.contains("never in production"));
	}

	@Test
	@DisplayName("still off unless enabled, even with store=remote")
	void remoteStoreDoesNotEnableAnything() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.store=remote", "spring.ai.test.vcr.remote.url=" + this.server.url(),
					"spring.ai.test.vcr.remote.namespace=order-service")
			.run(context -> {
				assertThat(context).doesNotHaveBean(CassetteServerClient.class);
				assertThat(context).doesNotHaveBean(DeterministicVcrAdvisor.class);
			});
	}

}
