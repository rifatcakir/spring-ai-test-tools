# Spring AI 2.0 GA compatibility / gap analysis

Status: **Analysis, not a build plan.** Produced against the published `2.0.0` GA jars
(this project's pinned version — see `CLAUDE.md`) plus `2.0.1` where noted, by unpacking
jars and reading bytecode/source (`javap`, `-sources.jar` decompilation) rather than
trusting prose. Two places where an automated doc-summary tool's claim was checked against
real bytecode and turned out to be **wrong or version-skewed** are called out explicitly
(§6, §7) — exactly the discipline `CLAUDE.md`'s own verified-API table exists to enforce,
applied here to the *feature-inventory* question instead of an *individual API signature*
question.

**Method:** for every claim below marked "confirmed via javap/bytecode" or "confirmed via
source", the actual jar was downloaded from Maven Central (`spring-ai-*:2.0.0`, and
`spring-ai-*:2.0.1` for the version-skew checks) and inspected directly — not inferred from
blog prose. Where a live-model e2e test already exists in this repo, it's cited by class
name. One throwaway probe test was written and run against a real `llama3.2:1b` (via
Testcontainers) for §6 specifically, then deleted once its result was captured here — this
document is the deliverable, not a growing test suite.

**Classification legend:**

- **Covered** — the library already handles this correctly, verified (not assumed).
- **Partial** — works in the common case, with a documented edge/limitation.
- **Gap** — a real, confirmed limitation; nothing in this library reaches this scenario.
- **N/A** — out of scope by design, or not independently verifiable right now.

Each row also carries a **kind**: *already-covered* (needs nothing), *scenario-opportunity*
(a demo/doc worth adding, no library code needed), or *real-gap* (would need actual library
code to close, not attempted here per this task's "analysis, not features" scope).

## Matrix

| # | Feature (2.0 GA) | Status | Evidence | Kind | Note / action |
|---|---|---|---|---|---|
| 1.1 | Spring Boot 4 / Framework 7 baseline | **Covered** | `pom.xml` already pins `spring-boot.version=4.0.0`; `mvn test` 250/250 on the real dependency graph | already-covered | This is the baseline this whole project is built against, not something to re-verify per feature |
| 1.2 | Jackson 3 (`tools.jackson.*`) | **Covered** | `VcrTrackStore`/`VcrTrackMapper` already use `tools.jackson.databind.json.JsonMapper`; `pom.xml` depends on `tools.jackson.core:jackson-databind`, not `com.fasterxml.jackson.databind.ObjectMapper` (`CLAUDE.md` "Do not" list) | already-covered | No `JsonHelper`/`JacksonUtils` (Spring AI's own Jackson 3 customization helpers) are used by this library, correctly — this library's fixture format is its own hand-rolled DTO (`VcrTrack`), never Spring AI's own JSON shapes, so there is nothing to customize a shared mapper for |
| 1.3 | JSpecify null-safety | **Covered** | `250/250` unit tests pass against the fully-`@Nullable`-annotated 2.0.0 API surface; `VcrCacheKeyGenerator`/`VcrTrackMapper` already null-check every `ChatOptions`/`ChatResponseMetadata` accessor explicitly (ternaries, `Assert.notNull`) rather than assuming non-null | already-covered | This library was written defensively before 2.0 GA's JSpecify baseline was even confirmed (see `CLAUDE.md`'s own "prefer explicit null handling over `Optional` in hot paths" rule) — 2.0 GA formalized a discipline this code already followed |
| 1.4 | Options refactoring: builders, immutable, reflection-free merging | **Covered / N/A** | `VcrCacheKeyGenerator` reads options exclusively via the public `ChatOptions` interface getters (`getModel()`/`getTemperature()`/.../`getStopSequences()`), confirmed via `javap` to still be exactly the 8-method interface `CLAUDE.md`'s table lists, byte-for-byte unchanged at 2.0.0 GA — see §1 detail below | already-covered | How Spring AI *merges* options internally (reflection vs. builder-based) is invisible to this library — it only ever reads the *final* merged `Prompt.getOptions()`, never merges anything itself. Not a surface this library touches at all |
| 1.5 | Removal of the `.options` config-property segment | **N/A** | Applies to *provider* auto-configuration properties (e.g. `spring.ai.ollama.chat.options.temperature` → `spring.ai.ollama.chat.temperature`); `grep`-confirmed this library's own `spring.ai.test.vcr.*` namespace (`VcrProperties`) never used an `.options.` segment | already-covered | Nothing to change |
| 2 | Provider consolidation (OpenAI/Anthropic/Google GenAI down to one SDK-based impl each; Bedrock, Mistral, DeepSeek, Ollama, OCI, Azure Cosmos DB) | **Covered** | `AnthropicChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions` confirmed via `javap` against the real `spring-ai-anthropic:2.0.0` jar — same interface contract as Ollama/OpenAI, never read via `instanceof`. `OpenAiViaOllamaEndToEndTests` already proves, against a real second implementation, that a fixture recorded through native Ollama replays byte-for-byte through the OpenAI-SDK client | already-covered | The architectural guarantee ("which Java class or transport reaches a model cannot change what that model computes, so it must not bust the cache") is provider-count-agnostic by construction. Anthropic/Bedrock/Google GenAI are structurally confirmed, not end-to-end tested with a real account — same honest scope limit `docs/STATUS.md` already states for `OpenAiViaOllamaEndToEndTests` |
| 3.1 | Tool loop as a first-class advisor (`ToolCallingAdvisor`, auto-registered, `HIGHEST_PRECEDENCE + 300`) | **Covered** | `CLAUDE.md`'s verified-API table already pins this; `VcrScope` (`OUTSIDE_TOOL_LOOP`/`INSIDE_TOOL_LOOP`) is built entirely around this exact order value; `OllamaToolCallingEndToEndTests`, `OllamaToolIsolationEndToEndTests` prove it against a real model | already-covered | This is this library's oldest, most-tested surface |
| 3.2 | Recursive advisors as a *general* pattern (`chain.copy(this).nextCall(...)`) | **Partial → Covered for the two concrete instances that exist** | `StructuredOutputValidationAdvisor` fully verified both directions (default order = wraps as one fixture; order override = per-attempt fixtures) — `OllamaStructuredOutputValidationEndToEndTests`, `docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md`. `ToolCallingAdvisor` already covered (3.1) | already-covered | "General pattern" support isn't something to build in the abstract — every *concrete* recursive advisor Spring AI 2.0 actually ships (`ToolCallingAdvisor`, `StructuredOutputValidationAdvisor`) has been verified. A *third-party* custom recursive advisor a user writes themselves would need the same `order`-placement reasoning R5 documents, but that reasoning is now written down and empirically confirmed once, not per-advisor |
| 4 | `ToolSearchToolCallingAdvisor` (progressive/dynamic tool disclosure) | **Partial** | Architecture confirmed compatible via `javap` (`extends ToolCallingAdvisor`, inherits the identical `DEFAULT_ORDER`); a real two-attempt `llama3.2:1b` e2e test never got the model to complete the two-hop search-then-call protocol. Full writeup: `docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md` §7 | scenario-opportunity (needs a stronger model to demo, not library code) | No code gap — a documentation/demo gap blocked on model capability, not this library |
| 5 | `StructuredOutputValidationAdvisor` (self-correcting structured output) | **Covered** | Fully verified both directions against a real forced multi-attempt loop — `OllamaStructuredOutputValidationEndToEndTests`, `docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md` §2/§6. Also demoed in the example project (`SelfCorrectingStructuredOutputRecordReplayTest`) | already-covered | The most thoroughly proven finding in this whole analysis |
| 6 | Cumulative usage across multi-step flows (`UsageAccumulator`/`UsageCalculator`) | **Covered — but this is a `2.0.1`+ feature, absent at pinned `2.0.0`** | See §2 detail below — real version-skew finding, verified both ways via bytecode and a real-model probe | already-covered (2.0.1+) / N/A (2.0.0) | See detail — worth a `CLAUDE.md` table addendum if/when the pin moves to `2.0.1` |
| 7.1 | MCP client tool consumption (`@McpTool`-exposed server tools reaching a Spring AI `ChatClient`) | **Covered** | `SyncMcpToolCallbackProvider implements ToolCallbackProvider`, `getToolCallbacks()` returns plain `org.springframework.ai.tool.ToolCallback[]` — confirmed via `javap` against the real `spring-ai-mcp:2.0.0` jar. Identical interface this library's `VcrCacheKeyGenerator`/`VcrToolCallingManagerBeanPostProcessor` already read/wrap for any other tool source | already-covered | See §3 detail — MCP-sourced tools are structurally indistinguishable from `@Tool`-sourced ones once resolved to `ToolCallback` |
| 7.2 | MCP server (`@McpTool`/`@McpResource`/`@McpPrompt` exposing *your* app as an MCP server) | **N/A** | Architectural: an MCP server responds to an external MCP *client* (which might not be a Spring AI `ChatClient` at all — Claude Desktop, another framework), not an LLM chat completion this library's advisor-layer interception can see | N/A by design | Testing an MCP server's own tool-handler logic is an ordinary Spring `@WebMvcTest`/contract-testing concern, not an LLM-response-caching one — genuinely a different kind of test, not a gap in this library |
| 7.3 | MCP client sampling (`@McpSampling` — server asks the client's LLM to complete a prompt) | **Gap** | Confirmed by an authoritative source (Spring AI team member recipe), not assumed: sampling handlers are implemented against a raw `ChatModel`, deliberately *not* a `ChatClient` — "if you attempted to use a `ChatClient` configured with MCP tools, you could easily end up in an infinite loop." This library only intercepts at the `ChatClient` advisor layer for chat (unlike embeddings, chat has no `ChatModel`-wrapping `BeanPostProcessor`) | real-gap | Genuine, confirmed gap: a sampling handler built the recommended way is invisible to this library entirely. Closing it would need a `VcrChatModel`-style direct `ChatModel` wrapper (R4's `VcrEmbeddingModel` precedent) — not attempted here, flagged for a future PRD if a real user hits it |
| 7.4 | MCP client elicitation (`@McpElicitation`) | **N/A / unresearched** | Not independently verified this session — same category as sampling (client-side callback, likely similarly `ChatClient`-adjacent or `ChatClient`-bypassing) but not confirmed either way | unverified | Flagged, not claimed either way — do not treat as Covered or Gap without checking |
| 7.5 | MCP transport (Streamable HTTP default, WebMVC/WebFlux moved into Spring AI, OAuth2/API-key security) | **N/A** | Pure transport/protocol-layer concern between the MCP client and server, entirely below where `ToolCallback` resolution happens; invisible to this library regardless of which transport is in use, by the same "transport cannot bust the cache" argument already proven for `OpenAiViaOllamaEndToEndTests` | N/A by design | No action — this is exactly the class of thing 7.1's interface-level guarantee already covers |
| 8.1 | `spring-ai-session` (event-sourced memory, LLM-summarization compaction) | **N/A — not independently verifiable** | Confirmed via Maven Central search: no `org.springframework.ai:spring-ai-session` artifact exists at any version (404 on the group directory, zero hits on Maven Central search) | N/A for now | Cannot unpack a jar that doesn't exist yet — this is a community/incubating project per the GA blog, not yet a real dependency surface. If it *were* published, the summarization call would be an ordinary `ChatClient` call and therefore already Recorder-cacheable by this library's existing mechanism, same reasoning as E1's `RelevancyEvaluator` finding — worth re-checking once it actually publishes |
| 8.2 | `spring-ai-agent-utils` (file/shell/web-fetch/task tool primitives, Agent Skills) | **N/A — not independently verifiable** | Same check, same result: no `spring-ai-agent-utils` artifact on Maven Central | N/A for now | If/when published: these would almost certainly be ordinary `@Tool`/`ToolCallback` implementations, i.e. already Covered by the exact same interface-level argument as 7.1 — nothing suggests a special case, but unverifiable against a real jar today |
| 9.1 | Chat memory (`MessageChatMemoryAdvisor`, growing conversation history injected into every prompt) | **Covered by default order — documented footgun if reordered** | `MessageChatMemoryAdvisor.Builder`'s default order confirmed via `javap`: `-2147483448` = `HIGHEST_PRECEDENCE + 200` — numerically *below* both of this library's own default orders (`+250` `OUTSIDE_TOOL_LOOP`, `+400` `INSIDE_TOOL_LOOP`). See §4 detail | already-covered (default) / scenario-opportunity (docs) | Not yet empirically re-verified with a real multi-turn memory-advisor e2e test the way R5's validation-advisor ordering claim was — an honest gap in *proof depth*, not in the underlying architecture, which is the same class of "ordering math predicts X" claim R5 already taught this project not to ship undemonstrated. Worth one e2e test before writing this up as officially "supported" in the README |
| 9.2 | Official Spring AI evaluation/testing guidance (`docs/api/testing.html`, `guides/llm-as-judge.html`) | **N/A — confirms this library's own value proposition, doesn't compete with it** | Fetched directly: the official testing reference recommends calling a real model through `RelevancyEvaluator`/`FactCheckingEvaluator` with no record/replay, no mocking, no deterministic-testing story of its own; "LLM-as-judge" is a *blog pattern* (hand-build a custom recursive advisor), not a shipped advisor class | N/A — validates, not overlaps | This is reassuring, not a gap: Spring AI 2.0 does not attempt to solve the determinism problem this library exists for. `docs/A4-JUDGE-ASSERTION-PRD.md`'s deferred fluent-judge-assertion design remains the right shape if/when built — no new built-in class makes it obsolete or redundant |
| 9.3 | Official `spring-ai-test` module | **N/A — different audience, not a competing surface** | Unpacked the real jar: `AbstractToolCallingAdvisorIT`, `AbstractChatOptionsTests`, `MockWeatherService`, `BaseVectorStoreTests` — abstract JUnit base classes for Spring AI *contributors* writing provider-conformance tests, not an application-developer-facing testing library | N/A | No naming collision risk in practice (different Maven coordinate, different audience) but worth knowing this exists so nobody confuses it with this project's own `spring-ai-test-tools` in search results |

## 1. Options refactoring — detail

`ChatOptions` (the interface `VcrCacheKeyGenerator` reads) was re-verified via `javap`
against the real `2.0.0` jar and is unchanged from `CLAUDE.md`'s pinned table:

```
getModel(), getFrequencyPenalty(), getMaxTokens(), getPresencePenalty(),
getStopSequences(), getTemperature(), getTopK(), getTopP(), mutate(), builder()
```

Eight getters, the same eight `VcrCacheKeyGenerator#canonicalize` reads, in the same
order the method already appends them. `ToolCallingChatOptions` (which `ChatOptions`
callers reach for tool-related state) is likewise unchanged: `getToolCallbacks()`,
`getToolContext()`, `mutate()` — `getToolNames()`'s removal (already in `CLAUDE.md`'s
table) is the only historical change, and it long predates this GA review.

The GA blog's "builders, immutable, reflection-free merging" claims describe *how* a
concrete `ChatOptions` implementation (`OllamaChatOptions`, `OpenAiChatOptions`, ...)
gets constructed and merged internally — a concern entirely upstream of where this
library ever looks. `VcrCacheKeyGenerator` never merges, builds, or reflects over an
options object; it only calls the public interface getters on whatever the framework
handed it. Reflection-free merging closing off a class of bugs in Spring AI's own code
is good news for this library's users but changes nothing about what this library reads.

## 2. Cumulative usage — detail (real version-skew finding)

This is the one place an automated fetch of `docs/api/advisors-recursive.html`
produced a claim that turned out to be **wrong for the pinned version** — worth recording
in full because it's exactly the failure mode `CLAUDE.md`'s verified-API table exists to
catch, just applied to a different kind of claim (a feature's *existence*, not a method's
*signature*).

**What the fetched doc said:** "`ToolCallingAdvisor` accumulates token usage across every
iteration of the loop, so the final `ChatResponse` reports the cumulative usage of all
model calls" and the same for `StructuredOutputValidationAdvisor`, citing
`org.springframework.ai.support.UsageCalculator` and a class called `UsageAccumulator`.

**What `javap` against the real `spring-ai-client-chat:2.0.0` jar shows:** zero
usage-related bytecode in `ToolCallingAdvisor.class` — no reference to `Usage`,
`UsageCalculator`, or any accumulator type anywhere in the class. Confirmed a second way,
independent of bytecode: the full `-sources.jar` for both `ToolCallingAdvisor.java` and
`StructuredOutputValidationAdvisor.java` (already read in full for R5) contain the string
"usage" **zero times**, in either file. `org.springframework.ai.chat.client.advisor.
UsageAccumulator` **does not exist at all** in `spring-ai-client-chat:2.0.0` — confirmed by
listing every class in the jar. `UsageCalculator` does exist at `2.0.0`, but with only two
methods (`getCumulativeUsage`, `isEmpty`), and is referenced from exactly one place in the
entire dependency graph checked (`spring-ai-client-chat`, `spring-ai-model`,
`spring-ai-ollama`, `spring-ai-openai`, `spring-ai-tool-search-advisor`,
`spring-ai-tool-search-tool`): `OpenAiChatModel`, for its own internal streaming-chunk
accumulation — never by `ToolCallingAdvisor` or any advisor-chain code.

**What `2.0.1` actually adds:** `spring-ai-client-chat:2.0.1`'s `ToolCallingAdvisor.class`
*does* construct a `new UsageAccumulator()`, call `.addRoundResponse(ChatResponse)` after
every loop iteration (both `.call()` and `.stream()` paths), and call
`.applyAccumulatedUsage(ChatClientResponse)` before returning the final response.
`UsageCalculator` at `2.0.1` gains two new methods, `accumulateResponseUsage` and
`withUsage`, that `2.0.0`'s version doesn't have. **Cumulative usage accumulation is a real
`2.0.1` feature, not present in the `2.0.0` GA jar this project is pinned to** — the
reference docs the fetch tool summarized track "current" documentation, which resolves
ahead of the exact GA tag.

**Consequence for this library, verified empirically, not just architecturally:** a
throwaway probe (`UsageAccumulationProbeTest`, run once against `-Dspring-ai.version=2.0.1`
with a real two-turn `INSIDE_TOOL_LOOP` tool call via Testcontainers `llama3.2:1b`, then
deleted) confirmed the composition holds perfectly:

- Each fixture stores its own **per-turn, non-cumulative** usage, exactly as
  `VcrTrackMapper` always has (never computes or merges usage itself — pure pass-through
  of whatever `ChatResponseMetadata.getUsage()` reports for that one response): turn 1
  `{promptTokens: 128, completionTokens: 25, totalTokens: 153}`, turn 2
  `{promptTokens: 162, completionTokens: 59, totalTokens: 221}`.
- The **live** call's final, `UsageAccumulator`-stamped cumulative usage:
  `{promptTokens: 290, completionTokens: 84, totalTokens: 374}` — exactly `128+162`,
  `25+59`, `153+221`. Arithmetic checks out.
- The **replayed** call's final cumulative usage: **identical**,
  `{promptTokens: 290, completionTokens: 84, totalTokens: 374}` — because
  `UsageAccumulator` sums whatever `ChatResponse` objects flow through `chain.nextCall()`
  regardless of whether they came from a live HTTP call or a VCR replay; from
  `ToolCallingAdvisor`'s perspective the two are indistinguishable, which is this whole
  library's foundational guarantee applied to a feature that didn't exist when that
  guarantee was first written.

**Bottom line:** at the pinned `2.0.0`, there is no cumulative-usage concept to worry
about — per-call usage is genuinely per-call, confirmed against real committed fixtures in
the example project too (`llm-cache/tool-calling/`'s two fixtures: `172/29/201` and
`119/23/142` — different, non-monotonic, i.e. not a running total). At `2.0.1`+, cumulative
usage composes correctly with replay with zero code changes needed, verified against a real
model, not merely argued from the ordering. Nothing to build; nothing to fix.

## 3. MCP client tool consumption — detail

`SyncMcpToolCallbackProvider` (`spring-ai-mcp:2.0.0`, confirmed via `javap`):

```
public class SyncMcpToolCallbackProvider implements ToolCallbackProvider, ...
  public ToolCallback[] getToolCallbacks();
```

`ToolCallbackProvider`/`ToolCallback` are the exact same `org.springframework.ai.tool.*`
interfaces every other tool source in this library's test suite already produces callbacks
through (`@Tool`-annotated methods via `MethodToolCallbackProvider`, `Function`-based tools,
`ToolSearchToolCallingAdvisor`'s discovered tools). `VcrCacheKeyGenerator
#canonicalToolDescriptors` reads `ToolCallback.getToolDefinition()` — name, description,
input schema — with no `instanceof` check for which provider produced the callback.
`VcrToolCallingManagerBeanPostProcessor` (tool-execution isolation, T1) wraps the
*application's single, shared* `ToolCallingManager` bean, not per-tool-source logic, so an
MCP-sourced tool call is isolated on replay exactly like any other. **No code path in this
library distinguishes an MCP tool from any other tool at all** — confirmed by reading the
actual field the hash and the isolation layer both key off, not merely inferred from "it's
all `ToolCallback` so it should work."

Not end-to-end tested against a real MCP server in this session (would need standing up an
actual MCP server, out of scope for a jar-level compatibility pass) — the interface-level
guarantee is the same kind of proof `OpenAiViaOllamaEndToEndTests` already generalized from
one real e2e test to a documented architectural principle; this row extends that principle
to a third tool-callback *source*, not a fourth model *implementation*, which is an easier
claim (the exact same interface, not merely the exact same behavior through a different
interface).

## 4. Chat memory ordering — detail

`MessageChatMemoryAdvisor$Builder`'s constructor, disassembled via `javap -c`:

```
ldc #9   // int -2147483448
putfield order:I
```

`-2147483448` = `Integer.MIN_VALUE + 200` = `HIGHEST_PRECEDENCE + 200`. Compared to every
order value already in `CLAUDE.md`'s table and this library's own defaults:

| Advisor | Default order |
|---|---|
| `MessageChatMemoryAdvisor` | `HIGHEST_PRECEDENCE + 200` |
| `DeterministicVcrAdvisor` (`OUTSIDE_TOOL_LOOP`, default) | `HIGHEST_PRECEDENCE + 250` |
| `ToolCallingAdvisor` | `HIGHEST_PRECEDENCE + 300` |
| `DeterministicVcrAdvisor` (`INSIDE_TOOL_LOOP`) | `HIGHEST_PRECEDENCE + 400` |

Memory (`+200`) runs upstream of this library's advisor in *both* scope settings by
default: `MessageChatMemoryAdvisor.before()` (a `BaseAdvisor` hook — always runs, cannot
short-circuit, per `CLAUDE.md`'s own table) injects the growing conversation history into
`Prompt.getInstructions()` *before* `DeterministicVcrAdvisor` ever computes a hash. Since
`VcrCacheKeyGenerator` already hashes every message in `Prompt.getInstructions()` in order,
a growing memory-backed conversation naturally produces a growing, different hash per
turn — the exact same mechanism already proven for `INSIDE_TOOL_LOOP`'s multi-turn
tool-loop messages, just arriving from a different advisor. No new code needed for the
*default* configuration.

**The footgun, not yet demonstrated live:** both this library's `order` and
`MessageChatMemoryAdvisor.Builder#order(int)` are user-overridable. Setting either such
that memory ends up *downstream* of this library's advisor would reproduce exactly the
class of blind spot the structured-output schema fix (`VcrTrack` schema `"3"`) closed —
this library would hash a shorter prompt than the one actually sent, because the memory
advisor's injection happens *after* the hash is computed. This is analytical, backed by a
real, verified order comparison (not a guess), but — following the exact lesson R5's own
history teaches this project — an ordering argument was wrong to ship as "supported"
without a real e2e test once before (§6's discovery underscores the same point again from
a different angle: doc claims and version reality can diverge). **Recommendation: add one
real multi-turn `MessageChatMemoryAdvisor` + `DeterministicVcrAdvisor` e2e test before
documenting this as officially supported**, mirroring `OllamaStructuredOutputValidationEndToEndTests`'s
pattern. Not built in this pass — this document is analysis, not a feature commit.

## Overall verdict

Spring AI 2.0 GA does not break this library's core guarantee anywhere checked. Every
piece of new machinery this GA release ships that actually reaches the `ChatClient`
advisor layer or the `ChatOptions`/`ToolCallback` interfaces this library reads —
`ToolCallingAdvisor`'s promotion to a first-class advisor, `StructuredOutputValidationAdvisor`,
`ToolSearchToolCallingAdvisor`'s architecture, MCP client tool callbacks, `2.0.1`'s
cumulative usage accumulation, the default chat-memory advisor ordering — either already
worked with zero code changes or was confirmed to work with zero code changes, verified
against real bytecode and, wherever the claim was non-trivial, a real model. The two
places this pass found an actual gap (MCP sampling; `ToolSearchToolCallingAdvisor`'s
practical demo blocked on model capability) are both honestly outside what a `ChatClient`-
advisor-layer interception strategy can reach or prove today, not oversights.

**Tally: 11 Covered · 1 Partial · 1 Gap · 8 N/A** (21 rows total. Row 6 — "Covered (2.0.1+)
/ N/A (2.0.0)" — is counted once under Covered, reflecting the feature's real state once
this project's pin eventually moves; row 3.2's "Partial → Covered for the two concrete
instances that exist" is likewise counted under Covered, since every recursive advisor
Spring AI 2.0 actually ships today has been verified, with the "Partial" wording there
flagging only that a hypothetical *third* one would need the same reasoning re-applied,
not an existing shortfall).

## Priority list, most to least worth acting on

1. **Chat memory + VCR e2e test** (§4) — the one "Covered by default order" claim in this
   whole pass that rests on ordering arithmetic alone, unverified against a real model.
   Same category of claim R5 shipped once without proof and had to go back and verify
   empirically; do it up front this time. Cheap (~1 new e2e test, same pattern as every
   other `Ollama*EndToEndTests` class), closes the one remaining "trust the math" gap.
2. **MCP client sampling gap** (7.3) — a confirmed, real, non-hypothetical gap with a clear
   shape for a fix (`VcrChatModel`, mirroring R4's `VcrEmbeddingModel` precedent: a
   `BeanPostProcessor`-wrapped `ChatModel` for the one scenario that structurally cannot go
   through `ChatClient`). Worth a PRD if a real user asks — same bar A4/E2's declined
   toxicity evaluator and R5's per-attempt scope both already use, not built speculatively
   here.
3. **`ToolSearchToolCallingAdvisor` real-model demo** (row 4, `docs/R5-RECURSIVE-ADVISOR-SCOPE-PRD.md` §7) — blocked on model
   capability, not library code; revisit with a larger local model or a hosted provider
   account, otherwise low priority since the architectural case is already closed.
4. **`spring-ai-session`/`spring-ai-agent-utils` re-check** (8.1/8.2) — no action possible
   today (unpublished); a five-minute Maven Central check once they actually publish is
   enough to move them out of N/A.
5. **MCP elicitation** (7.4) — unresearched, not urgent, flagged so it isn't silently
   assumed either way.
