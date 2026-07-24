# Streaming

`ChatClient...stream()` records and replays too — chunk-for-chunk, not as a single-chunk
fake standing in for a real stream:

```java
Flux<String> tokens = chatClient.prompt().user(prompt).stream().content();
```

The first call reaches the real model and records every chunk the live
`Flux<ChatResponse>` emitted, in order. The identical second call replays the exact same
chunk sequence — same count, same order, same per-chunk text/finish-reason/tool-call
content — with zero network calls.

## No artificial delay on replay

A fixture replays as fast as Reactor's scheduler processes it — deterministic tests
should never depend on wall-clock timing, so there is no attempt to reproduce the
original inter-chunk delay.

## Streamed tool calls are supported, with no extra configuration

Empirically, against real Ollama, a genuine tool call always arrives whole — id, name,
and the complete arguments — in a single chunk, never fragmented across multiple chunks,
so storing the raw chunk sequence verbatim already replays a streamed tool call
faithfully. `VcrScope.INSIDE_TOOL_LOOP`/`OUTSIDE_TOOL_LOOP` (see [Tool Calling](tool-calling.md))
apply to streaming exactly as they do to `.call()` — the same advisor, and the same
shared order, governs both chains.

## The fixture is its own type

`VcrStreamTrack` is a new, independent fixture type — not a field bolted onto the
`.call()` fixture shape — so the two fixture families evolve separately. Alongside the
raw chunk list, it stores a computed aggregate text (and aggregate finish-reason/tool-call
summary) purely for PR reviewability — never read back for replay — so a reviewer sees
the final answer at a glance instead of having to mentally concatenate small chunk
fragments.

Verified against a real model, not just designed: both a plain-text stream and a genuine
streamed tool-calling round trip record and replay chunk-for-chunk with zero additional
HTTP requests.
