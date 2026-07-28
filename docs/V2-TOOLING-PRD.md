# V2 tooling — bulk re-record + orphaned-cassette cleanup

Last updated: 2026-07-28

Design agreed with the maintainer before any code was written. Tracks ROADMAP item 1
("v0.2 — fixture lifecycle tooling", second bullet) and the DX gap an external review
(Gemini — see `docs/EXTERNAL-FEEDBACK.md`) flagged: fixtures whose recorded hash is no
longer looked up by any test are never detected or removed, and there is no supported way
to re-record a whole suite in one pass.

Two phases, gated: Phase 1 (this document + the multi-module conversion) lands first and
is reported before Phase 2 (the actual plugin logic) starts.

## Agreed design

### Module layout

Multi-module Maven reactor, same repository, root `pom.xml` becomes a `pom`-packaging
parent aggregator:

| Module | Packaging | Purpose |
|---|---|---|
| `spring-ai-test-tools` | `jar` | The library. **Published coordinate does not change**: `io.github.rifatcakir:spring-ai-test-tools:0.1.0`. |
| `spring-ai-test-tools-build-core` | `jar` | Plain Java, no Maven API dependency: manifest reading, orphan detection, deletion logic. Reusable from a future Gradle plugin without dragging in Maven types. |
| `spring-ai-test-tools-maven-plugin` | `maven-plugin` | Thin Mojo layer calling into `build-core`. |
| *(future)* `spring-ai-test-tools-gradle-plugin` | — | Not started. Named here only so `build-core`'s Maven-API-free boundary is deliberate, not accidental. |

### Runtime usage-tracking (Phase 2 — recorded here so the store-layer contract is settled now)

A new SPI, `VcrUsageListener`, with a single method:

```java
void onFixtureRead(String hash, Path path, FixtureKind kind);
```

Plus a `NOOP` singleton. Every store type (`VcrTrackStore`, `VcrStreamTrackStore`,
`VcrEmbeddingTrackStore`, the tool-execution store) is the single choke point for reading
a fixture back off disk — one listener call added per store, on every successful read.
`VcrCacheKeyGenerator` and `DeterministicVcrAdvisor` are not touched; usage-tracking is a
read-side concern only, never part of the hash/replay decision.

- Auto-configuration wires the `NOOP` listener by default — zero cost, zero behavior
  change, for every consumer who never touches this feature.
- A property (name TBD in Phase 2, e.g. `spring.ai.test.vcr.usage.tracking=true`, set by
  the Maven plugin itself before invoking tests) swaps in `CollectingVcrUsageListener`,
  which accumulates hits in a `ConcurrentHashMap.newKeySet()` (safe under parallel
  Surefire forks writing to the same JVM-local set; safe across forked JVMs because each
  fork writes its own manifest, reconciled below).
- At the end of a test run (a JVM shutdown hook, or a JUnit `TestExecutionListener` —
  whichever proves reliable under Surefire's forking model, decided in Phase 2, not
  guessed now) the collected hashes are written to a manifest, atomically (temp file +
  move — the same discipline `VcrTrackStore` already uses for fixture writes).

### Where things live on disk

| What | Location | Committed? | Survives `mvn clean`? |
|---|---|---|---|
| Fixtures | `src/test/resources/llm-cache/**` | Yes — source, the point of the library | Yes |
| Usage manifest | `target/vcr-manifest.json` | No — ephemeral | No, `mvn clean` deletes it |

The manifest is a build artifact, not a fixture: it says which hashes *this run*
touched, not a durable record. Regenerated every run; never reviewed in a PR.

### Plugin goals (Phase 2 — design only, not implemented in Phase 1)

- **`vcr:record`** — `missing` (default) or `all` mode. Runs against a real model,
  opt-in, never in a sealed CI run.
- **`vcr:prune`** — default **dry-run**, prints what it would delete. Only deletes with
  `-Dvcr.prune.force`. **Refuses to run against a filtered test invocation** — deciding
  "was this actually a full run" is the plugin's job (inspect `MavenSession`, reject if
  `-Dtest`/`-Dgroups`/similar filtering flags are present), not the runtime listener's;
  the listener only ever reports what it saw, it cannot know if what it saw was
  everything. In a multi-module reactor, aggregates every module's manifest via
  `@Mojo(aggregator = true)` rather than pruning module-by-module — an orphan
  determination needs the whole suite's hash set, not one module's slice of it.
- **`vcr:verify`** — the CI drift gate: orphaned fixtures *or* fixtures referenced by a
  test but missing from disk both fail the build. Never deletes anything itself — a
  read-only check, safe to run on every PR.

None of Phase 2's goals are implemented yet. This document exists so Phase 1's module
boundaries (particularly `build-core` staying Maven-API-free) are drawn correctly the
first time, without having to re-shuffle packages once Phase 2 starts.

## Phase 1 — multi-module conversion (this session)

Scope: reactor structure only. No usage-tracking code, no Mojo goals, no fixture-handling
logic beyond what already exists in `spring-ai-test-tools`. `build-core` and
`maven-plugin` are empty/minimal skeletons that compile and package correctly.

Acceptance bar, checked before reporting done:

1. Published coordinate unchanged: `io.github.rifatcakir:spring-ai-test-tools:0.1.0`.
2. `mvn clean install` from the repo root builds all three modules.
3. The full pre-existing test suite (203 tests, per `docs/STATUS.md`) still passes inside
   the relocated `spring-ai-test-tools` module.
4. `mvn -Prelease package -DskipTests` (or `javadoc:javadoc`) still produces a clean
   sources/javadoc jar from the library module specifically, not the reactor root.
5. The sibling `spring-ai-test-tools-example` project still resolves
   `io.github.rifatcakir:spring-ai-test-tools:0.1.0` after a local `mvn install` of the
   new reactor, and rebuilds green without Docker.
6. `.github/workflows/ci.yml`/`docs.yml` and `site/` (mkdocs) keep working from the new
   layout — updated only if the restructure actually requires it.

No deploy. Version stays `0.1.0`. Phase 2 (usage-tracking SPI, `build-core` logic, the
three Mojo goals above) starts only after Phase 1 is reported and approved.
