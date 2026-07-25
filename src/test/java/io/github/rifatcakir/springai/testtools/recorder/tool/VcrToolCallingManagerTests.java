package io.github.rifatcakir.springai.testtools.recorder.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The behavioural contract that matters (mirroring {@code VcrEmbeddingModelTests} and
 * {@code DeterministicVcrAdvisorTests}'s own "assert on invocation counts, not just
 * payloads" discipline): under {@link VcrToolMode#REPLAY_FROM_CASSETTE}, a cassette hit
 * means the real {@link ToolCallingManager} delegate is <em>never</em> invoked — proving
 * genuine isolation, not just that the returned text happens to match.
 *
 * @author Rifat Cakir
 */
class VcrToolCallingManagerTests {

	@TempDir
	Path cacheDirectory;

	private VcrToolExecutionTrackStore store;

	private VcrToolExecutionCacheKeyGenerator keyGenerator;

	private final AtomicInteger delegateInvocations = new AtomicInteger();

	@BeforeEach
	void setUp() {
		this.store = new VcrToolExecutionTrackStore(this.cacheDirectory);
		this.keyGenerator = new VcrToolExecutionCacheKeyGenerator();
		this.delegateInvocations.set(0);
	}

	private VcrToolCallingManager manager(ToolCallingManager delegate, VcrToolMode mode) {
		return new VcrToolCallingManager(delegate, this.keyGenerator, this.store, mode);
	}

	private static AssistantMessage.ToolCall toolCall(String id, String name, String arguments) {
		return new AssistantMessage.ToolCall(id, "function", name, arguments);
	}

	private static AssistantMessage assistantMessageWithToolCalls(AssistantMessage.ToolCall... calls) {
		return AssistantMessage.builder().content("").properties(Map.of()).toolCalls(List.of(calls)).media(List.of()).build();
	}

	private static Prompt promptWithUserMessage(String text) {
		return new Prompt(List.of(new UserMessage(text)), ChatOptions.builder().build());
	}

	private static ChatResponse chatResponseWithToolCalls(AssistantMessage assistantMessage) {
		return ChatResponse.builder().generations(List.of(new Generation(assistantMessage))).build();
	}

	/**
	 * Simulates what the real {@code DefaultToolCallingManager} does: for every tool call
	 * in the response, look up a canned result by (name, arguments) from {@code
	 * resultsByNameAndArgs}, increment the invocation counter once per call, and build a
	 * {@code ToolExecutionResult} shaped exactly like the real one (conversation history =
	 * original instructions + the assistant message + a trailing {@code
	 * ToolResponseMessage}) — confirmed against real {@code DefaultToolCallingManager}
	 * bytecode in {@code docs/TOOL-ISOLATION-PRD.md} section 1, not invented for this test.
	 */
	private ToolCallingManager realDelegate(Map<String, String> resultsByNameAndArgs) {
		ToolCallingManager delegate = mock(ToolCallingManager.class);
		given(delegate.executeToolCalls(any(), any())).willAnswer(invocation -> {
			Prompt prompt = invocation.getArgument(0);
			ChatResponse chatResponse = invocation.getArgument(1);
			AssistantMessage assistantMessage = chatResponse.getResult().getOutput();

			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
				this.delegateInvocations.incrementAndGet();
				String result = resultsByNameAndArgs.get(call.name() + "|" + call.arguments());
				responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
			}
			ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();

			List<Message> history = new ArrayList<>(prompt.getInstructions());
			history.add(assistantMessage);
			history.add(toolResponseMessage);

			return ToolExecutionResult.builder().conversationHistory(history).returnDirect(false).build();
		});
		return delegate;
	}

	private static String textOf(ToolExecutionResult result) {
		Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
		return ((ToolResponseMessage) last).getResponses().get(0).responseData();
	}

	@Test
	@DisplayName("REPLAY_FROM_CASSETTE: first call is a miss, invokes the real tool once, and records")
	void firstCallInvokesRealToolAndRecords() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C"));
		AssistantMessage.ToolCall call = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");

		ToolExecutionResult result = manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE)
			.executeToolCalls(promptWithUserMessage("weather?"), chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));

		assertThat(this.delegateInvocations).hasValue(1);
		assertThat(textOf(result)).isEqualTo("Sunny, 28C");
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);
	}

	@Test
	@DisplayName("REPLAY_FROM_CASSETTE: second identical call NEVER invokes the real tool, and returns the exact recorded result")
	void secondCallIsolatesTheRealTool() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C"));
		AssistantMessage.ToolCall call = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");
		VcrToolCallingManager vcrManager = manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE);

		// First call: records.
		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));
		assertThat(this.delegateInvocations).hasValue(1);

		// Second, identical call: must NOT reach the real tool again.
		ToolExecutionResult replayed = vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));

		assertThat(this.delegateInvocations).as("the real tool must not be invoked on a cassette hit").hasValue(1);
		assertThat(textOf(replayed)).isEqualTo("Sunny, 28C");
	}

	@Test
	@DisplayName("EXECUTE_REAL: the real tool runs on every call, even when a cassette entry already exists")
	void executeRealAlwaysInvokesTheRealTool() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C"));
		AssistantMessage.ToolCall call = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");
		VcrToolCallingManager vcrManager = manager(delegate, VcrToolMode.EXECUTE_REAL);

		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));
		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));

		assertThat(this.delegateInvocations).as("EXECUTE_REAL must invoke the real tool every time").hasValue(2);
		// Still records, so a later switch back to REPLAY_FROM_CASSETTE has something to replay.
		assertThat(this.cacheDirectory.toFile().listFiles()).hasSize(1);
	}

	@Test
	@DisplayName("Same tool, different arguments: each argument set gets its own fixture and its own correct result")
	void differentArgumentsResolveToDifferentResults() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C",
				"getWeather|{\"city\":\"Amsterdam\"}", "Rainy, 14C"));
		VcrToolCallingManager vcrManager = manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE);

		AssistantMessage.ToolCall istanbul = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");
		AssistantMessage.ToolCall amsterdam = toolCall("call-2", "getWeather", "{\"city\":\"Amsterdam\"}");

		// Record both.
		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(istanbul)));
		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(amsterdam)));
		assertThat(this.delegateInvocations).hasValue(2);
		assertThat(this.cacheDirectory.toFile().listFiles()).as("two distinct fixtures, one per argument set")
			.hasSize(2);

		// Replay both -- each must come back with ITS OWN city's weather, not the other's.
		ToolExecutionResult replayedIstanbul = vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(istanbul)));
		ToolExecutionResult replayedAmsterdam = vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(amsterdam)));

		assertThat(this.delegateInvocations).as("neither replay should touch the real tool").hasValue(2);
		assertThat(textOf(replayedIstanbul)).isEqualTo("Sunny, 28C");
		assertThat(textOf(replayedAmsterdam)).isEqualTo("Rainy, 14C");
	}

	@Test
	@DisplayName("A turn with two parallel tool calls only replays once BOTH have a cassette entry")
	void partialHitFallsBackToRealInvocationForTheWholeTurn() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C",
				"getTime|{\"city\":\"Istanbul\"}", "14:00"));
		AssistantMessage.ToolCall weatherCall = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");
		AssistantMessage.ToolCall timeCall = toolCall("call-2", "getTime", "{\"city\":\"Istanbul\"}");
		VcrToolCallingManager vcrManager = manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE);

		// Only record getWeather standalone first.
		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(weatherCall)));
		assertThat(this.delegateInvocations).hasValue(1);

		// A turn asking for BOTH getWeather and getTime together: getWeather alone has a
		// fixture, getTime does not -- must fall back to a full real invocation of the
		// whole turn (not a partial replay), and record fresh fixtures for both.
		ToolExecutionResult result = vcrManager.executeToolCalls(promptWithUserMessage("weather and time?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(weatherCall, timeCall)));

		assertThat(this.delegateInvocations).as("a partial hit must invoke the real tool(s) for the whole turn")
			.hasValue(3);
		List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) result.conversationHistory()
			.get(result.conversationHistory().size() - 1)).getResponses();
		assertThat(responses).extracting(ToolResponseMessage.ToolResponse::responseData)
			.containsExactly("Sunny, 28C", "14:00");
	}

	@Test
	@DisplayName("@VcrTool(mode = EXECUTE_REAL) override wins over the configured default")
	void threadLocalOverrideWinsOverConfiguredMode() {
		ToolCallingManager delegate = realDelegate(Map.of("getWeather|{\"city\":\"Istanbul\"}", "Sunny, 28C"));
		AssistantMessage.ToolCall call = toolCall("call-1", "getWeather", "{\"city\":\"Istanbul\"}");
		VcrToolCallingManager vcrManager = manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE);

		vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
				chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));
		assertThat(this.delegateInvocations).hasValue(1);

		try {
			VcrToolModeOverride.set(VcrToolMode.EXECUTE_REAL);
			vcrManager.executeToolCalls(promptWithUserMessage("weather?"),
					chatResponseWithToolCalls(assistantMessageWithToolCalls(call)));
			assertThat(this.delegateInvocations).as("override to EXECUTE_REAL must still invoke the real tool")
				.hasValue(2);
		}
		finally {
			VcrToolModeOverride.clear();
		}
	}

	@Test
	@DisplayName("resolveToolDefinitions delegates unchanged")
	void resolveToolDefinitionsDelegatesUnchanged() {
		ToolCallingManager delegate = mock(ToolCallingManager.class);
		given(delegate.resolveToolDefinitions(any())).willReturn(List.of());

		manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE).resolveToolDefinitions(null);

		verify(delegate, times(1)).resolveToolDefinitions(any());
	}

	@Test
	@DisplayName("No tool calls present: delegates straight through, no fixture written")
	void noToolCallsDelegatesThrough() {
		ToolCallingManager delegate = mock(ToolCallingManager.class);
		ToolExecutionResult emptyResult = ToolExecutionResult.builder().conversationHistory(List.of()).build();
		given(delegate.executeToolCalls(any(), any())).willReturn(emptyResult);

		AssistantMessage noToolCalls = AssistantMessage.builder().content("hi").build();
		manager(delegate, VcrToolMode.REPLAY_FROM_CASSETTE).executeToolCalls(promptWithUserMessage("hi"),
				chatResponseWithToolCalls(noToolCalls));

		verify(delegate, times(1)).executeToolCalls(any(), any());
		verify(delegate, never()).resolveToolDefinitions(any());
		assertThat(this.cacheDirectory.toFile().listFiles()).isEmpty();
	}

}
