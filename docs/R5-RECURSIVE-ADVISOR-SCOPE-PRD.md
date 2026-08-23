# R5 — Recursive-advisor scope: recording loops beyond the tool loop

Status: **Finding + design note. The correctness question is RESOLVED (no bug) and now
CONFIRMED against a real model with a forced multi-attempt loop (§2, §6). The per-attempt
capability extension's ordering hypothesis is CONFIRMED EMPIRICALLY too (§6) — but building
it as a first-class, documented feature (Option A/B below) remains a v0.2 candidate,
DEFERRED, per §5's reasoning.**

Prompted by the Spring AI **2.0.0 GA** review (2026): 2.0 makes *recursive advisors*
(advisors that re-enter the downstream chain in a loop) a first-class, documented pattern
— see `docs.spring.io/spring-ai/reference/api/advisors-recursive.html`. Two ship in core:
`ToolCallingAdvisor` (already handled — `VcrScope`) and the **new**
`StructuredOutputValidationAdvisor` (self-correcting structured output). This note records
what was verified about how the Recorder interacts with the *new* one, and what — if
anything — to build.

## 1. What was verified (against published source, not assumed)

Facts pinned from Spring AI 2.0.0 source
(`spring-ai-client-chat/.../advisor/StructuredOutputValidationAdvisor.java`, tag `v2.0.0`)
and this repo's `DeterministicVcrAdvisor`:

| Component | Order | Notes |
|---|---|---|
| `DeterministicVcrAdvisor` — `OUTSIDE_TOOL_LOOP` (default) | `HIGHEST_PRECEDENCE + 250` | i.e. `Integer.MIN_VALUE + 250` |
| `DeterministicVcrAdvisor` — `INSIDE_TOOL_LOOP` | `HIGHEST_PRECEDENCE + 400` | `Integer.MIN_VALUE + 400` |
| `ToolCallingAdvisor` (auto-registered) | `HIGHEST_PRECEDENCE + 300` | already accounted for by `VcrScope` |
| **`StructuredOutputValidationAdvisor`** (default) | **`LOWEST_PRECEDENCE − 2000`** | i.e. `Integer.MAX_VALUE − 2000` — near the very bottom, just above the terminal `ChatModel` call advisor (`LOWEST_PRECEDENCE`) |

Advisors run from `HIGHEST_PRECEDENCE` (most negative) to `LOWEST_PRECEDENCE` (most
positive). So the effective call order is:

```
VcrAdvisor (MIN+250 or MIN+400)   ← always near the top
  → ToolCallingAdvisor (MIN+300)
  → …
  → StructuredOutputValidationAdvisor (MAX−2000)   ← near the bottom
  → terminal ChatModel advisor (MAX)
```

**`DeterministicVcrAdvisor` is upstream of `StructuredOutputValidationAdvisor` in *both*
scope settings** (there is no scope value that places it below `MAX−2000`).

The validation advisor's loop (confirmed in source): on each attempt it calls
`callAdvisorChain.copy(this).nextCall(request)`, validates the JSON against the derived
schema, and on failure **augments the user message with the validation error** and retries,
up to `maxRepeatAttempts` (default **3**). Streaming is explicitly unsupported
(`adviseStream` returns `Flux.error`).

## 2. Consequence — the correctness question, resolved

Because VCR sits *above* the validation advisor:

- **On record (cache miss):** `VcrAdvisor.adviseCall` calls `nextCall` once; the whole
  downstream chain runs, including the validation advisor's full retry loop against the
  **real** model (up to 3 attempts). VCR records **one** fixture: original prompt → final
  validated response.
- **On replay (cache hit):** VCR short-circuits and returns that final response directly.
  The validation advisor never runs; the model is never called.
- The VCR cache key is computed from the **original** prompt (plus the structured-output
  context added by R1's schema `"3"`), never from the per-attempt *augmented* prompts —
  which VCR never sees. So the key is stable and replay is deterministic.

**Verdict: no bug.** A self-correcting `.entity(T.class, spec -> spec.validateSchema())`
call is recorded and replayed correctly for the caller — the final validated answer, zero
network on replay, exactly R1's structured-output guarantee, now confirmed to hold across
the new validation advisor too. This is the direct analogue of `OUTSIDE_TOOL_LOOP`: the
whole loop is wrapped as one fixture.

**Confirmed against a real model, not just reasoned from source (2026, `llama3.2:1b` via
Testcontainers):** `OllamaStructuredOutputValidationEndToEndTests#wholeValidationLoopRecordsAsOneFixtureAtDefaultOrder`.
Reasoning through the source alone wasn't enough to trust here, because a natural
validation failure can't be relied on to happen on demand — a small model might simply
succeed on its first attempt, proving nothing about what happens when it doesn't. So the
test forces a **guaranteed** multi-attempt loop deterministically: a
`StructuredOutputValidationAdvisor` is attached by hand (not via `.entity()`'s own
`JsonSchemaGenerator`-derived schema, which only ever emits `type`/`properties`/`required`
— confirmed by reading its source — and is therefore always trivially satisfiable by a
model that can read what's required) with a raw schema requiring an unguessable `const` the
model is never shown (the validation advisor only ever injects an *error message* into the
retry prompt, never the schema itself — confirmed by reading
`StructuredOutputValidationAdvisor`'s source). `maxRepeatAttempts(1)` then guarantees
exactly two real attempts, every run. Result: **exactly two real HTTP calls to Ollama, one
fixture on disk**, and a replay of the identical prompt makes **zero** additional HTTP
calls and returns the identical (still-failing, by design) final answer. The "no bug"
verdict above is no longer just an analysis of order values — it's been reproduced against
a real forced retry loop.

## 3. What is *not* possible today — the capability gap

There is **no scope that records the individual validation attempts**, the way
`INSIDE_TOOL_LOOP` records each tool-loop model turn. Two things follow:

1. On replay the self-correction loop is invisible: the validation advisor doesn't run, so
   a test cannot assert "the model produced invalid JSON on attempt 1 and self-corrected by
   attempt 2." Only the final answer is reproducible.
2. The intermediate (invalid) attempts are never captured in a fixture, so they can't be
   reviewed in a PR the way tool-loop turns can.

This is a capability limit, not a defect. Whether it's worth closing is §5.

## 4. Design options (if/when we close the gap)

- **Option A — generalize the scope axis.** Today `VcrScope` is tool-loop-specific
  (`INSIDE_TOOL_LOOP` / `OUTSIDE_TOOL_LOOP`). Recursive advisors are now a *general* Spring
  AI pattern (tool loop, validation loop, and "evaluation loops … alike" per the 2.0 docs).
  A cleaner axis is "wrap the loop as one fixture" vs "record each iteration," expressed as
  an explicit numeric order the caller drops between a named recursive advisor and the model
  — which the existing `int order` constructor/`spring.ai.test.vcr.order` property **already
  supports today** for advanced users. So per-attempt recording of the validation loop is
  *already reachable* by setting `order` to something `> (MAX−2000)` (e.g.
  `LOWEST_PRECEDENCE − 1000`), placing VCR below the validation advisor. **This should be
  verified empirically before being documented as supported** (see §6) — the ordering math
  says it works; a real Ollama test must confirm each attempt records/replays.
- **Option B — a first-class `INSIDE_VALIDATION_LOOP` scope constant**, mirroring
  `INSIDE_TOOL_LOOP`, that resolves to an order below `MAX−2000`. Ergonomic, but bakes in a
  dependency on a second advisor's private default order (fragile the same way
  `TOOL_CALLING_ADVISOR_ORDER` is a mirrored literal — see its Javadoc caveat).
- **Not an option:** streaming + validation — the validation advisor doesn't support
  streaming, so there's nothing to record there.

## 5. Recommendation

1. **Document now (cheap, correct, ship-worthy):** state in the docs and in article #3's
   structured-output coverage that a validated `.entity()` call is recorded/replayed as a
   single fixture (final answer), and that this is correct-by-default; note the `order`
   escape hatch for per-attempt recording as *advanced* — §6 has now confirmed it works
   against a real model, but it's still an undocumented/unnamed raw-`order` recipe, not a
   first-class supported scope value (that's the Option A/B decision, still deferred).
2. **Defer the per-attempt capability** (Option A verification, or Option B) until a real
   user asks for it — same reasoning as `docs/A4-JUDGE-ASSERTION-PRD.md` §7 and E2's
   declined toxicity evaluator: v0.1.0, no external adoption yet; don't build speculative
   surface. The correctness guarantee that matters (replay works) already holds.

## 6. Open verification (before any "supported" claim)

- **CONFIRMED (2026, real `llama3.2:1b` via Testcontainers):**
  `OllamaStructuredOutputValidationEndToEndTests#eachValidationAttemptRecordsItsOwnFixtureWhenOrderPlacesVcrInsideTheLoop`
  sets `spring.ai.test.vcr.order = Ordered.LOWEST_PRECEDENCE - 1000` — numerically between
  the validation advisor's default order (`MAX−2000`) and the terminal
  `ChatModelCallAdvisor` (`MAX`), i.e. below the validation advisor in the sorted chain —
  against the exact same hand-attached, guaranteed-to-fail advisor (`maxRepeatAttempts(1)`,
  hidden `const` schema) used for the default-order test above. Result: still exactly two
  real HTTP calls to Ollama (the order change doesn't alter how many times the validation
  advisor itself loops), but now **two separate fixtures**, one per attempt — each
  `chain.copy(this).nextCall(...)` re-entry the validation advisor makes on a failed
  attempt reaches `DeterministicVcrAdvisor` fresh, with that attempt's differently-augmented
  (validation-error-appended) prompt, so each is its own cache miss. A replay of both
  attempts together makes zero additional HTTP requests and reproduces the identical final
  answer. §4 Option A's "per-attempt recording is already reachable via `order`" claim is
  therefore no longer just an ordering argument — it's been reproduced against a real
  model. (Building this as a first-class, *documented-as-supported* capability — a named
  `INSIDE_VALIDATION_LOOP`-style scope constant, or just writing up the raw `order` value
  as a supported recipe rather than "advanced/unverified" — is still the v0.2-candidate
  decision in §5; this section only closes the empirical gap that decision was blocked on.)
- Confirm no cache-key collision between a `.entity(T)` call with vs. without
  `validateSchema()` when prompt and target type are identical (expected harmless: same
  final answer either way, but verify the key composition). **Still open** — not exercised
  by the test above, which attaches the validation advisor by hand rather than through
  `.entity()`.

## 7. Also noted during the 2.0 GA review (out of scope for R5, tracked here)

- **Spring AI is now at 2.0.1** (docs default); this repo pins `2.0.0`. **Checked
  empirically (2026, JDK 21 + Maven 3.9.9): `mvn test` is green at both `2.0.0` (250/250)
  and with `-Dspring-ai.version=2.0.1` (250/250) — 2.0.1 is a drop-in, nothing in the
  VERIFIED API FACT table broke.** The pin stays deliberate per `CLAUDE.md` ("do not
  upgrade"); bumping to 2.0.1 is a safe, owner's-call one-liner, not required. (Integration
  tests behind `-Pintegration-test` were not part of this check — they need a live Ollama.)
- **`ToolSearchToolCallingAdvisor` (progressive/dynamic tool disclosure,
  `spring-ai-tool-search-advisor:2.0.0`) — architecture confirmed compatible, real-model
  demo attempted and honestly abandoned.** Confirmed via `javap` against the real jar,
  not assumed: it `extends ToolCallingAdvisor` and its `Builder` never overrides
  `advisorOrder`, so it inherits the exact same `DEFAULT_ORDER`
  (`HIGHEST_PRECEDENCE + 300`, byte-identical constant) as the plain `ToolCallingAdvisor`
  `VcrScope` already accounts for — no new ordering reasoning needed, `OUTSIDE_TOOL_LOOP`
  should wrap its whole search-then-call loop as one fixture exactly like it does for plain
  tool calling. Attached via `ChatClient`'s 5-arg `builder(ChatModel, ObservationRegistry,
  ChatClientObservationConvention, AdvisorObservationConvention, ToolCallingAdvisor.Builder)`
  static factory (confirmed present via `javap`), substituting
  `ToolSearchToolCallingAdvisor.Builder` for the default — `DefaultChatClientBuilder`'s
  `autoRegisterToolCallingAdvisor()` (confirmed by reading its source) skips adding its own
  default `ToolCallingAdvisor` whenever any `ToolAdvisor` is already present, so this is a
  clean substitution, not a conflict.
  <br><br>
  **But a real-model e2e test (two separate attempts, `llama3.2:1b` via Testcontainers)
  never got the real `@Tool` method to fire at all** — the model answered in plain text
  without ever invoking the built-in `toolSearchTool`, even with an explicit
  step-by-step-instructed prompt and a single, unambiguous tool (no decoy). This is a
  capability gap in the *model*, not evidence against the library: `llama3.2:1b`'s
  unreliability with even a single direct tool call is already documented (see the example
  project's own README, "How the fixtures were produced" — "some attempts produced a
  plain-text string that merely looked like a tool call"), and `ToolSearchToolCallingAdvisor`
  asks a 1B-parameter model to complete a *two-hop* indirect protocol (call the search tool,
  interpret its result, then call the discovered tool) — strictly harder than the one-hop
  case that already isn't perfectly reliable. Rather than keep re-recording against a
  weak local model hoping for a lucky run (the same anti-pattern `CLAUDE.md`'s empirical
  discipline exists to prevent in the other direction — don't force a claim reality doesn't
  support), the e2e test and the test-only `spring-ai-tool-search-advisor` dependency it
  needed were both removed. **Net conclusion: nothing in `DeterministicVcrAdvisor` or
  `VcrScope` needs to change for `ToolSearchToolCallingAdvisor` — the architecture-level
  case is closed by the ordering fact above — but a real end-to-end proof needs a model
  capable of reliably completing the two-hop protocol (a larger local model, or a hosted
  provider) to be worth recording.** Left as a documented capability gap for whoever picks
  this back up with access to a stronger model, not a TODO against this library.
- **Other new surfaces worth their own scenario/PRD later:** MCP annotations
  (`@McpTool`/`@McpResource` servers + client sampling/elicitation), `spring-ai-session`
  (event-sourced memory with LLM summarization — the summarization call is itself
  Recorder-cacheable). These are content/scenario leads, not library gaps.
