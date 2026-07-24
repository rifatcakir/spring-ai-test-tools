# Configuration Reference

## Chat (`ChatClient`)

Every property is under the `spring.ai.test.vcr` prefix:

| Property | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Whether to attach the advisor at all. Off unless explicitly enabled — a library that silently starts caching model responses is a library that silently makes a production build pass for the wrong reason. |
| `mode` | `VcrMode` | `RECORD_OR_REPLAY` | Record-and-replay strategy — see [Modes](record-replay.md#modes). |
| `scope` | `VcrScope` | `OUTSIDE_TOOL_LOOP` | Where the advisor sits relative to tool calling — see [Tool Calling](tool-calling.md). |
| `cache-directory` | `String` | `src/test/resources/llm-cache` | Where fixtures are read from and written to. Meant to be committed to version control. |
| `order` | `Integer` | derived from `scope` | Explicit advisor order. Only needed to interleave with other custom advisors at a specific position. |

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
