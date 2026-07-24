# Tool Calling

Spring AI 2.0 moved the tool-calling loop into the advisor chain as `ToolCallingAdvisor`
(order `HIGHEST_PRECEDENCE + 300`). Where the record/replay advisor sits relative to it
decides what a fixture contains:

```yaml
spring.ai.test.vcr.scope: OUTSIDE_TOOL_LOOP   # default
```

- **`OUTSIDE_TOOL_LOOP`** — one fixture per interaction, holding the final answer.
  Fastest. On a hit the loop never runs, so your `@Tool` methods are never invoked. A
  test asserting a tool's side effect will fail on replay.
- **`INSIDE_TOOL_LOOP`** — one fixture per model turn. Tool-call requests replay from
  disk while real `@Tool` methods still execute each iteration. Use this for side-effect
  assertions.

```java
@Test
void testCalledTheRealToolOnEveryReplay() {
    // With scope=INSIDE_TOOL_LOOP, this @Tool method actually runs on every
    // test run -- even the ones where the model's own turns replay from disk.
    String answer = chatClient.prompt()
        .user("What's the weather in Ankara? Use the tool to find out.")
        .tools(weatherTool)
        .call()
        .content();

    assertThat(weatherTool.invocations).hasValue(1);
}
```

Verified against a real model, not just designed: a two-turn tool-calling round trip (the
model calls a tool, the real `@Tool` method runs, the result goes back, the model
answers) records two fixtures under `INSIDE_TOOL_LOOP`, replays both with zero further
network calls, and still re-invokes the real `@Tool` method on replay, exactly as
documented above.

## ⚠️ Tool side-effects on replay

**Under `INSIDE_TOOL_LOOP`, your real `@Tool` method runs on every replay, not just the
first live call.** That's by design — it's what makes side-effect assertions possible at
all — but it means exactly what it says: if the tool writes to a database, calls an
external API, sends an email, or does anything else with a real-world effect, **that
effect happens again, every single time the test runs** — on your machine, on a
teammate's, in CI, forever, not just once at recording time. A fixture only replaces the
*model call*; it has no opinion about what your own tool code does when Spring AI's tool
loop invokes it.

!!! danger "This is a real bug-report magnet — read it before you pick a scope"
    - A `@Tool` method that only computes and returns a value (a lookup, a calculation) is
      fine under `INSIDE_TOOL_LOOP` — re-running it on every replay is exactly the point.
    - A `@Tool` method with a real side effect (writes a row, POSTs to a third-party API,
      sends a notification) will perform that side effect on **every test run**, not once,
      for as long as `INSIDE_TOOL_LOOP` is in effect.
    - If that's not what you want, either use `OUTSIDE_TOOL_LOOP` (the default — the tool
      never runs on a replay at all, only on the first live call that produces the fixture),
      or make the tool itself idempotent/safe to re-run (write to a test double, guard
      against duplicate side effects, point it at a sandboxed target) — the same discipline
      any test re-running against a real dependency already needs.

This is not a bug — it's the literal, documented contract of `INSIDE_TOOL_LOOP` — but it's
exactly the kind of thing that reads as a bug report if it's discovered by surprise
instead of read here first.

The cache key is sensitive to which tool was called, with what arguments, and what that
tool responded with — two different tool calls, or two different tool results, are told
apart even inside conversation history under `INSIDE_TOOL_LOOP`, where each model turn
gets its own fixture. See [What busts the cache](record-replay.md#what-busts-the-cache)
for the full list.

For asserting on a still-pending tool call directly (before Spring AI's built-in tool
loop resolves it), see [Assertions](assertions.md).
