# Cassette Server demo

The smallest application that records and replays its AI calls through a
[Cassette Server](../../spring-ai-test-tools-server/README.md). No API key, no Ollama: the
"model" is a stand-in that stamps the current time into its answer, so you can see whether an
answer came from the model or from the server.

The whole setup is [`application.yml`](src/main/resources/application.yml):

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        store: remote
        remote:
          url: http://localhost:9090
          namespace: demo
```

The code ([`DemoApplication.java`](src/main/java/io/github/rifatcakir/demo/DemoApplication.java))
just asks a question through `ChatClient`. It knows nothing about the server.

## Try it

From the repository root, build the server and the demo together with the library:

```bash
mvn -pl spring-ai-test-tools-server,examples/cassette-server-demo -am clean package -DskipTests
```

`-am` and `clean` both matter. Without `-am`, Maven takes `spring-ai-test-tools` 0.1.1 from
your local repository, which predates the remote store; without `clean`, a fat jar built
earlier keeps whatever library it was first packaged with. The demo refuses to start with a
library that has no remote store, rather than quietly writing local files.

**1. Start the server** (one terminal):

```bash
java -jar spring-ai-test-tools-server/target/spring-ai-test-tools-server-0.1.0-SNAPSHOT.jar --admin-token=secret
```

**2. Run the demo twice** (another terminal):

```bash
java -jar examples/cassette-server-demo/target/cassette-server-demo-0-SNAPSHOT.jar
```

```
VCR CACHE MISS [72602edbf470] invoking the real model and recording
  >>> the model was called
  Answer:   Paris. (answered by the model at 14:08:19)
```

Run it again: no model call, and the same time, because the answer came from the server:

```
VCR CACHE HIT  [72602edbf470] replaying demo/chat/72602edbf470...
  Answer:   Paris. (answered by the model at 14:08:19)
```

**3. See what is stored:**

```bash
curl -H "Authorization: Bearer secret" http://localhost:9090/__admin/namespaces/demo/fixtures
```

**4. Lock the environment** so nothing new reaches the model:

```bash
curl -X PUT -H "Authorization: Bearer secret" -H "Content-Type: application/json" \
  -d '{"mode":"REPLAY_ONLY"}' http://localhost:9090/__admin/namespaces/demo/mode
```

The recorded question still replays. A new one fails loudly instead of calling the model:

```bash
java -jar examples/cassette-server-demo/target/cassette-server-demo-0-SNAPSHOT.jar "What is the capital of Turkey?"
# VcrCacheMissException: No VCR fixture for this request, and mode is REPLAY_ONLY ...
curl -H "Authorization: Bearer secret" http://localhost:9090/__admin/namespaces/demo/misses
```

**5. Stop the server** and run the demo once more: it fails with a `CassetteServerException`.
It never calls the model behind your back and never falls back to local files.

## With a real model

Remove the `fakeModel` bean, add a model starter (for example
`spring-ai-starter-model-ollama`) and its usual configuration. Nothing else changes.
