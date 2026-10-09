# Spring AI Production Readiness Checklist

For teams taking LLM features from pilot to production on Spring Boot 4 and Spring AI 2.0.
32 checks in six areas. Tick what is true for your system today.

Maintained by [Rıfat Çakır](https://github.com/rifatcakir), author of [spring-ai-test-tools](https://github.com/rifatcakir/spring-ai-test-tools).

## 1. Platform and upgrade path

- [ ] You know your support dates. Spring Boot 3.5 and Spring Framework 6.2 left open-source support on 30 June 2026; Spring Boot 4.0's window closes at the end of 2026.
- [ ] Spring AI comes from the BOM, pinned to one version across services.
- [ ] The move from Spring AI 1.x to 2.0 is planned as a project. Boot 4, Jackson 3, immutable option builders and tool calling through `ToolCallingAdvisor` all change code.
- [ ] Model name and version are pinned in config and logged with every response.
- [ ] Provider settings differ per environment and none of them live in code.

## 2. Deterministic tests

- [ ] Unit and slice tests never call a live model.
- [ ] Model interactions are recorded once and replayed in CI. CI runs in replay-only mode, so a missing recording fails the build instead of calling the model.
- [ ] Replayed tool calls never run real side effects: no emails sent, no rows written.
- [ ] Structured output is tested against its target type, including malformed responses.
- [ ] Streaming responses are tested chunk by chunk.
- [ ] One real integration test against the provider runs before each release, not on every commit.
- [ ] The unit-test pipeline holds no provider API keys.

## 3. Evaluation

- [ ] A golden set of real user questions exists, with the expected outcome for each.
- [ ] Assertions pin the decision, not the wording: which action, which category, which source.
- [ ] Relevance and factuality are checked automatically, for example with Spring AI's `RelevancyEvaluator` and `FactCheckingEvaluator`.
- [ ] The eval set covers ambiguous inputs, tool misuse and authorization edge cases.
- [ ] A scheduled drift check runs the evals against the live model and alerts when scores drop.
- [ ] No prompt or model change ships without an eval run.

## 4. Tools and agents

- [ ] Each tool has the narrowest permissions it needs and checks the calling user's rights.
- [ ] Tools with side effects are idempotent or guarded against repeats.
- [ ] Irreversible actions wait for a human to confirm.
- [ ] Tool-calling loops have hard limits on iterations, time and tokens.
- [ ] MCP servers you expose or call are authenticated with OAuth 2.0 or API keys.

## 5. Guardrails and data

- [ ] Personal data is masked before it reaches a prompt, unless your provider contract covers it.
- [ ] Retrieved documents and tool results are treated as data, never as instructions.
- [ ] Model output is validated before use; invalid output triggers a retry or a safe fallback.
- [ ] Users get a clear fallback when the model refuses, times out or the provider is down.

## 6. Observability and cost

- [ ] Every model and tool call emits traces and metrics through Micrometer and OpenTelemetry.
- [ ] Token use and cost are visible per feature, not only per account.
- [ ] Each call has a latency budget, a timeout and handling for HTTP 429.
- [ ] An audit trail links each answer to its prompt version, model version and sources.
- [ ] The feature has a named owner and a classification under your AI policy and the EU AI Act.

---

### How to read your score

| Checks ticked | Stage | What it usually means |
| --- | --- | --- |
| 0–12 | Pilot | Works in a demo. Expect surprises in production. |
| 13–25 | Hardening | The basics are there. Close the gaps in evaluation and tools first. |
| 26–32 | Production-ready | You can change prompts and models without guessing. |

Found a gap I should add? Open an issue on GitHub or message me on [LinkedIn](https://www.linkedin.com/in/rifatcakir/).
