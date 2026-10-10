# Cassette Server

Fixtures on the local file system work for a laptop and for CI. They don't work for an
application running in a shared test, QA or demo environment: there is no checked-out
`src/test/resources` there, the application may run as several instances, and nobody can
see what was recorded without shelling into a container.

The Cassette Server moves only the fixtures. The library stays the client: the advisor, the
hash, tool isolation, streaming replay and redaction all keep running inside your
application. The server just stores the documents, one server per environment.

| | File store (default) | Remote store + Cassette Server |
|---|---|---|
| Where fixtures live | `src/test/resources/llm-cache*` | The server's storage, per namespace |
| Who records and replays | The advisor in your application | **The same advisor** |
| Hash, fixture format | unchanged | **unchanged**, byte for byte |
| Tool isolation, streaming, embeddings | yes | **yes** |
| Calls a model provider | your application, on a miss | your application, on a miss; **the server never does** |
| Typical use | unit and CI tests | deployed test environments, shared fixtures, e2e, QA |

## Run the server

```bash
docker run -p 9090:9090 -v cassettes:/data/cassettes \
  -e CASSETTE_ADMIN_TOKEN=change-me ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0
```

or, with the jar from the GitHub Release:

```bash
java -jar spring-ai-test-tools-server-0.2.0.jar --port=9090 --storage-dir=/data/cassettes --admin-token=change-me
```

Use the same version as the library. Internal test networks only: fixtures contain prompts
and answers.

## Point an application at it

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        store: remote
        remote:
          url: http://cassette-server:9090
          namespace: order-service
```

No code changes, no `base-url` rewiring. With `store: remote` the application logs one `WARN`
line at startup, so it can never be on unnoticed; keep the dependency out of production
artifacts with a profile used only for test-environment builds.

!!! warning "An unreachable server fails the call"
    It is never treated as a cache miss, so the model is never called because the server
    blipped, and there is no silent fallback to local files.

## Modes: the client decides

Set `spring.ai.test.vcr.mode` and it always wins, in either direction. Leave it unset and the
application follows its namespace's default, read from the server on every call. An admin
can change that default at runtime:

```bash
curl -X PUT -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"mode":"REPLAY_ONLY"}' http://cassette-server:9090/__admin/namespaces/order-service/mode
```

## Review recordings as diffs

```bash
curl -H "Authorization: Bearer $ADMIN" -o fixtures.zip \
  http://cassette-server:9090/__admin/namespaces/order-service/export
unzip -o fixtures.zip -d src/test/resources
```

The zip is in exactly the library's file layout, so the same tests replay with
`store: file`. Commit it and review the pull request like any other fixture change.

## More

- Flags, environment variables, the full API and the storage layout:
  [server README](https://github.com/rifatcakir/spring-ai-test-tools/blob/main/spring-ai-test-tools-server/README.md).
- A five-minute walkthrough with no API key and no model:
  [`examples/cassette-server-demo`](https://github.com/rifatcakir/spring-ai-test-tools/tree/main/examples/cassette-server-demo).
- Design and decisions: [`docs/SERVER-PRD.md`](https://github.com/rifatcakir/spring-ai-test-tools/blob/main/docs/SERVER-PRD.md).
