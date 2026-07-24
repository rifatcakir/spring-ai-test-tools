# Prompt Normalizer & Redactor

`VcrPromptNormalizer` and `VcrFixtureRedactor` sound similar and solve adjacent problems,
but they change different things, and confusing them has a real cost.

| | Affects the hash? | Affects what a hit returns? | Affects what's written to disk? |
|---|---|---|---|
| `VcrPromptNormalizer` | **Yes** | No (replay is unaffected either way) | Yes |
| `VcrFixtureRedactor` | **No** | No | Yes |

## Prompt normalizers — for volatile-but-harmless values

Naive prompt hashing dies the moment a prompt contains a date:

```java
.user("Today is " + LocalDate.now() + ". Summarise the backlog.")
```

The hash changes daily, the cache misses forever, and the fixture directory grows without
bound. A `VcrPromptNormalizer`, applied before hashing, collapses that noise into a stable
placeholder instead:

```java
@Bean
VcrPromptNormalizer ignoreVolatileValues() {
    return RegexPromptNormalizer.ISO_DATE
        .andThen(RegexPromptNormalizer.UUID);
}
```

Built in: `ISO_DATE`, `ISO_DATE_TIME`, `UUID`, `EPOCH_MILLIS`, plus
`RegexPromptNormalizer.of(regex, placeholder)` for anything else.

Normalizers affect the hash and what gets written to the fixture. The text sent to the
real model on a miss is always the original, unmodified prompt.

!!! warning "Redact volatile values, not meaningful ones"
    Normalizing away something the model conditions on will make two genuinely different
    requests share one fixture. A normalizer *merges* requests — that's exactly what you
    want for a timestamp, and exactly what you don't want for something the model
    actually behaves differently for.

## Fixture redactors — for real secrets or PII

A redactor never merges anything. It runs once, after the real model has already
answered and after the cache key has already been computed from the un-redacted request,
and it only changes what a reviewer sees in the committed JSON:

```java
@Bean
VcrFixtureRedactor redactCustomerId() {
    return track -> new VcrTrack(track.schemaVersion(), track.hash(), track.recordedAt(),
            // canonicalRequest is a *separate* top-level field that also embeds the raw
            // message text -- redact it too, or the value leaks right back through here.
            track.canonicalRequest().replaceAll("customer-\\d+", "[REDACTED]"),
            new VcrTrack.RequestSnapshot(track.request().model(), track.request().temperature(),
                    track.request().topP(), track.request().topK(), track.request().maxTokens(),
                    track.request().stopSequences(),
                    track.request().messages().stream()
                        .map(message -> new VcrTrack.MessageSnapshot(message.type(),
                                message.text().replaceAll("customer-\\d+", "[REDACTED]"),
                                message.toolCalls(), message.toolResponses()))
                        .toList(),
                    track.request().tools(), track.request().structuredOutput()),
            track.response());
}
```

!!! danger "A partial redaction is a silent leak, not a smaller one"
    The `canonicalRequest` line above is not optional decoration. Always check the raw
    committed JSON file, not just the one field you remembered to redact and test.

The value you redact **still determines which fixture a request resolves to** — it is
simply never written down. Two requests differing only in a redacted field still get two
different fixtures, exactly as before redaction existed. This is why it's safe to use on
real secrets or PII in a way a normalizer is not: redacting can never cause a cache
collision.

`VcrTrack#hash()` and `VcrTrack#schemaVersion()` in whatever a redactor returns are
ignored — the fixture is always filed under the hash actually computed. Multiple
redactors run in registration order; a redactor that throws is not swallowed, so a broken
redactor fails the recording loudly rather than shipping a half-redacted fixture.
