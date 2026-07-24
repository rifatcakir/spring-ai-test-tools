# Spring AI Tests

Test Spring AI `ChatClient`/`EmbeddingModel` code without hitting a real model every run.
The core of it is **record/replay**: the first call reaches a real model and writes the
exchange to a fixture, and every call after that replays it — no container, no network,
no tokens, and no answer to hand-author yourself. For what record/replay structurally
can't capture — a timeout, a refusal, a specific `finishReason` no real provider will
reproduce on demand — reach for an explicit **stub** instead, written inline or loaded
from a file you name and manage, WireMock-style, no hash, no lookup.

## The problem

You're writing a test for code that calls a Spring AI `ChatClient`. You have three bad
options:

1. **Mock `ChatModel` with Mockito.** You end up hand-building a `ChatResponse` from
   scratch for every scenario — the exact boilerplate this library's Stub layer exists to
   remove — and the mock never catches a real integration bug.
2. **Stand up WireMock and replay raw HTTP.** Now you're maintaining JSON bodies shaped
   like your provider's wire protocol, at the wrong abstraction level entirely — tool
   calls and structured output don't exist yet at the HTTP layer WireMock operates at.
3. **Call a real model, every run.** Testcontainers + Ollama means every `mvn test`
   re-runs full inference. In CI there's no GPU, and a hosted provider means flakiness,
   token spend, and a credential in the pipeline.

Spring AI's own production semantic cache doesn't help either: it matches on similarity
thresholds — exactly backwards for a test, where a prompt that changed by one character
should produce a new fixture or a loud failure, never a "close enough" hit.

## The fix — record/replay first, stub for what it can't capture

All (four, counting file-sourced stubs separately) produce the same
`ChatModel`/`EmbeddingModel`. The choice is purely which one gets constructed in the test
itself — the application code under test never changes.

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

## Links

- [GitHub repository](https://github.com/rifatcakir/spring-ai-test-tools)
- [Worked examples in a standalone consumer project](https://github.com/rifatcakir/spring-ai-test-tools-example)
- [Apache-2.0 license](https://github.com/rifatcakir/spring-ai-test-tools/blob/main/LICENSE)
