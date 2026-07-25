# Tool Calling

Spring AI 2.0 moved the tool-calling loop into the advisor chain as `ToolCallingAdvisor`
(order `HIGHEST_PRECEDENCE + 300`). Where the record/replay advisor sits relative to it
decides what a fixture contains:

```yaml
spring.ai.test.vcr.scope: OUTSIDE_TOOL_LOOP   # default
```

- **`OUTSIDE_TOOL_LOOP`** — one fixture per interaction, holding the final answer.
  Fastest. On a hit the loop never runs at all, so a `@Tool` method never runs either.
- **`INSIDE_TOOL_LOOP`** — one fixture per model turn. Tool-call requests replay from
  disk turn by turn. What happens to the tool invocation *itself* on a hit is governed by
  a second, independent setting — see below.

```java
@Test
void testCalledTheRealToolOnEveryReplay() {
    // With scope=INSIDE_TOOL_LOOP, the model's own turns replay from disk on a hit.
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
answers) records two fixtures under `INSIDE_TOOL_LOOP` and replays both with zero further
network calls.

## ✅ Tool side-effects are isolated from replay by default

Earlier versions of this library (and this page) warned that a real `@Tool` method with a
side effect — a database write, an outbound API call, an email — would re-run on *every*
replay under `INSIDE_TOOL_LOOP`, forever. **That is no longer the default.** A second,
independent axis, `VcrToolMode`, governs whether an individual tool invocation is
isolated from replay or allowed to run for real:

```yaml
spring.ai.test.vcr.tool.mode: REPLAY_FROM_CASSETTE   # default -- full isolation
```

- **`REPLAY_FROM_CASSETTE`** (default) — on a cassette hit, the tool's recorded
  arguments/result pair is returned directly. The real `@Tool` method's body **never
  executes**, not even once, not even under `INSIDE_TOOL_LOOP` re-running the surrounding
  model turns. A side-effecting tool fires at most once per distinct (tool name,
  arguments) pair, no matter how many times the suite re-runs.
- **`EXECUTE_REAL`** — the real tool runs on every call, exactly like this library's old
  default. Use this for a test that specifically wants to assert the real `@Tool` method
  was actually invoked, with the right arguments, the right number of times:

```java
@Test
@VcrTool(mode = VcrToolMode.EXECUTE_REAL)
void assertsTheRealToolRan() {
    // opts this one test out of isolation, without weakening it for every other test
    String answer = chatClient.prompt()
        .user("What's the weather in Ankara? Use the tool to find out.")
        .tools(weatherTool)
        .call()
        .content();

    assertThat(weatherTool.invocations).hasValue(1);
}
```

Verified against a real model, not just designed: a two-turn tool-calling round trip
records the tool invocation once on the live call, then replays it with the real
`@Tool` method invoked **zero** times — full isolation, not just a replayed model answer
— with zero additional HTTP requests either way.

!!! warning "The one thing isolation cannot reach"
    Isolation works by wrapping the `ToolCallingManager` Spring bean Spring AI's own
    autoconfiguration registers — the same bean this library's advisor mechanism already
    depends on a Spring context for. A `ChatClient.builder(model)` built directly, outside
    a Spring context (the same pattern [Stubbing](stub.md)'s fastest path uses on
    purpose), never creates that bean, so there is nothing to wrap: the real tool runs
    there exactly as it always has, regardless of `VcrToolMode`. Not a new limitation —
    the same Spring-context-only scope every other mechanism on this page already has.

The cache key for a tool invocation is its name plus the exact argument string the model
produced — not call order — so a tool called twice with different arguments (two
different cities, say) in the same conversation resolves to two separate, correct
fixtures, in their own cache directory (`spring.ai.test.vcr.tool.cache-directory`,
independent of the chat cache). See
[Configuration Reference](configuration.md) for both properties.

Separately, the model-turn cache key is sensitive to which tool was called, with what
arguments, and what that tool responded with — two different tool calls, or two
different tool results, are told apart even inside conversation history under
`INSIDE_TOOL_LOOP`, where each model turn gets its own fixture. See
[What busts the cache](record-replay.md#what-busts-the-cache) for the full list.

For asserting on a still-pending tool call directly (before Spring AI's built-in tool
loop resolves it), see [Assertions](assertions.md).
