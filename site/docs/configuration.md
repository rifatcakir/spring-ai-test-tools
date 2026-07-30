# Configuration Reference

## Compatibility

Tested against, and only against:

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring AI | 2.0.0 |

**Other versions are untested; compatibility is not guaranteed.** Spring AI's API surface
has moved fast release to release — much of what pre-2.0 tutorials and blog posts say
about advisor interfaces, `ChatClientCustomizer`, or `ToolCallingChatOptions` no longer
matches the 2.0.0 bytecode. Pinning to one verified combination is deliberate. If you're
on a different Spring AI or Spring Boot version, expect to hit real breakage before you
hit anything this library controls.

## Chat (`ChatClient`)

Every property is under the `spring.ai.test.vcr` prefix:

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Whether to attach the advisor at all. Off unless explicitly enabled — a library that silently starts caching model responses is a library that silently makes a production build pass for the wrong reason. |
| `mode` | `VcrMode` | `RECORD_OR_REPLAY` | Record-and-replay strategy — see [Modes](record-replay.md#modes). |
| `scope` | `VcrScope` | `OUTSIDE_TOOL_LOOP` | Where the advisor sits relative to tool calling — see [Tool Calling](tool-calling.md). |
| `cache-directory` | `String` | `src/test/resources/llm-cache` | Where fixtures are read from and written to. Meant to be committed to version control. |
| `order` | `Integer` | derived from `scope` | Explicit advisor order. Only needed to interleave with other custom advisors at a specific position. |
| `fixture-size-warn-threshold` | `DataSize` | `256KB` | Log a `WARN` when a written fixture reaches this size. Advisory only — nothing is refused, truncated or compressed. Applies to every fixture family. `0` disables it. |

## Tool calling isolation

Under `spring.ai.test.vcr.tool`, governed by the same top-level `enabled` flag as chat —
see [Tool Calling](tool-calling.md):

| Property | Type | Default | Description |
|---|---|---|---|
| `mode` | `VcrToolMode` | `REPLAY_FROM_CASSETTE` | Whether a cassette hit isolates a tool invocation from the real `@Tool` method (default — full isolation) or lets it run for real (`EXECUTE_REAL`). |
| `cache-directory` | `String` | `src/test/resources/llm-cache-tool` | A separate directory from the chat cache, by default. |

## Embeddings (`EmbeddingModel`)

Under `spring.ai.test.vcr.embedding`, independent of the chat properties above — see
[Embeddings](embeddings.md):

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Whether to wrap the `EmbeddingModel` bean at all. Independent of the top-level `spring.ai.test.vcr.enabled`. |
| `mode` | `VcrMode` | `RECORD_OR_REPLAY` | Same mode semantics as the chat advisor. |
| `cache-directory` | `String` | `src/test/resources/llm-cache-embedding` | A separate directory from the chat cache, by default. |

## Stubbing

`io.github.rifatcakir.springai.testtools.stub` has **no configuration properties at
all** — see [Stubbing](stub.md). It is a plain Java utility, built programmatically, with
no Spring autoconfiguration to enable or tune.
