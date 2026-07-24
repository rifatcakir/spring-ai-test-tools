# Programmatic Stubbing — PRD

Status: **design decided, API shape below is what will be implemented unless a genuine
fork surfaces during implementation.** No hash/fixture impact whatsoever — this is a
parallel mechanism, not a change to Recorder, so nothing here required a stop-and-ask
under this project's own standing rule. `ChatModel`/`EmbeddingModel` shapes below were
confirmed via `javap` against `spring-ai-model-2.0.0.jar`, not guessed.

## Positioning — read this before anything else

**Record/replay stays the headline feature.** This is not "WireMock for AI" and must
never be pitched that way — a stub never touches a fixture, a hash, or a real model, and
Recorder remains the only path to "does my prompt actually get this answer from a real
model." Stubbing exists for the two things Recorder structurally cannot give you:

1. **Error/edge scenarios you cannot record**, because no real provider will reliably
   hand you a malformed response, a timeout, a refusal, or a specific `finishReason` like
   `"length"` on demand. Recording is fundamentally "capture what actually happened";
   these scenarios are "what if this happened."
2. **Pure unit tests** that want a `ChatModel`/`EmbeddingModel` with zero I/O, zero
   fixture file, zero Spring context — faster and more self-contained than even a
   `REPLAY_ONLY` fixture read.

Everything else — "does this prompt really produce this text," "did this tool call
really round-trip," anything a PR reviewer should be able to trust reflects a real model
— stays Recorder's job. The quality bar is **WireMock's**: stable, minimal boilerplate,
obvious defaults, a test author writes two or three lines and moves on — but the scope is
deliberately narrower than WireMock's request-matching/routing table, for reasons
covered in "Request matching" below.

## Confirmed API surface being stubbed

```
ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel
    ChatResponse call(Prompt)                         -- abstract, the only one to implement
    Flux<ChatResponse> stream(Prompt)                 -- default: throws
                                                          UnsupportedOperationException(
                                                          "streaming is not supported")

EmbeddingModel extends Model<EmbeddingRequest, EmbeddingResponse>
    EmbeddingResponse call(EmbeddingRequest)           -- abstract
    float[] embed(Document)                           -- ALSO abstract, not a template
                                                          method over call(...) the way
                                                          embed(String)/embed(List<String>)
                                                          are
```

Two facts drive real design decisions here, confirmed by bytecode, not assumed:

- **`ChatModel`'s own default `.stream(Prompt)` throws `UnsupportedOperationException`.**
  A stub that implements only `call(Prompt)` therefore behaves exactly like any other
  real, non-streaming `ChatModel` when `.stream()` is called on it — this is a correct,
  self-consistent default, not a broken corner. See "Streaming stub" below.
- **`EmbeddingModel.embed(Document)` is genuinely separate from `call(EmbeddingRequest)`,
  not routed through it.** R4's own `VcrEmbeddingModel` deliberately leaves it as an
  uncached pass-through to a real delegate (`docs/R4-EMBEDDING-INTERCEPTION.md`) — but a
  stub has no delegate to fall back to. Leaving it unimplemented (or throwing) would
  silently break any test that hands a stub `EmbeddingModel` to RAG-shaped code that
  calls `embed(Document)`, defeating the entire point of a stub being a drop-in
  substitute. **Decision: the stub's `embed(Document)` routes through the same canned
  response as `call(EmbeddingRequest)`** — a deliberate divergence from R4's own scope
  cut, justified by the fact that R4 had a real model to defer to and a stub does not.

## API shape

Package: `io.github.rifatcakir.springai.testtools.stub` — new, independent of
`recorder`/`assertions`, matching this project's existing per-concern package split.

Entry point: `VcrStubs`, mirroring `VcrAssertions`'s existing role as this project's
static-factory front door for a layer:

```java
public final class VcrStubs {
    public static VcrChatModelStubBuilder chatModel() { ... }
    public static VcrEmbeddingModelStubBuilder embeddingModel() { ... }
}
```

### Chat — three lines, only the field a test cares about

```java
ChatModel model = VcrStubs.chatModel().respondingWith("Yes.").build();

ChatModel model = VcrStubs.chatModel()
    .withToolCall("getWeather", "{\"city\":\"Ankara\"}")
    .build();

ChatModel model = VcrStubs.chatModel().withFinishReason("length").build();

ChatModel model = VcrStubs.chatModel().failingWith(new RuntimeException("timeout")).build();
```

`VcrChatModelStubBuilder`:

```java
respondingWith(String text)
withToolCall(String name, String argumentsJson)          // auto id: "call_1", "call_2", ...
withToolCall(String id, String name, String argumentsJson)
withFinishReason(String finishReason)
withModel(String modelName)                               // metadata only, default "stub"
withUsage(int promptTokens, int completionTokens)          // default 0/0/0
failingWith(RuntimeException exception)                    // if set, call(...) always throws this
build()                                                    // -> ChatModel
```

Every method returns `this` for chaining, and every method is optional — `build()` with
nothing configured produces a valid, empty-text `ChatResponse`, never `null` and never a
`NullPointerException` downstream, because a stub with no calls made against it is a
legitimate (if silly) starting point, not a misuse.

### Embedding — same shape, one field that matters

```java
EmbeddingModel model = VcrStubs.embeddingModel().respondingWith(new float[] { 0.1f, 0.2f }).build();

EmbeddingModel model = VcrStubs.embeddingModel().failingWith(new RuntimeException("timeout")).build();
```

`VcrEmbeddingModelStubBuilder`:

```java
respondingWith(float[] vector)                 // same vector answers every input in a batch call
withModel(String modelName)                    // default "stub"
failingWith(RuntimeException exception)
build()                                        // -> EmbeddingModel
```

A batch `call(EmbeddingRequest)` with N inputs returns N `Embedding` results, each
carrying the same configured vector — the simplest default that still makes
`embed(List<String>)` (which routes through `call(...)`) behave sensibly without forcing
a test to configure one vector per input for a scenario that doesn't care about that
distinction.

## Partial definition + sensible defaults — the design center

The whole point, per the requesting brief: a test states only the one field it's
asserting on. Defaults for everything else:

| Field | Default | Auto-adjusted when |
|---|---|---|
| response text | `""` | — |
| finish reason | `"STOP"` | becomes `"TOOL_CALLS"` automatically if at least one tool call was added and `withFinishReason(...)` was never called — a real model's own behavior, not something a test should have to spell out by hand |
| model name | `"stub"` | — |
| usage | `0/0/0` | — |
| tool call id | `"call_1"`, `"call_2"`, ... | only when `withToolCall(name, args)` (2-arg) is used; the 3-arg overload lets a test pin an exact id when it matters |
| embedding vector | `float[0]` | — |

No field is required. `failingWith(...)` short-circuits everything else: if set, the
built model's `call(...)` (and, for chat, `embed(Document)`'s underlying route) throws
that exact exception every time, regardless of any response-shaping calls also present on
the same builder — documented plainly rather than validated/rejected, since there's no
ambiguity a test could be confused by once it's stated once in the Javadoc.

## Streaming stub — v1 or v2? Decided: v2, deferred

**Not building a streaming stub in v1.** Reasoning:

- A streaming stub is a **materially different builder shape**, not an incremental
  option on the same one — it needs an ordered list of response fragments
  (`.respondingWithChunks("Hel", "lo", "!")` or similar), which is a second, parallel API
  surface, not a flag on the existing builder.
- The two motivating use cases in this brief — error/edge scenarios, and pure unit tests
  — are overwhelmingly non-streaming in practice. Neither named use case actually needs a
  chunked `Flux` to be well served.
- A stub `ChatModel` that implements only `call(Prompt)` already behaves **correctly**
  when `.stream()` is called on it: it throws `UnsupportedOperationException("streaming
  is not supported")`, Spring AI's own default for exactly this situation — not a broken
  gap needing an apology, a real interface contract already being honored.
- This project's own established discipline (see R3, A2, and multiple "Explicitly
  rejected"/"Future" entries in `docs/ROADMAP.md`) is: don't build speculative
  infrastructure ahead of a demonstrated need. If a streaming-stub need shows up later,
  it's an additive builder (`VcrStreamingChatModelStubBuilder` or a `.streaming()`
  variant), not a redesign of what ships now.

## Request matching — deliberately not built

WireMock's core feature is a matching/routing table: different responses for different
request shapes. **This is explicitly not being built.** A `VcrStubs.chatModel()` call
always answers the same way, for any prompt, every time it's invoked — the simplest
possible contract, and the one this brief's own guidance ("basit tut," "aşırı
mühendislik yapma") points at directly.

Considered and rejected for v1: an optional `.when(Predicate<Prompt>)` conditioning a
single stub's answer on request shape. Two reasons against, not one:

1. It is the first step toward exactly the "AI WireMock" framing this feature is
   explicitly positioned against — a routing table invites building out matchers,
   priority/ordering rules, and unmatched-request fallback behavior, none of which this
   brief asked for and all of which duplicate what a well-organized test suite already
   gets for free.
2. **A test that needs two different answers for two different prompts already has the
   idiomatic tool: build two separate stub instances**, one per `@Test` method or one per
   local variable — exactly the pattern this project's own `FakeChatModel` test helpers
   already use today (see `DeterministicVcrAdvisorStructuredOutputTests`). A routing table
   solves a problem Java method scoping already solves.

If a real, demonstrated need for per-prompt branching appears later, it's a new,
separate, explicitly-named method — not retrofitted onto this scope.

## Spring-wiring approach — none, by design

**No `@AutoConfiguration`, no `spring.ai.test.vcr.stub.*` property, no Spring Boot
dependency beyond what the module already compiles against.** This is the brief's own
explicit requirement ("unit test, Spring context'siz") made concrete: `VcrStubs` is a
plain Java utility. A test either:

- passes the built `ChatModel`/`EmbeddingModel` straight to `ChatClient.builder(stub)`
  with no Spring context at all (the primary, intended usage), or
- registers it as an ordinary `@Bean` in a hand-written `@TestConfiguration` if a test
  happens to want a full Spring context anyway — no different from wiring in any other
  test double, and nothing this library needs to provide scaffolding for.

This mirrors the existing `FakeChatModel`-in-a-unit-test pattern this codebase already
uses (`DeterministicVcrAdvisorStructuredOutputTests`) — `VcrStubs` makes that pattern
reusable and ergonomic rather than hand-rolled per test file, without adding any new
Spring machinery to reuse it.

## Test strategy

- **Deterministic unit tests, no model, no Spring context** (`VcrStubsTests` or split by
  concern): every builder method's effect verified field-by-field on the produced
  `ChatResponse`/`EmbeddingResponse` — text, finish reason (including the tool-call
  auto-default), tool call id/name/arguments (both overloads), usage, model name, the
  empty-build default, and `failingWith(...)` actually throwing the exact exception
  instance passed in, both for chat and for embedding, including `embed(Document)`
  routing through the same canned response.
- **Example project showcase**: the brief explicitly asked for real usage, specifically
  for edge/error scenarios record/replay cannot produce — a refusal-shaped response, a
  `finishReason = "length"` truncation, and a stub `ChatModel` that throws, none of which
  need Docker/Ollama/a fixture. Existing real-fixture showcases are left untouched;
  this is a new, additive demonstration.

## What this explicitly is not

- Not a replacement for any existing Recorder test — no existing fixture-based test is
  converted to a stub.
- Not a semantic/fuzzy matcher of any kind — the "Do not" list in `CLAUDE.md` already
  forbids that for hashing, and this feature doesn't touch hashing at all, but the same
  philosophy (exact, explicit, no fuzzy convenience) applies here too: a stub answers
  exactly what it was told to, nothing inferred.
- Not wired into `spring.ai.test.vcr.enabled` or any other existing flag — entirely
  independent, since it has no fixture/cache concept to gate.
