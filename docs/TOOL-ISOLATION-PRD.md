# Tool Isolation — PRD

Last updated: 2026-07-25

Status: **implemented.** All three forks below were resolved as recommended and signed
off, and the design is built: `VcrToolCallingManager`/`VcrToolCallingManagerBeanPostProcessor`,
`VcrToolExecutionTrack` + its store, `VcrToolMode`, and the `@VcrTool` per-test override
all exist in `io.github.rifatcakir.springai.testtools.recorder.tool` (and `...recorder.junit`
for the annotation). Verified against a real model end to end
(`OllamaToolIsolationEndToEndTests`, through Spring AI's own autoconfiguration graph) and
with a full unit-test suite (`VcrToolCallingManagerTests`) proving isolation, real
execution, per-test override, and multi-argument keying. See `docs/ROADMAP.md`'s `T1` row
for the summary and `docs/EXTERNAL-FEEDBACK.md` for how this closes the gap an external
review raised. This document is kept as the historical diagnosis and design record — the
"what's mechanical" section below is no longer forward-looking, it describes what was
actually built.

This supersedes `docs/TOOL-ISOLATION-PROPOSAL.md` (Gemini's original
proposal, kept as-is for the record) wherever the two disagree — every disagreement below
is backed by bytecode inspection and a working diagnostic probe, not by re-reading
Gemini's design more carefully. Same discipline as `docs/A1-ASSERTIONS-PRD.md`,
`docs/R4-EMBEDDING-INTERCEPTION.md`, and `CLAUDE.md`'s own verified-facts table: don't
trust a proposal's premise, check the real jar.

**Decision already made by the project owner, not open for debate here:** this will be
built, before this project's first publish. Default behavior becomes full isolation —
replay never invokes a real `@Tool` method. What's still open is *how*, captured as three
forks at the end.

## 1. Diagnosis — Gemini's proposed interception point does not work as described

Gemini's proposal (`docs/TOOL-ISOLATION-PROPOSAL.md`) was: wrap every `ToolCallback` bean
in a `BeanPostProcessor`, the same pattern `VcrEmbeddingModelBeanPostProcessor` already
uses for `EmbeddingModel`. **Checked against the real `spring-ai-model-2.0.0.jar` and
`spring-ai-client-chat-2.0.0.jar` bytecode (`javap -p -c`), plus a working diagnostic
probe booted against a real Spring context — not assumed from the proposal's own
reasoning.**

### 1.1 `ToolCallback`'s real shape (confirmed, `javap` against `spring-ai-model-2.0.0.jar`)

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();
    ToolMetadata getToolMetadata();               // default
    String call(String toolInput);
    String call(String toolInput, ToolContext);   // default
}
```

Matches Gemini's premise closely — `call(String)` is real, `getToolDefinition()` is real.
Where the premise breaks is *what actually holds instances of this interface* at
execution time, not the interface's own shape.

### 1.2 The decisive finding: most `ToolCallback` instances are never Spring beans

Disassembled `DefaultToolCallingManager.executeToolCall(Prompt, AssistantMessage,
ToolContext)` — the one method that actually invokes a tool. Its logic, in order:

1. Read `List<ToolCallback> toolCallbacks` off `prompt.getOptions()` **if** it's a
   `ToolCallingChatOptions` and its `getToolCallbacks()` is non-empty. This is exactly
   what `.tools(someObject)` at a `ChatClient` call site produces: Spring AI converts
   `someObject`'s `@Tool`-annotated methods to `MethodToolCallback` instances (via
   `ToolCallbacks.from(...)`) **fresh, per request**, and stuffs them into this one
   request's `ToolCallingChatOptions` — never registered anywhere as a Spring bean.
2. For each tool call the model made: look up the matching callback by name in that list
   first (`.stream().filter(cb -> name matches).findFirst()`).
3. **Only if not found there**, fall back to `this.toolCallbackResolver.resolve(name)` —
   confirmed via `lambda$executeToolCall$2`, which is a one-line delegate to
   `ToolCallbackResolver.resolve(String)`.

`ToolCallbackResolver` (`DelegatingToolCallbackResolver` wrapping a
`StaticToolCallbackResolver`) is what's actually built from Spring `ToolCallback` **beans**
— confirmed in `ToolCallingAutoConfiguration.toolCallbackResolver(GenericApplicationContext,
List<ToolCallback>, ...)`, a `@Bean` method that constructor-injects every `ToolCallback`
bean in the context. **This is real, and Gemini's mechanism would work for it** — but it's
only ever consulted as a *fallback*, for a tool referenced by name that wasn't already
attached to the request's own `ChatOptions`. This project's own tests, and the dominant,
documented Spring AI usage pattern (`chatClient.prompt()...tools(weatherTool)`, see
`OllamaToolCallingEndToEndTests`), never reach this fallback at all — the object-based
`.tools(...)` path is resolved from step 1 above, which a `ToolCallback`-level
`BeanPostProcessor` never sees, because there is no bean to post-process.

**Verdict: proxying `ToolCallback` beans is real but insufficient — it would silently
miss the majority, and the specific pattern this project's own README documents as the
main way to attach a tool at all.**

### 1.3 The correct interception point: `ToolCallingManager`, not `ToolCallback`

`executeToolCall` is a private method on `DefaultToolCallingManager`, called from the one
public method that matters: `executeToolCalls(Prompt, ChatResponse)` — **the single
choke point every tool invocation passes through, regardless of whether the callback came
from `ChatOptions` (step 1) or the resolver (step 3).** And unlike individual
`ToolCallback` instances, `ToolCallingManager` itself **is** a singleton Spring bean:

- `ToolCallingAutoConfiguration.toolCallingManager(ToolCallbackResolver,
  ToolExecutionExceptionProcessor, ...)` — a `@Bean` method, confirmed via `javap -p`.
- `ChatClientAutoConfiguration.toolCallingAdvisorBuilder(ChatClientBuilderProperties,
  ToolCallingManager, ...)` — takes that same bean as a method-injected parameter,
  builds the `ToolCallingAdvisor.Builder` that becomes part of the auto-configured
  `ChatClient.Builder`.
- `ToolCallingAdvisor` (confirmed via `javap -p`) holds it as
  `protected final ToolCallingManager toolCallingManager` — set once, at construction,
  never re-resolved per call.

**A `BeanPostProcessor` wrapping the `ToolCallingManager` bean — the same mechanism
`VcrEmbeddingModelBeanPostProcessor` already uses successfully for `EmbeddingModel`, just
targeting a different bean type — intercepts every tool execution, regardless of how the
`ToolCallback` was obtained**, because by the time any tool call actually happens, it's
already funneled through this one bean's `executeToolCalls(...)`.

### 1.4 Empirically confirmed, not just argued from bytecode

Wrote and ran a throwaway diagnostic probe (deleted after, never committed — the pom.xml
change needed to add `spring-ai-autoconfigure-model-tool`/
`spring-ai-autoconfigure-model-chat-client` as temporary test-scope dependencies was
reverted via `git checkout -- pom.xml` immediately after): booted a real
`ApplicationContextRunner` with both of Spring AI's own autoconfiguration classes plus a
`BeanPostProcessor` wrapping `ToolCallingManager` in a marker type. Result: `context.
getBean(ToolCallingManager.class)` returned the wrapper, and `context.getBean(ChatClient.
Builder.class)` — which depends on `toolCallingAdvisorBuilder`, which depends on that same
bean — built successfully with no wiring conflict. **The mechanism works, confirmed
against a live Spring context, not just inferred.**

### 1.5 A scope limit inherited from Recorder's existing design, not new

`spring-ai-starter-model-ollama` was confirmed (via `mvn dependency:tree` on the sibling
`spring-ai-test-tools-example` project) to transitively pull in both
`spring-ai-autoconfigure-model-tool` and `spring-ai-autoconfigure-model-chat-client` — so
the README's own documented usage (`@SpringBootTest` + `@Autowired ChatClient.Builder`)
does have a real `ToolCallingManager` bean to intercept.

**But this project's own `ChatClient.builder(chatModel)` static-factory path — used by
its own existing e2e tests, and by every stub-based test this library's README shows as
the "fastest path" — never goes through Spring Boot autoconfiguration at all.** Confirmed
via `javap -c` on `DefaultChatClientBuilder`: when no `ToolCallingAdvisor.Builder` is
supplied, it builds its own private, non-bean `ToolCallingManager` inline
(`ToolCallingManager.builder().observationRegistry(...).build()`). **There is no bean to
post-process in this path — tool isolation, like the rest of Recorder's Spring-context
mechanisms, simply does not apply here.** This is not a new limitation this feature
introduces; it's the same boundary `ChatClientBuilderCustomizer` (the mechanism Recorder's
own chat-caching advisor already relies on) already has. Document it as a limitation, not
fix it — a fix would mean reaching into `ChatClient.builder(model)`'s own construction,
which this library has never done and has no hook for.

## 2. Design

### 2.1 Mechanism

`VcrToolCallingManagerBeanPostProcessor` wraps the `ToolCallingManager` bean (structurally
parallel to `VcrEmbeddingModelBeanPostProcessor`). The wrapper,
`VcrToolCallingManager implements ToolCallingManager`:

- `resolveToolDefinitions(ToolCallingChatOptions)` — delegates unchanged. This is what
  advertises tool name/description/schema to the model; it must be exactly what the real
  tool declares, or the model behaves differently and (correctly) busts the existing
  cache, since tool definitions already participate in `VcrCacheKeyGenerator`'s hash.
- `executeToolCalls(Prompt, ChatResponse)` — the interception point. For each tool call
  in the response: on a cassette hit under `VcrToolMode.REPLAY_FROM_CASSETTE`, return the
  recorded result without calling the delegate — the real `@Tool` method's body never
  runs. On a miss, or under `VcrToolMode.EXECUTE_REAL`, delegate to the real manager,
  capture the arguments and result, and write them to the cassette.

### 2.2 New fixture type — not an extension of `VcrTrack`

Same reasoning `docs/R4-EMBEDDING-INTERCEPTION.md` gave for not bolting embedding fields
onto `VcrTrack`: a tool execution is not a model turn. It happens *between* model-turn
advisor invocations, orchestrated by `ToolCallingAdvisor` calling `ToolCallingManager`
directly — not something `DeterministicVcrAdvisor` (which only ever sees prompts and
responses at the model-call boundary) has a slot for today. `VcrTrack.ToolCallSnapshot`/
`ToolResponseSnapshot` capture what the *model* said about a tool call in conversation
history; they do not capture what the *tool itself* returned when actually invoked, and
reusing them would conflate two different things that happen to look similar.

Proposed: `VcrToolExecutionTrack(String schemaVersion, String hash, String recordedAt,
String toolName, String arguments, String result)`, its own store/mapper pair
(`VcrToolExecutionTrackStore`/`Mapper`, mechanically identical to every other
store/mapper pair in this codebase: one JSON file per hash, atomic write, tolerant read —
a corrupt fixture degrades to a miss per design rule #7), own cache directory
(`spring.ai.test.vcr.tool.cache-directory`, defaulting to `src/test/resources/
llm-cache-tool`, mirroring `llm-cache-embedding`), own independent
`CURRENT_SCHEMA_VERSION` starting at `"1"`. **No change to `VcrTrack`'s schema version or
to `VcrCacheKeyGenerator`'s existing hash** — the model-turn cache key is unaffected,
because a replayed tool result is byte-identical to what was recorded, so subsequent-turn
canonicalization (which already embeds prior tool call/response content, per `VcrTrack`
schema `"2"`) sees the same text either way.

### 2.3 Cache key — hand-assembled, same discipline as everything else

```
vcr-tool-canonical-form/v1
name=<tool name>
arguments=<the exact argument JSON string the model produced, unmodified>
```

**Not** re-serialized/re-canonicalized JSON — hashed exactly as the model returned it,
the same "store and hash exactly what was received, never reflect it through a
round-trip" discipline `VcrTrack.ToolCallSnapshot.arguments()`'s own Javadoc already
states ("as the model returned them — not parsed or validated by this library"). Design
rule #1 (exact match, no fuzzy matching, ever) applies here exactly as everywhere else:
two argument strings that are semantically equal but syntactically different (key order,
whitespace) are treated as different requests and get two fixtures, on purpose — the
same reasoning that already governs prompt hashing.

## 3. Forks needing your sign-off

### Fork (a) — intercept point: confirmed, not actually a fork anymore

Diagnosis above settles this: `ToolCallingManager` bean, via `BeanPostProcessor`, not
`ToolCallback`. Listed here for completeness since you asked for it explicitly, but
there's no decision left to make — the bytecode and the probe agree.

**One real sub-decision remains:** what happens for the non-Spring-context path
(§1.5) — a plain `ChatClient.builder(model)` with no `ToolCallingManager` bean to wrap.
Recommendation: **document it as an explicit limitation, same category as "stubs have no
Spring context at all"** — tool isolation is a Recorder feature, and Recorder already
only applies inside a Spring context with its own autoconfiguration active. Not fixing
this is consistent with the project's existing scope boundary, not a new gap.

### Fork (b) — reconciling with today's `INSIDE_TOOL_LOOP`

Today: `INSIDE_TOOL_LOOP` means "each model turn replays from its own fixture, but the
real `@Tool` method still runs on every replay" — documented, tested
(`OllamaToolCallingEndToEndTests` asserts `weatherTool.invocations.hasValue(1)` on a
replay run), and warned about prominently (README/`tool-calling.md`'s "⚠️ Tool
side-effects on replay" section, written last session).

**This PRD's new default inverts that:** `VcrToolMode.REPLAY_FROM_CASSETTE` (default)
means the real `@Tool` method does *not* run on a cassette hit, under either
`VcrScope`. The two scopes and the new mode are orthogonal, answering different
questions:

- `VcrScope` (`OUTSIDE_TOOL_LOOP`/`INSIDE_TOOL_LOOP`) — does an entire interaction
  replay as one fixture, or does each model turn replay individually while the tool loop
  itself still runs?
- `VcrToolMode` (`REPLAY_FROM_CASSETTE`/`EXECUTE_REAL`) — when the tool loop *does* run
  (i.e., under `INSIDE_TOOL_LOOP`, or on a genuine `OUTSIDE_TOOL_LOOP` miss), does an
  individual tool invocation replay from its own cassette or actually execute?

Under `OUTSIDE_TOOL_LOOP` this is moot on a hit (the whole interaction replays before the
tool loop is ever reached — `VcrToolMode` is never consulted). Under `INSIDE_TOOL_LOOP`,
`VcrToolMode` is what decides whether today's documented "the real tool still runs"
behavior happens. **`EXECUTE_REAL` recovers exactly today's `INSIDE_TOOL_LOOP` behavior**
— nothing is deleted, the mode that reproduces the current contract just stops being the
default.

**Why this is a clean breaking change, not a migration:** confirmed via `git tag` (no
tags exist) and `docs/PUBLISHING.md` ("nobody has actually published anything to Central
yet") — there are zero external users of a released version. The only things that
actually need updating are internal:

- `OllamaToolCallingEndToEndTests`'s existing assertion (`invocations.hasValue(1)` on
  replay) needs `@VcrTest(toolMode = VcrToolMode.EXECUTE_REAL)` (or equivalent) added to
  keep testing what it already tests — real tool re-invocation — plus a **new** test
  asserting `invocations.hasValue(0)` on replay under the new default, proving isolation
  actually holds.
  Because this project's own e2e suite builds `ChatClient` via the plain
  `ChatClient.builder(model)` path (§1.5), **a second, new e2e test is also needed that
  goes through real Spring Boot autoconfiguration** (the way the sibling example project
  does) — today's suite structurally cannot exercise the new mechanism at all as
  currently written.
- README's/`tool-calling.md`'s "⚠️ Tool side-effects on replay" section needs rewriting,
  not just amending: the warning was written to describe `INSIDE_TOOL_LOOP`'s *old*
  default, and would now be actively wrong about the new default. It becomes something
  closer to "side effects only happen if you opt into `EXECUTE_REAL`" — the warning's
  spirit (this is a bug-report magnet, be explicit about it) still applies, just aimed at
  the opt-in case instead of the default.
- The sibling `spring-ai-test-tools-example` project's `ToolCallingRecordReplayTest` (and
  any committed fixtures it produced) needs the same review.

**Signed off:** `EXECUTE_REAL` mapping to today's `INSIDE_TOOL_LOOP`
contract, named per-test the same way `@Vcr(mode = ...)` already overrides `VcrMode`
today (e.g. `@VcrTest(toolMode = VcrToolMode.EXECUTE_REAL)`, or folded into the existing
`@Vcr` annotation as a second attribute rather than a new annotation — your call, not
decided here).

### Fork (c) — cassette keying: name + argument string, not call order

Considered both options you asked about:

- **Tool name + exact argument string (recommended).** Deterministic, matches design
  rule #1 exactly, requires no ordering assumptions. A tool called twice in one
  conversation with different arguments (`getWeather("Istanbul")`, then
  `getWeather("Amsterdam")`) naturally gets two different fixtures — no special-casing
  needed. **Explicit, accepted limitation:** a genuinely non-idempotent tool (a random
  number generator, a counter) called twice with identical arguments and expected to
  return different results each time is fundamentally incompatible with this design —
  but this is the exact same category of limitation `record-replay.md` already documents
  for `temperature > 0` freezing one sampled draw. Not a new kind of gap this feature
  introduces.
- **Call-order-within-cassette, considered and not recommended.** Would handle the
  non-idempotent case, but introduces an ordering dependency this library has
  deliberately avoided everywhere else (fixtures are keyed by content hash, never by
  "the Nth thing recorded") — fragile against reordered tool calls, parallel tool calls
  in one turn resolving in a different order, or a test being run with a subset of its
  original tool calls. Rejected unless you have a concrete non-idempotent-tool scenario
  that name+arguments genuinely can't serve — recommend not building this unless a real
  need shows up, per design rule discipline elsewhere in this project (e.g. Evaluator's
  toxicity-checking, `docs/VISION.md`: "a documented, buildable pattern... not built
  speculatively ahead of a real need").

**Signed off:** name + exact argument string as the sole key, and
the non-idempotent-tool case is out of scope (documented as a limitation) rather than
something to design around now.

## 4. What's mechanical once the forks above are resolved

- `VcrToolExecutionTrack`/`VcrToolExecutionTrackStore`/`VcrToolExecutionTrackMapper` —
  structurally identical to every other store/mapper pair in this codebase.
- `VcrToolCallingManagerBeanPostProcessor` + `VcrToolCallingManager` — structurally
  parallel to `VcrEmbeddingModelBeanPostProcessor`/`VcrEmbeddingModel`.
- `VcrProperties` gains a `tool` group: `spring.ai.test.vcr.tool.mode` (`VcrToolMode`,
  default `REPLAY_FROM_CASSETTE`), `.cache-directory`.
- Per-test override, exact API shape pending fork (b)'s sign-off.
- Two e2e tests updated/added per fork (b): the existing assertion re-pointed at
  `EXECUTE_REAL`, a new one proving isolation under the new default, and (separately) a
  new autoconfiguration-based e2e test since the existing suite never exercises the
  Spring-bean path at all today.
- README/`tool-calling.md`/`record-replay.md`'s existing side-effect warning rewritten,
  not just amended.

Nothing above touches `VcrTrack`, `VcrCacheKeyGenerator`, or any existing chat/embedding/
streaming fixture — fully additive, same as R3 (streaming) and R4 (embeddings) were.
