# Fixture contract

The library and the Cassette Server share no code (PRD fork d). This directory is what they
share instead: one real fixture per family, written by the library, laid out exactly like a
server namespace and like the library's default `src/test/resources` layout.

```
schema-versions.properties     latest schemaVersion per kind
llm-cache/                     one chat and one stream fixture
llm-cache-embedding/           one embedding fixture
llm-cache-tool/                one tool-execution fixture
```

- `spring-ai-test-tools/.../contract/FixtureContractTests` proves the samples are what the
  library writes today (same field structure, same hashes, same schema versions) and that
  each one replays through the file stores.
- `spring-ai-test-tools-server/.../FixtureContractTests` proves the server accepts every
  sample on `PUT`, serves it back byte for byte on `GET`, and knows the same schema versions.

When a fixture format changes on purpose, regenerate and commit the samples with the change:

```bash
mvn -pl spring-ai-test-tools test -Dtest=FixtureContractTests -Dfixture-contract.regenerate=true
```

The server's test then fails until `FixtureKind` knows the new version, which is the point.
