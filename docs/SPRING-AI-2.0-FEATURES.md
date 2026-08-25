# Spring AI 2.0.0 GA — Feature Inventory

**Purpose:** a technical, source-verified inventory of what Spring AI 2.0.0 GA (12 June
2026) actually contains. This is inventory only — no compatibility or gap analysis
against this project. That analysis lives separately in `docs/SPRING-AI-2.0-COMPAT.md`
(a different work item) and is deliberately **not** duplicated or referenced for
conclusions here.

**Verification method:** every claim below comes from one or both of:

1. **Docs** — the official announcement, release notes, upgrade notes, and reference
   manual (URLs in each section and in [Sources](#sources)).
2. **Bytecode** — `spring-ai-*-2.0.0` jars pulled from Maven Central and inspected with
   `javap -p -constants` and `unzip -l`. Claims backed this way are marked
   **✅ bytecode-verified**, with the exact class and jar named. Everything else is
   **doc-only** — plausible and sourced, but not independently confirmed against a class
   file. Where the docs and the bytecode disagreed, both are reported, and the disagreement
   is called out (see [Corrections to source docs](#corrections-to-source-docs)).

Verification jars pulled from `repo1.maven.org/maven2/org/springframework/ai/`:
`spring-ai-model-2.0.0.jar`, `spring-ai-client-chat-2.0.0.jar`,
`spring-ai-commons-2.0.0.jar`, plus the `spring-ai-mcp-2.0.0.pom`.

---

## Table of contents

1. [Foundations](#1-foundations)
2. [Provider SDK consolidation](#2-provider-sdk-consolidation)
3. [ChatClient & Prompts](#3-chatclient--prompts)
4. [Advisor architecture](#4-advisor-architecture)
5. [Recursive advisors](#5-recursive-advisors)
6. [Tool calling & ToolCallingAdvisor](#6-tool-calling--toolcallingadvisor)
7. [ToolSearchToolCallingAdvisor (progressive tool disclosure)](#7-toolsearchtoolcallingadvisor-progressive-tool-disclosure)
8. [Structured output](#8-structured-output)
9. [Chat memory](#9-chat-memory)
10. [Usage / token accounting](#10-usage--token-accounting)
11. [Model Context Protocol (MCP)](#11-model-context-protocol-mcp)
12. [Model evaluation / testing](#12-model-evaluation--testing)
13. [Community extensions](#13-community-extensions)
14. [Breaking-change catalog (upgrade notes, condensed)](#14-breaking-change-catalog-upgrade-notes-condensed)
15. [Corrections to source docs](#corrections-to-source-docs)
16. [Sources](#sources)

---

## 1. Foundations

### Baseline
- Spring Boot **4.0/4.1** and Spring Framework **7.0** minimum. Release notes show a later
  dependency bump to **Spring Boot 4.1.0** (PR #6329) within the 2.0.x line.
- Java 17+ generally; this project pins Java 21 (see `CLAUDE.md`).

### Jackson 3
- Spring AI 2.0 moved from Jackson 2 (`com.fasterxml.jackson.databind.*`) to **Jackson 3**
  (`tools.jackson.*`). Jackson 3 annotations remain at their old package
  (`com.fasterxml.jackson.annotation.*`) — only `databind`/`core` moved.
- **New `JsonHelper`** — `org.springframework.ai.util.JsonHelper`, in `spring-ai-commons`.
  **✅ bytecode-verified**: `org/springframework/ai/util/JsonHelper.class` and
  `JacksonUtils.class` present in `spring-ai-commons-2.0.0.jar`. Two constructors per
  docs: no-arg (`JacksonUtils.getDefaultJsonMapper()`) and one taking a custom
  `tools.jackson.databind.json.JsonMapper`.
- `JsonParser` (`org.springframework.ai.util.json.JsonParser`) is **deprecated**; every
  method now delegates to `JsonHelper`.
- `ModelOptionsUtils` lost its JSON-handling methods entirely (`JSON_MAPPER`,
  `jsonToMap`, `jsonToObject`, `toJsonString`, `toJsonStringPrettyPrinter`,
  `getJsonSchema(...)`, `toUpperCaseTypeValues`) — replaced by `JacksonUtils` /
  `JsonHelper` / `JsonSchemaUtils`.
- `McpJsonParser` removed; replaced by `JsonHelper.convertToMap` /
  `JsonHelper.convertFromMap`.
- `StructuredOutputValidationAdvisor` internally holds a
  `tools.jackson.databind.json.JsonMapper` field and validates against a
  `com.networknt.schema.Schema` (the `networknt/json-schema-validator` library).
  **✅ bytecode-verified** via `javap` on
  `org/springframework/ai/chat/client/advisor/StructuredOutputValidationAdvisor.class`
  — not previously documented in the reference manual text; found only in bytecode.

### JSpecify null-safety
- The full codebase is annotated with **JSpecify** (`org.jspecify.annotations.Nullable` /
  `NonNull`) following the "null-safe applications with Spring Boot 4" convention —
  every unannotated type/parameter/return is implicitly non-null; `@Nullable` marks the
  exceptions. Release notes show a concrete example: PR #6388, *"Make
  `org.springframework.ai.image.observation` null-marked."* Doc-only for the sweep's
  completeness; the package-level `@NullMarked` convention itself is standard JSpecify/
  Spring Framework 7 practice.

### Options refactoring
Four related changes, confirmed in combination by docs + bytecode:

1. **Defaults live on the Options, not the Model or the `@ConfigurationProperties`
   class.** `ChatModel#getDefaultOptions()` is deprecated in favor of
   `ChatModel#getOptions()`.
2. **Options are built via builders and are immutable once built.**
   `ChatOptions#copy()` and `*Options#fromOptions()` are **removed**; the replacement is
   `mutate()`. **✅ bytecode-verified**:
   `org/springframework/ai/chat/prompt/ChatOptions.class` declares
   `abstract ChatOptions.Builder<?> mutate()` and a static `builder()`, with **no**
   `copy()` method present. Collections returned by getters are unmodifiable; mutating
   one throws `UnsupportedOperationException`.
3. **Reflection-free merging.** The `Builder` interface itself carries a
   `combineWith(ChatOptions.Builder<?>)` method. **✅ bytecode-verified**:
   `ChatOptions$Builder.class` declares
   `abstract B combineWith(ChatOptions.Builder<?>)` and `abstract B clone()` (the
   interface extends `java.lang.Cloneable`) — merging happens through typed builder
   methods, not `Field`/`Method` reflection or Jackson `ObjectMapper.convertValue`.
4. **Config property flattening.** The artificial `.options` segment is gone from
   property keys, e.g. `spring.ai.openai.embedding.options.model` →
   `spring.ai.openai.embedding.model`. Deprecated old-style properties are kept for a
   migration window.
5. **Renamed builder method**: `.N(value)` → `.n(value)` across `*Options` builders and
   properties classes (capitalization fix).

`StructuredOutputChatOptions` (`org.springframework.ai.model.tool`) is a new marker/
extension interface — **✅ bytecode-verified** —
`extends ChatOptions`, adds `getOutputSchema()`, and is what
`useProviderStructuredOutput()` type-checks against to decide whether a given model's
options support provider-native structured output (§8).

### Provider consolidation (core project scope)
Per the GA announcement, the **core** Spring AI project now maintains one variant per
provider rather than parallel "SDK" and "hand-rolled API" implementations:

| Provider | Status |
|---|---|
| OpenAI | Consolidated to 1 variant, built on the official `openai-java` SDK; OpenAI-compatible-API friendly |
| Anthropic | Consolidated to 1 variant, rebuilt on `com.anthropic:anthropic-java` |
| Amazon Bedrock | Unchanged tier-1 |
| Google GenAI | Consolidated to 1 implementation on the GenAI SDK |
| Mistral AI, DeepSeek, Ollama | Unchanged tier-1 |

Externally maintained (moved out of the core repo): **OCI Generative AI** (Oracle),
**Azure Cosmos DB** (Microsoft) vector store.

---

## 2. Provider SDK consolidation

### OpenAI → official `openai-java` SDK
- `spring-ai-openai` is rebuilt on the official SDK. Docs state migration is "seamless":
  properties, builders and options preserved; `extraBody` transparently maps to the
  SDK's `additionalBodyProperties`.
- `spring-ai-azure-openai` and `spring-ai-openai-sdk` modules (and their starters/
  autoconfigurations) are **removed**. Use `spring-ai-openai` and drop the `Azure`/`Sdk`
  naming from class references.
- Tool-calling **strict mode now defaults to `false`** (was `true`); optional parameters
  are excluded from the JSON Schema `required` array unless
  `OpenAiChatOptions.builder().strict(true)` is set explicitly.
- `OpenAiAudioSpeechModel.stream()` now emits real incremental audio chunks instead of
  buffering the whole response as one element.
- `TranscriptionModel` now extends `StreamingTranscriptionModel`; the default `stream()`
  returns `Flux.error(new UnsupportedOperationException())` unless overridden.

### Anthropic → `anthropic-java` SDK
- `spring-ai-anthropic` rebuilt on `com.anthropic:anthropic-java`. Transparent for
  `ChatClient` / `ChatModel.call(Prompt)` users; **breaking** for anyone who used the
  low-level `AnthropicApi` directly.
- **Removed**: `org.springframework.ai.anthropic.api.AnthropicApi` and all nested types;
  the `AnthropicChatModel(AnthropicApi, AnthropicChatOptions, …)` constructor. Use
  `AnthropicChatModel.builder()`.
- Cache-related classes moved out of the `.api` sub-package:
  `AnthropicCacheOptions`, `AnthropicCacheStrategy`, `AnthropicCacheTtl`,
  `CacheBreakpointTracker`, `CacheEligibilityResolver` now live directly under
  `org.springframework.ai.anthropic`; `CitationDocument` renamed to
  `AnthropicCitationDocument`.
- **Default `maxTokens` changed from 500 (Spring AI's old cost-limiting default) to 4096**
  (the SDK's own default). Set explicitly if the old ceiling is wanted:
  `AnthropicChatOptions.builder().maxTokens(500)`.
- Removed types `AnthropicCacheType`, `StreamHelper`, `metadata.AnthropicRateLimit` in
  favor of SDK equivalents (`CacheControlEphemeral`,
  `AsyncStreamResponse<RawMessageStreamEvent>`, `RateLimitException`).
- New chat options: thinking `Display.SUMMARIZED` / `Display.OMITTED`; service tier
  (`AnthropicServiceTier.AUTO` / `STANDARD_ONLY`, property
  `spring.ai.anthropic.chat.service-tier`); web search tool
  (`AnthropicWebSearchTool`, `spring.ai.anthropic.chat.web-search-tool.*`); inference
  geo pinning (`inferenceGeo("us"|"eu")`, `spring.ai.anthropic.chat.inference-geo`).
- A dedicated migration guide exists: `api/chat/anthropic-migration.html`.

### Google GenAI
- Consolidated to a single implementation on the GenAI SDK (release notes PR #6406,
  *"Update Google GenAI models"*).
- Embedding connection-details class relocated:
  `org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails` →
  `org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails`.

### Mistral AI
- Moderation `Categories`/`CategoryScores` restructured: `isDangerousAndCriminalContent()`
  and its builder counterpart removed, replaced by `isCriminal()`, `isDangerous()`, and a
  new `isJailbreaking()`. Moderation model bumped
  `mistral-moderation-2411` → `mistral-moderation-2603`.
- `ChatModel` enum: `MAGISTRAL_MEDIUM`, `MAGISTRAL_SMALL`, `DEVSTRAL`,
  `OPEN_MISTRAL_NEMO` constants removed. `MISTRAL_SMALL`/`MISTRAL_MEDIUM`/
  `MISTRAL_LARGE` re-point to `mistral-small-2603` / `mistral-medium-3-5` (both
  MEDIUM and LARGE alias to the same model now, for backward compatibility).

### Other provider/module removals
- `spring-ai-hanadb-store` — removed outright.
- `spring-ai-spring-cloud-bindings` — removed.
- `spring-ai-azure-cosmos-db-store` + its chat-memory-repository module — removed from
  the core repo; continued at `azurecosmosdb.github.io/spring-ai`.
- `spring-ai-oci-genai` — removed from the core repo; continued at
  `github.com/oracle/spring-cloud-oracle`.
- **MiniMax** dedicated support removed; MiniMax itself recommends routing through the
  Anthropic-compatible endpoint (`https://api.minimax.io/anthropic`) instead. Embeddings
  are no longer supported through Spring AI for MiniMax.

---

## 3. ChatClient & Prompts

`ChatClient` is the primary user-facing API; `ChatModel` is positioned as the low-level
building block underneath it (comparable to the Prompt-as-View / ChatModel-as-JDBC
framing the reference docs use).

### Construction
Standard DI pattern, autoconfigured prototype-scoped builder:
```java
public MyController(ChatClient.Builder chatClientBuilder) {
    this.chatClient = chatClientBuilder.build();
}
```

New **5-argument `ChatClient.builder()` overload** for manual multi-model wiring that
still preserves observability/customizer behavior:
```java
ChatClient.Builder builder = ChatClient.builder(chatModel,
    observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
    chatClientObservationConvention.getIfUnique(),
    advisorObservationConvention.getIfUnique(),
    toolCallingAdvisorBuilder.getIfAvailable());
```
Parameters: `ChatModel`, `ObservationRegistry`, nullable
`ChatClientObservationConvention`, nullable `AdvisorObservationConvention`, nullable
`ToolCallingAdvisor.Builder<?>` (the last param is new in 2.0 — it's how you swap in a
custom tool-calling advisor builder, e.g. `ToolSearchToolCallingAdvisor.builder()`, for
auto-registration).

### Fluent request building
```java
chatClient.prompt().user("...").call().content();      // build from scratch
chatClient.prompt(promptInstance).call().content();      // from a Prompt
chatClient.prompt("user content").call().content();      // string convenience
```
Template rendering defaults to `StTemplateRenderer` (`{}` delimiters, StringTemplate
under the hood), swappable per-request or on the builder.

### Response extraction
`.call()` → `.content()` (String), `.chatResponse()` (`ChatResponse`),
`.chatClientResponse()` (`ChatClientResponse`, carries advisor execution context),
`.entity(Class)`/`.entity(ParameterizedTypeReference)` (typed), `.responseEntity(...)`
(typed + `ChatResponse` together). `.stream()` mirrors `.content()`/`.chatResponse()`/
`.chatClientResponse()` but returns `Flux<...>`; `.entity()` is **not** available on
`.stream()`.

### `EntityParamSpec` — the two structured-output reliability switches
```java
chatClient.prompt().user("...").call()
    .entity(ActorFilms.class, spec -> spec
        .useProviderStructuredOutput()
        .validateSchema());
```
See §8 for what each switch does.

### Builder defaults vs. per-call overrides
Every `default*` builder method (`defaultSystem`, `defaultUser`, `defaultOptions`,
`defaultTools`, `defaultToolContext`, `defaultTemplateRenderer`, `defaultAdvisors`) has a
non-default per-call counterpart (`system`, `user`, `options`, `tools`, `toolContext`,
`templateRenderer`, `advisors`) that merges with/overrides it at request time.

**`.options()`/`.defaultOptions()` now require a `ChatOptions.Builder`, not a built
instance** — generic constraint `<B extends ChatOptions.Builder<?>>`. The builder is
merged with the model's own defaults *before* the advisor chain runs. Note the asymmetry:
`ChatModel` (the low-level API) still requires a fully-built `ChatOptions` — no merging
happens at that layer.

### Message metadata
Per-message metadata is new: `.user(u -> u.text("...").metadata("key", "value"))`, with a
batch form (`Map<String,Object>`) and the same on `defaultSystem`/`defaultUser`. Keys
must be non-null/non-empty, values non-null, map entries non-null.

### Mutation
`chatClient.mutate()` and `requestSpec.mutate()` both return a fresh
`ChatClient.Builder` pre-populated with the source's defaults — the mechanism for
deriving specialized clients from a shared base configuration.

### `Prompt` / `PromptTemplate`
`Prompt implements ModelRequest<List<Message>>` wraps a `List<Message>` plus
`ChatOptions`. Convenience accessors: `getUserMessage()` (last user message, or empty),
`getSystemMessage()` (first, or empty), `getLastUserOrToolResponseMessage()`,
`getUserMessages()`, `getSystemMessages()`.

`Content`/`Message`/`MediaContent` interface hierarchy unchanged in shape:
```java
interface Content { String getContent(); Map<String,Object> getMetadata(); }
interface Message extends Content { MessageType getMessageType(); }
interface MediaContent extends Content { Collection<Media> getMedia(); }
```
`MessageType` enum: `USER`, `ASSISTANT`, `SYSTEM`, `TOOL`.

`TemplateRenderer` is a `BiFunction<String, Map<String,Object>, String>`; default impl
`StTemplateRenderer`, alternative `NoOpTemplateRenderer`. Custom delimiters:
```java
StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build()
```

`Media.Builder` lost its generic `data(Object)` overload; replaced with typed overloads
— `data(byte[])`, `data(String)`, `data(URI)`, `data(URL)`, `data(Resource)`.

---

## 4. Advisor architecture

**✅ bytecode-verified against `spring-ai-client-chat-2.0.0.jar`.** This is the biggest
structural change from 1.x, and CLAUDE.md's "VERIFIED API FACTS" table already captures
the headline (no `CallAroundAdvisor`/`StreamAroundAdvisor`/`AbstractAdvisor`,
`adviseCall` not `aroundCall`). This section adds the parts that table doesn't cover.

### Interface hierarchy (confirmed via `javap`)
```java
public interface Advisor extends org.springframework.core.Ordered {
    int DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;   // = -2147483448  (HIGHEST_PRECEDENCE + 200)
    String getName();
}

public interface CallAdvisor extends Advisor {
    ChatClientResponse adviseCall(ChatClientRequest, CallAdvisorChain);
}

public interface StreamAdvisor extends Advisor {
    Flux<ChatClientResponse> adviseStream(ChatClientRequest, StreamAdvisorChain);
}

public interface AdvisorChain {
    default ObservationRegistry getObservationRegistry();
}

public interface CallAdvisorChain extends AdvisorChain {
    ChatClientResponse nextCall(ChatClientRequest);
    List<CallAdvisor> getCallAdvisors();
    CallAdvisorChain copy(CallAdvisor);
}

public interface StreamAdvisorChain extends AdvisorChain {
    Flux<ChatClientResponse> nextStream(ChatClientRequest);
    List<StreamAdvisor> getStreamAdvisors();
    StreamAdvisorChain copy(StreamAdvisor);
}

public interface BaseAdvisor extends CallAdvisor, StreamAdvisor {
    static final Scheduler DEFAULT_SCHEDULER;
    default ChatClientResponse adviseCall(ChatClientRequest, CallAdvisorChain);   // implemented, calls before()/chain/after()
    default Flux<ChatClientResponse> adviseStream(ChatClientRequest, StreamAdvisorChain);
    default String getName();
    ChatClientRequest before(ChatClientRequest, AdvisorChain);   // abstract — implement this
    ChatClientResponse after(ChatClientResponse, AdvisorChain);  // abstract — implement this
    default Scheduler getScheduler();
}
```
This directly confirms `CLAUDE.md`'s existing claim: `BaseAdvisor` supplies only
`before()`/`after()` hooks and its default `adviseCall`/`adviseStream` **always**
invoke the chain — there is no override point to short-circuit, which is exactly why
this project cannot build the VCR cache advisor on `BaseAdvisor` and must implement
`CallAdvisor`/`StreamAdvisor` directly.

### Marker interfaces (both bytecode-confirmed as empty marker interfaces)
```java
public interface ToolAdvisor extends Advisor {}      // implemented by ToolCallingAdvisor
public interface MemoryAdvisor extends Advisor {}     // implemented by BaseChatMemoryAdvisor
```
`ToolAdvisor` is how `DefaultChatClient` enforces "exactly one tool-calling advisor in
the chain" — registering a custom `ToolAdvisor` implementation suppresses the
auto-registered default rather than stacking two tool loops. `MemoryAdvisor` is the
analogous detection hook for chat-memory advisors.
`BaseChatMemoryAdvisor extends BaseAdvisor, MemoryAdvisor` and supplies a default
`getConversationId(Map<String,Object>)` — note the **1-arg** signature; the 1.x
signature was `getConversationId(Map, String)` (a default conversation id fallback that
no longer exists — see §9).

### Ordering semantics
Standard Spring `Ordered` stack semantics apply: lower `getOrder()` values run first on
the way in (request phase) and last on the way out (response phase) — i.e. advisors
nest like a call stack, first-registered outermost.

`Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = HIGHEST_PRECEDENCE + 200`
**✅ bytecode-verified** (raw constant `-2147483448`; `Integer.MIN_VALUE` is
`-2147483648`, so `+200` checks out exactly). This is a change from 1.x, where memory
advisors defaulted to `HIGHEST_PRECEDENCE + 1000`; the new value places memory advisors
**outside** (numerically before) `ToolCallingAdvisor`'s `HIGHEST_PRECEDENCE + 300`, so
memory advisors see only the final exchange of a tool-calling round-trip, not every
intermediate tool-call/tool-response pair.

### `DefaultAroundAdvisorChain` (implementation detail, not public API)
The concrete `BaseAdvisorChain` implementation backing both `CallAdvisorChain` and
`StreamAdvisorChain` is internally still named `DefaultAroundAdvisorChain`
**✅ bytecode-verified** (`org.springframework.ai.chat.client.advisor.
DefaultAroundAdvisorChain`, package-private constructor, public `builder(
ObservationRegistry)`). This is purely an internal class name held over from the 1.x
`AroundAdvisor` vocabulary — it implements `copy(CallAdvisor)` / `copy(StreamAdvisor)`
via a private `copyAdvisorsAfter(List<? extends Advisor>, Advisor)` helper. It does
**not** contradict CLAUDE.md's "`CallAroundAdvisor`/`StreamAroundAdvisor` removed" claim
— those were *public interfaces* in 1.0.0-RC1 and earlier; this is an unrelated internal
class that happens to share a word.

### `ToolCallAdvisor` — a deprecated compatibility subclass, not a removed name
Despite the upgrade notes describing "renamed: `ToolCallAdvisor` → `ToolCallingAdvisor`"
as a rename, the old name still exists as a real class in the 2.0.0 jar:
**✅ bytecode-verified**:
```java
public class ToolCallAdvisor extends ToolCallingAdvisor {
    protected ToolCallAdvisor(ToolCallingManager, ToolExecutionEligibilityChecker, int, boolean);
    public static ToolCallAdvisor.Builder<?> builder();
}
```
It is a thin subclass with no behavior of its own — a soft-deprecation shim so that
code referencing the old type name still compiles and behaves identically, rather than a
hard rename. (Not confirmed `@Deprecated`-annotated from `javap -p` output alone; treat
as "present and functional, not necessarily advertised" until confirmed otherwise.)

### Built-in advisors catalog (doc-only unless noted)
- `MessageChatMemoryAdvisor`, `VectorStoreChatMemoryAdvisor` — chat memory (§9)
- `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor` — RAG
- `ToolCallingAdvisor` — tool loop (§6), **✅ bytecode-verified** present
- `ToolSearchToolCallingAdvisor` — progressive tool disclosure (§7), separate module
- `StructuredOutputValidationAdvisor` — schema self-correction (§8), **✅
  bytecode-verified** present
- `ReReadingAdvisor` — RE2 reasoning-enhancement pattern (implemented as a `BaseAdvisor`)
- `SafeGuardAdvisor` — content-safety guard, **✅ bytecode-verified** class present
  (`SafeGuardAdvisor.class` in the jar)
- `SimpleLoggerAdvisor` — logging example/utility, **✅ bytecode-verified** class present

---

## 5. Recursive advisors

New reference-doc concept and section (`api/advisors-recursive.html`) formalizing a
pattern that didn't have a name in 1.x: an advisor that calls back into its own
downstream sub-chain repeatedly, rather than calling `chain.next*(request)` exactly
once.

### The enabling primitive
`CallAdvisorChain.copy(CallAdvisor after)` / `StreamAdvisorChain.copy(StreamAdvisor
after)` — **✅ bytecode-verified** present on both chain interfaces (see §4 listing).
It returns a new chain scoped to only the advisors *after* the given one, so a recursive
advisor can invoke `callAdvisorChain.copy(this).nextCall(request)` in a loop without
re-running advisors ahead of it in the stack and without corrupting the original chain's
position bookkeeping.

### The two shipped recursive advisors
- **`ToolCallingAdvisor`** — loops until its `ToolExecutionEligibilityChecker` says the
  latest `ChatResponse` has no more tool calls to execute; see §6 for the full hook
  lifecycle (`doInitializeLoop`/`doBeforeCall`/`doAfterCall`/`doFinalizeLoop`/
  `doGetNextInstructionsForToolCall`, all **✅ bytecode-verified** present).
- **`StructuredOutputValidationAdvisor`** — loops up to `maxRepeatAttempts` (default 3)
  times, re-issuing the call with the validation error appended to the prompt each time
  it fails; **✅ bytecode-verified** class present with `adviseCall`/`adviseStream`
  implementing `CallAdvisor`/`StreamAdvisor` directly (not `BaseAdvisor` — it needs to
  short-circuit and loop, which `BaseAdvisor` cannot do, consistent with §4).

### Usage accumulation across loop iterations — doc/bytecode discrepancy
The reference docs describe a `UsageAccumulator` type with `addRoundResponse(...)` /
`applyAccumulatedUsage(...)` methods, backed by
`org.springframework.ai.support.UsageCalculator` methods
`accumulateResponseUsage`/`withUsage`. **Neither exists in the 2.0.0 jars actually
shipped.** What's really there:

```java
// org.springframework.ai.support.UsageCalculator — spring-ai-model-2.0.0.jar
public final class UsageCalculator {
    public static Usage getCumulativeUsage(Usage, ChatResponse);
    public static boolean isEmpty(Usage);
}
```
No class named `UsageAccumulator` exists anywhere in `spring-ai-model-2.0.0.jar` or
`spring-ai-client-chat-2.0.0.jar` (checked via `unzip -l ... | grep -i accumulator` —
zero matches in either jar). See [Corrections to source docs](#corrections-to-source-docs).
The real, confirmed mechanism for cumulative usage is `UsageCalculator.getCumulativeUsage
(priorUsage, latestResponse)`, called by both recursive advisors after each iteration.

---

## 6. Tool calling & ToolCallingAdvisor

### `@Tool` and `ToolCallback`
Declarative tools via `@Tool(description, name?, returnDirect?, resultConverter?)` +
`@ToolParam(description, required?)`. Programmatic tools via
`MethodToolCallback.builder()` (wraps a `Method` + target object) or
`FunctionToolCallback.builder(name, fn)` (wraps a `Function`/`Supplier`/`Consumer`/
`BiFunction`). `ToolContext` threads request-scoped, non-model-visible data (tenant IDs,
auth) into `@Tool` methods without the model ever seeing it.

`ToolCallback` interface: `getToolDefinition()`, `getToolMetadata()`,
`call(String toolInput)`, `call(String toolInput, ToolContext)`.

### The old loop is gone from `ChatModel`
`ChatModel.call(prompt)`/`ChatModel.stream(prompt)` **no longer execute tool calls
automatically** — they return the raw model response including any unresolved tool-call
requests. The tool round-trip loop moved entirely into the advisor chain
(`ToolCallingAdvisor`), or must be driven manually via `ToolCallingManager` if bypassing
`ChatClient` altogether. `internalToolExecutionEnabled` and the
`spring.ai.<provider>.chat.internal-tool-execution-enabled` property family are removed
from every provider's options — there is no way to ask `ChatModel` to loop internally
anymore.

### `ToolCallingAdvisor` — full class shape (**✅ bytecode-verified**)
```java
public class ToolCallingAdvisor implements CallAdvisor, StreamAdvisor, ToolAdvisor {
    public static final int DEFAULT_ORDER = -2147483348;   // HIGHEST_PRECEDENCE + 300
    protected static final ToolExecutionEligibilityChecker DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER;
    // hooks, call path:
    protected ChatClientRequest doInitializeLoop(ChatClientRequest, CallAdvisorChain);
    protected ChatClientRequest doBeforeCall(ChatClientRequest, CallAdvisorChain);
    protected ChatClientResponse doAfterCall(ChatClientResponse, CallAdvisorChain);
    protected ChatClientResponse doFinalizeLoop(ChatClientResponse, CallAdvisorChain);
    protected List<Message> doGetNextInstructionsForToolCall(
        ChatClientRequest, ChatClientResponse, ToolExecutionResult);
    // mirrored stream-path hooks: doInitializeLoopStream / doBeforeStream /
    // doAfterStream / doFinalizeLoopStream / doGetNextInstructionsForToolCallStream
    public static ToolCallingAdvisor.Builder<?> builder();
}
```
`DEFAULT_ORDER` raw value `-2147483348` = `Integer.MIN_VALUE + 300` exactly, confirming
`HIGHEST_PRECEDENCE + 300`.

`Builder<T extends Builder<T>>` (self-typed generic builder, **✅ bytecode-verified**):
`toolCallingManager(ToolCallingManager)`, `toolExecutionEligibilityChecker(...)`,
`advisorOrder(int)`, `conversationHistoryEnabled(boolean)`,
`disableInternalConversationHistory()` (shortcut for
`conversationHistoryEnabled(false)`), plus `copy()`/`build()`.

`ToolExecutionEligibilityChecker` default behavior per docs:
`chatResponse -> chatResponse != null && chatResponse.hasToolCalls()`. The checker type
itself lives in `org.springframework.ai.model.tool` (confirmed by the constructor
signature in `javap` output), not in the `chat.client.advisor` package.

### Conversation history inside the loop
By default `ToolCallingAdvisor` keeps the full conversation (user message, model
responses, tool-call requests, tool responses) internal to its own loop iterations, and
resends the complete history on every iteration. Disabling it
(`disableInternalConversationHistory()`) makes each iteration send only the system
message plus the latest tool response. This internal-history behavior is *why* the
memory-advisor default order moved outside the tool loop (§4) — a memory advisor
upstream of `ToolCallingAdvisor` only ever sees the final exchange, not each
intermediate tool round-trip.

### `returnDirect`
When every tool called in a given round has `ToolMetadata.returnDirect == true`, the
loop breaks immediately and the tool's raw output becomes the `ChatResponse` content —
the model never sees the tool result. Mixed rounds (some `returnDirect`, some not) do
not short-circuit.

### Auto-registration & opt-out
`ChatClient` auto-registers a `ToolCallingAdvisor` at `HIGHEST_PRECEDENCE + 300` by
default whenever a request carries tools. Disable it:
- globally: `spring.ai.chat.client.tool-calling.enabled=false`
- per call: `.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))`
- implicitly: registering any custom `ToolAdvisor` implementation suppresses the default
  (the single-`ToolAdvisor`-per-chain invariant, §4)

Custom builder bean for swapping the tool-calling advisor implementation used during
auto-registration (this is what the tool-search starter's autoconfiguration relies on,
§7):
```java
@Bean
ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ToolCallingManager mgr) {
    return ToolCallingAdvisor.builder().toolCallingManager(mgr)
        .advisorOrder(Ordered.LOWEST_PRECEDENCE);
}
```

### Tool call limits (new in 2.0)
```properties
spring.ai.tools.limits.max-calls-per-tool-default=40
spring.ai.tools.limits.max-calls-per-tool.<name>=-1     # per-tool override, -1 = unlimited
spring.ai.tools.limits.excluded-tools=someTool          # never limited
spring.ai.tools.limits.max-total-tool-calls=150          # cap across all tools combined
spring.ai.tools.limits.on-limit-exceeded=THROW           # or RETURN_ERROR_RESPONSE
```
Same knobs available on `ToolCallingManager.builder()`. Defaults per the upgrade notes:
`DEFAULT_MAX_CALLS_PER_TOOL = 40`, `DEFAULT_MAX_TOTAL_TOOL_CALLS = 150`.

### Tool resolution fallback (now opt-in, was opt-out in 1.x)
`spring.ai.tools.resolution.fallback.enabled=true` (default `false`) re-enables
resolving tool names against the application-wide `ToolCallbackResolver` for tools not
explicitly attached to the request — flagged in the docs as a real security
consideration (every resolver-visible tool becomes reachable). `SpringBeanToolCallback
Resolver` and `toolNames()` are both removed entirely; there is no bean-scanning
fallback path any more, only the explicit resolver mechanism.

### Observability rename
Tool-call spans renamed `tool_call <name>` → `execute_tool <name>`;
`gen_ai.operation.name` attribute value `framework` → `execute_tool`; new span
attributes `spring.ai.tool.type` and `spring.ai.tool.call.id`.

---

## 7. ToolSearchToolCallingAdvisor (progressive tool disclosure)

New module (`spring-ai-tool-search-advisor` / starter
`spring-ai-starter-tool-search-advisor`) addressing large tool catalogs: rather than
sending every tool definition on every request, tools are indexed once per session and
the model retrieves only what it needs via a built-in `toolSearchTool` call. Docs claim
34–64% token reduction across OpenAI/Anthropic/Gemini for 50+-tool catalogs (marketing
figure — not independently verified here).

### Flow
1. Session start: all registered tools indexed, **none** sent to the model yet.
2. First request carries only the built-in `toolSearchTool` definition.
3. Model calls `toolSearchTool` with a natural-language query.
4. `ToolIndex` matches and returns tool references; their full definitions are appended
   for the *next* iteration.
5. Model issues a normal tool call against a now-visible definition.
6. `ToolCallingManager` executes it; result returned; model produces the final answer.

### `ToolIndex` implementations
| Type | Class | Notes |
|---|---|---|
| `regex` (**default** when unconfigured) | `RegexToolIndex` | zero dependencies, matches tool names against patterns |
| `lucene` | `LuceneToolIndex` | keyword search, default min score `0.25`, bundled Lucene |
| `vector` | `VectorToolIndex` | embedding similarity, requires a `VectorStore` bean |

`ToolIndex` interface: `indexTool(sessionId, ToolReference)`,
`indexTools(sessionId, List<ToolReference>)` (default loops the first),
`search(ToolSearchRequest)` → `ToolSearchResponse`, `clearIndex(sessionId)`.

### Builder (extends `ToolCallingAdvisor.Builder`)
`toolIndex(ToolIndex)` (required), `maxResults(Integer)` (null = model decides),
`systemMessageSuffix(String)`, `referenceToolNameAccumulation(boolean)` (default
`true`), `sessionIdKeyName(String)` (default `ChatMemory.CONVERSATION_ID`),
`evictionStrategy(ToolIndexEvictionStrategy)` (default `LruEvictionStrategy(1000)`).

Eviction strategies: `LruEvictionStrategy(cap)`, `NeverEvictStrategy` (manual
`advisor.evictSession(id)`), `AlwaysEvictStrategy` (re-index every request — testing),
`TtlEvictionStrategy(Duration)`, `CompositeEvictionStrategy(...)` (composes several).

### Config properties
```properties
spring.ai.chat.client.tool-search-advisor.enabled=false          # false by default; opt-in
spring.ai.chat.client.tool-search-advisor.tool-index-type=regex  # regex|lucene|vector
spring.ai.chat.client.tool-search-advisor.max-results=
spring.ai.chat.client.tool-search-advisor.session-id-key-name=chat_memory_conversation_id
spring.ai.chat.client.tool-search-advisor.eviction.lru-max-sessions=1000
spring.ai.chat.client.tool-search-advisor.eviction.ttl=
spring.ai.chat.client.tool-search-advisor.lucene.min-score-threshold=0.25
```
Enabling the property auto-registers a `ToolSearchToolCallingAdvisor.Builder` **as** the
`ToolCallingAdvisor.Builder<?>` bean — it transparently replaces the plain
`ToolCallingAdvisor` used for auto-registration, no `ChatClient` code changes required.
A custom `ToolIndex` bean always wins over auto-selection
(`@ConditionalOnMissingBean`).

**When to use it, per the docs:** 10+ tools, >10K tokens of tool definitions per
request, or multi-server MCP setups with large catalogs. Below that, the extra
search round-trip likely costs more than it saves — stick with plain
`ToolCallingAdvisor`.

---

## 8. Structured output

Three composable techniques, all reachable via `EntityParamSpec` on `.entity(...)`
(§3), or independently via advisor registration.

### 1. Output converters (`StructuredOutputConverter<T>`)
```java
public interface StructuredOutputConverter<T> extends Converter<String, T>, FormatProvider {
    default String getJsonSchema() { return NO_JSON_SCHEMA; }  // "" if unset
}
public interface FormatProvider { String getFormat(); }
```
Built-ins: `BeanOutputConverter<T>` (JSON Schema DRAFT_2020_12 generated from the target
type), `MapOutputConverter` (→ `Map<String,Object>`), `ListOutputConverter`
(comma-delimited text → `List<String>`, via a Spring `ConversionService`).

`BeanOutputConverter` schema generation changed in 2.0: it now **delegates to
`JsonSchemaGenerator`**. Effects: Kotlin optional properties no longer land in
`required`; `@JsonProperty(required = false)` is honored (previously ignored);
generated schemas carry OpenAPI-style `format` hints (`int32`, `int64`, `date-time`).
The old extension point `BeanOutputConverter.postProcessSchema(JsonNode)` is **removed**
— override `generateSchema()` instead. `getJsonSchema()` on the converter interface
(added in 2.0) is the bridge that lets a converter participate in
`useProviderStructuredOutput()`/`validateSchema()` — leave it at the `NO_JSON_SCHEMA`
default to opt a custom converter out of both.

### 2. Provider-native structured output (`useProviderStructuredOutput()`)
Sends the JSON Schema as an API-level constraint instead of prompt text, so the
provider's own decoding enforces conformance rather than best-effort prompting. Off by
default (compatibility with older models); detection is by checking whether the active
`ChatOptions` implements `StructuredOutputChatOptions` (**✅ bytecode-verified**
interface exists, `org.springframework.ai.model.tool.StructuredOutputChatOptions
extends ChatOptions`, adds `getOutputSchema()`) — unsupported combinations silently fall
back to prompt-based. Known gaps: OpenAI rejects a bare top-level `List<T>` (wrap in a
container record instead); full JSON Schema surface (`$ref`, `allOf`/`anyOf`/`oneOf`,
regex patterns, deep recursion) is not universally supported by every provider.

### 3. Schema validation & self-correction (`validateSchema()` / `StructuredOutputValidationAdvisor`)
**✅ bytecode-verified class**, `final class StructuredOutputValidationAdvisor
implements CallAdvisor, StreamAdvisor` (deliberately not `BaseAdvisor` — needs to
short-circuit/loop, see §4–5). Validates the model's JSON text against a
`com.networknt.schema.Schema` built from either an explicit type or a pre-supplied
schema string; on failure, appends the validation error to the prompt and re-issues the
call, up to `maxRepeatAttempts` (default 3) times; accumulates usage across attempts via
`UsageCalculator` (§5, §10).

Builder (**✅ bytecode-verified**): `outputType(Type)` / `outputType(TypeReference<T>)`
/ `outputType(ParameterizedTypeReference<T>)` (mutually exclusive with
`outputJsonSchema`), `outputJsonSchema(String)`, `maxRepeatAttempts(int)`,
`jsonMapper(tools.jackson.databind.json.JsonMapper)` (custom mapper), `advisorOrder(int)`.

**Not supported with streaming** — `validateSchema()` needs the complete response body
to validate, so `.stream()` is incompatible with it per the docs.

### Constraints applying to all three
- `.entity()`/`EntityParamSpec` reliability switches only exist on `.call()`, not
  `.stream()`.
- Structured output is "best effort" prompting except when
  `useProviderStructuredOutput()` is active — the model is *asked*, not *forced*, to
  conform.
- Not used for tool calling — tool call arguments are already structured by the tool
  protocol itself.

---

## 9. Chat memory

### Core abstractions (unchanged shape from 1.x)
`ChatMemory` — retains contextually relevant messages (not necessarily the full
history). `ChatMemoryRepository` — pure storage; the `ChatMemory` implementation
(`MessageWindowChatMemory`) decides retention policy. Default `MessageWindowChatMemory`
keeps a sliding window (`maxMessages`), always preserving `SystemMessage`s, and evicts
on **turn boundaries** (a full user-message-through-tool-response turn is dropped
atomically, never split mid-turn) — so `maxMessages` is an upper, not exact, bound.

### `ChatMemory.CONVERSATION_ID` is now mandatory — no default
Biggest behavioral change here: the 1.x fallback constant
`ChatMemory.DEFAULT_CONVERSATION_ID` (`"default"`) is **removed**. Both
`MessageChatMemoryAdvisor` and `VectorStoreChatMemoryAdvisor` throw
`IllegalArgumentException` if `ChatMemory.CONVERSATION_ID` is absent from the advisor
context — every call must supply it explicitly:
```java
chatClient.prompt().user("Hello!")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "my-session"))
    .call();
```
The `.conversationId(String)` convenience method is removed from the advisor builders
for the same reason. `BaseChatMemoryAdvisor.getConversationId(Map)` correspondingly
dropped its second `String` (default) parameter — **✅ bytecode-verified** the method
signature is `getConversationId(Map<String,Object>)`, one argument (§4).

### `PromptChatMemoryAdvisor` removed
Folded into `MessageChatMemoryAdvisor`, which now covers both the "inject as messages"
and (previously) "inject as prompt text" cases — same builder API, history is always
delivered as messages now.

### Repository implementations & their config
| Repository | Key properties | Tool-call messages? |
|---|---|---|
| `InMemoryChatMemoryRepository` (default) | none | n/a |
| `JdbcChatMemoryRepository` | `spring.ai.chat.memory.repository.jdbc.initialize-schema` (default `embedded`), `...schema` | **No** — silently filtered |
| `CassandraChatMemoryRepository` | `spring.ai.chat.memory.cassandra.table` (default `ai_chat_memory`), `...time-to-live`, `...initialize-schema` | **No** |
| `Neo4jChatMemoryRepository` | `spring.ai.chat.memory.repository.neo4j.{session,message,tool-call,metadata}-label` | supports tool-call label, so presumably **yes** for Neo4j (not explicitly contradicted by docs) |
| `MongoChatMemoryRepository` | `spring.ai.chat.memory.repository.mongo.create-indices` (default `false`), `...ttl` (default `0`) | **No** |
| `RedisChatMemoryRepository` | `spring.ai.chat.memory.repository.redis.{host,port,index-name,key-prefix,time-to-live,initialize-schema}` | not stated; ships an `AdvancedRedisChatMemoryRepository` with `findByType`/`findByContent`/`findByTimeRange` query helpers (requires Redis Stack 7.0+) |

**Redis module rename** (landed as a 2.0.1 upgrade note but relevant here): artifact
`spring-ai-autoconfigure-model-chat-memory-redis` →
`spring-ai-autoconfigure-model-chat-memory-repository-redis`; property prefix
`spring.ai.chat.memory.redis.*` → `spring.ai.chat.memory.repository.redis.*` (old
module kept as a deprecated transitive shim).

### JDBC `sequence_id` column (new in 2.0)
`JdbcChatMemoryRepository` schema gains a `sequence_id BIGINT` column plus index
`SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX`, because `TIMESTAMP` precision
is too coarse to order messages reliably on some databases (MySQL/MariaDB default to
1-second resolution). Existing deployments need a schema migration + backfill (see
upgrade notes). Messages now carry a creation timestamp in metadata, retrievable via
`message.getMetadata().get(JdbcChatMemoryRepository.CONVERSATION_TS)`.

### Advisors
`MessageChatMemoryAdvisor.builder(chatMemory).build()` — history injected as messages.
`VectorStoreChatMemoryAdvisor.builder(vectorStore).promptTemplate(custom).build()` —
history retrieved from a vector store and appended as plain text into the system
message, with template placeholders `instructions` (original system text) and
`long_term_memory` (retrieved memory).

Order default per §4: `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = HIGHEST_PRECEDENCE
+ 200` — **outside** `ToolCallingAdvisor`'s `+300`, so memory advisors only persist the
final exchange of a tool-calling round-trip, not each intermediate tool call/response
(a deliberate, documented behavior change from 1.x's `+1000` default).

---

## 10. Usage / token accounting

### `Usage` interface — cache token fields (**✅ bytecode-verified**)
```java
public interface Usage {
    Integer getPromptTokens();
    Integer getCompletionTokens();
    default Integer getTotalTokens();      // sum of the above unless overridden
    Object getNativeUsage();
    default Long getCacheReadInputTokens();    // NEW in 2.0 — null when provider doesn't report
    default Long getCacheWriteInputTokens();   // NEW in 2.0 — null when provider doesn't report
}
```
Both new methods are `default` and return `Long` (not `Integer`, unlike the token-count
fields) — confirmed by `javap` against `Usage.class` in `spring-ai-model-2.0.0.jar`.
Per the upgrade notes, providers currently populating these: Anthropic, Bedrock
Converse, and read-only support from OpenAI and Google GenAI (i.e. those two report
cache *reads* but not cache *writes*).

### `UsageCalculator` — the real cumulative-usage API (**✅ bytecode-verified**)
```java
public final class UsageCalculator {
    public static Usage getCumulativeUsage(Usage priorUsage, ChatResponse latestResponse);
    public static boolean isEmpty(Usage);
}
```
This — not a `UsageAccumulator` class — is what `ToolCallingAdvisor` and
`StructuredOutputValidationAdvisor` actually call to fold each loop iteration's token
usage into a running total before returning the final `ChatResponse`. See
[Corrections to source docs](#corrections-to-source-docs) for the discrepancy with the
reference-manual text describing a `UsageAccumulator`/`accumulateResponseUsage`/
`withUsage` API that does not exist in the shipped jar.

### `ToolCallingAdvisor` usage accumulation change
Per the upgrade notes: `ToolCallingAdvisor` now accumulates usage **across the entire
tool-calling loop**, not just the final call in the loop — a behavior change from 1.x
where only the last iteration's token counts were reported.

---

## 11. Model Context Protocol (MCP)

### SDK version — **✅ bytecode/POM-verified**, corrects a stale doc page
`spring-ai-mcp-2.0.0.pom` (pulled directly from Maven Central) declares a managed
dependency on:
```xml
<groupId>io.modelcontextprotocol.sdk</groupId>
<artifactId>mcp</artifactId>
<version>2.0.0</version>
```
This matches the GA announcement and release notes ("Upgrade to MCP SDK 2.0.0", PR
#6385) and the `io.modelcontextprotocol.sdk:mcp` Maven Central version list, which shows
`2.0.0` as a real published version alongside `2.0.1`. One reference-doc page
(`api/mcp/mcp-overview.html`) currently states "MCP Java SDK Version: 1.0.0 (RC1 or
later)" — that is **wrong** (or badly stale); trust the POM. See
[Corrections to source docs](#corrections-to-source-docs).

Spec compliance per the GA blog: **2025-11-25 MCP specification**.

### Module/package relocations — Spring transports moved into Spring AI
The Spring-specific WebFlux/WebMVC MCP transports moved **out of** the MCP Java SDK and
**into** Spring AI itself:
```xml
<!-- before -->
<groupId>io.modelcontextprotocol.sdk</groupId><artifactId>mcp-spring-webflux</artifactId>
<!-- after -->
<groupId>org.springframework.ai</groupId><artifactId>mcp-spring-webflux</artifactId>
```
Class relocations:
| Class | Old package | New package |
|---|---|---|
| `WebFluxSseServerTransportProvider`, `WebFluxStreamableServerTransportProvider`, `WebFluxStatelessServerTransport` | `io.modelcontextprotocol.server.transport` | `org.springframework.ai.mcp.server.webflux.transport` |
| `WebMvcSseServerTransportProvider`, `WebMvcStreamableServerTransportProvider`, `WebMvcStatelessServerTransport` | `io.modelcontextprotocol.server.transport` | `org.springframework.ai.mcp.server.webmvc.transport` |
| `WebFluxSseClientTransport`, `WebClientStreamableHttpTransport` | `io.modelcontextprotocol.client.transport` | `org.springframework.ai.mcp.client.webflux.transport` |

Boot-starter users need only a POM/Gradle bump (autoconfiguration absorbs the move);
manual/direct transport-class users must update imports. An OpenRewrite recipe
(`M3MigrateMcpSpringTransports`) is provided for the mechanical part.

**Streamable HTTP is now the default transport**, replacing SSE. STDIO remains for
local-process integrations. Server protocol selection:
`spring.ai.mcp.server.protocol=STREAMABLE|SSE|STATELESS`.

### `@McpTool` / `@McpResource` / `@McpPrompt` — migrated into Spring AI proper
The **mcp-annotations** module, previously community-incubated at
`org.springaicommunity.mcp.*`, is now first-party under
`org.springframework.ai.mcp.annotation.*` (methods under `...annotation.method.*`,
providers under `...annotation.provider.*`). The transitive
`org.springaicommunity:mcp-annotations` dependency is gone; the module ships as
`spring-ai-mcp-annotations`, auto-included by every MCP Boot starter.

Server annotations: `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`.
Client annotations: `@McpLogging`, `@McpSampling`, `@McpElicitation`, `@McpProgress`,
`@McpToolListChanged`, `@McpResourceListChanged`, `@McpPromptListChanged`.

```java
@Component
class CalculatorTools {
    @McpTool(name = "add", description = "Add two numbers together")
    public int add(
        @McpToolParam(description = "First number", required = true) int a,
        @McpToolParam(description = "Second number", required = true) int b) {
        return a + b;
    }
}
```

### Special/auto-injected parameters
`McpSyncRequestContext` / `McpAsyncRequestContext` — unified context object exposing
`request()`, `exchange()`, `sessionId()`, `clientInfo()`, `clientCapabilities()`,
`requestMeta()`, `transportContext()`, plus convenience operations `info()`, `ping()`,
`progress()`, `elicit(...)`/`elicitation(...)`, `sample(...)`/`sampling(...)`, `roots()`
guarded by `elicitEnabled()`/`sampleEnabled()`/`rootsEnabled()` capability checks.
`McpMeta` (request `_meta`), `@McpProgressToken`, `McpTransportContext` (stateless-mode
lightweight context), `CallToolRequest` (raw request for dynamic-schema tools) round out
the injectable parameter set — all excluded from generated JSON Schema and from
parameter-count limits.

### Exception handling change (a 2.0.1 upgrade note, MCP-tool-specific)
`@McpTool` exception handling now mirrors plain `@Tool`: an undeclared `RuntimeException`
becomes an error `CallToolResult`; a declared checked exception bubbles up wrapped in
`UndeclaredThrowableException`; `Error` subtypes and `McpError` bubble up unchanged. The
old `toolCallExceptionClass` constructor parameter on the various
`{Sync,Async}{,Stateless}McpToolMethodCallback` classes is deprecated for removal in
2.1.0.

### Server-side input validation on by default (via the SDK bump)
MCP servers now validate tool call arguments against the tool's JSON schema by default
(new in MCP SDK 2.0.0); failed validation returns a `CallToolResult` with `isError=true`
rather than invoking the tool. Disable with
`McpServer.sync(transportProvider).validateToolInputs(false)`. `@McpTool`-based servers
auto-generate compatible schemas, so this is largely transparent for annotation users.

### MCP client customizer consolidation
`McpAsyncClientCustomizer`/`McpSyncClientCustomizer` (two interfaces) collapsed into one
generic `McpClientCustomizer<B>`. The separate HTTP-request-customizer interfaces
(`McpSyncHttpClientRequestCustomizer`, `McpAsyncHttpClientRequestCustomizer`) are removed
in favor of `httpRequestCustomizer()`/`asyncHttpRequestCustomizer()` on the transport
builders themselves, reached via `McpClientCustomizer<HttpClientSseClientTransport.
Builder>` / `McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>`.

### MCP schema breaking changes (from the SDK bump)
- `CreateMessageResult.builder(Role, String response, String modelHint)` — `model` is
  now a required constructor argument, not a post-hoc setter.
- `CreateMessageRequest.builder(List<Message>, int maxTokens)` — `maxTokens` now
  required.
- Several builder-style constructors deprecated in favor of factory methods requiring
  their mandatory fields up front (`TextContent.builder(text)`,
  `ReadResourceResult.builder(contents)`, `GetPromptResult.builder(messages)
  .description(d)`, `ElicitRequest.builder(message, schema)`,
  `CallToolRequest.builder(name)`, etc.).
- `Tool.inputSchema()` changed return type from the SDK's `JsonSchema` type to
  `Map<String,Object>`, to let arbitrary JSON Schema keywords round-trip losslessly;
  transparent to `@McpTool` users.
- Several MCP schema interfaces (`JSONRPCMessage`, `Request`, `Result`, `Notification`,
  `ResourceContents`, `CompleteReference`, `Content`) are **no longer `sealed`** —
  exhaustive `switch` expressions over them now need a `default` branch.
- `Builder.customizeRequest()` removed from the HTTP client transport builders;
  replaced by `httpRequestCustomizer()`/`asyncHttpRequestCustomizer()`.

### MCP Security — community, work-in-progress, not core
`spring-ai-community/mcp-security` (artifacts under `org.springaicommunity`, e.g.
`mcp-server-security`, `mcp-client-security`, `mcp-authorization-server`) — explicitly
flagged **WIP** and **not officially endorsed** by the core Spring AI project. Server
security is **WebMVC-only** (no WebFlux), JWT-only (no opaque tokens), no support for
the deprecated SSE transport (Streamable HTTP / Stateless only). It supports:
- OAuth2 resource-server config via `McpServerOAuth2Configurer`/
  `McpResourceServerConfigurer`, method-level `@PreAuthorize` on `@McpTool` methods, and
  RFC 8707 audience-claim validation (`mcpAuthorization.validateAudienceClaim(true)`).
- API-key auth via a pluggable `ApiKeyEntityRepository` (ships an in-memory bcrypt
  implementation explicitly flagged as unsuitable for high-traffic production).
- Client-side OAuth2 in three flows (authorization code, client credentials, hybrid) via
  `OAuth2*SyncHttpRequestCustomizer` (HttpClient transports) or
  `McpOAuth2*ExchangeFilterFunction` (WebClient transports); a documented startup-order
  workaround is needed because Spring AI's autoconfiguration initializes MCP clients
  before user-context auth would normally be available.
- A companion `mcp-authorization-server` module (Spring Authorization Server +
  MCP-specific extensions: Dynamic Client Registration, Resource Indicators) for
  standing up a test/dev IdP, with known integrations for Cursor, Claude Desktop, and
  MCP Inspector.

---

## 12. Model evaluation / testing

### `Evaluator` — unchanged functional shape from 1.x
```java
@FunctionalInterface
public interface Evaluator {
    EvaluationResponse evaluate(EvaluationRequest evaluationRequest);
}
```
`EvaluationRequest(String userText, List<Content> dataList, String responseContent)`.

### Built-in evaluators (LLM-as-judge pattern)
- **`RelevancyEvaluator`** — checks whether a response is relevant to the query *given*
  the retrieved RAG context; default prompt asks the judge model a binary YES/NO
  question with `{query}`/`{response}`/`{context}` placeholders. Custom templates must
  keep those three placeholder names.
- **`FactCheckingEvaluator`** — checks factual support of a claim against a supplied
  document/context (`{document}`/`{claim}` template), aimed at hallucination detection;
  docs show it running against Ollama's `bespoke-minicheck` as a purpose-built,
  cheap fact-checking model, independent of the model that generated the original
  response.

Both integrate naturally with `RetrievalAugmentationAdvisor`'s exposed document context
(`chatResponse.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)`).

A dedicated guide exists for the general pattern:
`guides/llm-as-judge.html` ("LLM-as-a-Judge Evaluation").

**Not found in the reference docs:** any built-in guidance for VCR-style record/replay
testing of model calls (i.e., nothing overlapping this project's own purpose) — the
`api/testing.html` page and the separate `api/testcontainers.html` page both focus on
live-model evaluation and Testcontainers-backed integration testing, not fixture replay.

---

## 13. Community extensions

Both are **spring-ai-community** projects, not part of the core `spring-ai` release
train, called out in the GA announcement as notable ecosystem additions:

### spring-ai-session
An event-sourced `ChatMemory` replacement. Per the announcement: stores **all** message
types including tool calls (addressing the exact gap noted in §9 for the built-in JDBC/
Cassandra/Mongo repositories), with pluggable, turn-aware compaction strategies that can
use an LLM to summarize old turns rather than just windowing them out.

### spring-ai-agent-utils
"Production-ready primitives for agentic systems" — includes an implementation of the
external **AgentSkills** specification (agentskills.io) described as "portable,
Spring AI-native," plus ready-made tool implementations for file access, shell
execution, web fetch, task management, and automatic memory management.

Both are doc-only in this inventory (announcement-level description; not independently
verified against their own bytecode, since they ship outside `spring-ai-*` artifacts).

---

## 14. Breaking-change catalog (upgrade notes, condensed)

Everything below is from `docs.spring.io/spring-ai/reference/upgrade-notes.html`
directly; condensed here as a checklist rather than repeated narrative. Items already
covered in depth above are cross-referenced rather than restated.

**Upgrading to 2.0.0** (the GA line itself):
- Advisors: `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`
  (artifact rename). Memory advisor order `+1000` → `+200` (§4, §9).
- Tool calling: `internalToolExecutionEnabled` removed everywhere (§6);
  `ToolExecutionEligibilityPredicate`/`DefaultToolExecutionEligibilityPredicate` removed,
  replaced by `ToolExecutionEligibilityChecker` (§6); new
  `spring.ai.chat.client.tool-calling.enabled` property (§6); `streamToolCallResponses`
  removed from every advisor builder and property set; `ChatModel`'s internal tool loop
  removed (§6); `SpringBeanToolCallbackResolver`/`toolNames()` removed (§6);
  `ToolCallAdvisor` → `ToolCallingAdvisor` rename (old name kept as a compat subclass,
  §4); the full `FunctionCallback` → `ToolCallback` API rename (table in §"Tool
  Calling (1.x → 2.x)" of the upgrade notes: `FunctionCallback`→`ToolCallback`,
  `FunctionCallingOptions`→`ToolCallingChatOptions`, `defaultFunctions()`→
  `defaultTools()`, `.functions()`→`.tools()`, etc.)
- JDBC chat memory `sequence_id` column (§9).
- Options: strict immutability, `copy()`/`fromOptions()` removed for `mutate()` (§1);
  defaults moved onto Options; `.options` property-key segment removed; `.N()` → `.n()`
  builder rename.
- Ollama: `spring.ai.ollama.chat.think-option` → `spring.ai.ollama.chat.think`.
- Minimax dedicated support removed (§2).
- JSON utilities refactor: `JsonHelper`/`JsonParser` deprecation/`ModelOptionsUtils`
  removal/`McpJsonParser` removal (§1).
- MCP elicitation `TypeReference` param type: `tools.jackson.core.type.TypeReference<T>`
  → `org.springframework.core.ParameterizedTypeReference<T>`.
- Google GenAI embedding package move (§2).
- ChatClient tool calling: auto-registration always on unless disabled; new
  `ToolAdvisor`/`MemoryAdvisor` marker interfaces (§4); new 5-arg
  `ChatClient.builder()` overload (§3); `tools(Consumer<ToolSpec>)` /
  `ChatClient.ToolSpec` removed, use `tools(Object...)` + `toolContext()` instead;
  `MethodToolCallbackProvider` now throws `IllegalArgumentException` (was
  `IllegalStateException`) for zero-tools/duplicate-name cases.
- `spring-ai-spring-cloud-bindings` removed.
- `BeanOutputConverter` schema generation delegates to `JsonSchemaGenerator`;
  `postProcessSchema(JsonNode)` removed (§8).
- Azure Cosmos DB modules removed from core (§2).
- MCP SDK required-field breaking changes (`CreateMessageResult`/`CreateMessageRequest`
  builders, several deprecated constructors) (§11).
- Tool-call observability span/attribute rename (§6).
- `spring-ai-hanadb-store` removed (§2).
- Chat memory `CONVERSATION_ID` now mandatory; `DEFAULT_CONVERSATION_ID` and
  `.conversationId(String)` removed; `BaseChatMemoryAdvisor.getConversationId` signature
  change (§9).
- `PromptChatMemoryAdvisor` removed, folded into `MessageChatMemoryAdvisor` (§9).
- `.options()`/`.defaultOptions()` now require a `ChatOptions.Builder`, not a built
  instance (§3); `buildRequestPrompt` no longer public.
- OpenAI rebuilt on `openai-java` SDK; `spring-ai-azure-openai` and
  `spring-ai-openai-sdk` modules removed (§2).
- MCP Java SDK bumped to 2.0.0 (§11, and independently POM-verified here).
- Cache usage metrics unified onto `Usage` (§10).
- Anthropic rebuilt on `anthropic-java` SDK, with the full class-removal/relocation list
  in §2.
- MCP annotations migrated from `org.springaicommunity.mcp.*` into
  `org.springframework.ai.mcp.annotation.*`, with an OpenRewrite recipe provided (§11).
- MCP Spring transport modules moved from the MCP SDK into Spring AI, group ID change,
  with an OpenRewrite recipe provided (§11).
- MCP client customizer API consolidated (§11).

**Upgrading to 2.0.1** (already-published patch on top of GA, included for completeness
since it's the version several reference-doc pages currently document against):
- Redis chat-memory-repository module/property rename (§9).
- Mistral moderation/model enum changes (§2).
- `@McpTool` exception-handling alignment with `@Tool`; `toolCallExceptionClass` param
  deprecated (§11).
- Tool call limits introduced (`DefaultToolCallingManager`, §6); `ToolCallingAdvisor`
  usage accumulation now spans the whole loop, not just the final call (§10).
- OpenAI strict-mode default flip to `false` (§2); `OpenAiAudioSpeechModel.stream()` now
  chunks (§2).
- `TranscriptionModel extends StreamingTranscriptionModel` (§2).
- `Media.Builder.data(Object)` replaced with typed overloads (§3).
- Tool resolution fallback now opt-in (§6).

---

## Corrections to source docs

Documented explicitly per this task's instructions ("bytecode-verified where checked,
no overclaim"). Two places where the live reference-doc pages, fetched directly, say
something the shipped 2.0.0 jars / POM do not support:

1. **MCP Java SDK version.** `docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html`
   states *"MCP Java SDK Version: 1.0.0 (RC1 or later) — Required for Spring AI 2.0."*
   The `spring-ai-mcp-2.0.0.pom` pulled directly from Maven Central declares a managed
   dependency on `io.modelcontextprotocol.sdk:mcp:2.0.0`, matching the GA blog post and
   the release notes ("Upgrade to MCP SDK 2.0.0", PR #6385). The reference page appears
   stale or mis-rendered for this section; **2.0.0 is correct** (§11).

2. **Usage accumulation API.** `docs.spring.io/spring-ai/reference/api/advisors-recursive.html`
   describes a `org.springframework.ai.support.UsageAccumulator` class with
   `addRoundResponse(ChatResponse)` and `applyAccumulatedUsage(ChatClientResponse)`
   methods, and describes `UsageCalculator` as exposing
   `accumulateResponseUsage`/`withUsage`. Neither the class name `UsageAccumulator` nor
   those two `UsageCalculator` method names exist anywhere in `spring-ai-model-2.0.0.jar`
   or `spring-ai-client-chat-2.0.0.jar` (checked via full `unzip -l | grep -i
   accumulator` across both jars — zero hits — and via `javap -p` against the real
   `UsageCalculator.class`, which exposes only `getCumulativeUsage(Usage, ChatResponse)`
   and `isEmpty(Usage)`). The narrative concept (recursive advisors fold usage across
   iterations) is correct and independently confirmed; the specific class/method names
   in that doc page are not what shipped (§5, §10).

No other doc/bytecode conflicts were found among the facts checked in this pass — see
each "✅ bytecode-verified" tag above for the full list of independently confirmed
claims (roughly 20 distinct classes/constants across `Advisor`, `CallAdvisor`,
`StreamAdvisor`, `BaseAdvisor`, `AdvisorChain`, `CallAdvisorChain`,
`StreamAdvisorChain`, `ToolAdvisor`, `MemoryAdvisor`, `BaseChatMemoryAdvisor`,
`ToolCallingAdvisor` (+ `Builder`), `ToolCallAdvisor`, `StructuredOutputValidationAdvisor`
(+ `Builder`), `DefaultAroundAdvisorChain`, `ChatOptions` (+ `Builder`),
`StructuredOutputChatOptions`, `Usage`, `UsageCalculator`, `JsonHelper`, `JacksonUtils`,
and the `spring-ai-mcp` POM).

---

## Sources

- GA announcement — <https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now>
- Release notes (v2.0.0 tag) — <https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0>
- Upgrade notes — <https://docs.spring.io/spring-ai/reference/upgrade-notes.html>
- Reference docs index — <https://docs.spring.io/spring-ai/reference/>
- ChatClient — <https://docs.spring.io/spring-ai/reference/api/chatclient.html>
- Advisors — <https://docs.spring.io/spring-ai/reference/api/advisors.html>
- Recursive advisors — <https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html>
- Tool calling — <https://docs.spring.io/spring-ai/reference/api/tools.html>
- ToolCallingAdvisor — <https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html>
- Tool search tool — <https://docs.spring.io/spring-ai/reference/api/tools/tool-search-tool.html>
- Structured output overview — <https://docs.spring.io/spring-ai/reference/api/structured-output.html>
- Structured output — validation — <https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html>
- Structured output — native — <https://docs.spring.io/spring-ai/reference/api/structured-output/native.html>
- Structured output — converters — <https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html>
- Chat memory — <https://docs.spring.io/spring-ai/reference/api/chat-memory.html>
- Prompts — <https://docs.spring.io/spring-ai/reference/api/prompt.html>
- ChatModel — <https://docs.spring.io/spring-ai/reference/api/chatmodel.html>
- MCP overview — <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html> (see correction #1 above)
- MCP annotations overview — <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-overview.html>
- MCP annotations — special params — <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-special-params.html>
- MCP security (WIP) — <https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html>
- Model evaluation/testing — <https://docs.spring.io/spring-ai/reference/api/testing.html>
- Maven Central (bytecode verification jars/POM):
  - `org/springframework/ai/spring-ai-model/2.0.0/`
  - `org/springframework/ai/spring-ai-client-chat/2.0.0/`
  - `org/springframework/ai/spring-ai-commons/2.0.0/`
  - `org/springframework/ai/spring-ai-mcp/2.0.0/spring-ai-mcp-2.0.0.pom`
  - `io/modelcontextprotocol/sdk/mcp/maven-metadata.xml`
