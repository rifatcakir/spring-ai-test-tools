# E3 — Batch Verification: Design Note and Recommendation

Status: **evaluation only — recommendation below, no code.** E3 was deliberately parked in
`docs/ROADMAP.md` pending "its own dedicated design note" resolving the open questions
`docs/BRAINSTORM.md` §2 raised. This is that note. **Recommendation: do not build this in
`spring-ai-test-tools`.** Not "defer and revisit" — close it out, for a reason specific to
this library's own architecture, not a generic "not now."

## The one fact that decides this

E1/E2 already exist and already ship: **a single per-test LLM-as-judge call, made through
a Recorder-backed `ChatClient.Builder`, is deterministic *and* free on every CI run after
the first recording** (`REPLAY_ONLY`, zero network calls, confirmed against a real model
in `OllamaEvaluatorEndToEndTests`). Batch verification's entire reason for existing — "make
fewer live LLM calls, because live LLM calls are slow/costly/flaky" — is a problem this
library **already solved**, by a different, simpler, already-shipped mechanism: caching
the individual calls, not batching them. Everything below traces back to this one fact.

## The four questions, answered against today's architecture

### (a) Context contamination — preventable?

**Not preventable by this library's architecture — and today's architecture doesn't have
this problem in the first place, because it never batches.** E1's per-test judge call
sees exactly one case per call; there is structurally nothing for a neighboring case to
contaminate. Context contamination is a property of *putting multiple cases in one
prompt* — building E3 would be reintroducing a failure mode this project's current design
is already immune to, in exchange for the (already-obsolete, see above) promise of fewer
calls. There is no cache-key trick, no schema, no prompt-engineering fix that resolves
contamination without eliminating batching itself; it is intrinsic to the idea, not an
implementation detail to solve.

### (b) Traceability — how does one verdict-per-item work when one call returns everything?

**Same answer: today's per-test-call architecture doesn't have this problem, because
verdict and test are already 1:1.** A batch would need a strict, validated per-item output
schema (a JSON array keyed by test id, count-checked against what was submitted) —
`docs/BRAINSTORM.md` already named this. But even a perfect schema doesn't remove the
fragility BRAINSTORM already flagged: **one malformed or truncated judge response turns N
individually-fine tests into "inconclusive" all at once.** `CLAUDE.md` rule #7 ("a corrupt
fixture degrades to a cache miss, it does not fail the build — the build is still
supposed to notice") sets this project's own bar for corrupted-data blast radius: one
corrupt fixture affects one test. A corrupt batch verdict affects every test in that
batch simultaneously — a strictly worse blast radius than anything else in this codebase
tolerates, by design, not oversight.

### (c) Should the batch judge call itself be Recorder-cached?

**No — and this is the structural core of why E3 doesn't fit here.** `VcrCacheKeyGenerator`
's entire design center (`CLAUDE.md` rule #1) is one canonical `Prompt` → one SHA-256 hash
→ one fixture, and every fixture type in this project (`VcrTrack`, `VcrStreamTrack`,
`VcrEmbeddingTrack`) models exactly one call's request/response. **A "batch" is not a
single, stable request — it's an emergent property of an entire test run's composition,**
order- and membership-sensitive. Concretely: add one new test to the suite, and the
batch's canonical form changes (it now includes N+1 outputs instead of N), which changes
the hash, which forces a fresh judge call for **all N+1 cases, including the N that never
changed** — a cache-invalidation blast radius no other fixture type in this project has.
Every other cache miss here is scoped to the one request that actually changed; a batch
fixture would invalidate atomically on any single unrelated addition to the suite. That is
a fundamentally heavier, more volatile caching unit than this library's per-request design
was built for, and forcing it in would mean either a second, structurally different cache
key scheme living alongside the existing one (real new complexity, for a feature whose
underlying cost problem is already solved), or accepting the "yes-cache" branch's own
named flaw: a frozen verdict that stops verifying anything the moment it's replayed
against a suite that has since changed underneath it.

The "no-cache, always live" alternative fares no better: it reintroduces network
dependency, cost, and non-determinism into `REPLAY_ONLY`'s sealed CI guarantee — the exact
guarantee E1/E2 exist to provide — requiring an explicit, documented carve-out for the
verifier alone. `docs/BRAINSTORM.md` already correctly named this as "undermining the
sealed CI guarantee." Neither branch is acceptable, and neither has gotten easier to
answer since BRAINSTORM first raised it — the recorder's own design is what makes both
branches bad, not a gap in effort.

### (d) Is the cost claim actually true?

**No, on two independent grounds.** First, the token-economics point `docs/BRAINSTORM.md`
already made: providers typically bill per input+output token, not per call, so one large
prompt holding N cases costs roughly the same input tokens as N separate small calls —
the real, honest claim is "fewer round trips" (latency), not "cheaper," and that
distinction matters when stating this to a user. Second, and decisive **in this library's
specific context**: E1/E2 already make the per-call cost **zero** on every CI run after
the first recording, via `REPLAY_ONLY`. Batch verification's cost argument compares
"N live calls" against "1 live call" — but the real comparison this library already offers
is "N live calls (once, ever, at record time)" against "0 live calls (every CI run after
that)." Batching cannot beat zero.

## The critical question — does batch have real added value here?

**No.** Every one of the four questions above resolves the same way once E1/E2 are taken
as given: the problem E3 was invented to solve (live-LLM-judging is slow/costly/flaky) has
already been solved, more simply, by caching the individual judge calls this library
already makes. What's left of E3's original appeal — "centralize the judge prompt in one
place" — is real, but does not require batching to get: a shared prompt-template helper
used by N separate (still Recorder-backed, still free-on-replay) calls captures that
benefit with none of batching's downsides (no contamination, no traceability schema, no
cache-granularity mismatch). If that specific, smaller need (a shared judge-prompt
template) ever becomes concrete, it's a lightweight addition to the existing Evaluator
usage pattern (E1/E2) — not a reason to build batch verification.

## Recommendation: out of scope, not deferred

**Close E3 as explicitly out of scope for `spring-ai-test-tools`**, not "revisit later" —
the reason isn't "not enough time yet," it's that this library's own architecture (Recorder
caching individual calls) already delivers what E3 was chasing, and building it anyway
would add real, named risks (contamination, traceability, cache-granularity mismatch) to
solve an already-solved problem. Update `docs/ROADMAP.md`'s "Explicitly rejected" table
with this entry and this reasoning, mirroring how the semantic-matching rejection is
recorded there, so a future contributor doesn't re-propose it without first reading why.

**If a genuine future need for cross-test batch judging surfaces anyway:** it belongs in
a separate, dedicated test-orchestration/reporting tool — `docs/BRAINSTORM.md` already
named this possibility — one that consumes `spring-ai-test-tools`' Recorder for its own
individual judge calls' caching (a small, well-scoped integration point) rather than this
library growing a batching mechanism of its own. That composition question is answerable
on its own merits later; it does not need to be answered now, and answering it now would
be building ahead of a demonstrated need, which this project's own stated discipline
(`docs/ROADMAP.md` §6, "Considered and deferred") already argues against.
