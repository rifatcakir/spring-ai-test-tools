# Cassette Server — PRD

Last updated: 2026-10-10

Status: **design signed off 2026-10-10, implementation in progress.** Decisions already made by the project owner, not open
for debate here:

- **Client–server, not a proxy.** The existing library stays the client: the advisor,
  the hash, tool isolation and streaming replay keep running inside the application. What
  moves out is *where fixtures live*: a server that every application in an environment
  talks to over REST.
- **The server is a standalone, single-instance executable jar** (`java -jar …`), in the
  spirit of WireMock standalone, with a Docker image as packaging around the same jar.
- **Switched on from YAML only.** A Spring application that has the library as a
  dependency and enables the remote store in its config routes every recorded AI call
  through the server at the given host. No code changes.
- It lives **in this repository** as a module of a multi-module Maven build.
- Design first (this document), code after sign-off.

The forks in section 9 were resolved by the project owner on 2026-10-10; each one records the decision.

## 1. Problem

Today fixtures live on the local file system of whoever runs the tests. That works for a
developer laptop and a CI job that checks the repository out. It does not work for:

- **Deployed test environments.** An application running in a shared test, acceptance or
  QA environment has no checked-out `src/test/resources` to read from or write to, and its
  instances come and go.
- **Several instances or services sharing answers.** Three replicas of the same service,
  or two services sending the same prompt, each record their own copy, or race each other
  writing one.
- **Seeing what is recorded.** Nobody can list, inspect or delete an environment's
  fixtures without shelling into a container.

Teams that test this way usually already run a shared WireMock server for their other
external APIs, pointed at through `base-url` properties. The same operational model fits
here: one server per environment, applications configured to use it.

## 2. Positioning

| | File store (today) | Remote store + Cassette Server (this PRD) |
|---|---|---|
| Where fixtures live | `src/test/resources/llm-cache` | The server's storage, per namespace |
| Who records and replays | The advisor in the app | **The same advisor in the app** |
| Hash | `VcrCacheKeyGenerator` | **Unchanged**, same fixtures, same format |
| Tool isolation | Yes | **Yes**, tool fixtures are stored remotely too |
| Provider-independent | Yes | **Yes**, the server never sees provider JSON |
| Turned on by | `spring.ai.test.vcr.enabled=true` | Plus `spring.ai.test.vcr.store=remote` and a URL |
| Typical use | Unit and CI tests | Deployed test environments, shared fixtures, e2e, QA, demos |

The server is deliberately dumb: it stores and serves `VcrTrack`-family JSON documents by
(namespace, kind, hash) and holds per-namespace mode. All Spring AI knowledge stays in the
client. That is what keeps every existing design rule intact and keeps the server free of
Spring AI as a dependency.

### 2.1 Why not a model proxy

Considered and rejected for this design (see conversation history in the PR): a server
that the application's `spring.ai.openai.base-url` points at would see provider wire JSON,
so fixtures would become provider-specific, tool isolation would be impossible (tools run
in the application while the proxy only replays the model's tool-call request), and the
hash would need a second, wire-level canonical form. A proxy mode may still be added later
as a separate feature; it is not this PRD.

## 3. Non-goals for the MVP

- No semantic, fuzzy or threshold matching. Design rule #1 applies unchanged.
- No TTL or time-based invalidation.
- No proxying of model traffic. The server never calls a model provider.
- No clustering or high availability. One instance per environment, by design.
- No web UI in the MVP (REST admin API only). A dashboard is a later phase.
- No hosted, multi-tenant SaaS.

## 4. Architecture

### 4.1 Request flow

```
application (library on classpath, store=remote)
  ChatClient ──▶ DeterministicVcrAdvisor
                   │ canonicalize Prompt, SHA-256   (unchanged)
                   ▼
                 RemoteFixtureStore ──GET /api/v1/ns/{ns}/chat/{hash}──▶ Cassette Server
                   │                                                       │
                   │◀──────────── 200 VcrTrack JSON  |  404 ───────────────┘
                   │
         hit  → replay locally (zero model calls)
         miss → mode REPLAY_ONLY?  → VcrCacheMissException (as today)
                otherwise           → call the real model, then
                                      PUT /api/v1/ns/{ns}/chat/{hash} ──▶ Cassette Server
```

The model call on a miss is made by the application, with the application's own provider
configuration, exactly as today. The server never holds or needs a provider key.

### 4.2 What the client stores remotely

All four fixture families, each under its own `kind`, so nothing silently falls back to
local disk:

| Kind | Today's store class | Remote path segment |
|---|---|---|
| Chat call | `VcrTrackStore` | `chat` |
| Streaming call | `VcrStreamTrackStore` | `stream` |
| Embedding | `VcrEmbeddingTrackStore` | `embedding` |
| Tool execution | `VcrToolExecutionTrackStore` | `tool` |

### 4.3 Namespaces

A namespace keeps one application's (or one team's) fixtures apart, and is where mode
attaches. Typically the application name: `order-service`, `support-bot`. Two services
that deliberately share answers can point at the same namespace.

## 5. Client side (the library)

### 5.1 Configuration

```yaml
# application-test-env.yml of the application under test
spring:
  ai:
    test:
      vcr:
        enabled: true                 # still required, still off by default
        mode: REPLAY_ONLY             # the client's mode wins; omit to use the server's namespace default
        store: remote                 # file (default) | remote
        remote:
          url: http://cassette-server:9090
          namespace: order-service
          token: ${CASSETTE_SERVER_TOKEN}
          connect-timeout: 2s
          read-timeout: 5s
```

Nothing else changes for the application: no code, no base-url rewiring, the provider
configuration stays as it is.

### 5.2 Refactor this requires

- Introduce one store abstraction per fixture family, or one generic interface, with the
  current file-system classes as the default implementations (fork e). `read`, `write`,
  `delete` already have the right shape.
- Replace `pathFor(hash)` in log lines and in `VcrCacheMissException` with a location
  string (`file:…/abc.json` or `remote://cassette-server/order-service/chat/abc…`), so
  error messages stay useful in both modes.
- `RemoteFixtureStore` implementations use the JDK `HttpClient` and the existing Jackson 3
  mapper. **No new runtime dependency.**
- Auto-configuration picks the implementation from `spring.ai.test.vcr.store`.

### 5.3 Failure semantics

- **Server unreachable or 5xx:** fail loudly with a clear message naming the URL and
  namespace. Never fall back to calling the model silently, and never fall back to local
  disk silently (fork c).
- **Corrupt document from the server:** degrades to a cache miss, as a corrupt local
  fixture does today (design rule #7). In `REPLAY_ONLY` the caller turns that into a loud
  failure anyway.
- **Write conflict:** if the server already has a document for that hash (another instance
  recorded it first), the client discards its own and logs it. Last-write-wins only under
  `RECORD_ALWAYS`.

### 5.4 Running inside a deployed application

Today the library is a `test`-scoped dependency. For a deployed test environment it has
to be on the runtime classpath of that build. Guard rails, all in the MVP:

- `enabled` stays `false` by default; nothing activates without explicit config.
- Document the recommended setup: a Maven profile or Gradle configuration that adds the
  dependency only to test-environment builds, never to the production artifact.
- On startup with `store: remote`, log one `WARN` line stating that AI calls are being
  recorded and replayed through the given server, so it can never be on unnoticed.

## 6. Server side (the jar)

### 6.1 Running it

```bash
java -jar spring-ai-test-tools-server-0.1.0.jar \
  --port=9090 \
  --storage-dir=/data/cassettes \
  --admin-token=$ADMIN_TOKEN
```

The same jar inside `ghcr.io/rifatcakir/spring-ai-test-tools-server`, with the storage
directory on a volume. A plain Spring Boot application with no Spring AI dependency.

### 6.2 Client API (used by the library)

| Method and path | Result |
|---|---|
| `GET /api/v1/ns/{ns}/{kind}/{hash}` | `200` with the document, or `404` |
| `PUT /api/v1/ns/{ns}/{kind}/{hash}` | `201` stored, `409` already exists (body: the existing document), `400` if the body's own `hash` field doesn't match the path |
| `GET /api/v1/ns/{ns}/config` | `{ "mode": "REPLAY_ONLY" }`: the namespace default, used only by clients that set no mode (fork b) |

The server validates that a document is well-formed JSON with matching `hash` and a known
`schemaVersion`. It does not interpret request or response content.

### 6.3 Admin API (`/__admin`, admin token)

| Method and path | Purpose |
|---|---|
| `GET /__admin/namespaces` | Namespaces, mode, fixture count per kind |
| `PUT /__admin/namespaces/{ns}/mode` | Change the namespace's mode at runtime |
| `GET /__admin/namespaces/{ns}/fixtures?kind=chat` | List: hash, kind, recordedAt, model, first user message (truncated) |
| `GET /__admin/namespaces/{ns}/fixtures/{kind}/{hash}` | One document |
| `DELETE /__admin/namespaces/{ns}/fixtures/{kind}/{hash}` | Remove one, forcing a re-record on the next call |
| `GET /__admin/namespaces/{ns}/misses` | Recent `404`s on the client API, with time and hash, for debugging `REPLAY_ONLY` failures |
| `GET /__admin/namespaces/{ns}/export` | Zip of the namespace in the library's file layout, ready to commit |
| `GET /actuator/health` | Health check |

### 6.4 Storage

The same layout the library uses today, one directory per namespace and kind, one
pretty-printed JSON file per hash, atomic writes (temp file + move). A storage directory
can be copied into a project's `src/test/resources/llm-cache` and replay with
`store: file`, and vice versa. **The fixture format does not change.**

Single instance means one process owns the directory: writes per (namespace, kind, hash)
are serialized in-process; no file locking across hosts is attempted.

### 6.5 Reviewing fixtures (design rule #5)

Fixtures recorded in an environment are not in a pull request automatically. The MVP
answer is the `export` endpoint: a scheduled job exports a namespace and opens a PR in the
application's repository, so recordings are still reviewed as diffs. Built-in Git sync is
a later phase.

### 6.6 Security

- Client API: optional bearer token per namespace; admin API: required admin token.
- Fixtures contain prompts and answers. Redaction stays on the client: `VcrFixtureRedactor`
  is applied in `DeterministicVcrAdvisor` before a chat fixture reaches its store, so with
  the remote store the server receives only redacted content. Whether the stream,
  embedding and tool paths redact the same way is checked first (section 10); any path
  that doesn't gets it before the remote store ships.
- Intended for internal test networks. The README says plainly: do not expose it publicly.

## 7. Modules

```
pom.xml                            build aggregator only (never deployed, never a <parent>)
├── spring-ai-test-tools/          the library — coordinates and pom UNCHANGED
└── spring-ai-test-tools-server/   Spring Boot 4 executable jar, new
```

`io.github.rifatcakir:spring-ai-test-tools` is already on Maven Central at `0.1.0`. A shared
parent pom would have to be published next to it for consumers to resolve the library, so
there is none: the root pom only lists the modules, and the library's pom is byte-for-byte
what it was. Releases run inside the library's directory (`docs/PUBLISHING.md`). The
server depends only on Spring Boot and Jackson 3, not on the library and not on Spring AI;
the document format is shared as a written contract with a round-trip test on each side
(fork d).

## 8. Test strategy

Following `CLAUDE.md`: assert invocation counts, not just payloads.

- **Client, unit:** `RemoteFixtureStore` against a stub HTTP server. Hit → zero model
  calls; miss in `REPLAY_ONLY` → `VcrCacheMissException`, zero model calls, zero `PUT`s;
  miss in `RECORD_OR_REPLAY` → one model call and one `PUT`; server down → loud failure
  and zero model calls; `409` on `PUT` → no error, existing document kept.
- **Client, unit:** tool isolation with the remote store: the real `@Tool` runs zero times
  on a remote hit.
- **Server, unit:** `PUT`/`GET` round trip, `409`, hash/path mismatch `400`, admin token
  enforcement, atomic writes under parallel `PUT`s of the same hash.
- **Contract:** a fixture written by the library's file store is accepted by the server
  unchanged, and a server export replays with `store: file`.
- **Integration (`-Pintegration-test`):** the server jar started in a container, a Spring
  Boot app with `store: remote` and Ollama via Testcontainers. Record once; stop Ollama;
  replay; assert identical content and zero model requests. Same for streaming, embeddings
  and a two-turn tool call.

## 9. Forks needing sign-off

### Fork (a) — server unreachable during `RECORD_OR_REPLAY`

**Decided: fail loudly.**

- **Fail loudly (recommended).** Same as `REPLAY_ONLY`. A test environment that silently
  starts calling the model when the server blips is exactly the flakiness this exists to
  remove.
- **Call the model without recording.** Keeps environments working during a server
  outage, at the cost of silent model calls. Possible as an explicit opt-in later.

### Fork (b) — who decides the mode

**Decided: the client decides.** A mode set in the client's config always wins, in either direction. The namespace mode on the server is only the default for clients that set none, and the admin endpoint changes that default.

- **Server decides, client may override only toward stricter (recommended).** The
  namespace's mode from `GET …/config` applies, so an admin can switch an environment to
  `RECORD_ALWAYS` and back without redeploying anything. A client configured with
  `REPLAY_ONLY` stays `REPLAY_ONLY` whatever the server says.
- **Client config always wins.** Simpler, but every mode change is a redeploy.

### Fork (c) — local read-through cache

**Decided: no local cache in the MVP.**

- **None in the MVP (recommended).** Every lookup goes to the server; on a test-environment
  network that's a few milliseconds.
- **Optional on-disk cache** in the client, for chatty suites. Adds an invalidation story
  that the MVP doesn't need.

### Fork (d) — shared fixture-format module

**Decided: no shared module.** Contract test only.

- **No shared module (recommended for the MVP).** The server treats documents as JSON with
  three fields it checks (`schemaVersion`, `hash`, kind); the contract test in section 8
  keeps both sides honest.
- **Extract `spring-ai-test-tools-fixtures`** with the record types. Cleaner, but the
  record types sit next to Spring AI mappers today and the extraction is not needed for
  the server to work.

### Fork (e) — store abstraction shape

**Decided: one interface per family.**

- **One interface per family (recommended):** `ChatFixtureStore`, `StreamFixtureStore`,
  `EmbeddingFixtureStore`, `ToolFixtureStore`, each with a file and a remote
  implementation. Matches today's deliberate choice not to generalize the stores (see
  `VcrEmbeddingTrackStore`'s Javadoc).
- **One generic `FixtureStore<T>`.** Fewer types, but reverses that earlier decision.

## 10. To verify before writing code

- The exact current responsibilities of each `…TrackStore` and every caller of
  `pathFor(…)`, so the abstraction in 5.2 covers them without behavior change; the full
  existing suite (253 tests) stays green after the refactor alone, before any remote code.
- That every write path already goes through the redactors before reaching a store, so
  nothing unredacted can be sent to the server.
- Spring Boot 4's executable jar and layered Docker image build in a multi-module setup
  where only one module is a Boot application.

## 11. MVP scope and order of work

1. Root build aggregator; library moved into its own module with an unchanged pom; CI green.
2. Store abstraction refactor in the library, file implementations only; all existing
   tests green with no behavior change.
3. Server jar: client API, storage, admin token, health.
4. `RemoteFixtureStore` for chat; unit tests in section 8.
5. Stream, embedding and tool remote stores.
6. Namespace default mode (fork b), misses ring buffer, export endpoint.
7. Docker image; server README; README section "Shared fixtures with the Cassette Server".
8. Integration tests against Ollama.

Later phases: web dashboard, built-in Git sync, optional local cache, an HTTP proxy mode
for applications that don't use Spring AI.
