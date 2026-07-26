# Record & Replay

The option for capturing a realistic answer automatically, without hand-authoring one:
the first call reaches a real model and writes the exchange to a fixture; every call
after that replays it. Prefer to write the response yourself instead? See
[Stubbing](stub.md).

## Exact-match caching, always

One SHA-256 hash per canonical request. A prompt that changes by a single character
misses and re-records; it never returns a "close enough" answer from a different prompt.
This is the opposite trade-off from Spring AI's own production-facing semantic cache on
purpose — a test needs a prompt regression to be *loud*, never silently absorbed by a
similarity threshold.

Fixtures are pretty-printed JSON, one file per request hash, meant to be read in a pull
request — a fixture diff is a prompt regression check. See
[What busts the cache](#what-busts-the-cache) below for exactly what participates in that
hash.

## ⚠️ The most common gotcha: dynamic values in your prompt

Exact-match hashing has one sharp edge, and it's the thing most people trip over first:
if your prompt embeds anything that's different on every run — the current timestamp, a
freshly generated UUID, a random request ID — the hash is different every run too. That
means a permanent cache miss, not a replay: the fixture directory fills up with one file
per run instead of settling on one.

```java
.user("Today is " + LocalDate.now() + ". Summarise the backlog.")
```

The fix is a `VcrPromptNormalizer`, applied before hashing, which collapses that noise
into a stable placeholder instead of eliminating it — the model still sees the real,
unmodified value:

```java
@Bean
VcrPromptNormalizer ignoreVolatileValues() {
    return RegexPromptNormalizer.ISO_DATE
        .andThen(RegexPromptNormalizer.UUID);
}
```

Built in: `ISO_DATE`, `ISO_DATE_TIME`, `UUID`, `EPOCH_MILLIS`. See
[Prompt Normalizer & Redactor](prompt-normalizer-and-redactor.md) for the full mechanism,
including why this is a genuinely different tool from a fixture redactor even though the
two sound similar.

## One test, many fixtures

The cache key is per **request**, not per test method or test class. A single test that
makes several distinct `ChatClient` calls records — and later replays — one fixture per
distinct prompt, each independently:

```java
@Test
void handlesTwoDifferentQuestions() {
    String weather = chatClient.prompt().user("What's the weather in Ankara?").call().content();
    String status  = chatClient.prompt().user("What's the status of order ORD-4471?").call().content();

    assertThat(weather).contains("sunny");
    assertThat(status).contains("shipped");
}
```

First run: two cache misses, two real model calls, two fixtures written — one hash per
prompt. Every run after: two cache hits, each replaying its own recorded answer, never the
other one's. There is no grouping by test method or test class anywhere in this design;
the hash only ever depends on what is actually being asked.

## Modes

| Mode | Behaviour |
|---|---|
| `RECORD_OR_REPLAY` | Replay if a fixture exists, otherwise call the model and record. **Default.** |
| `REPLAY_ONLY` | Replay if a fixture exists, otherwise throw. **Use in CI.** |
| `RECORD_ALWAYS` | Ignore fixtures, call the model, overwrite. Re-recording only — never CI. |
| `BYPASS` | No reads, no writes. Straight to the model. |

Fixtures are stored one JSON file per request hash rather than one file holding many
ordered interactions, so "record what's missing" and "record everything from scratch" only
ever differ in whether an existing file gets overwritten — exactly the difference between
`RECORD_OR_REPLAY` and `RECORD_ALWAYS`.

## Escaping `REPLAY_ONLY` for one test

CI sealing the whole suite is the point — right up until one test legitimately needs a
live call anyway: a smoke test against a real provider, or an assertion on something a
fixture deliberately drops (a provider-native usage object, say). `@Vcr` lets that one
test opt out without weakening the seal for every other test in the same run:

```java
@Test
@Vcr(mode = VcrMode.BYPASS)
void assertsOnProviderNativeUsage() {
    // reaches the real model even though the rest of this CI run is REPLAY_ONLY
}
```

A method-level `@Vcr` overrides a class-level one; a test with no `@Vcr` anywhere runs
under whatever mode the advisor was actually configured with. The override is
thread-scoped and cleared automatically once the test completes, so it cannot leak into
the next test or silently re-enable network calls for the rest of the suite the way a
shared exempt-list property could.

## What busts the cache

Any of these changes the SHA-256 and forces a re-record:

- message text or role, and their order
- model, temperature, topP, topK, maxTokens, penalties, stop sequences
- tool name, description or JSON input schema
- which tool a model turn called, with what arguments, and what that tool responded
  with — the hash tells two different tool calls, or two different tool results, apart
  even inside conversation history under `INSIDE_TOOL_LOOP` (see [Tool Calling](tool-calling.md))
- an `entity()` call's target type — its format instructions and JSON schema participate
  in the hash, so two different structured-output types sharing the same prompt text
  always record and replay as their own separate fixtures (see [Structured Output](structured-output.md))

That makes fixtures a prompt regression check. If a teammate reshapes a system prompt, CI
fails with the exact canonical request that changed rather than a silently different
answer.

## Cross-platform fixtures

Line endings inside a tool's input schema or an `entity()` call's format
instructions/JSON schema are normalized before hashing, so a fixture recorded on Windows
replays identically on a Linux or macOS CI runner.

## Providers

Interception happens at the `ChatClient` advisor layer, above any provider-specific HTTP
client — the cache key is built from `ChatOptions` and message content alone, never from
which `ChatModel` implementation or wire protocol is in use. Switching implementations
doesn't require re-recording fixtures, as long as the model name and parameters stay the
same: a fixture is filed under what would be sent to a model, not under which Java class
sent it.

Verified with two genuinely different implementations, not assumed from one:
`OllamaChatModel` (`spring-ai-ollama`'s native, `RestClient`-based client) and
`OpenAiChatModel` (built on the official OpenAI Java SDK — an entirely different HTTP
stack) both record and replay correctly on their own, and a fixture recorded through the
native Ollama client replays identically through the OpenAI-SDK client too, at zero
additional network cost.

## Secrets

Interception happens at the advisor layer, above HTTP. No `Authorization` header, bearer
token, or API key ever reaches a fixture — there is nothing to filter, and no
header-scrubbing step to remember before committing one.

Prompt *content* is another matter: if your prompts carry PII, redact it — see
[Prompt Normalizer & Redactor](prompt-normalizer-and-redactor.md).

## Limitations

- **`ChatClient` and `EmbeddingModel` only.** Image, audio, and moderation models do not
  pass through either mechanism and are not cached.
- **Lossy by design.** Provider-native usage objects and non-portable metadata are
  dropped. If a test must assert on those, run it in `BYPASS`.
- **A fixture freezes one sample, not the model's behaviour.** If a prompt is recorded at
  `temperature > 0` (or with any other source of sampling variance), the fixture holds
  exactly one draw from that distribution. Replaying it makes the test deterministic —
  that is the entire point — but it does not mean the underlying model call is
  deterministic in production. If a test's purpose is to catch output *variance* itself,
  VCR replay is the wrong tool for it — run that one in `BYPASS`.
- **Tool isolation only reaches a Spring-managed `ToolCallingManager` bean.** A
  `ChatClient.builder(model)` built outside a Spring context never creates that bean, so
  a real `@Tool` method runs exactly as it always has there, regardless of
  `VcrToolMode` — see [Tool calling](tool-calling.md).
- **`EXECUTE_REAL` re-runs real `@Tool` side effects on every replay, by design** — the
  explicit opt-in for asserting a tool actually ran; not the default.
- **Committed fixtures can bloat the repo for large-context prompts.** A RAG pipeline
  embedding a large retrieved document, or any prompt carrying a big payload, gets
  committed to git verbatim inside its fixture. That is the direct cost of design rule #5
  (fixtures are pretty-printed and reviewed in a pull request, not compressed or stored
  externally) — a deliberate trade-off, not an oversight, but a real one for large-context
  use cases. See `docs/ROADMAP.md`'s v0.2 section for what a future large-fixture mode
  might look like.
- **Re-recording is manual, and orphaned fixtures are not cleaned up automatically.**
  `RECORD_ALWAYS` overwrites every fixture a test run actually touches, but if a prompt
  changes enough that its old hash is never looked up again, that file is simply left on
  disk — nothing today detects or prunes it. There is no bulk re-record or
  orphaned-fixture-pruning CLI/Maven task yet; see `docs/ROADMAP.md`'s v0.2 section.

See [Configuration Reference](configuration.md) for every property this library exposes.
