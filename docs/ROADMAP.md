# Roadmap

Last updated: 2026-07-19

## Why this file exists

There was no single roadmap before this. Planning was split across two documents that
each cover part of the picture:

- **`docs/STATUS.md`** — "Next tasks, in order" (7 items) plus an "Open questions for the
  maintainer" list. Sequential and compile/test-focused, written for whoever picks up the
  repo next.
- **`docs/DISPATCH_PROMPT.md`** — four copy-paste prompts for a dispatched coding agent
  (compile-and-green, e2e Testcontainers test, auto-config slice tests, and a design-only
  task for the `REPLAY_ONLY` escape hatch). Task 1 is now done (see `STATUS.md`).

Neither document ranks work by value, sizes it, or looks outward at how mature prior art
(VCR.py, Ruby VCR, WireMock, Polly.js) solved adjacent problems. That's what this file
adds. It does not replace `STATUS.md` — that stays the terse "what's true right now"
ledger. This file is the "what should we build, in what order, and why" view, and it
folds in everything `STATUS.md`'s task list and `DISPATCH_PROMPT.md` already committed
to, so you only need to read one thing to plan work.

## Current state (as of this writing)

`mvn test` green (42/42), plus a real Testcontainers + Ollama end-to-end test
(`OllamaEndToEndTests`, excluded from the default run, verified via
`mvn test -Pintegration-test`) proving record → replay → zero additional network
requests on the hit against a real model, not a mock. `VcrFixtureRedactor` now exists
alongside `VcrPromptNormalizer` for redacting committed-fixture content without ever
being able to change a request's cache key. See `STATUS.md` for the full detail and the
bugs fixed to get here.

---

## What prior art gets right (and what doesn't transfer)

| Tool | Model | Ideas worth stealing | Ideas that don't fit here |
|---|---|---|---|
| **[VCR.py](https://vcrpy.readthedocs.io/)** | HTTP-layer interception, one cassette (YAML/JSON) holds an ordered list of request/response pairs | Record modes (`once` / `new_episodes` / `none` / `all`), `before_record_request`/`filter_headers` as a **separate** hook from the request matcher, per-test cassette activation (`@pytest.mark.vcr`) | Fuzzy/partial request matching (`match_on` combinators) — this project's whole reason to exist is refusing exactly that; cassette-as-multi-interaction-file — this project already made the opposite call (one file per hash, O(1) lookup, see `STATUS.md` decision table) |
| **Ruby VCR** | Same lineage as VCR.py, more mature hook system | `allow_playback_repeats`, pluggable request matchers as first-class objects, `ignore_request` | Same fuzzy-matching caveat as VCR.py |
| **WireMock** (record-playback) | Server-side stub journal, supports **scenarios**: the same request returns different canned responses across ordered calls (simulate retry-then-success, rate-limit-then-ok) | The scenario/sequenced-response idea — useful for testing retry and backoff logic against an LLM client without a live flaky provider | Its stub-priority/proxy model doesn't map cleanly onto an in-process advisor with no HTTP layer |
| **Polly.js** | Interception adapters decoupled from storage "persisters" (fs, REST, localStorage) | Decoupling capture from storage *in principle* | Pluggable storage backends directly **contradict** this project's rule #5 (fixtures are committed, pretty-printed, reviewed in PRs) — adopting this would mean fixtures could live somewhere un-reviewable. Only worth revisiting if a second consumer with a real need shows up (see `STATUS.md`'s multi-module open question) |
| **nock** | Fixture "modes" (record / dryrun / wild / lockdown) similar in spirit to this project's `VcrMode` | Naming/semantics cross-check only — `VcrMode` already covers the same ground | Nothing new |

Two gaps below were found by making this comparison, not carried over from either
existing planning doc — they're flagged as such.

---

## Feature roadmap

### Must-have (blocking a credible v0.1.0)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 1 | ~~**Separate the cache-key normalizer from fixture redaction**~~ **Done** | `VcrFixtureRedactor` (new SPI in the root `vcr` package, alongside `VcrPromptNormalizer`): applied only on the write path, after the hash is already computed from the un-redacted request; `hash()`/`schemaVersion()` on a redactor's return value are ignored and re-applied from the original track by `DeterministicVcrAdvisor`, enforced in code (belt-and-suspenders — a redactor is structurally unable to change the cache key, not merely asked not to). Collected the same way normalizers already are (`List<VcrFixtureRedactor>`, `Ordered` sequence). Five tests in `VcrFixtureRedactorTests` cover bit-identical no-redactor behavior, redaction never reaching a live response or replay, a forged-hash attempt being ignored, ordering across multiple redactors, and a throwing redactor propagating with nothing written. README gained a "Redacting fixture content" section with a comparison table, specifically because a normalizer *merges* requests and a redactor *never* does — confusing the two silently causes cache collisions on real PII, which is exactly the failure mode this item exists to close | M (1–2 days) | New |
| 2 | ~~**Auto-configuration slice tests + `additional-spring-configuration-metadata.json`**~~ **Done** | `SpringAiVcrAutoConfigurationTests` (9 tests): absence/presence by `enabled`, scope-derived vs. explicit order, `@ConditionalOnMissingBean` for all four bean types, and registered `VcrPromptNormalizer` beans confirmed reaching the generated `VcrCacheKeyGenerator`. Metadata file merges cleanly (verified against the built `spring-configuration-metadata.json`) | S–M (1 day) | `STATUS.md` #3/#4, `DISPATCH_PROMPT.md` Task 3 |
| 3 | **`REPLAY_ONLY` escape hatch** — design note comparing the four options already listed in `DISPATCH_PROMPT.md` Task 4 (advisor-params override, `@Vcr` JUnit 5 extension, exempt-class property list, or "do nothing, use a separate source set"), then implement the chosen one | CI runs sealed (`REPLAY_ONLY`); there is currently no sanctioned way for a single test to make a live call without weakening the whole suite's guarantee. This is the single most VCR.py-like ergonomic feature missing (`@pytest.mark.vcr(record_mode=...)`) and it's already scoped as a design-first task — recommend the `@Vcr(mode=...)` extension unless the design note surfaces a reason not to | Design: hours. Impl: M (1 day) | `STATUS.md` #6, `DISPATCH_PROMPT.md` Task 4 |
| 4 | ~~**Real end-to-end proof**~~ **Core proof done** — `OllamaEndToEndTests` (`@Tag("integration")`, `mvn test -Pintegration-test`): real Testcontainers-managed Ollama container, real `llama3.2:1b`, genuine cache miss → record → hit → replay, with an HTTP request counter wired into the `RestClient` underneath `OllamaApi` proving zero additional network requests on the hit — not inferred from response text alone. **Not yet covered** by this test (narrower, can be added incrementally rather than re-blocking anything): `REPLAY_ONLY` throwing on a miss without touching the container, and the `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction via a counting `@Tool`. Docker Desktop needed starting first; once started, this was unblocked the same session | Core: done. Remaining two scenarios: S (a few hours, same test class) | `STATUS.md` #2, `DISPATCH_PROMPT.md` Task 2 |
| 5 | **CI workflow with a fixture-drift gate** — build on JDK 21, run with `-Dspring.ai.test.vcr.mode=REPLAY_ONLY`, fail if any committed fixture changed during the run | Turns "a fixture change in CI means someone bypassed review" from a stated intent into an enforced check. Needs items 2 and 4 done first so there's something meaningful to run in CI | S (a few hours once 2 and 4 land) | `STATUS.md` #6 |
| 6 | **Document the non-determinism caveat** — a fixture recorded at `temperature > 0` freezes one sample from a distribution; replay will make a flaky-in-production prompt look deterministically stable in tests, and that's a property of testing, not of the model | Near-zero cost, prevents a confused bug report down the line ("why does this always pass in CI but the model is clearly non-deterministic in prod") | XS (docs only) | New, but small enough to just do — **done**, see README "Limitations" |

#### How item 1 was actually resolved

The design sketch below is kept for the record; each open question it raised was settled
as follows when `VcrFixtureRedactor` was built:

- **Request-side, response-side, or both?** One interface, one method, taking the whole
  `VcrTrack` (`VcrTrack redact(VcrTrack track)`) rather than a split
  `VcrRequestRedactor`/`VcrResponseRedactor` pair. A `canonicalRequest` string field also
  turned out to need redacting — it holds the same message text a `RequestSnapshot`
  redactor would already be scrubbing, just in a second place in the same fixture, so
  splitting by request/response wouldn't even have been enough; a redactor needs to see
  everything that gets written, in one pass.
- **Does redaction ever run on the replay path?** No — enforced structurally, not just
  documented. `DeterministicVcrAdvisor.applyRedactors()` is only ever called from the
  record path (`recordAndReturn()`); there is no code path that invokes a redactor
  during a hit, so a replay is physically incapable of being touched by one.
- **Naming clash risk with `VcrPromptNormalizer`.** Resolved with documentation, not a
  combined interface: `VcrFixtureRedactor`'s Javadoc and the README's new "Redacting
  fixture content" section lead with a direct comparison table (does it affect the hash?
  does it affect what a hit returns?) precisely because the two are easy to reach for
  interchangeably and only one of them is safe on real PII.
- **Does this replace the "raw prompt alongside normalized" open question?** Left open
  below, unchanged — a redactor makes storing the raw prompt *safer* if someone chooses
  to do it later, but doesn't by itself decide whether the project should.

One thing the original sketch didn't anticipate: the hash/schemaVersion enforcement
needed to be defense-in-depth, not just "the advisor doesn't call redactors on a hit."
A redactor's `redact()` method still receives and returns a full `VcrTrack`, meaning
nothing stops an implementation from returning a track with a different `hash()` —
so `applyRedactors()` explicitly discards whatever a redactor returns for `hash()` and
`schemaVersion()` and re-applies the original values after every redactor in the chain,
rather than trusting well-behaved implementations to leave those two fields alone.

This needs a decision from whoever owns the API surface before any of it is coded — it's
listed here as a concrete starting point for that conversation, not a spec.

### Nice-to-have (valuable, doesn't block a v0.1.0 tag)

| # | Feature | Why | Size | Source |
|---|---|---|---|---|
| 7 | **Hit/miss diagnostics** — a lightweight counter or listener (e.g. `VcrDiagnostics`) exposing hits/misses/records per test run | `CLAUDE.md`'s own testing convention demands "assert on chain invocation counts, not just response payloads." Right now consumers have no supported way to do the equivalent for their own tests short of re-implementing chain-count assertions by hand each time | S (half a day) | New |
| 8 | **`allow_playback_repeats`-style explicit opt-in for identical repeated calls** | Same request hash hit multiple times in one test today just replays every time (this already works, since it's file-based and stateless) — but there's no way to *assert* "this fixture was meant to be used exactly N times," which matters once diagnostics (item 7) exist | XS once item 7 lands | Ruby VCR |
| 9 | **Publishing infrastructure** — Sonatype OSSRH coordinates, GPG signing, `maven-source-plugin`, `maven-javadoc-plugin`, `LICENSE` (Apache-2.0), `CONTRIBUTING.md` | Needed before a real `0.1.0` release, but only makes sense after the API stabilizes — no point signing and publishing something that changes shape next week | M | `STATUS.md` #7 |

### Future — needs its own design pass before any code is written

| # | Feature | Why it's parked | Source |
|---|---|---|---|
| 10 | **Streaming replay** (`.stream()` currently passes straight through) | `CLAUDE.md` already forbids touching this without reading `STATUS.md`'s note first: faking a token stream with a single-chunk `Flux` would make streaming tests pass while hiding exactly the chunk-boundary/timing/partial-tool-call bugs they exist to catch. Needs its own fixture schema field, not a bolt-on to `VcrTrack` | `STATUS.md` "Known risks" #3 |
| 11 | **Sequenced/scenario responses per hash** (WireMock- and VCR.py-style: same request, different response on the 2nd/3rd call — useful for testing retry and backoff logic) | Directly in tension with design rule #1 ("exact match only... never a close-enough hit") and the one-file-per-hash layout. Would need an explicit, clearly-opt-in fixture variant (e.g. a `sequence` array) so it can never silently change single-answer semantics for everyone else. Don't start without a design note weighing this against just writing N separate tests with N distinct prompts instead | New (from WireMock comparison) |
| 12 | **VCR support for non-chat models** (embedding, image, audio, moderation) | Explicitly out of scope today because none of these pass through the `ChatClient` advisor chain — each would need its own interception point and its own investigation into whether an equivalent advisor/interceptor API even exists in Spring AI 2.0 for that model type | `STATUS.md` "Scope limits" |
| 13 | **Pluggable fixture storage backend** (Polly.js "persister" style) | Contradicts design rule #5 (fixtures are pretty-printed, committed, and reviewed in PRs) — a pluggable backend invites un-reviewable fixtures. Only reconsider if a second real consumer needs it, which is the same trigger condition already named in `STATUS.md`'s multi-module open question | New (from Polly.js comparison), `STATUS.md` open question |

### Explicitly rejected — do not re-litigate

These are already decided in `CLAUDE.md`'s "Do not" list and are repeated here only so
this roadmap doesn't accidentally re-open them:

- Semantic / vector / similarity matching, at any threshold.
- TTL or time-based fixture invalidation.
- `ChatClientCustomizer`, `CallAroundAdvisor`, `AbstractAdvisor`, or
  `com.fasterxml.jackson.databind.ObjectMapper`.
- Auto-enabling the advisor by default (`spring.ai.test.vcr.enabled` must stay opt-in).

---

## LLM-specific concerns — where each one is actually handled

Called out separately because it's easy to plan a "VCR for LLMs" library by analogy to
HTTP VCR tools and quietly miss the parts that only exist because the thing being cached
is a model call, not a REST response.

| Concern | Current state | Gap, if any |
|---|---|---|
| **Streaming responses** | Passes through live, un-cached, by design | Item 10 above — deliberately parked |
| **Token/usage accounting** | `VcrTrack.ResponseSnapshot.usage` (`UsageSnapshot`: prompt/completion/total tokens) already captured and round-tripped | None currently known; provider-native usage objects are deliberately dropped (lossy by design, documented in README) |
| **Non-deterministic output** | This is the library's entire value proposition (freeze one sample, replay it) | Needs the caveat documented — item 6 above |
| **Embeddings** | Out of scope; embedding calls don't pass through `ChatClient`'s advisor chain | Item 12 above |
| **Tool / function calls** | Modeled today: `ToolDefinitionSnapshot` (name/description/schema) feeds the hash, `ToolCallSnapshot` round-trips tool-call fixtures, and `VcrScope` (`OUTSIDE_TOOL_LOOP` / `INSIDE_TOOL_LOOP`) already decides whether replay skips or re-runs `@Tool` methods | Behavior is designed but the `INSIDE_TOOL_LOOP` vs `OUTSIDE_TOOL_LOOP` distinction specifically is still **unproven** — the counting-`@Tool` scenario from item 4 was not yet added to `OllamaEndToEndTests` |
| **PII / secrets in prompt text** | Transport secrets (API keys, bearer tokens) never reach a fixture because interception is above HTTP — genuinely solved, better than VCR.py's manual `sk-...` scrubbing. Secrets/PII *inside the message text itself* now have a safe redaction path too: `VcrFixtureRedactor` (item 1, done) | None currently known |

---

## Suggested order

Re-sequenced once Docker Desktop was started and the e2e proof became unblocked.

1. **Done** — Item 6 (document non-determinism caveat, in README "Limitations").
2. **Done** — Item 2 (`ApplicationContextRunner` auto-configuration slice tests +
   config metadata).
3. **Done (core)** — Item 4 (e2e proof): `OllamaEndToEndTests` against a real
   Testcontainers-managed Ollama container proves record → replay → zero additional
   network requests on the hit. Chosen to run before the redactor/normalizer work
   (reversing the previous plan) precisely because it's the safety net for that next
   change: once it exists, any future change to the hashing/fixture-write path can be
   checked against a real model, not just mocks. Two narrower scenarios from the
   original scope (`REPLAY_ONLY` miss without touching the container, and the
   `INSIDE_TOOL_LOOP`/`OUTSIDE_TOOL_LOOP` counting-`@Tool` proof) are not yet added —
   tracked as a small follow-up in the same test class, not a blocker.
4. **Done** — Item 1 (`VcrFixtureRedactor`). Hard constraints from the acceptance bar were
   met and tested, not just asserted: `VcrFixtureRedactorTests#noRedactorMeansUnchangedFixture`
   proves the pre-existing constructor path and the new redactor-aware one (given an
   empty list) produce identical fixtures, and a companion characterisation test
   (`VcrCacheKeyGeneratorTests#hashIsPinnedForKnownInputs`) pins literal hash values
   independent of this feature. The hook is opt-in (default `List.of()`) and
   structurally unable to change the hash (enforced in `DeterministicVcrAdvisor`, not
   left to trust).
5. Item 3 (`REPLAY_ONLY` escape hatch) — design note first, sign-off, then implement.
6. Item 5 (CI workflow) — next natural step now that item 4 (redactor) has landed and
   CI can exercise the final shape of the write path.
7. Item 9 (publishing) — last, after the API has held still for a bit.
8. Items 7–8 (diagnostics) — opportunistic, whenever convenient.
9. Items 10–13 — not started without a dedicated design note each, per the table above.
   Batch-verification brainstorm explicitly excluded from this list — see
   `docs/BRAINSTORM.md`; no code planned there.

---

## Brainstorm

Two rougher ideas the maintainer wants to think through out loud — a lambda-based
callback/hook system, and single-LLM-call batch answer verification across a test run.
Neither has a decision yet; both are written up separately so they don't get mistaken
for committed roadmap items. See `docs/BRAINSTORM.md`.
