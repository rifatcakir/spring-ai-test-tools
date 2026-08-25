# Spring AI 2.0 GA compatibility / gap analysis

**Update:** row 6.2 (cache-read/cache-write token accounting) is closed — was **Gap**, now
**Covered**. See `docs/R6-CACHE-TOKEN-USAGE-PRD.md` for the fix (`VcrTrack.UsageSnapshot`/
`VcrTrackMapper`/`VcrStreamTrackMapper`, schema `"6"`) and the library's own version bump to
`0.1.1`. The row and tally below are updated in place rather than left stale; the rest of this
document's analysis is otherwise unchanged from when it was written.

Status: **Analysis, not a build plan.** Built directly on `docs/SPRING-AI-2.0-FEATURES.md`
(the feature inventory produced in the prior task) and re-verified against this library's
own source (`VcrCacheKeyGenerator`, `VcrTrackMapper`, `VcrTrack`, `DeterministicVcrAdvisor`,
`VcrToolCallingManager`, `VcrScope`) plus fresh bytecode/Maven-Central checks for every claim
below that the source-reading alone couldn't settle. This **replaces** an earlier draft of
this same file produced in a different session; see
[Corrections to the prior draft](#corrections-to-the-prior-draft-of-this-file) for the two
places that draft was checked again and turned out to be wrong.

**Method:** every "Covered"/"Gap" verdict below is backed by one of: (a) reading this
library's actual source in full (not summarized), (b) `javap`/`javap -c` against the real
`spring-ai-*:2.0.0` jars pulled from Maven Central this session, or (c) a Maven Central
directory/metadata listing checked directly (not searched via a possibly-stale index). Where
a claim is inherited from the prior draft's own real-model e2e-test evidence (Testcontainers
+ `llama3.2:1b`) rather than re-run this session, that's stated explicitly — this pass did
not re-execute any Docker-based test.

**Classification legend:**

- **Covered** — the library already handles this correctly, verified (not assumed).
- **Partial** — works in the common case, with a documented edge/limitation.
- **Gap** — a real, confirmed limitation; nothing in this library reaches this scenario.
- **N/A** — out of scope by design, or not independently verifiable right now.

Each row also carries a **kind**: *already-covered* (needs nothing), *scenario-opportunity*
(a demo/doc worth adding, no library code needed), or *real-gap* (would need actual library
code to close — none attempted in this analysis pass).

---

## The six open questions, answered directly

### 1. Options refactoring — does it break `VcrCacheKeyGenerator`?

**No. Confirmed unchanged.** `ChatOptions` (the only interface `VcrCacheKeyGenerator`
reads) was re-disassembled this session against the real `2.0.0` jar:

```
getModel(), getFrequencyPenalty(), getMaxTokens(), getPresencePenalty(),
getStopSequences(), getTemperature(), getTopK(), getTopP(), Builder<?> mutate(), builder()
```

Eight getters — the exact eight `VcrCacheKeyGenerator#canonicalize`
(`src/main/java/.../key/VcrCacheKeyGenerator.java:193-212`) reads, in the same order it
already appends them, confirmed by re-reading that method's source directly this session, not
assumed. `ToolCallingChatOptions.getToolCallbacks()`/`.getToolContext()` — what
`canonicalToolDescriptors` reads for tool schemas — is likewise unchanged (`getToolNames()`'s
removal predates this GA and is already in `CLAUDE.md`'s table).

The "builders, immutable, reflection-free merging (`combineWith`)" changes are all about *how
one concrete `ChatOptions` implementation is built and merged* — confirmed this session via
`javap` that `ChatOptions.Builder<B>` now exposes `combineWith(Builder<?>)` and `clone()`
alongside the setters. `VcrCacheKeyGenerator` never builds, merges, or reflects over an
options object; it only calls the public getters on whatever `Prompt.getOptions()` already
handed it, post-merge. **This is architecturally invisible to the cache key by construction**
— see §1 in the matrix. `config-property .options segment removal` is a *provider*
auto-configuration concern (e.g. `spring.ai.ollama.chat.options.temperature` →
`...chat.temperature`); this library's own `spring.ai.test.vcr.*` namespace never used that
segment (`grep`-confirmed against `VcrProperties.java`), so nothing to change there either.

### 2. Cumulative usage — is replayed usage correct? Two separate findings, one new.

**(a) Cross-iteration cumulative usage does not exist at the pinned `2.0.0` — independently
reconfirmed this session, not just inherited.** `javap -v` against the real
`spring-ai-client-chat:2.0.0` jar's `ToolCallingAdvisor.class` and
`StructuredOutputValidationAdvisor.class`, grepped for `usage` (case-insensitive) in the full
constant pool of each: **zero matches in either class.** Neither recursive advisor references
`Usage`, `UsageCalculator`, or any accumulator type at all at this version. So at `2.0.0`,
each loop iteration's `ChatResponse` carries its own independent, non-cumulative usage, and
only the *final* iteration's `ChatResponse` ever reaches `DeterministicVcrAdvisor` (when
`VcrScope.OUTSIDE_TOOL_LOOP`, the default) or each iteration's own usage individually (under
`INSIDE_TOOL_LOOP`, one fixture per turn) — `VcrTrackMapper#toResponseSnapshot`
(`track/VcrTrackMapper.java:257-264`) is a pure pass-through of whatever
`ChatResponseMetadata.getUsage()` reports for the one `ChatResponse` it's given; it neither
computes nor drops a running total, because there is no running total to compute at this
version. **Nothing to fix — there's no cumulative concept in play at the pinned version, and
what's recorded/replayed (each response's own real usage) is exactly correct for what it is.**
Not re-verified against a live model this session (inherited from the prior draft's own real
`llama3.2:1b` probe at both `2.0.0` and `-Dspring-ai.version=2.0.1`, which is source-level
consistent with this session's independent bytecode check).

**(b) A real, newly-found, version-independent gap: cache-read/cache-write token counts are
silently dropped by this library's own fixture schema — confirmed by reading the source, not
inherited.** `Usage` gained `getCacheReadInputTokens()`/`getCacheWriteInputTokens()`
(`Long`, default `null`) as a **headline 2.0 GA feature** (`docs/SPRING-AI-2.0-FEATURES.md`
§10) — populated by Anthropic and Bedrock Converse today, read-only by OpenAI/Google GenAI.
`VcrTrack.UsageSnapshot` (`track/VcrTrack.java:279`) has exactly three fields:
```java
public record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
```
`VcrTrackMapper#toResponseSnapshot` (`track/VcrTrackMapper.java:257-264`) never calls
`source.getCacheReadInputTokens()`/`getCacheWriteInputTokens()` on record, and
`VcrTrackMapper#toUsage` (`track/VcrTrackMapper.java:347-349`) rebuilds replay usage via
`new DefaultUsage(promptTokens, completionTokens, totalTokens)` — the 3-arg constructor.
`DefaultUsage`'s real 2.0.0 constructor set, re-disassembled this session, includes a 6-arg
overload `(Integer, Integer, Integer, Object nativeUsage, Long cacheReadInputTokens, Long
cacheWriteInputTokens)` that this library never calls. **Consequence: a test asserting on
prompt-cache token savings (a real, commonly-checked Anthropic cost metric) will see correct
numbers on a live/`BYPASS` call and silently `null` on every replay, forever, with no warning
— a genuine, closable gap, not a version-skew question.** See matrix row 6.2 and the priority
list — this is the single most concrete, actionable item this pass found.

### 3. MCP client tool consumption — auto-cached via `ToolCallingManager`? Server out of scope?

**Yes to both, confirmed by re-reading this library's own tool-execution interception code
this session (`VcrToolCallingManager.java`, `VcrCacheKeyGenerator.java`).**
`VcrToolCallingManager#executeToolCalls` is the single choke point every tool invocation
passes through regardless of source (`tool/VcrToolCallingManager.java:29-34`'s own Javadoc,
confirmed against the method body: it reads only `AssistantMessage.ToolCall.name()`/
`.arguments()` off the model's response — never `instanceof`-checks which `ToolCallback`
implementation produced the call, never touches an MCP-specific type). Likewise
`VcrCacheKeyGenerator#canonicalToolDescriptors` (`key/VcrCacheKeyGenerator.java:445-456`)
reads only `ToolCallback.getToolDefinition()` — `name()`/`description()`/`inputSchema()` —
through the plain `org.springframework.ai.tool.ToolCallback` interface. `SyncMcpToolCallback
Provider implements ToolCallbackProvider`, `getToolCallbacks()` returns plain
`ToolCallback[]` (re-confirmed via `javap` against `spring-ai-mcp:2.0.0` this session,
matching the prior draft's finding) — structurally indistinguishable from an `@Tool`-sourced
callback by the time either cache-key generation or tool-execution isolation ever sees it. No
MCP server was actually stood up this session to prove this end-to-end (would need a real MCP
server process, out of scope for a source/bytecode-level pass) — this is an
interface-level guarantee, the same class of proof `OpenAiViaOllamaEndToEndTests` already
generalized for a different implementation-independence question.

**MCP server (exposing this application's own tools to an external MCP client) is
genuinely out of scope by architecture, not an oversight.** An MCP server responds to
requests from an external MCP client — which might not be a Spring AI `ChatClient` at all —
never reaching this library's `ChatClient`-advisor-layer interception point. Testing an MCP
server's tool-handler logic is an ordinary `@WebMvcTest`/contract-testing concern.

### 4. `spring-ai-session` — is the LLM summarization call Recorder-cacheable? **Yes — and the prior draft was wrong that this couldn't be checked.**

**Correction, confirmed this session:** the prior draft of this file claimed
`spring-ai-session`/`spring-ai-agent-utils` don't exist on Maven Central and are therefore
unverifiable. **Both exist and are published**, just under a groupId the prior check missed
— `org.springaicommunity`, not `org.springframework.ai`. Confirmed by listing the real Maven
Central directory (`repo1.maven.org/maven2/org/springaicommunity/`) this session: it contains
`spring-ai-session`, `spring-ai-session-core` (`0.1.0`), `spring-ai-session-jdbc`,
`spring-ai-session-management`, `spring-ai-autoconfigure-session(-jdbc)`,
`spring-ai-starter-session-jdbc`, and separately `spring-ai-agent-utils` (`0.10.0`, twelve
published releases from `0.1.1` through `0.10.0`), `spring-ai-agent-utils-a2a`,
`spring-ai-agent-utils-common`, `spring-ai-agent-utils-bom`.

**The compaction/summarization mechanism is confirmed, at the bytecode-instruction level, to
be an ordinary `ChatClient` call — therefore already Recorder-cacheable with zero new code,
exactly like the E1 `RelevancyEvaluator`/`FactCheckingEvaluator` finding in `docs/STATUS.md`.**
`spring-ai-session-core-0.1.0.jar` was downloaded and disassembled this session.
`org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy` (yes —
the Java package is `org.springframework.ai.session.*` even though the Maven groupId is
`org.springaicommunity`, worth knowing if searching by package) takes a `ChatClient` in its
constructor/builder (`builder(ChatClient)`), and `javap -c` on its `compact(CompactionRequest)`
method shows, in bytecode-instruction order:
```
ChatClient.prompt()  ->  ChatClientRequestSpec.system(String)  ->  .user(String)
  ->  .call()  ->  CallResponseSpec.content()
```
— i.e. `chatClient.prompt().system(...).user(...).call().content()`, structurally identical
to the `ChatClient` call shape this library already intercepts for every other case checked
in this analysis. **If the `ChatClient` passed to `RecursiveSummarizationCompactionStrategy
.builder(...)` is the same Recorder-backed one `SpringAiVcrAutoConfiguration`'s
`ChatClientBuilderCustomizer` already attaches `DeterministicVcrAdvisor` to, the
summarization call records and replays with zero new library code** — same reasoning, now
confirmed one level more rigorously (real bytecode disassembly of the actual method, not just
a constructor-signature inference) than the E1 finding it mirrors. Not run against a real
model this session (would need a real `spring-ai-session` integration test, out of scope for
this pass) — see matrix row 8.1 and the priority list for the cheap follow-up.

`spring-ai-agent-utils` (file/shell/web-fetch/task primitives, Agent Skills) was not
decompiled this session beyond confirming it's real and versioned — its README-described
shape (tool primitives) strongly suggests ordinary `@Tool`/`ToolCallback` implementations,
which by §3's argument above would already be Covered, but this specific claim is *inferred
from the module's stated purpose*, not independently disassembled the way `spring-ai-session`
was. Flagged as such in the matrix (row 8.2) rather than overclaimed.

### 5. Recursive advisors, `ToolSearchToolCallingAdvisor`, `StructuredOutputValidationAdvisor` — tied to R5

Fully covered by `docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md`, read in full this session, and
summarized in matrix rows 3.2/4/5. Headline results, not re-verified again this pass since R5
already did the real-model work:
- **`StructuredOutputValidationAdvisor`**: no bug, confirmed both at default order (whole
  validation loop → one fixture) and — the harder, more interesting claim — with `order`
  overridden to sit *inside* the loop, each retry attempt gets its own fixture, **confirmed
  against a real forced-failure `llama3.2:1b` loop** (`OllamaStructuredOutputValidationEndToEndTests`,
  two variants). This is the strongest empirical result in the whole prior analysis: an
  *ordering argument* was not trusted on its own and was reproduced against a real model
  before being written down as fact.
- **`ToolSearchToolCallingAdvisor`**: architecture-level case closed (`extends
  ToolCallingAdvisor`, inherits the byte-identical `DEFAULT_ORDER`, confirmed via `javap`) —
  but the real-model demo genuinely failed: `llama3.2:1b` never completed the two-hop
  search-then-call protocol in a real e2e attempt. This is a model-capability gap, not a
  library gap — see matrix row 4 and the priority list.
- **Recursive advisors as a general pattern** (`CallAdvisorChain.copy(CallAdvisor)`,
  independently re-confirmed present on the interface in this session's `FEATURES.md` pass):
  this library's `VcrScope`/explicit-`order` mechanism generalizes to *any* recursive advisor
  a user places relative to it, not just the two Spring AI ships — proven for the one concrete
  case that exists beyond `ToolCallingAdvisor` (`StructuredOutputValidationAdvisor`), not
  proven in the abstract for a hypothetical third one, which is an honest scope limit, not a
  gap.

### 6. Jackson 3 / JSpecify / provider SDK consolidation — serialization & provider-agnosticism impact

**None negative; several positive/confirming.** `VcrTrackStore`/`VcrTrackMapper` already
depend on `tools.jackson.databind.json.JsonMapper` (Jackson 3), never
`com.fasterxml.jackson.databind.ObjectMapper` — consistent with `CLAUDE.md`'s "do not" list,
predating this GA review rather than reacting to it. This library never uses Spring AI's own
`JsonHelper`/`JacksonUtils` (confirmed present in `spring-ai-commons:2.0.0` this session, see
`docs/SPRING-AI-2.0-FEATURES.md` §1) — correctly so, since `VcrTrack` is this library's own
hand-rolled DTO, never Spring AI's own JSON shape, so there is no shared mapper to customize.

JSpecify null-safety: `250/250` unit tests already pass against the fully-`@Nullable`
annotated `2.0.0` surface; `VcrCacheKeyGenerator`/`VcrTrackMapper` already null-check every
`ChatOptions`/`ChatResponseMetadata` accessor explicitly (ternaries, not `Optional`, matching
`CLAUDE.md`'s own stated preference) — this discipline predates 2.0's formal JSpecify
baseline rather than being a reaction to it.

Provider SDK consolidation (OpenAI → `openai-java`, Anthropic → `anthropic-java`, Google
GenAI → GenAI SDK): re-confirmed this session that both `AnthropicChatOptions` and
`OpenAiChatOptions` implement the identical `ToolCallingChatOptions, StructuredOutputChatOptions`
interface pair (`javap` against real `2.0.0` jars for both), the same contract Ollama already
satisfies — so `VcrCacheKeyGenerator`'s `instanceof ToolCallingChatOptions` check
(`key/VcrCacheKeyGenerator.java:447`) treats every provider identically by construction. The
architectural guarantee ("which Java class or transport reaches a model cannot change what
that model computes, so it must not bust the cache") is provider-SDK-choice-agnostic; a real
account for a non-Ollama-routed provider remains the one honest, already-documented scope
limit (`docs/STATUS.md`'s own "provider independent... proven at the implementation level"
paragraph already states this precisely — not re-litigated here).

---

## Matrix

| # | Feature (2.0 GA) | Status | Evidence | Kind | Note / action |
|---|---|---|---|---|---|
| 1.1 | Spring Boot 4 / Framework 7 baseline | **Covered** | `pom.xml` pins `spring-ai.version=2.0.0` on the Boot 4.0.0 BOM; `mvn test` 250/250 on the real dependency graph | already-covered | Baseline this whole project is built against |
| 1.2 | Jackson 3 (`tools.jackson.*`) | **Covered** | `VcrTrackStore`/`VcrTrackMapper` use `tools.jackson.databind.json.JsonMapper`, never `com.fasterxml.jackson.databind.ObjectMapper`; own hand-rolled `VcrTrack` DTO, no need for `JsonHelper`/`JacksonUtils` | already-covered | §6 above |
| 1.3 | JSpecify null-safety | **Covered** | Explicit ternary null-checks throughout `VcrCacheKeyGenerator`/`VcrTrackMapper`, predating this GA's formal baseline; 250/250 green against the fully-annotated surface | already-covered | §6 above |
| 1.4 | Options refactoring (builders, immutable, `combineWith` reflection-free merge) | **Covered / N-A** | `ChatOptions`'s 8-getter + `mutate()`/`builder()` shape re-disassembled this session, byte-identical to `CLAUDE.md`'s table; `Builder.combineWith`/`.clone()` confirmed present but never called by this library, which only reads getters post-merge | already-covered | §1 above — architecturally invisible to the cache key by construction |
| 1.5 | `.options` config-property segment removal | **N/A** | Provider auto-config concern; `VcrProperties`'s own `spring.ai.test.vcr.*` namespace never used the segment | already-covered | Nothing to change |
| 2 | Provider SDK consolidation (OpenAI/Anthropic/Google GenAI → one SDK-based impl each) | **Covered** | `AnthropicChatOptions`/`OpenAiChatOptions` both re-confirmed `implements ToolCallingChatOptions, StructuredOutputChatOptions` this session; `OpenAiViaOllamaEndToEndTests` proves a fixture recorded via native Ollama replays byte-for-byte through the OpenAI-SDK client | already-covered | §6 above; a real non-Ollama-routed provider account remains the one honest, pre-existing scope limit |
| 3.1 | Tool loop as first-class advisor (`ToolCallingAdvisor`, `HIGHEST_PRECEDENCE + 300`) | **Covered** | `DEFAULT_ORDER` constant re-disassembled this session (`-2147483348`); `VcrScope` built entirely around it; `OllamaToolCallingEndToEndTests`, `OllamaToolIsolationEndToEndTests` prove it live | already-covered | This library's oldest, most-tested surface |
| 3.2 | Recursive advisors as a general pattern | **Covered for both shipped instances** | `CallAdvisorChain.copy(CallAdvisor)` re-confirmed present via `javap`; both `ToolCallingAdvisor` (3.1) and `StructuredOutputValidationAdvisor` (5) verified | already-covered | §5 above — "general" isn't proven in the abstract, only for what Spring AI actually ships |
| 4 | `ToolSearchToolCallingAdvisor` (progressive tool disclosure) | **Partial** | Architecture confirmed (`extends ToolCallingAdvisor`, identical `DEFAULT_ORDER`); real `llama3.2:1b` e2e demo failed to complete the two-hop protocol, per R5 §7 | scenario-opportunity | Needs a stronger model, not library code |
| 5 | `StructuredOutputValidationAdvisor` (self-correcting structured output) | **Covered** | Both default-order (one fixture) and per-attempt-order (one fixture per retry) confirmed against a real forced-failure loop, R5 §2/§6 | already-covered | Most thoroughly proven finding in this whole analysis |
| 6.1 | Cumulative usage across recursive-advisor loop iterations | **N/A at pinned 2.0.0 / Covered at 2.0.1+** | Independently re-confirmed this session: zero "usage" references anywhere in `ToolCallingAdvisor.class`/`StructuredOutputValidationAdvisor.class`'s constant pools at `2.0.0`. 2.0.1 behavior (real `UsageAccumulator`, verified via a real-model probe) inherited from the prior draft, not re-run this session | already-covered (2.0.1+) / N-A (2.0.0) | §2(a) above — nothing to build; there's no cumulative concept at the pinned version to get wrong |
| 6.2 | Cache-read/cache-write token accounting (`Usage.getCacheReadInputTokens/getCacheWriteInputTokens`) | **Covered (closed, see update above)** | `VcrTrack.UsageSnapshot` now carries both fields (schema `"6"`); `VcrTrackMapper#toResponseSnapshot` reads them, `#toUsage` calls `DefaultUsage`'s 6-arg ctor; `VcrStreamTrackMapper`'s duplicate call sites fixed too; round-trip + backward-compat tests in `VcrTrackStoreRoundTripTests`; `mvn clean test` 253/253. See `docs/R6-CACHE-TOKEN-USAGE-PRD.md` | already-covered | Was §2(b)'s new finding this session; closed the same session |
| 7.1 | MCP client tool consumption | **Covered** | `SyncMcpToolCallbackProvider implements ToolCallbackProvider` re-confirmed; `VcrToolCallingManager`/`VcrCacheKeyGenerator` re-read this session, confirmed to key off `AssistantMessage.ToolCall`/`ToolCallback.getToolDefinition()` alone, no MCP-specific branch anywhere | already-covered | §3 above |
| 7.2 | MCP server (exposing this app's own tools) | **N/A** | Responds to an external MCP client, never reaching `ChatClient`'s advisor chain | N/A by design | §3 above |
| 7.3 | MCP client sampling (`@McpSampling`) | **Gap** | Confirmed by an authoritative source (Spring AI team recipe, inherited from prior draft, not re-checked this session): sampling handlers use a raw `ChatModel`, deliberately not `ChatClient`, to avoid an infinite loop — invisible to this library's `ChatClient`-advisor-layer interception | real-gap | Would need a `VcrChatModel`-style direct wrapper (R4's `VcrEmbeddingModel` precedent) |
| 7.4 | MCP client elicitation (`@McpElicitation`) | **N/A / unresearched** | Not independently checked this session either | unverified | Flagged, not claimed either way |
| 7.5 | MCP transport (Streamable HTTP, WebMVC/WebFlux relocation, OAuth2/API-key security) | **N/A** | Pure transport/protocol layer, below `ToolCallback` resolution; invisible to this library regardless of transport in use | N/A by design | Same "transport can't bust the cache" argument as provider-SDK independence |
| 8.1 | `spring-ai-session` (event-sourced memory, LLM-summarization compaction) | **Covered (mechanism), not live-tested** | **Corrected this session** — the module is real and published (`org.springaicommunity:spring-ai-session-core:0.1.0`); `RecursiveSummarizationCompactionStrategy.compact()` disassembled at the bytecode-instruction level this session, confirmed to call `chatClient.prompt().system(...).user(...).call().content()` — an ordinary, already-interceptable `ChatClient` call | already-covered (mechanism) / scenario-opportunity (a real e2e demo) | §4 above — the prior draft's "doesn't exist, unverifiable" claim was wrong; see corrections section |
| 8.2 | `spring-ai-agent-utils` (file/shell/web-fetch/task primitives, Agent Skills) | **Partial — exists, not decompiled** | **Corrected this session** — `org.springaicommunity:spring-ai-agent-utils:0.10.0` is real, 12 published releases; not independently disassembled this pass, so "ordinary `@Tool`/`ToolCallback`" is inferred from its stated purpose, not proven the way `spring-ai-session` was | scenario-opportunity | §4 above — one step short of the confidence level 8.1 now has |
| 9.1 | Chat memory (`MessageChatMemoryAdvisor` default order vs. this library's) | **Covered by default order, still not live-tested** | `MessageChatMemoryAdvisor.Builder`'s `-2147483448` (`HIGHEST_PRECEDENCE + 200`) re-confirmed present via `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` in this session's `FEATURES.md` pass — numerically below both this library's own default orders (`+250`/`+400`) | already-covered (default) / scenario-opportunity (e2e proof) | Same math the prior draft already worked out; still no real multi-turn memory-advisor e2e test exists |
| 9.2 | Official evaluation/testing guidance (`RelevancyEvaluator`, `FactCheckingEvaluator`, `llm-as-judge` guide) | **N/A — validates, doesn't compete** | `docs/STATUS.md`'s E1/E2 findings: both evaluators are ordinary `ChatClient` calls, already Recorder-backed with zero new code, live-tested against a real model | N/A — confirms this library's value proposition | Not re-verified this session, already exhaustively proven in `docs/STATUS.md` |
| 9.3 | Official `spring-ai-test` module | **N/A** | Contributor-facing conformance-test base classes (`AbstractToolCallingAdvisorIT` etc.), different audience/coordinate than this project | N/A | No collision risk |

---

## §ext. Detail: chat memory ordering (unchanged from the prior draft, independently re-derivable)

```
MessageChatMemoryAdvisor         HIGHEST_PRECEDENCE + 200
DeterministicVcrAdvisor (OUTSIDE_TOOL_LOOP, default)   HIGHEST_PRECEDENCE + 250
ToolCallingAdvisor                HIGHEST_PRECEDENCE + 300
DeterministicVcrAdvisor (INSIDE_TOOL_LOOP)             HIGHEST_PRECEDENCE + 400
```
Memory (`+200`) runs upstream of this library's advisor in *both* scope settings by default —
`MessageChatMemoryAdvisor.before()` (a `BaseAdvisor` hook, always runs) injects growing
conversation history into `Prompt.getInstructions()` before `DeterministicVcrAdvisor` ever
computes a hash. Since `VcrCacheKeyGenerator` already hashes every message in
`Prompt.getInstructions()` in order (confirmed by re-reading the source this session,
`key/VcrCacheKeyGenerator.java:222-235`), a growing memory-backed conversation naturally
produces a growing, different hash per turn — the same mechanism already proven live for
`INSIDE_TOOL_LOOP`'s multi-turn messages. **The footgun, still not demonstrated live:** both
this library's `order` and `MessageChatMemoryAdvisor.Builder#order(int)` are user-overridable;
setting either such that memory ends up *downstream* of this library's advisor would hash a
shorter prompt than what's actually sent. Recommendation unchanged from the prior draft: one
real multi-turn `MessageChatMemoryAdvisor` + `DeterministicVcrAdvisor` e2e test, mirroring
`OllamaStructuredOutputValidationEndToEndTests`'s pattern, before documenting this as
officially supported.

---

## Overall verdict

Spring AI 2.0 GA does not break this library's core guarantee anywhere checked, re-verified
independently in this session everywhere the earlier draft's method allowed re-checking
(`ChatOptions`'s shape, `ToolCallingAdvisor`/`StructuredOutputValidationAdvisor`'s complete
absence of usage bytecode at `2.0.0`, `AnthropicChatOptions`/`OpenAiChatOptions`'s interface
contract, `spring-ai-session`'s real existence and its summarization call's exact bytecode
shape). One genuine, newly-found, actionable gap surfaced this session that the prior draft
missed entirely: **cache-read/cache-write token accounting is silently dropped by this
library's own fixture schema**, independent of any Spring AI version question — a real
regression in *fidelity*, not merely an unverified claim. Two other real gaps carry over
unchanged from the prior draft (MCP client sampling; `ToolSearchToolCallingAdvisor`'s
practical demo blocked on model capability), and two "N/A, unverifiable" rows from the prior
draft are now corrected to "Covered"/"Partial" once the real Maven coordinate was found.

**Tally (updated): 13 Covered · 3 Partial · 2 Gap · 5 N/A.** At the time this analysis was
first written the tally was 12 Covered · 3 Partial · 3 Gap · 5 N/A; row 6.2 has since moved
from Gap to Covered (see the update note at the top of this document and
`docs/R6-CACHE-TOKEN-USAGE-PRD.md`). 23 rows total — one more row than the prior draft's 21,
from splitting cumulative usage (6) into 6.1/6.2 once a second, independent finding existed
under that heading; row 6.1 is counted under Covered, reflecting its real state once the pin
eventually moves to 2.0.1, same convention the prior draft used; row 3.2's "Covered for both
shipped instances" is likewise counted under Covered.

## Priority list, most to least worth acting on

1. ~~**Cache-read/cache-write token fields (row 6.2)**~~ **Done — see
   `docs/R6-CACHE-TOKEN-USAGE-PRD.md`.** `cacheReadInputTokens`/`cacheWriteInputTokens`
   (`Long`) added to `VcrTrack.UsageSnapshot`; `VcrTrackMapper#toResponseSnapshot` reads them
   via the `Usage` getters, `#toUsage` writes them back via `DefaultUsage`'s 6-arg
   constructor (`nativeUsage = null`, consistent with that field already being dropped on
   purpose); `VcrTrack.CURRENT_SCHEMA_VERSION` bumped to `"6"`, additive exactly like every
   prior bump (a `"5"` fixture still deserializes, both new fields come back `null`). Three
   new tests in `VcrTrackStoreRoundTripTests`; `mvn clean test` 253/253, working tree clean
   afterward. Was the highest-value item in this list — the only real, user-visible fidelity
   gap found, with a clear, small, precedented fix — so closed first.
2. **Chat memory + VCR e2e test** (§ext. above) — the one remaining "trust the ordering math"
   claim in this whole pass, same category R5 already taught this project not to ship
   undemonstrated once. Cheap: one new e2e test, same pattern as every other
   `Ollama*EndToEndTests` class.
3. **`spring-ai-session` real e2e demo** (row 8.1) — the mechanism is now confirmed correct
   at the bytecode-instruction level (stronger evidence than most "Covered" rows in this
   analysis even get), but no real Testcontainers-backed proof exists yet that wiring a
   Recorder-backed `ChatClient` into `RecursiveSummarizationCompactionStrategy.builder(...)`
   actually records/replays a real summarization call. Would make an excellent, genuinely
   novel showcase in the example project — this is a community extension outside Spring AI
   core, and would be one of the first public demonstrations of Recorder composing with it.
4. **MCP client sampling gap** (row 7.3) — confirmed, real, with a clear shape for a fix
   (`VcrChatModel`, mirroring R4's `VcrEmbeddingModel`). Worth a PRD if a real user asks, not
   built speculatively.
5. **`ToolSearchToolCallingAdvisor` real-model demo** (row 4) — blocked on model capability,
   architecture case already closed; revisit with a larger local model or hosted provider.
6. **`spring-ai-agent-utils` decompilation** (row 8.2) — now that the real coordinate is
   known, a follow-up `javap` pass would move this from "Partial (inferred)" to "Covered
   (confirmed)" the same way 8.1 just moved — cheap, low-priority polish.
7. **MCP elicitation** (row 7.4) — unresearched, not urgent, flagged so it isn't silently
   assumed either way.

---

## Corrections to the prior draft of this file

Documented explicitly, matching the discipline `docs/SPRING-AI-2.0-FEATURES.md` already
applies to its own two source-doc corrections:

1. **`spring-ai-session`/`spring-ai-agent-utils` do exist on Maven Central.** The prior draft
   (rows 8.1/8.2) checked `repo1.maven.org/maven2/org/springframework/ai/spring-ai-session/`
   and `.../spring-ai-agent-utils/` — both 404, correctly reported as such — and concluded
   neither module is published anywhere. The real coordinate is a different groupId entirely,
   `org.springaicommunity` (confirmed by listing that directory directly this session, ~70
   sibling artifacts including `spring-ai-session-core:0.1.0` and
   `spring-ai-agent-utils:0.10.0` with 12 published releases). The 404 the prior draft hit was
   real and correctly reported for the path it checked — the miss was not trying the actual
   Spring AI community groupId next. Corrected in §4 above and matrix rows 8.1/8.2, with 8.1
   additionally upgraded from "unverifiable" all the way to "Covered (mechanism)" once the
   real jar was decompiled and its summarization call's bytecode read instruction-by-instruction.
2. **No second correction found this session beyond the above** — every other claim in the
   prior draft that this session had the tooling to re-check (order constants, `ChatOptions`'s
   shape, the `ToolCallingAdvisor`/`StructuredOutputValidationAdvisor` usage-bytecode absence,
   the provider `ChatOptions` interface contract) held up exactly as stated. The prior draft's
   own internal correction of the reference-doc `UsageAccumulator` claim (its §6) is *not* a
   second correction to *this* file — it was already correct there and is re-confirmed, not
   revised, in §2(a) above.
