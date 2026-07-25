# Spring AI Tests

**Spring AI Test Tools is a deterministic testing framework for Spring AI applications.**

| Capability | What it gives you |
|---|---|
| **[Record & Replay](record-replay.md)** | Capture a real model's answer once; replay it forever, offline |
| **[Stubbing](stub.md)** | Hand-author a response — inline or from a file — for what you can't record |
| **[Assertions](assertions.md)** | Fluent AssertJ checks on tool calls, finish reasons, JSON fields |
| **[Semantic Assertions](assertions.md#semantic-assertions)** | Compare answers by *meaning*, deterministically, via embeddings |
| **[Tool Isolation](tool-calling.md#tool-side-effects-are-isolated-from-replay-by-default)** | Replay a `@Tool` call's result without re-running its side effects |
| **[Embedding Replay](embeddings.md)** | `EmbeddingModel` calls cached independently of chat, vectors exact |
| **[Evaluator Testing](evaluator.md)** | Spring AI's own evaluators, deterministic in CI or live on demand |
| **[Streaming](streaming.md)** | `Flux` responses replayed chunk-for-chunk, tool calls included |

## Why this exists

**Testing an LLM-backed application is slow, expensive, and unrepeatable by default.**

- **Slow.** Testcontainers + Ollama means every `mvn test` re-runs full inference. In this
  project's own suite, a single cold model call takes **~47 seconds**; a two-turn
  tool-calling test takes **~54 seconds** end to end.
- **Expensive.** Against a hosted provider, every run of every test on every branch is
  billable tokens — multiplied by every developer and every CI job.
- **Unrepeatable.** The same prompt can return a different answer tomorrow. A test that
  asserts on model output is flaky by construction, and a red build tells you nothing
  about your code.
- **Untestable in CI.** No GPU, no model container, and putting a provider API key in a
  pipeline to run unit tests is a security problem, not a testing strategy.

**What this framework does about it:** the first run calls a real model and writes the
exchange to a JSON fixture you commit. Every run after that replays from disk in
**under a millisecond** — no container, no network, no tokens, no flakiness, and zero
changes to the code under test.

Spring AI's own production semantic cache doesn't solve this: it matches on similarity
thresholds — exactly backwards for a test, where a prompt that changed by one character
must produce a new fixture or a loud failure, never a "close enough" hit.

## What it costs to run a test

Measured on this project's own suites, not estimated — see the note for exactly how.

| | Real model | Replay |
|---|---|---|
| One call, cold (model load) | **~46.7 s** | — |
| One call, warm | **2.9 – 4.1 s** | **0.8 ms** (median) |
| Two-turn tool-calling interaction | **54.2 s** | **~30 ms** |
| Full 21-test example suite | needs Docker + a model | **11.5 s** |
| HTTP requests on replay | required | **0** (asserted by a request counter) |
| Token spend | per call, per run | **0** |
| Runs in CI with no GPU / no key | no | **yes** |

That's roughly a **3,500×** speedup on a warm single call (2.9 s → 0.8 ms), and the
difference between "needs a model" and "needs a file" for everything else.

??? note "How these numbers were measured"
    **Replay latency:** 200 timed iterations of a full
    `chatClient.prompt()...call().content()` against a committed fixture, after 20 warm-up
    iterations, in a real Spring Boot context — min 0.448 ms, median 0.819 ms, mean
    0.906 ms, p95 1.630 ms.

    **Real-model numbers:** `OllamaToolIsolationEndToEndTests` against
    Testcontainers-managed `llama3.2:1b` (pre-baked image, so no model download) —
    container start 2.50 s, first model turn 46.65 s (includes loading the model into
    RAM), second turn 0.40 s, whole test 54.24 s, and both turns replaying in ~30 ms.
    The warm single-call range comes from four recorded single-turn calls against a warm
    local Ollama (2.86 / 2.96 / 3.80 / 4.10 s).

    **Suite time:** Maven-reported `Total time` for the example project's 21 tests, all
    replay, no Docker.

    **Hardware:** Windows 11, Docker Desktop, CPU inference. A GPU would shrink the
    real-model column but not the replay one. Hosted-provider latency and cost were
    **not** measured here — no credentials, by design.

## How it compares

| | **spring-ai-test-tools** | WireMock / MockWebServer | Mockito |
|---|---|---|---|
| **Level it works at** | Spring AI's own abstractions (`ChatClient`, advisor chain) | Raw HTTP | Java objects |
| **Getting a response** | Recorded from a real model, or hand-authored | Hand-authored provider JSON | Hand-built `ChatResponse` graph |
| **Provider-specific coupling** | None — cache key is model + params + messages | Total — you maintain each provider's wire format | None, but you rebuild Spring AI's types by hand |
| **Switching providers** | Same fixture replays (verified across two SDKs) | Rewrite every stub | Rewrite every mock |
| **Streaming** | Chunk-for-chunk, recorded from a real stream | Hand-craft SSE frames | Hand-build a `Flux` |
| **Tool calling** | Recorded, replayed, with side-effect isolation | Model the whole multi-turn loop yourself | Hand-build `AssistantMessage.ToolCall` |
| **Structured output** | Target schema participates in the cache key | Invisible at HTTP level | Hand-build, schema never exercised |
| **Catches real integration bugs** | Yes — real response shapes | Partly — real bytes, wrong layer | No — you asserted your own mock |
| **Setup** | One property | A server, ports, request matchers | Per-scenario builder code |

!!! info "Where the alternatives are genuinely better"
    **WireMock and MockWebServer** are the right tool when the *HTTP layer itself* is what
    you're testing — retry/backoff policy, timeout handling, connection pooling, a proxy,
    a 429 with a `Retry-After` header, or a malformed body arriving mid-stream. This
    library deliberately sits above that layer and cannot see any of it.

    **Mockito** remains the right tool for everything that isn't a model call, and for a
    pure unit test wanting zero I/O and zero Spring context — which is exactly why
    [Stubbing](stub.md) exists rather than pretending record/replay covers it.

    And nothing here replaces a real integration test against a real provider before you
    ship. It replaces running one on *every* commit.

## What it looks like — choose per test

Record/replay is the primary mechanism; stubbing covers what it structurally can't
capture. All of these produce the same `ChatModel`/`EmbeddingModel`, so the choice is
purely which one a given test constructs — the application code under test never changes.

=== "Record/replay"

    ```yaml
    # application-test.yml -- the entire integration
    spring:
      ai:
        test:
          vcr:
            enabled: true
            mode: RECORD_OR_REPLAY   # REPLAY_ONLY in CI
    ```

    ```java
    @Test
    void answersAQuestionAboutTheOrder() {
        // First run records against a real model; every run after that
        // replays from disk in milliseconds -- no hand-authored answer.
        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
    ```

=== "Inline stub"

    ```java
    @Test
    void answersAQuestionAboutTheOrder() {
        ChatModel model = VcrStubs.chatModel()
            .respondingWith("Yes, shipped yesterday.")
            .build();
        ChatClient chatClient = ChatClient.builder(model).build();

        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).isEqualTo("Yes, shipped yesterday.");
    }
    ```

=== "File-sourced stub"

    ```java
    @Test
    void answersAQuestionAboutTheOrder() {
        ChatModel model = VcrStubs.chatModel()
            .respondingWithContentOf("responses/order-status.txt")
            .build();
        ChatClient chatClient = ChatClient.builder(model).build();

        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
    ```

See [Quick Start](quick-start.md) for the dependency coordinate, and
[Stubbing](stub.md#choosing-per-test-real-vs-stub-vs-recordreplay) for the full
per-test decision guide.

## Record & Replay is the core — Stubbing complements it

- **[Record & Replay](record-replay.md)** — the library's reason to exist: capture a real
  model's answer once, replay it automatically forever, without hand-authoring one.
- **[Stubbing](stub.md)** — explicit, WireMock-style, for what record/replay structurally
  can't capture: a timeout, a refusal, a specific `finishReason` no real provider will
  reproduce on demand, or a pure unit test that wants zero I/O and zero Spring context at
  all. Write the response, inline or from a file you name and manage. No hash, no lookup.

Both build on top of the same underlying pieces:

- **[Tool Calling](tool-calling.md)** and **[Structured Output](structured-output.md)** —
  cached with the same fidelity as plain text, verified against a real model.
- **[Streaming](streaming.md)** — chunk-for-chunk record/replay, not a single-chunk fake.
- **[Embeddings](embeddings.md)** — `EmbeddingModel` calls cache independently of chat.
- **[Assertions](assertions.md)** — fluent, deterministic checks on a response, working
  identically on a live call, a stub, or a replay, including embedding-backed semantic
  similarity.
- **[Evaluator](evaluator.md)** — Spring AI's own `RelevancyEvaluator`/
  `FactCheckingEvaluator`, made deterministic for free.

## How it's put together

One dependency, one property. Inside, each capability is its own package with its own
fixture type and its own cache directory — nothing is a special case of anything else:

```
io.github.rifatcakir.springai.testtools
├── recorder/
│   ├── advisor/     DeterministicVcrAdvisor  — CallAdvisor + StreamAdvisor, the interception point
│   ├── key/         VcrCacheKeyGenerator     — hand-assembled SHA-256 canonical form
│   ├── track/       VcrTrack                 — the .call() fixture format + mapper/store
│   ├── stream/      VcrStreamTrack           — the .stream() fixture format, raw chunk sequence
│   ├── embedding/   VcrEmbeddingModel        — EmbeddingModel interception (no advisor chain exists)
│   ├── tool/        VcrToolCallingManager    — tool-call isolation, own cassette
│   ├── junit/       @Vcr, @VcrTool           — per-test escape hatches
│   └── autoconfigure/                        — Spring Boot wiring, off unless enabled
├── assertions/      VcrAssertions            — fluent, deterministic response checks
└── stub/            VcrStubs                 — hand-authored ChatModel/EmbeddingModel
```

Three independent fixture families (`VcrTrack`, `VcrStreamTrack`, `VcrEmbeddingTrack`,
plus `VcrToolExecutionTrack` for tool calls), each with its own schema version, so one
capability's format can evolve without touching another's committed fixtures.

## Links

- [GitHub repository](https://github.com/rifatcakir/spring-ai-test-tools)
- [Worked examples in a standalone consumer project](https://github.com/rifatcakir/spring-ai-test-tools-example)
- [Apache-2.0 license](https://github.com/rifatcakir/spring-ai-test-tools/blob/main/LICENSE)
