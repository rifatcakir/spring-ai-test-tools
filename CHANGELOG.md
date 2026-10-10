# Changelog

All notable changes to `io.github.rifatcakir:spring-ai-test-tools` and, from 0.2.0, the
Cassette Server. Versions of the two are released together and kept equal.

## [0.2.0] - 2026-10-10

The Cassette Server: fixtures for a whole test environment on one server, while recording,
replay, hashing and tool isolation stay in the application. Design:
[`docs/SERVER-PRD.md`](docs/SERVER-PRD.md). Everything from 0.1.1 still works the same way
with the default file store; see "Upgrade notes" for the three things that behave
differently.

### Added

- **Remote store.** `spring.ai.test.vcr.store: remote` with `remote.url` and
  `remote.namespace` (plus optional `remote.token`, `remote.connect-timeout`,
  `remote.read-timeout`) keeps chat, streaming, tool-execution and embedding fixtures on a
  Cassette Server instead of local files. No code changes, no new runtime dependency (JDK
  `HttpClient` and the existing Jackson 3). An unreachable server or a `5xx` fails the call;
  it is never treated as a cache miss and never falls back to local files. One `WARN` line
  at startup says the remote store is on.
- **Namespace default mode.** With `store: remote` and no `spring.ai.test.vcr.mode` (or
  `embedding.mode`), the application follows its namespace's default, read from the server on
  every call, so an admin can switch an environment without a redeploy. A configured mode
  always wins.
- **Cassette Server** (`spring-ai-test-tools-server`, executable jar and Docker image; not on
  Maven Central): client API, admin API (namespaces, fixtures, delete, mode, recent misses,
  export as a zip in the library's file layout), bearer tokens, atomic writes,
  `/actuator/health`. Its storage layout is the library's own, so a namespace directory and a
  project's `src/test/resources` are interchangeable.
- `ChatFixtureStore`, `StreamFixtureStore`, `EmbeddingFixtureStore`, `ToolFixtureStore`: one
  store interface per fixture family, implemented by the existing file stores and the new
  remote ones; each gained `replace(...)`, used under `RECORD_ALWAYS`.
- `VcrFixtureRedactor#redactStream`, `#redactToolExecution`, `#redactEmbedding`.
- `DeterministicVcrAdvisor` and `VcrEmbeddingModel` constructors taking a
  `Supplier<VcrMode>`.
- `VcrCacheMissException(String hash, String expectedLocation, String canonicalRequest)` and
  `getExpectedLocation()`, for fixtures that are not local files.
- `fixture-contract/`: one library-written fixture per family, tested from both the library
  and the server so the format cannot drift between them.
- `examples/cassette-server-demo`: a runnable walkthrough with no API key and no model to
  install.

### Fixed

- **Redaction now runs on every write path.** `VcrFixtureRedactor` was applied to blocking
  chat fixtures only; streamed, tool-execution and embedding fixtures were written without
  it.
- Tool fixtures under `EXECUTE_REAL` with a redactor are no longer rewritten on every run (the
  "unchanged" check now compares the redacted fixture).

### Upgrade notes

1. **A chat redactor now also covers streamed fixtures.** `redactStream` defaults to running
   your `redact` over the stream's request side. If your `redact` changes the *response*, a
   streamed recording now fails with an `IllegalStateException` naming `redactStream`,
   instead of writing chunks that still contain the original text. Override `redactStream`
   to redact the chunks.
2. **`VcrProperties#getMode()` and `getEmbedding().getMode()` return `null` when no mode is
   set** (previously `RECORD_OR_REPLAY`). Behaviour is unchanged with the file store, where an
   unset mode still means `RECORD_OR_REPLAY`; only code reading the getter directly notices.
3. **Redactors that don't implement `redactToolExecution` or `redactEmbedding` are reported**
   with one `WARN` each when tool isolation or embedding caching starts. Nothing else changes;
   implement the method, even as a pass-through, to silence it.

## [0.1.1] - 2026-08

### Fixed

- Prompt-cache token usage (`cacheReadInputTokens`, `cacheWriteInputTokens`) is now recorded
  and replayed; it used to come back `null` on every replay. Chat fixture `schemaVersion` 6.
  See [`docs/R6-CACHE-TOKEN-USAGE-PRD.md`](docs/R6-CACHE-TOKEN-USAGE-PRD.md).

## [0.1.0] - 2026-08-03

First release: deterministic record and replay for `ChatClient` calls (blocking, streaming,
structured output), tool isolation, `EmbeddingModel` caching, `@Vcr` per-test modes, prompt
normalizers and fixture redactors, fixture validation, stubs, deterministic and semantic
assertions, and Spring AI evaluators on top of recorded calls.
