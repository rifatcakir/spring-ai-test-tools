# A4 — Fluent LLM-Judge Assertion: PRD

Status: **DEFERRED — backlog design note, v0.2 candidate. NOT started, do NOT build yet.**

Written per this project's "PRD-first for a real design decision" discipline — but the
sign-off this discipline requires is *deliberately withheld*. This is the artifact that
exists *before* a build decision, and the decision here is **"good idea, not now."** See
section 7 (Decision gate) for the explicit condition that must be met before any code.

Origin: a product-strategy discussion (2026-08-08), cross-checked with a second AI model.
Both independent analyses converged on the same shape and the same "keep it lean"
boundary; both independently flagged the same #1 design risk (section 4).

## 1. Problem

Two real gaps, one correctness and one DX — neither covered by what already ships.

**Correctness gap that A2 structurally cannot close.** Embedding cosine similarity
(A2, done) measures vector-space distance, not truth. Two sentences can sit at ~0.92
cosine similarity while asserting opposite facts — *"The feature was enabled"* vs
*"The feature was disabled"*. Logical equivalence, negation, and factual correctness are
exactly the properties a semantic-similarity assertion is blind to, and exactly what an
LLM-as-judge call decides. A2's own PRD already concedes this tradeoff (cheap/geometric
vs. real judgment); A4 is the assertion for the "real judgment" column.

**DX gap that E1 leaves open.** E1 (done) *proved* Spring AI's own `RelevancyEvaluator`
/ `FactCheckingEvaluator` become Recorder-backed and deterministic for free when built
from this library's VCR-wired `ChatClient.Builder`. But *using* that in a test today
means dropping out of the fluent AssertJ surface A1/A2 established and hand-writing the
raw Evaluator dance: construct an `EvaluationRequest`, call `evaluate()`, unwrap an
`EvaluationResponse`, and `assertTrue` on it. That is boilerplate, and it breaks the
mental model of `assertThat(response)...`.

A4 is the thin fluent bridge between Layer 2 (Assertions) and Layer 3 (Evaluator): the
mechanism already exists and is proven — what is missing is the *assertion-first
surface* over it.

## 2. Success criteria

A fluent, AssertJ-idiomatic assertion that delegates to Spring AI's own evaluators,
inherits E1's determinism, and reads like the rest of the library:

```java
assertThat(response)
    .usingJudge(chatClient)                 // the VCR-wired ChatClient (E1 wiring)
    .isJudgedEquivalentTo("Expected ground truth response");

// and the two Spring AI evaluators surfaced fluently, not as raw API:
assertThat(response).usingJudge(chatClient).isRelevantTo(userQuestion);       // RelevancyEvaluator
assertThat(response).usingJudge(chatClient).isFactuallySupportedBy(document); // FactCheckingEvaluator
```

- **Binary pass/fail only.** No public API that invites asserting against a float range
  (`hasScoreGreaterThan(0.75)`); a fuzzy threshold on a judge verdict is exactly the
  soft, non-reproducible test this library exists to prevent one layer down.
- **Loud failure.** On a failing verdict, surface the judge's *own* reasoning verbatim in
  the assertion message, treated with the same severity as an exact-string mismatch.
- **Recorder-backed by construction.** The judge call runs through the same
  `DeterministicVcrAdvisor` E1 already routes evaluator calls through — zero live model
  in `REPLAY_ONLY` CI, zero new fixture type expected (`VcrTrack`, as E1 confirmed).

## 3. Scope

**In (v1):** a thin fluent wrapper over `RelevancyEvaluator` / `FactCheckingEvaluator`,
plus an `isJudgedEquivalentTo`-style equivalence check built from the same
`FactCheckingEvaluator` shape E1 already dissected. Nothing more.

**Out (the scope-creep line):** a bespoke scoring/rubric engine. Both AIs independently
drew this line. The moment A4 starts inventing its own judge prompts, rubric DSLs, or
multi-criterion scoring, it stops being "a fluent surface over Spring AI's evaluators"
and becomes a competing evaluation framework to maintain. Delegate evaluation logic to
Spring AI; own only the AssertJ ergonomics. (Same reasoning E2's PRD already applied when
it declined to build a speculative toxicity evaluator.)

## 4. Constraints / non-negotiables

1. **Phantom-pass — the #1 risk both analyses flagged first.** The judge fixture's cache
   key must bind to **both** the primary response text under judgment **and** the judge
   criterion/expected string. If a user edits the expected value or the criterion and the
   key does not change, a stale "pass" verdict replays green — a silent false negative,
   and a direct violation of the library's exact-match identity. E1 already proved the
   *response* side of this (its two extra tests: changing the judged response yields a
   different canonical request and a second fixture, counted by real HTTP requests and
   real files). **A4 must prove the *criterion* side the same empirical way before it
   ships** — a changed expected/criterion string must force a new fixture or a loud miss.
2. **Determinism via E1, not a new mechanism.** Judge call at `temperature=0`, routed
   through the existing advisor. If A4 needs a new fixture type or a new advisor, the
   design is wrong — re-derive it.
3. **Brand integrity.** No soft float ranges in the public API (see §2). "Deterministic,
   exact, loud-failure" holds at this layer too.

## 5. Failure modes

- **Phantom pass / tape pollution** — §4.1. The one that, done wrong, breaks the whole
  library's promise.
- **Judge drift on re-record** — refreshing fixtures can flip a previously-passing test
  purely because the underlying judge model changed. Same category as
  `docs/VISION.md`'s "provider independence is unproven" caveat; document, don't pretend
  to solve.
- **Recording latency** — a primary model call followed by 1–2 secondary judge calls
  doubles/triples record-time per test. Fine for a handful of high-value cases, wrong for
  a large suite.

## 6. When NOT to use (honest boundary)

| Checking… | Use | Not A4, because |
|---|---|---|
| Exact format, JSON schema, SQL, code output | A1 (exact / JSON-pointer assertions) | Zero-margin checks; a judge adds cost and non-determinism for nothing |
| "Roughly the same meaning," cheap/high-volume | A2 (embedding cosine) | One small embedding call beats a full judge call at scale |
| Large regression suites (100+ cases) | A1 / A2 | Judge recording latency (§5) makes the record phase slow and expensive |
| Negation, factual correctness, summary fidelity, RAG relevancy | **A4** | Exactly the semantic nuance A1/A2 structurally miss |
| Active prompt-engineering / prototyping | **A4** | High flexibility while a prompt is in flux |

## 7. Decision gate — why this is deferred, not scheduled

The library is v0.1.0 with no external adoption yet. It already carries *more* surface
than an unadopted library needs (Recorder, A1, A2, E1, E2 all done). The current
bottleneck is visibility, not features. Building A4 now would be polishing a product
nobody uses yet — the exact premature-infrastructure trap this repo's own roadmap argues
against elsewhere (E2's declined toxicity evaluator; ROADMAP §6's rejected features).

**Build A4 only when both hold:**
1. A real user/issue asks for it, or hits the "A2 can't judge negation/facts" wall in
   practice; **and**
2. Distribution has produced actual adoption (downloads, stars, an issue tracker with
   real traffic) — i.e., feedback exists to confirm this gap is real, not assumed.

Until then: this note is the record that the idea was analysed, scoped, and consciously
parked — not forgotten.

## 8. Open questions (resolve at build time, not now)

- Exact method names (`isJudgedEquivalentTo` vs `isEquivalentTo`; `usingJudge` vs
  `usingJudgeModel`) — mirror A2's `usingEmbeddingModel` naming.
- Whether equivalence is its own `FactCheckingEvaluator`-shaped judge prompt or a
  distinct template; if the latter, that prompt is the one piece of "owned" logic and
  must be versioned/reviewed like any judge prompt.
- Precisely how the criterion enters the cache key (§4.1) — verify against real Spring AI
  2.0.0 bytecode the same way E1/R1 did, don't assume.
- Default judge configuration and whether the judge `ChatClient` is passed explicitly
  (`usingJudge(chatClient)`) or auto-discovered from context.

## 9. Relationship to existing items

- **Depends on:** E1 (mechanism, done), A1/A2 (fluent surface precedent, done), R4 not
  required (this is a chat-judge call, not an embedding call).
- **Distinct from:** E3 (batch verification) — A4 is one judge call per assertion, the
  ordinary case; E3's many-in-one-call batching is a separate, still-open concern.
- **Fills:** the named-but-unbuilt "LLM-as-judge assertion" that `docs/A1-ASSERTIONS-PRD.md`
  routes to E1 — A4 is that assertion's fluent surface, now that E1's mechanism is proven.
