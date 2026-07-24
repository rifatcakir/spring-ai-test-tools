# Stubbing

Record/replay stays the headline: it proves a prompt gets a real answer from a real
model. `io.github.rifatcakir.springai.testtools.stub` exists for the opposite need — a
scenario no real provider will reliably reproduce on demand (a timeout, a refusal, a
malformed response, a specific finish reason like `"length"`), or a pure unit test that
wants zero I/O and zero Spring context. `VcrStubs` builds a plain `ChatModel`/
`EmbeddingModel` for exactly those cases — no fixture, no cache, no Spring wiring:

```java
ChatModel model = VcrStubs.chatModel().respondingWith("Yes.").build();

ChatModel model = VcrStubs.chatModel()
    .withToolCall("getWeather", "{\"city\":\"Ankara\"}")
    .build(); // finish reason auto-defaults to "TOOL_CALLS", no need to say so separately

ChatModel model = VcrStubs.chatModel().withFinishReason("length").build();

ChatModel model = VcrStubs.chatModel().failingWith(new RuntimeException("timeout")).build();

EmbeddingModel embeddingModel = VcrStubs.embeddingModel().respondingWith(new float[] { 0.1f, 0.2f }).build();
```

Every builder method is optional — `build()` with nothing configured still returns a
valid, empty-text response, never `null`. Pass the built model straight to
`ChatClient.builder(stub)`; no autoconfiguration, no `spring.ai.test.vcr.stub.*` property,
no Spring context needed at all.

## Testing error handling without waiting for a real timeout

```java
@Test
void handlesAModelTimeoutGracefully() {
    ChatModel model = VcrStubs.chatModel()
        .failingWith(new RuntimeException("Ollama request timed out"))
        .build();
    ChatClient chatClient = ChatClient.builder(model).build();

    assertThatThrownBy(() -> chatClient.prompt().user("anything").call().content())
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Ollama request timed out");
}
```

## Why this is deliberately narrower than a general-purpose mocking framework

A stub always answers the same way, for any prompt — there is no request-matching or
per-prompt routing table, on purpose. A test that needs two different answers builds two
stub instances, exactly the pattern this project's own unit tests already use for a
hand-rolled fake `ChatModel`.

Streaming stubs (`Flux<ChatResponse>`) are not built yet: a stub's default `.stream()`
behaves exactly like a real non-streaming `ChatModel` does — it throws
`UnsupportedOperationException("streaming is not supported")` — which is a correct,
self-consistent default, not a missing feature.
