# spring-ai-test-tools

Deterministic, file-based record-and-replay caching for Spring AI integration tests.

!!! info "Not an official Spring project"
    This is an independent, community-maintained project. It is not affiliated with,
    endorsed by, or an official project of Broadcom, VMware, Spring, or Spring AI.
    "Spring" and "Spring AI" are trademarks of their respective owners; this library
    simply integrates with their public APIs.

## The problem

You're writing an integration test for code that calls a Spring AI `ChatClient`. You have
three bad options:

1. **Mock `ChatModel` with Mockito.** You end up hand-building a `ChatResponse` from
   scratch for every scenario, and the mock never catches a real integration bug — the
   wiring between your code and Spring AI's actual response shape is never exercised.
2. **Stand up WireMock and replay raw HTTP.** Now you're maintaining JSON bodies shaped
   like whatever your provider's wire protocol happens to look like this month, at the
   wrong abstraction level entirely — Spring AI's own `ChatClientResponse`, tool calls,
   and structured output don't exist yet at the HTTP layer WireMock operates at.
3. **Call a real model.** Testcontainers + Ollama means every `mvn test` re-runs full
   inference on your CPU — seconds per test, minutes per build. In CI there's no GPU, and
   calling a hosted provider means flakiness, token spend, and a credential in the
   pipeline environment.

Spring AI's own production-facing semantic cache doesn't help either: it matches on
similarity thresholds, which is exactly backwards for a test. A prompt that changed by one
character should produce a new fixture or a loud failure — never a "close enough" hit from
the *old* prompt's answer.

## The fix

Attach one advisor to your `ChatClient.Builder`. The first call reaches the real model and
writes the exchange to a JSON file; every call after that — in this run, and in every run
after this one, forever — replays it instead. No container, no network, no tokens, and
zero changes to the code under test.

=== "Before"

    ```java
    @Test
    void answersAQuestionAboutTheOrder() {
        // Needs a real Ollama container running, every single run.
        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
    ```

=== "After"

    ```yaml
    # application-test.yml — the entire integration
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
        // Identical test code. First run records against Ollama;
        // every run after that replays from disk in milliseconds.
        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
    ```

That's the whole story for the common case — see [Quick Start](quick-start.md) for the
dependency coordinate and a complete minimal example.

## What's built on top

Record/replay (the **Recorder** layer, above) is the foundation everything else here
depends on:

- **[Tool Calling](tool-calling.md)** and **[Structured Output](structured-output.md)** —
  cached with the same fidelity as plain text, verified against a real model.
- **[Streaming](streaming.md)** — chunk-for-chunk record/replay, not a single-chunk fake.
- **[Embeddings](embeddings.md)** — `EmbeddingModel` calls cache independently of chat.
- **[Assertions](assertions.md)** — fluent, deterministic checks on a response, including
  embedding-backed semantic similarity.
- **[Evaluator](evaluator.md)** — Spring AI's own `RelevancyEvaluator`/
  `FactCheckingEvaluator`, made deterministic for free.
- **[Stubbing](stub.md)** — programmatic canned responses for the error/edge scenarios
  record/replay structurally cannot capture.

## Links

- [GitHub repository](https://github.com/rifatcakir/spring-ai-test-tools)
- [Worked examples in a standalone consumer project](https://github.com/rifatcakir/spring-ai-test-tools-example)
- [Apache-2.0 license](https://github.com/rifatcakir/spring-ai-test-tools/blob/main/LICENSE)
