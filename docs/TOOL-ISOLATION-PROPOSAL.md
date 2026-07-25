# Tool-isolation proposal (Gemini, round 2) — superseded by docs/TOOL-ISOLATION-PRD.md

Last updated: 2026-07-25

Status: **superseded.** This was Gemini's own concrete design for a fully
side-effect-free tool-calling replay mode, written down while it was fresh so a future
PRD (or a decision to reject it) wouldn't have to reconstruct it from a chat transcript.
That PRD now exists — `docs/TOOL-ISOLATION-PRD.md` — and checked this proposal's central
claim (proxy `ToolCallback` via a `BeanPostProcessor`) against real Spring AI 2.0.0
bytecode rather than accepting it as written: the intercept point turned out to be wrong
(most `ToolCallback` instances are never Spring beans), though the underlying isolation
*idea*, the `VcrToolMode` enum shape, and the name+argument keying insight were all
correct and did ship. Kept here unmodified as the historical record of the original
proposal; see the PRD for what was actually built. See `docs/EXTERNAL-FEEDBACK.md` (open
action 2) for how this fits into the external-feedback log this document is a
detail-level companion to.

## Where this comes from

Raised in Gemini's second review round as the concrete counter-proposal to today's
`INSIDE_TOOL_LOOP` behaviour (see `docs/EXTERNAL-FEEDBACK.md`, "(b) Tool-calling side
effects"): Gemini's position is that a *replayed* test should never invoke real
application code at all, full stop — including a `@Tool` method. Today, under
`INSIDE_TOOL_LOOP`, it does: the model-turn fixtures replay, but Spring AI's own
`ToolCallingAdvisor` loop still runs for real on every replay, so a side-effecting tool
(a DB write, an outbound API call, an email) fires again on every test run, not just at
recording time.

**Gemini also explicitly confirmed the DTO-wall finding closes its own concern.** Told
that `VcrTrack`/`RequestSnapshot`/`ResponseSnapshot` are hand-written projections and
`VcrTrackMapper` never serialises a Spring AI class directly (see
`docs/EXTERNAL-FEEDBACK.md`'s "Verification performed" section for how this was actually
checked against source and against 14 real fixtures, not just re-asserted), Gemini
confirmed this fully addresses the "don't couple your fixture format to Spring AI's
internal class shapes" concern from round 1 — no residual doubt on that point.

## The proposed design

**Core idea:** record a tool call's arguments and its result onto the cassette, the same
way a model turn is recorded today. On replay, don't let the real tool run at all —
inject the recorded result straight back into the tool-calling loop, so the `@Tool`
method's own code never executes on a replay.

### Why the advisor layer is the wrong interception point for this

Today's `DeterministicVcrAdvisor` sits relative to `ToolCallingAdvisor` in the chain
(`OUTSIDE_TOOL_LOOP` before it, `INSIDE_TOOL_LOOP` after/inside it — see
`DeterministicVcrAdvisor`'s order constants). Gemini's point: the tool-calling loop is
*inside* `ToolCallingAdvisor`, which itself recursively re-invokes the advisor chain for
each turn. An advisor can intercept "the model was asked something" and "the model
answered something," but the *tool invocation itself* — the actual Java method call
Spring AI's `ToolCallingManager` makes to a `@Tool`-annotated method or a `ToolCallback` —
happens inside that recursive loop, below the advisor's own vantage point. Trying to
short-circuit *just the tool call* from the advisor layer means fighting the recursion
rather than working with it.

### The proposed interception point: `ToolCallback`

Spring AI already gives every tool — whether it's an object with `@Tool`-annotated
methods (converted via `ToolCallbacks.from(...)`) or a hand-built one — a uniform shape
before `ToolCallingManager` ever touches it: `org.springframework.ai.tool.ToolCallback`.
Confirmed by disassembling `spring-ai-model-2.0.0.jar`'s actual `ToolCallback.class`
(the same "check the bytecode, don't trust a blog post" discipline `CLAUDE.md` already
asks for elsewhere in this project):

```java
public interface ToolCallback {
    ToolDefinition getToolDefinition();
    ToolMetadata getToolMetadata(); // default
    String call(String toolInput);
    String call(String toolInput, ToolContext toolContext); // default
}
```

The proposal: wrap every `ToolCallback` reaching `ToolCallingManager` in a
`VcrToolCallbackProxy implements ToolCallback` that delegates `getToolDefinition()` (and
`getToolMetadata()`) straight through unchanged — so the tool's name, description, and
input schema are exactly what the real tool declares, and `ToolCallingManager` (and the
model) can't tell the proxy apart from the real thing — and intercepts only the two
`call(...)` methods:

- **On a cassette hit:** don't call the delegate at all. Read the recorded result for
  this exact tool name + arguments off the cassette and return it directly. The real
  `@Tool` method's body never runs — genuine, complete isolation, not just "the model
  call is replayed."
- **On a cassette miss (recording, or `BYPASS`):** call the real delegate, capture
  the arguments and the returned result, write them to the cassette, return the real
  result.

Proposed enum surface (naming, not committed API):

```java
enum VcrToolMode {
    REPLAY_FROM_CASSETTE, // default -- full isolation, no real @Tool code runs on a hit
    EXECUTE_REAL          // today's behaviour -- the real tool runs, side effects included
}
```

exposed per-test the same way `@Vcr(mode = ...)` already overrides `VcrMode` today —
something like `@VcrTest(toolMode = VcrToolMode.EXECUTE_REAL)` for the test that
specifically wants to assert the real side effect happened.

### The keying problem this design has to solve

A single cassette can and does contain more than one call to the *same* tool with
*different* arguments in one conversation (`getWeather("Istanbul")`, then later
`getWeather("Amsterdam")`, in the same multi-turn exchange). A recorded tool result can't
simply be keyed by tool name — it has to be keyed by **tool name + argument content**
(a hash of the arguments, matching this project's existing "exact match, no fuzzy
matching, ever" rule — see `CLAUDE.md`'s non-negotiable design rule #1, which this would
need to inherit rather than relax) **or** by call-order-within-the-cassette as a
fallback for a tool that's deliberately non-deterministic per call. Gemini flagged this
explicitly as the detail that would make or break a real implementation — a naive
"one result per tool name" design would silently return the wrong city's weather on the
second call.

## Open questions this document does not resolve

- **How does a proxy-the-`ToolCallback`-bean strategy reach tools that are never Spring
  beans in the first place?** This project's own tests pass tool objects directly —
  `chatClient.prompt()...tools(weatherTool)` (see `OllamaToolCallingEndToEndTests`) —
  where `weatherTool` is a plain instance with an `@Tool`-annotated method, converted to
  a `ToolCallback` internally by Spring AI at call time, not registered as a bean a
  `BeanPostProcessor` would ever see. A `BeanPostProcessor`-based proxy (the same pattern
  `VcrEmbeddingModelBeanPostProcessor` already uses successfully for `EmbeddingModel`)
  would catch a `ToolCallback` that *is* a Spring bean, but would not catch this
  per-call, non-bean usage — which looks like the more common pattern for tool-equipped
  tests today, including this project's own. Any real PRD needs to answer this before
  "wrap it in a `BeanPostProcessor`" can be the whole strategy — likely needs a second
  interception point (e.g. wrapping whatever `ChatClient.builder()...defaultTools(...)`/
  `.tools(...)` hands to `ToolCallingManager`, not just bean post-processing) or an
  explicit requirement that side-effect-isolated tools must be registered as beans.
- **Design tension with today's `INSIDE_TOOL_LOOP`, left open, not resolved:** this
  proposal and today's documented `INSIDE_TOOL_LOOP` behaviour serve different, mutually
  exclusive goals for the same scenario. Today's behaviour exists specifically so a test
  can assert "the real tool was actually invoked, with the right arguments, N times" —
  an assertion this proposal's `REPLAY_FROM_CASSETTE` mode would make structurally
  impossible on a replay (nothing real runs, so there's nothing to assert an invocation
  count on). They are not "old way vs. new correct way" — they answer different
  questions ("did my glue code correctly wire up and invoke the tool" vs. "does my
  side-effecting tool never actually fire twice"). Both may need to coexist as distinct,
  named options rather than one replacing the other.
- **0.1.0 blocker or v0.2.0-and-later?** Not decided. The user has explicitly not made
  this call yet — treat this as "under evaluation," not committed to any release.
- **Fixture-shape design.** Today's schema (`VcrTrack`/model-turn fixtures) has no slot
  for "a tool call's arguments and result, independent of a model turn." This would need
  its own fixture type or a schema bump, not a reuse of the existing `ToolCallSnapshot`/
  `ToolResponseSnapshot` records, which capture what a *model* said about a tool call,
  not what the *tool itself* returned when actually invoked outside `INSIDE_TOOL_LOOP`.

## What this document is not

Not a commitment, not sized, not sequenced, and not code. If this becomes a real PRD, it
should get its own `docs/T1-TOOL-ISOLATION-PRD.md` (or similar, matching this project's
`A1`/`A2`/`E1`/`E2`/`R3`/`R4` naming convention) with the open questions above actually
answered, not just listed.
