# Quick Start

## Requirements

Java 21 · Spring Boot 4.0+ · Spring AI 2.0+ (Jackson 3, `tools.jackson.*`)

## Add the dependency

```xml
<dependency>
    <groupId>io.github.rifatcakir</groupId>
    <artifactId>spring-ai-test-tools</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## Enable it

`src/test/resources/application-test.yml`:

```yaml
spring:
  ai:
    test:
      vcr:
        enabled: true
        mode: RECORD_OR_REPLAY
```

That is the entire integration. The advisor attaches itself to every `ChatClient.Builder`
in the context via `ChatClientBuilderCustomizer`, so no production code changes and no
test knows the cache exists.

## Write a test, exactly as you would without this library

```java
@SpringBootTest
class OrderStatusTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Test
    void answersAQuestionAboutTheOrder() {
        ChatClient chatClient = this.chatClientBuilder.build();

        String answer = chatClient.prompt()
            .user("What's the status of order ORD-4471?")
            .call()
            .content();

        assertThat(answer).contains("shipped");
    }
}
```

The first run needs a real model reachable (Ollama, or whichever provider your
`ChatClient` is built against) and writes
`src/test/resources/llm-cache/{sha256}.json`. Commit that file. Every run after that — on
your machine, on a teammate's, in CI — replays it in milliseconds, with zero network
calls.

## In CI

```yaml
spring.ai.test.vcr.mode: REPLAY_ONLY
```

`REPLAY_ONLY` replays a known fixture and throws `VcrCacheMissException` immediately on
anything unrecorded — never silently reaching a real model in a pipeline that isn't
expecting to. See [Record & Replay](record-replay.md) for the full mode reference and
what actually busts the cache.
