# Embeddings

`EmbeddingModel` calls record and replay too, independently of chat — enable with
`spring.ai.test.vcr.embedding.enabled=true` (separate from, and off unless enabled
alongside, the top-level `spring.ai.test.vcr.enabled`):

```yaml
spring:
  ai:
    test:
      vcr:
        embedding:
          enabled: true
          mode: RECORD_OR_REPLAY
          cache-directory: src/test/resources/llm-cache-embedding
```

`EmbeddingModel` has no advisor chain the way `ChatClient` does, so interception wraps the
`EmbeddingModel` bean itself, transparently — `@Autowired EmbeddingModel` in your own code
is unchanged, and every entry point (`embed(String)`, `embed(List<String>)`,
`embedForResponse(List<String>)`) is covered, since they all route through the same
underlying call.

```java
@Autowired
private EmbeddingModel embeddingModel;

@Test
void replaysTheExactRecordedVector() {
    float[] first = this.embeddingModel.embed("Istanbul is the largest city in Turkey.");
    float[] second = this.embeddingModel.embed("Istanbul is the largest city in Turkey.");

    assertThat(second).isEqualTo(first); // exactly, not "same length"
}
```

A replayed vector is exactly — not "the same length as" — what was recorded.

## Why this exists on its own

This is the foundation [Assertions](assertions.md)' semantic similarity check depends on:
an assertion that computes cosine similarity against a reference answer needs its own
embedding call to be exactly this deterministic, or every CI run would make a live,
non-reproducible embedding call to check a "deterministic" test.
