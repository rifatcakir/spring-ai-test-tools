package io.github.rifatcakir.springai.testtools.recorder.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

/**
 * Intercepts {@link ToolCallingManager#executeToolCalls(Prompt, ChatResponse)} — the
 * single choke point every tool invocation passes through in Spring AI 2.0, regardless of
 * whether the {@code ToolCallback} came from the request's own {@code
 * ToolCallingChatOptions} (what {@code .tools(someObject)} produces, fresh per request,
 * never a Spring bean) or from the bean-backed {@code ToolCallbackResolver}. See {@code
 * docs/TOOL-ISOLATION-PRD.md} section 1 for the bytecode-verified diagnosis this class's
 * design rests on — in particular, why wrapping individual {@code ToolCallback} beans (as
 * originally proposed) would silently miss the dominant, {@code .tools(...)}-based usage
 * pattern this project's own README documents.
 *
 * <h2>Isolation, not just caching</h2>
 *
 * <p>Under {@link VcrToolMode#REPLAY_FROM_CASSETTE} (the default), a cassette hit means
 * the real delegate — and therefore the real {@code @Tool} method's body — is never
 * invoked at all. This is a stronger guarantee than {@code DeterministicVcrAdvisor}'s own
 * model-call replay: that class replays a model's answer, but Spring AI's tool-calling
 * loop still runs for real underneath it whenever {@code VcrScope#INSIDE_TOOL_LOOP} is in
 * effect. This class is what makes replay under that scope side-effect-free by default.
 *
 * <h2>Granularity: one turn, not one tool call, is the unit of replay</h2>
 *
 * <p>A single model turn can request more than one tool call in parallel. This class
 * replays a turn only when <em>every</em> tool call in it already has a cassette entry —
 * a partial hit falls through to a full real invocation of the whole turn, recording
 * fresh fixtures for every call in it. This keeps the "first run records, every run after
 * replays" behaviour simple and avoids inventing a way to invoke a subset of one
 * {@code ToolExecutionResult}, which Spring AI's own API has no hook for.
 *
 * <h2>Known, accepted approximation: {@code returnDirect}</h2>
 *
 * <p>{@code ToolExecutionResult#returnDirect()} is a whole-turn aggregate — {@code true}
 * only if every tool call in the turn declares {@code ToolMetadata#returnDirect() ==
 * true} — computed by Spring AI from each involved {@code ToolCallback}'s own metadata,
 * which this class deliberately never resolves (doing so would mean duplicating {@code
 * DefaultToolCallingManager}'s private callback-resolution algorithm just to read
 * metadata). Instead, the turn-level aggregate at record time is stored redundantly on
 * every participating fixture, and recomputed as the logical AND of whichever fixtures
 * are actually replayed together for a given turn. This reproduces the original value
 * correctly as long as a fixture is replayed alongside the same tool calls it was
 * recorded with — the narrow, accepted gap is a tool invocation recorded once, then later
 * replayed standalone in a different turn with a different set of co-occurring tools,
 * where {@code returnDirect} genuinely differs. {@code returnDirect} is a comparatively
 * rare Spring AI feature; this is judged an acceptable v1 approximation rather than a
 * reason to duplicate private resolution logic.
 *
 * @author Rifat Cakir
 */
public class VcrToolCallingManager implements ToolCallingManager {

	private static final Logger logger = LoggerFactory.getLogger(VcrToolCallingManager.class);

	private final ToolCallingManager delegate;

	private final VcrToolExecutionCacheKeyGenerator keyGenerator;

	private final VcrToolExecutionTrackStore store;

	private final VcrToolMode mode;

	public VcrToolCallingManager(ToolCallingManager delegate, VcrToolExecutionCacheKeyGenerator keyGenerator,
			VcrToolExecutionTrackStore store, VcrToolMode mode) {
		Assert.notNull(delegate, "delegate must not be null");
		Assert.notNull(keyGenerator, "keyGenerator must not be null");
		Assert.notNull(store, "store must not be null");
		Assert.notNull(mode, "mode must not be null");
		this.delegate = delegate;
		this.keyGenerator = keyGenerator;
		this.store = store;
		this.mode = mode;
	}

	/**
	 * Delegated unchanged. This is what advertises tool name/description/schema to the
	 * model — it must be exactly what the real tool declares, or the model behaves
	 * differently and correctly busts {@code VcrCacheKeyGenerator}'s existing hash, which
	 * already includes tool definitions.
	 */
	@Override
	public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
		return this.delegate.resolveToolDefinitions(chatOptions);
	}

	@Override
	public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
		Assert.notNull(prompt, "prompt must not be null");
		Assert.notNull(chatResponse, "chatResponse must not be null");

		Optional<AssistantMessage> assistantMessage = findAssistantMessageWithToolCalls(chatResponse);
		if (assistantMessage.isEmpty()) {
			// Defensive: ToolCallingAdvisor should never reach this without a tool call
			// present. Nothing to isolate; delegate straight through.
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		List<AssistantMessage.ToolCall> toolCalls = assistantMessage.get().getToolCalls();
		VcrToolMode effectiveMode = VcrToolModeOverride.current().orElse(this.mode);

		if (effectiveMode == VcrToolMode.REPLAY_FROM_CASSETTE) {
			Optional<ToolExecutionResult> replayed = tryReplay(prompt, assistantMessage.get(), toolCalls);
			if (replayed.isPresent()) {
				return replayed.get();
			}
			logger.info(
					"VCR TOOL CACHE MISS for at least one of {} call(s) — invoking the real tool(s) and recording",
					toolCalls.size());
		}

		ToolExecutionResult realResult = this.delegate.executeToolCalls(prompt, chatResponse);
		record(toolCalls, realResult);
		return realResult;
	}

	private Optional<AssistantMessage> findAssistantMessageWithToolCalls(ChatResponse chatResponse) {
		if (chatResponse.getResults() == null) {
			return Optional.empty();
		}
		for (Generation generation : chatResponse.getResults()) {
			AssistantMessage output = generation.getOutput();
			if (output != null && !CollectionUtils.isEmpty(output.getToolCalls())) {
				return Optional.of(output);
			}
		}
		return Optional.empty();
	}

	/**
	 * Attempts full-turn replay. Returns empty (not a partial result) the moment any one
	 * call in the turn lacks a cassette entry — see this class's Javadoc on granularity.
	 */
	private Optional<ToolExecutionResult> tryReplay(Prompt prompt, AssistantMessage assistantMessage,
			List<AssistantMessage.ToolCall> toolCalls) {
		Map<String, VcrToolExecutionTrack> hitsById = new LinkedHashMap<>();
		for (AssistantMessage.ToolCall call : toolCalls) {
			VcrToolExecutionCacheKey key = this.keyGenerator.generate(call.name(), call.arguments());
			Optional<VcrToolExecutionTrack> existing = this.store.read(key.hash());
			if (existing.isEmpty()) {
				return Optional.empty();
			}
			hitsById.put(call.id(), existing.get());
		}

		logger.info("VCR TOOL REPLAY isolating {} call(s) — real tool method(s) not invoked", toolCalls.size());

		List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
		boolean returnDirect = true;
		for (AssistantMessage.ToolCall call : toolCalls) {
			VcrToolExecutionTrack track = hitsById.get(call.id());
			responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), track.result()));
			returnDirect = returnDirect && track.returnDirect();
		}
		ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder().responses(responses).build();

		List<Message> history = new ArrayList<>(prompt.getInstructions());
		history.add(assistantMessage);
		history.add(toolResponseMessage);

		return Optional.of(ToolExecutionResult.builder().conversationHistory(history).returnDirect(returnDirect).build());
	}

	/**
	 * Records every tool call in the turn against the real result that was just produced,
	 * so {@link VcrToolMode#EXECUTE_REAL} keeps a cassette current for a later switch back
	 * to {@link VcrToolMode#REPLAY_FROM_CASSETTE}.
	 *
	 * <p><strong>An existing fixture whose recorded result is unchanged is deliberately
	 * left alone</strong> rather than rewritten with a fresh {@code recordedAt}. Under
	 * {@code EXECUTE_REAL} the real tool runs on every call, so rewriting unconditionally
	 * would dirty a committed fixture on every single test run — caught for real by the
	 * sibling example project's CI guard ("mvn test modified this project's own working
	 * tree"), not predicted. A genuinely changed tool result still overwrites, since that
	 * is real signal a reviewer should see in the diff.
	 */
	private void record(List<AssistantMessage.ToolCall> toolCalls, ToolExecutionResult result) {
		List<Message> history = result.conversationHistory();
		if (history.isEmpty()) {
			return;
		}
		Message last = history.get(history.size() - 1);
		if (!(last instanceof ToolResponseMessage toolResponseMessage)) {
			return;
		}

		Map<String, ToolResponseMessage.ToolResponse> responsesById = new HashMap<>();
		for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
			responsesById.put(response.id(), response);
		}

		String recordedAt = Instant.now().toString();
		for (AssistantMessage.ToolCall call : toolCalls) {
			ToolResponseMessage.ToolResponse response = responsesById.get(call.id());
			if (response == null) {
				continue;
			}
			VcrToolExecutionCacheKey key = this.keyGenerator.generate(call.name(), call.arguments());
			if (isUnchanged(key, response.responseData(), result.returnDirect())) {
				logger.debug("VCR TOOL fixture [{}] already records this exact result — leaving it untouched",
						VcrToolExecutionTrackStore.shortHash(key.hash()));
				continue;
			}
			VcrToolExecutionTrack track = new VcrToolExecutionTrack(VcrToolExecutionTrack.CURRENT_SCHEMA_VERSION,
					key.hash(), recordedAt, key.canonicalRequest(), call.name(), call.arguments(),
					response.responseData(), result.returnDirect());
			this.store.write(track);
		}
	}

	private boolean isUnchanged(VcrToolExecutionCacheKey key, String result, boolean returnDirect) {
		return this.store.read(key.hash())
			.filter(existing -> Objects.equals(existing.result(), result) && existing.returnDirect() == returnDirect)
			.isPresent();
	}

}
