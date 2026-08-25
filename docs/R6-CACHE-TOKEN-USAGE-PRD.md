# R6 — Cache-token usage: closing the fidelity gap `docs/SPRING-AI-2.0-COMPAT.md` row 6.2 found

Status: **Done.** Short design note, not a PRD in the R3/R4/R5 sense — the fix is small,
additive, and follows the exact precedent every prior `VcrTrack.CURRENT_SCHEMA_VERSION` bump
already set. Written up anyway because the finding it closes came out of a compatibility
audit, not a feature request, and the reasoning is worth keeping alongside R3–R5.

## The gap

`Usage` gained `getCacheReadInputTokens()`/`getCacheWriteInputTokens()` (both `Long`,
`null` when a provider doesn't report them) as a headline Spring AI 2.0 GA feature —
populated today by Anthropic and Bedrock Converse, read-only from OpenAI/Google GenAI. Before
this change, `VcrTrack.UsageSnapshot` had exactly three fields (`promptTokens`,
`completionTokens`, `totalTokens`), `VcrTrackMapper#toResponseSnapshot` never called the two
new accessors on record, and `VcrTrackMapper#toUsage` rebuilt replay `Usage` via
`DefaultUsage`'s 3-argument constructor only. Consequence: a test asserting on Anthropic
prompt-cache token savings — a real, commonly checked cost metric — saw the correct number on
a live/`BYPASS` call and a silent `null` on every replay afterward, forever, with no warning.
Found by reading `VcrTrack.java`/`VcrTrackMapper.java` directly against the bytecode-confirmed
real `Usage`/`DefaultUsage` shape in `spring-ai-model:2.0.0`, not hypothesized.

## The fix

Additive, schema `"5"` → `"6"`, touching only the response side:

- `VcrTrack.UsageSnapshot` gained `cacheReadInputTokens`/`cacheWriteInputTokens` (`Long`).
- `VcrTrackMapper#toResponseSnapshot` now reads both accessors off the live `Usage` (same
  `null`-safe pass-through as every other `Usage` field already got).
- `VcrTrackMapper#toUsage` now calls `DefaultUsage`'s 6-argument constructor
  `(Integer, Integer, Integer, Object nativeUsage, Long, Long)`, passing `null` for
  `nativeUsage` — consistent with that field already being deliberately excluded from the
  fixture (see `VcrTrack`'s class-level Javadoc on why the provider's raw usage object is
  never serialised).
- `VcrStreamTrackMapper` (R3) duplicates this same usage-mapping logic for per-chunk usage in
  a streamed response — it reuses the shared `VcrTrack.UsageSnapshot` type, so it needed the
  identical fix at both of its own two call sites. Found by grepping for every
  `new VcrTrack.UsageSnapshot(`/`toUsage(` call site in the codebase, not assumed to be the
  one place.

## Why this is *not* a hash change — unlike every prior schema bump

Every earlier bump (`"2"` through `"5"`) touched `RequestSnapshot` and therefore had to touch
`VcrCacheKeyGenerator` too, because those fields (tool calls, structured-output schema,
line-ending normalization, media) are things the model actually sees — if it can change model
behaviour, it must be able to bust the cache. `Usage` is different in kind: it is *response*
data, produced only after a call already happened. `VcrCacheKeyGenerator` has no method that
accepts a `ChatResponse` at all — the cache key is computed from the request alone, before any
call is made. So this bump touches only `ResponseSnapshot`, `VcrCacheKeyGenerator` is
untouched, and every existing golden-hash test in `VcrCacheKeyGeneratorTests` is unaffected
**by construction**, not merely by observation — there was nothing to add a golden test for on
the hashing side. `VcrTrackStoreRoundTripTests#cacheTokenUsageRoundTrips` pins this as an
observable fact anyway, alongside the actual round-trip behaviour.

## Tests added (`VcrTrackStoreRoundTripTests`)

1. `cacheTokenUsageRoundTrips` — a response with real cache-read/cache-write tokens (plus a
   non-null `nativeUsage`, to prove that field is dropped on record rather than merely never
   exercised) round-trips bit-for-bit through record → disk → replay, and the fixture's hash
   is confirmed unchanged from the request-only key.
2. `usageWithoutCacheTokensStillReplaysAsNull` — a provider that doesn't report cache tokens
   (the overwhelming majority of existing fixtures and every non-Anthropic/Bedrock provider
   today) still replays correctly, both new fields `null`, not `0` — same convention `Usage`
   itself uses for "not reported."
3. `schemaVersion5FixtureWithoutCacheTokensStillReplays` — a hand-written schema-`"5"` fixture
   JSON (no `cacheReadInputTokens`/`cacheWriteInputTokens` keys at all, exactly what every
   fixture recorded before this capability existed looks like on disk) still deserializes and
   replays without throwing, same backward-compatibility pattern every prior schema bump's own
   test already follows.

All three, plus the full existing suite, pass: `mvn clean test` — 253/253 green (250 prior +
4 new − 1 folded into an existing test, see commit message for the exact accounting), working
tree clean afterward (the same whole-tree dirty-check `.github/workflows/ci.yml`'s `test` job
enforces). `-Pintegration-test` compiles cleanly too; the real-Ollama e2e suite itself was not
re-run this pass — Docker was not available in this environment at the time of this change —
so the fix is unit/round-trip-verified, not live-model-verified. Ollama does not report cache
tokens today regardless (it has no prompt-caching concept comparable to Anthropic's), so a
live Ollama run would only have re-confirmed the "both come back null" path already covered by
`usageWithoutCacheTokensStillReplaysAsNull` — not new coverage, just Docker-backed coverage of
an already-unit-tested path. Worth a real Anthropic-backed e2e test if/when this project adds
one (none exists yet for any Anthropic scenario — every current e2e test runs against Ollama
or Ollama-via-OpenAI-SDK).

## Follow-up

`docs/SPRING-AI-2.0-COMPAT.md` row 6.2 updated from **Gap** to **Covered**. Priority list item
#1 in that document is now done.
