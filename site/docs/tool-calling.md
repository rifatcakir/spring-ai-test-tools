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

The cache key is sensitive to which tool was called, with what arguments, and what that
tool responded with — two different tool calls, or two different tool results, are told
apart even inside conversation history under `INSIDE_TOOL_LOOP`, where each model turn
gets its own fixture. See [What busts the cache](record-replay.md#what-busts-the-cache)
for the full list.

For asserting on a still-pending tool call directly (before Spring AI's built-in tool
loop resolves it), see [Assertions](assertions.md).
