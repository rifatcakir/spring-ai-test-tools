# Assertions

Beyond record/replay, `io.github.rifatcakir.springai.testtools.assertions` gives you
fluent, AssertJ-idiomatic checks on top of a response — deterministic, with no model call
made by the assertion itself, and working identically whether the response came from a
live call or a replay:

```java
import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;

ChatResponse response = chatModel.call(prompt); // or chatClient...call().chatResponse()

assertThat(response)
    .hasToolCall("getOrderStatus", args -> assertThat(args).containsEntry("orderId", "ORD-4471"))
    .hasFinishReason("stop");

assertThat(response).hasJsonField("/estimatedDays", 9).extractingText().contains("Turkish Airlines");
```

- **Tool-call-shape assertions** — `hasToolCall(name)`, exact-argument matching
  (`hasToolCall(name, Map<String,Object>)`), partial/custom matching
  (`hasToolCall(name, Consumer<Map<String,Object>>)`), `hasNoToolCalls()`,
  `hasToolCallCount(int)`. Arguments are parsed before comparison, not string-matched.
- **`hasFinishReason(String)`** and **`extractingText()`** (bridges into an ordinary
  AssertJ string assertion).
- **Field-level JSON assertions** — `hasJsonField(jsonPointer)`,
  `hasJsonField(jsonPointer, expectedValue)`, `hasJsonFieldOfType(jsonPointer, JsonNodeType)`,
  addressed by RFC 6901 JSON Pointer (e.g. `"/carrier"` or `"/shipping/carrier"`).

!!! note "Tool-call assertions have one real scope limit"
    They see a tool call that is still *pending* on the response you're asserting on — a
    raw `ChatModel#call(Prompt)` result, for instance. A normal
    `chatClient.prompt()...tools(...).call()`'s built-in tool loop already resolves and
    executes the call internally before you ever see the final response, so there's
    nothing left for `hasToolCall(...)` to find on that final answer — check the model's
    own turn instead (see [Tool Calling](tool-calling.md)).

## Semantic assertions

"Is this response close enough in meaning to what I expected" — a plain string or JSON
assertion can't answer that, but an embedding comparison can. `usingEmbeddingModel(...)`
supplies the model, `isSemanticallySimilarTo(...)` compares:

```java
assertThat(response)
    .usingEmbeddingModel(embeddingModel) // pass a Recorder-backed EmbeddingModel -- see Embeddings
    .isSemanticallySimilarTo("Paris is the capital city of France.");

assertThat(response).usingEmbeddingModel(embeddingModel)
    .isSemanticallySimilarToAnyOf(List.of("shipped", "on its way", "out for delivery"), 0.8);
```

Both embedding calls this makes — the response text's and the expected text's — go
through the model you supply exactly like any other caller would use it. Pass a
Recorder-backed `EmbeddingModel` (see [Embeddings](embeddings.md)) and both are cached and
replayed for free, with zero additional network calls on a second identical assertion.

Cosine similarity is computed directly, no dependency added.
`isSemanticallySimilarTo(expected)` uses a default threshold of `0.7`;
`isSemanticallySimilarTo(expected, threshold)` takes an explicit one.

!!! tip "The default threshold is a starting point, not a universal constant"
    Confirmed empirically, not just argued: small models whose embeddings come from an
    LLM's own hidden states (rather than a model purpose-trained for embedding
    separation) compress similarity scores into a narrow, uniformly-high range — real
    measurements against `llama3.2:1b` showed genuine paraphrases at 0.93–0.95 but
    unrelated sentences at 0.66–0.72, high enough that `0.7` doesn't reliably separate
    them for that specific model. Observe your own model's score distribution and pick an
    explicit threshold accordingly.

This is an *assertion*, not the semantic *matching* this library permanently rejects for
cache-key resolution (see [Record & Replay](record-replay.md)): it runs strictly after
record/replay has already resolved the response by exact hash, and never influences which
fixture is served.
