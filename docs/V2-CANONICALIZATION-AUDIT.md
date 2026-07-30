# Canonicalization audit — v2 roadmap item 3, pre-deploy pass

Last updated: 2026-07-30

Tracks ROADMAP item 3 ("canonicalization as a first-class component + a dedicated
compatibility test suite"). This document is the record of a pre-deploy diagnostic pass:
does `VcrCacheKeyGenerator` miss any real, model-visible request dimension that a user
would actually change, in a way that silently collides two structurally different
requests onto one fixture? The same failure mode design rule #1 exists to make impossible
("a prompt that changed by one character must produce a new fixture or a loud failure —
never a close-enough hit"), and the same class of bug already found and fixed twice before
this audit (the tool-call schema bump "2", the structured-output schema bump "3").

## Method

Real Spring AI 2.0.0 jars (`spring-ai-client-chat`, `spring-ai-model`, `spring-ai-commons`,
`spring-ai-openai`, and the real, published `spring-ai-mcp:2.0.0` — not in this project's
own dependency tree, downloaded specifically to check) were unpacked and read via `javap
-c`, the same "verify against the real constant pool, don't trust training data or prior
tutorials" discipline `CLAUDE.md`'s own verified-API table already applies. Two findings
were then confirmed empirically with throwaway unit tests (written, run, deleted — never
committed) that fed two structurally different requests through the real
`VcrCacheKeyGenerator` and diffed the resulting hash.

## Findings, ranked by severity

### 🔴 High — native vs. text-spliced structured output (fixed, schema "5")

`entity(Class)` and `entity(Class, spec -> spec.useProviderStructuredOutput())` populate
identical `ChatClientAttributes.OUTPUT_FORMAT`/`STRUCTURED_OUTPUT_SCHEMA` context values
for the same target type — confirmed by decompiling `DefaultChatClient`'s
`resolveAdvisorChain` and `ChatModelCallAdvisor`'s `augmentWithFormatInstructions` — but
are genuinely different requests: the non-native path splices format instructions into
the last message's text; the native path instead sets a
`StructuredOutputChatOptions.outputSchema` field on `ChatOptions` (concretely,
`OpenAiChatOptions.getOutputSchema()`) and leaves the message untouched. A
`ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE` context key (`"spring.ai.chat.client.
structured.output.native"`) is present if and only if the native path was used.

Before this audit, `VcrCacheKeyGenerator` read only the schema/format text — identical in
both modes — so the two collided on one hash. Confirmed empirically before the fix
(identical hash for a native and a non-native call sharing the same DTO) and confirmed
different after it. **Fixed**: `appendStructuredOutput` now also appends
`structuredOutputNative=true` when that context key is present. Additive: a request that
never touched `entity()`, and a non-native `entity()` call, hash exactly as before — proven
by every pre-existing golden hash test in `VcrCacheKeyGeneratorTests` passing unchanged.

**Real, not hypothetical impact**: the sibling example project's own
`StructuredOutputRecordReplayTest` uses `useProviderStructuredOutput()` (Ollama's native,
schema-constrained decoding — the example project's own comment explains this is more
reliable than text-instruction-based conversion for a 1B-parameter model). Its committed
fixture was recorded before this fix existed, so under the corrected hash formula it now
needs re-recording against a real model — the direct, expected consequence of closing a
real collision, the same category of follow-up the schema "4" line-ending fix already
required for two other example-project fixtures.

### 🔴 High — multimodal content invisible to the hash and the fixture (fixed, schema "5")

`UserMessage`/`AssistantMessage` both implement `MediaContent`, carrying a
`List<Media>` (images, audio). `VcrCacheKeyGenerator` read only `Message.getText()` —
`getMedia()` was never called, and neither was it captured by `VcrTrackMapper` into the
fixture, so a reviewer could not even see that an image was part of a recorded request.
Confirmed empirically: two prompts with the same caption text but two different image
URIs hashed identically before this fix.

**Fixed**: each message's `Media` list now participates in both the hash
(`VcrCacheKeyGenerator#appendMessageMedia`) and the fixture
(`VcrTrack.MessageSnapshot.media`, a new `MediaSnapshot` record). A `byte[]`-backed
attachment (a `Resource`) is digested (`sha256:<hex>`) rather than embedded verbatim — the
same reasoning that motivated the fixture-size-warning feature: an embedded multi-megabyte
image would make both the canonical form and the committed fixture balloon for no
reviewable benefit. A `URI`-backed attachment's data is already a compact, meaningful
string (the URI itself), so it participates as-is.

**A real gotcha caught while implementing this, not assumed**: `Media.getName()` is
deliberately excluded from both the hash and the fixture. Decompiling `Media`'s own two
public constructors shows both call `generateDefaultName(mimeType)` unconditionally, which
returns `mimeType.getSubtype() + "-" + UUID.randomUUID()` — a fresh random value on *every*
construction, even for "the same" logical image. Hashing it would have meant the identical
image, attached twice (once when a fixture is recorded, again on a later run expected to
replay it), gets two different names and two different hashes — breaking exact-match
replay for the ordinary case, not an edge case. `getId()` carries no such risk (both public
constructors leave it `null`; only an explicit `Media.builder()...id(...)` sets a real,
stable value), so it participates normally.

### 🟡 Medium — provider-specific, behavior-changing `ChatOptions` fields (deferred)

The real, published `OpenAiChatOptions` (this project's own test-scope dependency) carries
fields with no base-`ChatOptions` equivalent that unquestionably change model output:
`reasoningEffort`, `seed`, `toolChoice`, `logitBias`, `n`. `VcrCacheKeyGenerator`
deliberately reads only the base `ChatOptions`/`ToolCallingChatOptions` interface — by
design, confirmed correct for provider-independence by R2's own empirical proof (see
`docs/ROADMAP.md`) — so none of these participate in the hash.

**Not fixed in this pass, deliberately.** This is a genuine tension between two of this
project's own stated goals (stay provider-agnostic vs. "anything model-visible must bust
the cache") rather than a one-line bug: capturing these would mean either downcasting to
specific provider `ChatOptions` subtypes (breaking the provider-independence this library's
main sources deliberately never depend on `spring-ai-openai`/`spring-ai-anthropic` to
preserve) or a pluggable per-provider extension point that does not exist yet and needs its
own design note before any code — exactly ROADMAP item 3's own stated bar. **Not yet
empirically triggered** in this project's own test suite, which only exercises Ollama and
OpenAI-via-Ollama with default options — a real but currently dormant risk, live the
moment someone uses a reasoning-effort/seed/tool-choice-bearing provider.

**Deferred to post-deploy**, tracked as the remaining scope of ROADMAP item 3.

### ✅ Checked, confirmed not a gap

- **Chat memory / conversation history.** `MessageChatMemoryAdvisor`'s default order
  (`Ordered.HIGHEST_PRECEDENCE + 200`, confirmed via bytecode) runs before this library's
  own advisor's default (`+250` outside the tool loop, `+400` inside it) in Spring's
  advisor chain — loaded conversation history is already baked into `Prompt.getInstructions()`
  by the time the hash is computed, so the existing per-message loop already covers it.
  Only a gap if a user explicitly overrides the memory advisor's order past this library's
  own — an advanced misconfiguration, not a default-path risk.
- **`ToolContext`.** Confirmed via `DefaultToolCallingManager` bytecode:
  `resolveToolDefinitions()` (what is actually sent to the model as the tool schema) never
  sees it; only `executeToolCall()` (local tool execution) does. It never reaches the wire
  request, so correctly excluded from the chat-level hash. (A narrower, separate,
  already-scoped question — whether the *tool-execution*-level cache,
  `VcrToolExecutionCacheKeyGenerator`, should key on it too, for a `@Tool` method whose own
  behavior depends on injected context — is out of scope here and not new.)
- **MCP tool metadata.** The real `spring-ai-mcp:2.0.0` module's `SyncMcpToolCallback`/
  `AsyncMcpToolCallback` implement the standard `ToolCallback` interface and expose
  `getToolDefinition()` exactly like any local `@Tool` method. MCP-sourced tools are already
  captured transparently by the existing `canonicalToolDescriptors()` logic; no special
  case needed.
- **Reasoning/thinking as a cross-provider concept.** Absent from the base `ChatOptions`
  interface (consistent with `CLAUDE.md`'s verified-API table). The only related class found,
  `ThinkingTagCleaner`, is a response-side text-cleanup utility operating on message text
  already covered by existing hashing — not a request parameter. The real manifestation of
  "reasoning config" turned out to be the Medium-severity finding above
  (`OpenAiChatOptions.getReasoningEffort()`), a provider-specific option, not a generic one.

## Verdict applied

High-severity findings #1 and #2 were fixed before deploy (`VcrTrack` schema bumped
`"4"` → `"5"`, additive — every prior fixture still deserializes, verified by a dedicated
backward-compatibility test). Rationale: both are the same failure class already found and
fixed twice before (tool-call and structured-output schema bumps), both are reachable to
the advisor with a cheap, well-established fix (a context-key check, a per-message field),
and both are dimensions a real user will genuinely vary (native vs. non-native structured
output; which image a vision-model test attaches) — shipping v0.1.0 with either known and
already-diagnosed would risk an early adopter's first real "wrong answer silently replayed"
incident against the library's central promise.

The Medium-severity finding (provider-specific `ChatOptions` fields) is deferred, remains
the scope of ROADMAP item 3 going forward, and needs its own design note (a pluggable
per-provider hashing extension point, or an explicit decision to accept the gap for
providers this library doesn't test against) before any code.
