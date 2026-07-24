# Evaluator

Spring AI ships its own `Evaluator` mechanism — `RelevancyEvaluator` ("is this response
relevant to the query, given this context") and `FactCheckingEvaluator` ("is this claim
supported by this document"), both built from a `ChatClient.Builder`. Both make their
internal judge call exactly the way any other `ChatClient` call is made — so wiring the
same record/replay-enabled builder this library already customizes into one of them makes
its judge call deterministic and free to run in CI, with **no new code from this library
at all**:

```java
ChatClient.Builder chatClientBuilder = ...; // already customized by this library's ChatClientBuilderCustomizer

Evaluator relevancyEvaluator = RelevancyEvaluator.builder()
    .chatClientBuilder(chatClientBuilder)
    .build();

EvaluationResponse result = relevancyEvaluator.evaluate(
    new EvaluationRequest(query, List.of(new Document(context)), response));
```

The first `evaluate()` call for a given input records a fixture the same way any other
call does; every identical call after that replays it — no additional judge-model call,
no additional token spend, no flakiness from the judge model's own non-determinism.

A recorded verdict is never frozen against a response that has since changed, either:
because the judge prompt is rendered with the actual response/claim spliced directly into
the message text this library hashes, judging a *different* answer produces a *different*
cache key and reaches the judge model again.

## Two modes, one evaluator

Spring AI's own `Evaluator` has no concept of replay — every `evaluate()` call reaches the
model, live, every time, by design. **This is the actual differentiator this project adds
on top: run the exact same evaluator in either of two modes, with zero new mechanism,
purely by which mode its `ChatClient.Builder` was built with:**

- **Deterministic replay** (`REPLAY_ONLY`) — every CI run, every push/PR. No network, no
  token spend, no flakiness. The judge's verdict for a known input is read from a
  committed fixture.
- **Live drift/quality check** (`BYPASS`, or `RECORD_ALWAYS` to overwrite the fixture with
  a fresh verdict) — a deliberate, separate run: nightly, on demand, or a developer
  checking before a release whether the model's judgment on a known case has drifted.
  `BYPASS` reaches the real model on *every* call, even when a matching fixture already
  sits on disk — the live path never replays, by construction.

!!! danger "Never run the live path in default CI"
    It reintroduces every problem record/replay exists to eliminate. It belongs in a
    separate, opt-in job — exactly like this project's own nightly end-to-end workflow
    runs its real-model proofs.

Spring AI gives you the evaluators; this project gives you the choice of which mode to
run them in.

**Toxicity checks:** confirmed absent from Spring AI 2.0.0 — checked, not assumed, across
every jar this project depends on. A toxicity judge would need a bespoke `Evaluator`
implementation, built the same shape `FactCheckingEvaluator` already is — a documented,
buildable pattern, not something built here speculatively ahead of a real need.
