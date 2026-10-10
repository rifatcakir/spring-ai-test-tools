# Cassette Server

A standalone fixture store for [spring-ai-test-tools](../README.md). Applications in a shared
test environment keep recording and replaying their Spring AI calls exactly as they do in unit
tests, but the fixtures live on this server instead of in each application's
`src/test/resources`.

| | File store (default) | Remote store + Cassette Server |
|---|---|---|
| Where fixtures live | `src/test/resources/llm-cache*` | The server's storage, per namespace |
| Who records and replays | The advisor in your application | **The same advisor in your application** |
| Hash, fixture format | `VcrCacheKeyGenerator`, `VcrTrack` JSON | **Unchanged**: same hash, same files, byte for byte |
| Tool isolation, streaming, embeddings | Yes | **Yes**, all four fixture kinds are stored remotely |
| Calls a model provider | Only your application, on a miss | Only your application, on a miss. **The server never does** |
| Turned on by | `spring.ai.test.vcr.enabled=true` | Plus `store: remote`, a URL and a namespace |
| Typical use | Unit and CI tests | Deployed test environments, several instances or services sharing answers, e2e, QA, demos |

The server is deliberately dumb: it stores JSON documents by (namespace, kind, hash), checks
only that each is well formed with a matching `hash`, a known `schemaVersion` and the right
kind, and holds each namespace's default mode. It has no Spring AI dependency and holds no
provider key. Everything Spring AI-specific stays in the library. Design:
[`docs/SERVER-PRD.md`](../docs/SERVER-PRD.md).

> **Internal test networks only.** Fixtures contain prompts and answers. Do not expose this
> server publicly. Redaction happens in the client before anything is sent (see the library's
> "Redacting fixture content"), and the admin API is disabled unless you give it a token.

A five-minute walkthrough with no API key and no model to install:
[`examples/cassette-server-demo`](../examples/cassette-server-demo/README.md).

## Run it

```bash
mvn -pl spring-ai-test-tools-server package -DskipTests
java -jar spring-ai-test-tools-server/target/spring-ai-test-tools-server-0.1.0-SNAPSHOT.jar \
  --port=9090 --storage-dir=/data/cassettes --admin-token=change-me
```

Or as a container, built from the same jar ([`Dockerfile`](Dockerfile), layered):

```bash
docker build -t spring-ai-test-tools-server spring-ai-test-tools-server
docker run -p 9090:9090 -v cassettes:/data/cassettes \
  -e CASSETTE_ADMIN_TOKEN=change-me spring-ai-test-tools-server
```

| Flag | Environment variable | Default | Meaning |
|---|---|---|---|
| `--port` | `SERVER_PORT` | `9090` | HTTP port |
| `--storage-dir` | `CASSETTE_STORAGE_DIR` | `./cassettes` (`/data/cassettes` in the image) | One directory per namespace |
| `--admin-token` | `CASSETTE_ADMIN_TOKEN` | none: admin API disabled | Bearer token for `/__admin` |
| `--client-token` | `CASSETTE_CLIENT_TOKEN` | none: client API open | Bearer token for `/api/v1`; per namespace with `cassette.namespace-tokens.<ns>` |
| `--default-mode` | `CASSETTE_DEFAULT_MODE` | `RECORD_OR_REPLAY` | Mode for clients that set none, in namespaces without their own |

Health check: `GET /actuator/health`. One instance per environment; it is not clustered.

## Point an application at it

The library on the application's classpath, and in that environment's config:

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true                  # still required, still off by default
        store: remote                  # file (default) | remote
        remote:
          url: http://cassette-server:9090
          namespace: order-service     # typically the application name
          token: ${CASSETTE_TOKEN:}    # only if the server has a client token
          connect-timeout: 2s
          read-timeout: 5s
        # mode: REPLAY_ONLY            # omit to follow the namespace's default
```

No code changes, no `base-url` rewiring: the provider configuration stays as it is. On startup
the application logs one `WARN` line saying AI calls go through the server, so it can never be
on unnoticed. Keep the dependency out of production artifacts (a Maven profile or Gradle
configuration used only for test-environment builds).

**If the server is down or answers `5xx`, the call fails.** It never silently calls the model
and never falls back to local files: a shared environment that quietly starts spending tokens
and returning fresh answers is exactly the flakiness this exists to remove.

## Modes: the client decides

A mode set in the application's config always wins, in either direction, and the server is
never asked. An application that sets no mode asks the server for its namespace's default on
every call, so an admin can switch a whole environment without a redeploy:

```bash
curl -X PUT -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"mode":"RECORD_ALWAYS"}' http://cassette-server:9090/__admin/namespaces/order-service/mode
# ... re-record ...
curl -X PUT -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"mode":"REPLAY_ONLY"}' http://cassette-server:9090/__admin/namespaces/order-service/mode
```

## Review what was recorded

Fixtures recorded in an environment still belong in a pull request (they are reviewed as
diffs). Export a namespace and unzip it into the application's test resources:

```bash
curl -H "Authorization: Bearer $ADMIN" -o fixtures.zip \
  http://cassette-server:9090/__admin/namespaces/order-service/export
unzip -o fixtures.zip -d src/test/resources
```

The zip holds `llm-cache/`, `llm-cache-embedding/` and `llm-cache-tool/` exactly as the
library's file store lays them out, so the same tests replay with `store: file`. The reverse
works too: copy those directories into `{storage-dir}/{namespace}/` to seed a namespace.

## Storage

```
{storage-dir}/{namespace}/llm-cache/{hash}.json             chat and streaming
{storage-dir}/{namespace}/llm-cache-embedding/{hash}.json
{storage-dir}/{namespace}/llm-cache-tool/{hash}.json
{storage-dir}/.cassette-server/namespace-modes.json        namespace default modes
```

Documents are stored byte for byte as the client sent them (what the library's file store
would have written), written atomically (temp file and move), with writes to one fixture
serialized. A file the server cannot read is treated as missing, so the next client records it
again.

## API

Client API (used by the library; bearer token only if `--client-token` is set):

| Method and path | Result |
|---|---|
| `GET /api/v1/ns/{ns}/{kind}/{hash}` | `200` with the document, or `404` |
| `PUT /api/v1/ns/{ns}/{kind}/{hash}` | `201` stored; `409` already exists, with the existing document; `400` if the body's `hash`, kind or `schemaVersion` is wrong |
| `PUT …?overwrite=true` | `201` stored or `200` replaced; sent only under `RECORD_ALWAYS` |
| `GET /api/v1/ns/{ns}/config` | `{"namespace", "mode", "source"}`: the mode for clients that set none |

`{kind}` is `chat`, `stream`, `embedding` or `tool`; `{hash}` is the 64-character SHA-256.

Admin API (`/__admin`, admin token required):

| Method and path | Purpose |
|---|---|
| `GET /__admin/namespaces` | Namespaces, their mode, fixture count per kind |
| `PUT /__admin/namespaces/{ns}/mode` | Set the namespace default: `{"mode": "REPLAY_ONLY"}` |
| `DELETE /__admin/namespaces/{ns}/mode` | Clear it; clients without a mode use the server default |
| `GET /__admin/namespaces/{ns}/fixtures?kind=chat` | Hash, kind, recordedAt, model, first user message |
| `GET /__admin/namespaces/{ns}/fixtures/{kind}/{hash}` | One document |
| `DELETE /__admin/namespaces/{ns}/fixtures/{kind}/{hash}` | Remove one; the next call re-records it (or fails in `REPLAY_ONLY`) |
| `GET /__admin/namespaces/{ns}/misses` | The last 100 `404`s, newest first, with whether each has been recorded since |
| `GET /__admin/namespaces/{ns}/export` | Zip in the library's file layout |

## Tests

`mvn test` runs the server's own tests. The end-to-end proof with the library and a real
model lives in [`spring-ai-test-tools-it`](../spring-ai-test-tools-it/pom.xml) and needs
Docker; run it from the repository root with `mvn -Pintegration-test verify`.

## Not in this version

No web UI, no clustering or high availability, no TTL, no fuzzy matching, no proxying of model
traffic, no built-in Git sync. See the PRD's later phases.
