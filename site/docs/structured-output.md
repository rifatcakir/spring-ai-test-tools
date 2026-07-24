# Structured Output

`ChatClient...call().entity(MyDto.class)` (Spring AI's `BeanOutputConverter`-based
structured output) round-trips through record/replay — verified against a real model, not
assumed:

```java
record CityWeather(String city, Integer temperatureCelsius) {}

CityWeather weather = chatClient.prompt().user(prompt).call().entity(CityWeather.class);
```

POJO conversion happens entirely client-side, after the advisor chain returns, so a
replayed response converts to the same object a live call would have produced — no extra
configuration needed.

## The cache key is sensitive to the target type, not just the prompt

An `entity()` call's target type — its format instructions and JSON schema — participates
in the hash right alongside the message content. Two `entity()` calls that share
identical prompt text but ask for different target types always record and replay as two
separate fixtures — a schema change is exactly the kind of thing that should bust the
cache. See [What busts the cache](record-replay.md#what-busts-the-cache).

## Two ways to get structured output, both cached the same way

- **Text-instruction-based** (the default) — the model is asked, in plain language, to
  produce JSON matching a schema. Works with any provider, but asks a smaller model to
  follow written instructions closely.
- **Provider-native** (`entity(Class, spec -> spec.useProviderStructuredOutput())`) — for
  providers that support it (Ollama included), the schema constrains generation at the
  token level instead of relying on the model to read and follow instructions. More
  reliable for smaller models.

See the
[worked example in the example project](https://github.com/rifatcakir/spring-ai-test-tools-example/blob/main/src/test/java/com/example/vcrdemo/StructuredOutputRecordReplayTest.java)
for provider-native structured output end to end.
