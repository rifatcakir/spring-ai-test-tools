# External feedback log

Last updated: 2026-07-24

Not a PRD and not the roadmap (`docs/ROADMAP.md`). This is a running log of external
review rounds — what an outside model or reviewer flagged, what turned out to already be
handled, what was a genuine gap, and what action (if any) followed. Kept so the next
person doesn't have to re-derive "didn't we already look at this?" from git history and
Slack scrollback.

## Round 1 — ChatGPT + Gemini, first pass

Early review of the README/positioning before the record-replay-vs-stub emphasis had
been through its later revert-and-re-revert (see `git log --oneline -- README.md` for
the actual flip commits — this document doesn't re-litigate that history, `docs/VISION.md`
and the README itself are the source of truth for current positioning). General
"is this compelling/clear to an adopter" pass rather than a code-correctness audit; no
standing action items survive from round 1 specifically — its concerns were absorbed into
the positioning work described elsewhere in this repo's history rather than tracked here
as discrete findings.

## Round 2 — Gemini, second pass (against the current codebase)

A more targeted technical review, run against the current state of the project rather
than a stale snapshot. Four points raised:

### (a) The DTO wall — is Spring AI ever serialised directly?

**Gemini's concern:** don't serialise Spring AI's own domain classes directly; use your
own contract DTOs plus a mapper, so that a Spring AI API change doesn't silently corrupt
or break old committed fixtures.

**This appears to already be exactly the design** — `VcrTrack` (and its sibling
`VcrStreamTrack`/`VcrEmbeddingTrack`) are hand-written, flat records; `VcrTrackMapper`
(and its stream/embedding siblings) translate in both directions; no fixture on disk
contains a fully-qualified Spring AI class name. This was flagged as a **verify, don't
assume** action rather than accepted at face value — see "Verification performed" below
for how it was actually checked, not just re-asserted from memory.

### (b) Tool-calling side effects — an isolation violation?

**Gemini's concern:** under `INSIDE_TOOL_LOOP`, a replayed test still invokes the real
`@Tool` method — Gemini's position is that a cache hit should replay the tool's *recorded
result* instead of re-running the method, on the grounds that "replay" should mean no
real code runs at all.

**This is a real, deliberate design trade-off, not a bug** — see the "⚠️ Tool
side-effects on replay" warning already in `README.md`'s [Tool calling](../README.md#tool-calling)
section and in `site/docs/tool-calling.md`, added specifically because this is a genuine
bug-report magnet if a user discovers it by surprise. `INSIDE_TOOL_LOOP`
exists precisely so a test can assert "did the real tool actually get invoked, with the
right arguments, N times" — an assertion that is only meaningful if the tool genuinely
runs. Gemini's alternative (replay the tool's recorded output, never call the method)
would make that class of assertion impossible and would need its own fixture format for
"tool call → tool result" pairs, decoupled from the model turn fixtures that exist today.
**Not implemented, but not dismissed either** — tracked as an open action below, because
"replay the tool's result without invoking the method" is a legitimate, different
isolation mode for the side-effecting-tool case the current warning tells users to avoid
via `OUTSIDE_TOOL_LOOP` instead. Whether it's worth building as a third scope alongside
`OUTSIDE_TOOL_LOOP`/`INSIDE_TOOL_LOOP` is a real design question, not a settled one.
Gemini's own concrete design for this — proxy `ToolCallback`, not the advisor layer;
`VcrToolMode.REPLAY_FROM_CASSETTE`/`EXECUTE_REAL`; keyed by tool name + argument hash —
is captured in full in `docs/TOOL-ISOLATION-PROPOSAL.md`, including the open questions
it doesn't yet answer (a `BeanPostProcessor` strategy doesn't obviously reach tool
objects passed per-call via `.tools(...)` rather than registered as beans, and this
proposal is in direct tension with what `INSIDE_TOOL_LOOP` is *for* today — see that
document for both).

**Confirmation from Gemini, worth recording:** told how `VcrTrack`/`RequestSnapshot`/
`VcrTrackMapper` actually work — see finding (a) above and "Verification performed"
below — Gemini confirmed this fully closes its own DTO-wall concern from round 1; no
residual doubt on that point from Gemini's side.

### (c) Version realism — is Spring Boot 4 / Spring AI 2.0 really current?

**Gemini's concern (paraphrased):** treated Spring Boot 4.0.0 / Spring AI 2.0.0 as
implausible or not-yet-stable version numbers.

This is very likely a **training-data recency artifact**, not a real compatibility
problem — Gemini's knowledge cutoff predates these releases. As of this repository's
current date, Spring Boot 4.0.0 and Spring AI 2.0.0 GA are the actual, current, pinned
versions this project targets (`CLAUDE.md`'s tech-stack table, `pom.xml`). **What Gemini
got right despite the wrong premise:** pin to a specific, verified version combination and
publish a compatibility matrix so adopters know exactly what's tested versus what's an
unverified extrapolation. That recommendation was valid on its own merits regardless of
which versions are "current," and has since been acted on — see `README.md`'s
Compatibility section and `site/docs/configuration.md#compatibility`.

### (d) The "wow" is streaming + tool calling

Gemini's read: the most differentiating, hardest-to-fake capability this library has is
chunk-for-chunk streaming replay combined with tool-calling replay (`R3`/`INSIDE_TOOL_LOOP`)
— worth foregrounding in docs rather than burying as one bullet among many. No disagreement
here; both already have dedicated doc-site pages (`streaming.md`, `tool-calling.md`) and
README subsections, verified end-to-end against a real model
(`OllamaStreamingEndToEndTests`, `OllamaToolCallingEndToEndTests`), not just designed.

## Open actions

1. **DTO wall: verified, not just re-asserted — see "Verification performed" below.**
   Closed for now, and Gemini itself confirmed the finding closes its concern too; re-check
   if `VcrTrackMapper`/`VcrTrack` (or their stream/embedding siblings) ever grow a field
   that stores something Spring AI-typed rather than a primitive/record projection of one.
2. **Tool-isolation mode for side-effecting tools — done.** Built before this project's
   first publish, as approved. Gemini's own concrete proposal
   (`docs/TOOL-ISOLATION-PROPOSAL.md` — proxy `ToolCallback` via a `BeanPostProcessor`)
   was checked against real Spring AI 2.0.0 bytecode rather than accepted as-is, and the
   intercept point turned out to be wrong: most `ToolCallback` instances (everything
   `.tools(someObject)` produces) are never Spring beans, so a `ToolCallback`-level
   `BeanPostProcessor` would have silently missed the dominant usage pattern. The actual,
   bytecode-confirmed choke point is `ToolCallingManager` itself — see
   `docs/TOOL-ISOLATION-PRD.md` for the full diagnosis. `VcrToolCallingManager` now wraps
   it, a new independent `VcrToolExecutionTrack` fixture keys each invocation by tool name
   + exact argument string, and a new `VcrToolMode` axis defaults to
   `REPLAY_FROM_CASSETTE` (full isolation) with `EXECUTE_REAL` as the `@VcrTool` opt-in.
   Verified against a real model end to end (`OllamaToolIsolationEndToEndTests`) and with
   a full unit-test suite. See `docs/ROADMAP.md`'s `T1` row for the summary.

## Verification performed (action 1, this session)

Checked directly, not assumed from the design docs or from `CLAUDE.md`'s own claims:

- **`VcrTrack.java`** (and `VcrStreamTrack`/`VcrEmbeddingTrack`): every field is a JSON
  primitive (`String`, `Integer`, `Double`, `Boolean`) or a nested record of the same —
  no field's declared type is a Spring AI class.
- **`VcrTrackMapper.java`**: every value written into a `VcrTrack` field comes from a
  getter call on a Spring AI object (`options.getModel()`, `message.getText()`,
  `call.name()`, etc.) — grepped for `getClass()`, `JsonTypeInfo`, `@JsonTypeName`,
  `activateDefaultTyping`/`enableDefaultTyping` across `src/main/java`: the only
  `getClass()` call in the entire `recorder` codebase is a log statement in
  `VcrEmbeddingModelBeanPostProcessor` (`embeddingModel.getClass().getSimpleName()`, for
  a startup log line, never written to a fixture); no polymorphic-typing annotation or
  Jackson default-typing configuration exists anywhere in the project.
- **`VcrTrackStore.java`/`VcrStreamTrackStore.java`/`VcrEmbeddingTrackStore.java`**: each
  builds a plain `tools.jackson.databind.json.JsonMapper` with no default typing enabled
  — nothing that would embed a `@class`/`@type` discriminator into the JSON even if a
  field's declared type were an interface.
- **14 real, committed fixtures** in the sibling `spring-ai-test-tools-example` project
  (`src/test/resources/llm-cache*/**/*.json`) grepped for `org.springframework`,
  `com.openai`, `@class`, `@type`: **zero matches** across every fixture directory
  (`llm-cache`, `llm-cache-embedding`, `llm-cache-embedding-semantic-similarity`,
  `llm-cache-stream`). A representative fixture
  (`llm-cache/basics/1f03d7b0...json`) was read in full: flat JSON, human-readable field
  names, no type metadata of any kind.

**No leak found.** The DTO wall holds up under direct inspection, not just under the
design intent described in `VcrTrack`'s own Javadoc and `CLAUDE.md`'s non-negotiable
design rule #2.
