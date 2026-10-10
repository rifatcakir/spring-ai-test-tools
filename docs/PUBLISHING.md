# Publishing to Maven Central

Last updated: 2026-10-10

**Status: `0.2.0` published 2026-10-10** (library only; the Cassette Server image and
GitHub Release are still to do, steps 3 and 4 below). Built and signed from a clean clone
of `18ff237`, uploaded as deployment `4780ad18-0c5f-45ee-a75c-362c62ec3b29`, published by
the maintainer in the Portal, live on `repo1.maven.org` about 7 minutes later; the jar
there is byte-identical (SHA-1 `59b6c78b…`) to the one built and signed. History below.

**Before that: `0.1.0` and `0.1.1` published; `0.2.0` prepared, not yet published.**
Both earlier versions are live on Maven Central (`repo1.maven.org` answers HTTP 200 for
`spring-ai-test-tools-0.1.0.pom` and `-0.1.1.pom`, checked 2026-10-10; this document
still called `0.1.1` unpublished until then). `0.2.0` adds the Cassette Server's client
(the remote store, `docs/SERVER-PRD.md`) and redaction on every write path; see
[`CHANGELOG.md`](../CHANGELOG.md), including its upgrade notes. Its version is set in
every module and `mvn test` is green at the root, but **no release build, signing,
upload, image push or tag has been run for it.** The steps below are what remains, all
of them yours to run. Everything further down is the historical record of how `0.1.0`
was prepared, kept as the general runbook (GPG key, Sonatype token, `settings.xml`).

> **The Cassette Server moved to its own repository on 2026-10-10:**
> [`rifatcakir/spring-ai-test-tools-server`](https://github.com/rifatcakir/spring-ai-test-tools-server). From now on this runbook covers the
> library only; steps 3 and 4 below are the record of how the server's `0.2.0` was released
> from here. Release the server from its repository, with the same version as the library.

## Release `0.2.0` (done)

Two things ship, in two different ways:

| What | Where | How |
|---|---|---|
| The library `io.github.rifatcakir:spring-ai-test-tools:0.2.0` | Maven Central | `mvn deploy -Prelease` in `spring-ai-test-tools/`, as before |
| The Cassette Server `0.2.0` | `ghcr.io/rifatcakir/spring-ai-test-tools-server` and a GitHub Release asset | `docker push` and `gh release create`; **never Maven Central** (`maven.deploy.skip` is set in its pom) |

Ship them together and keep their versions equal: the server refuses fixtures with a
`schemaVersion` newer than it knows, so "library X.Y works with server X.Y" is the rule
users can follow. Nothing else in the repository is published: not the root aggregator,
not `spring-ai-test-tools-it`, not `examples/`.

### 1. Check everything from the root

```bash
mvn clean test                              # library + server unit tests, no Docker needed
mvn -Pintegration-test verify               # needs Docker: library e2e + Cassette Server e2e
```

Both green at the time of writing (library 321 unit tests, server 69, library e2e 19,
Cassette Server e2e 11).

### 2. Release the library (Maven Central)

The same three commands as every earlier release, inside the library's module:

```bash
cd spring-ai-test-tools
grep -m1 "<version>" pom.xml                # should print: <version>0.2.0</version>
mvn clean verify -Prelease                  # first command that signs; expect a GPG prompt
mvn deploy -Prelease                        # uploads and validates; does NOT publish
```

Then open <https://central.sonatype.com>, review the pending `0.2.0` deployment and click
"Publish" yourself. That click is permanent.

### 3. Release the Cassette Server (image and jar)

From the root, after the library is out (or at least after step 1 passed):

```bash
mvn -pl spring-ai-test-tools-server clean package
docker build -t ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0 spring-ai-test-tools-server
docker tag ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0 ghcr.io/rifatcakir/spring-ai-test-tools-server:latest
echo "$GHCR_TOKEN" | docker login ghcr.io -u rifatcakir --password-stdin   # a PAT with write:packages
docker push ghcr.io/rifatcakir/spring-ai-test-tools-server:0.2.0
docker push ghcr.io/rifatcakir/spring-ai-test-tools-server:latest
```

The first push creates the package as private; make it public under the package's
settings on GitHub if the README's `docker run` should work for everyone.

### 4. Tag and write the GitHub Release

After merging `feat/cassette-server` into `main`:

```bash
git tag -a v0.2.0 -m "spring-ai-test-tools 0.2.0: Cassette Server"
git push origin v0.2.0
gh release create v0.2.0 spring-ai-test-tools-server/target/spring-ai-test-tools-server-0.2.0.jar --title "0.2.0: Cassette Server" --notes-file release-notes.md
```

`release-notes.md` is the `## [0.2.0]` section of [`CHANGELOG.md`](../CHANGELOG.md), copied
into a scratch file (not committed).

### 5. Afterwards

- Point `spring-ai-test-tools-example` at `0.2.0` and add the Cassette Server example there
  (`examples/cassette-server-demo` in this repository is the starting point).
- Update the status line at the top of this file and in `docs/STATUS.md`.

**No agent should run any of the commands in steps 2 to 4, generate a GPG passphrase,
log in to a registry, or write real Sonatype credentials into `settings.xml`.** This
section exists so you have the exact commands; running them is deliberately left to you,
for the reasons in "Why this is a manual walkthrough" at the bottom of this document.

### History: `0.1.0` sync check

**Confirmed synced, 2026-08-03.** `repo1.maven.org/maven2/io/github/rifatcakir/spring-ai-test-tools/0.1.0/`
serves the jar and pom (HTTP 200), and `search.maven.org` indexes the coordinate.
Verified end to end by clearing the artifact out of a local `~/.m2/repository` entirely
and running `mvn clean test` in the sibling
[`spring-ai-test-tools-example`](https://github.com/rifatcakir/spring-ai-test-tools-example)
repo — Maven re-downloaded `spring-ai-test-tools-0.1.0.jar` straight from Central and all
tests passed. (The general propagation lag noted in earlier drafts of this doc — Central's
search index and `repo1.maven.org` sync can trail the Portal's own "published" state by
minutes to hours — applies to future releases too; it's just already elapsed for `0.1.0`.)

## What's already done (in this repo)

- `LICENSE` — Apache-2.0, full text, at the repo root.
- `pom.xml` — `name`, `description`, `url`, `licenses`, `developers` (including a public
  contact email), `scm` (the metadata Central rejects a release without). Both `TODO`s
  that used to be here are resolved: the repo URL/`scm` are confirmed against the real
  remote, and a contact email is listed.
- The GitHub repository exists and is public: <https://github.com/rifatcakir/spring-ai-test-tools>,
  matching `pom.xml`'s `url`/`scm` exactly. `main` is pushed and up to date.
- CI (`.github/workflows/ci.yml`) is live and green on `main` — unit tests run on every
  push/PR with no Docker required, and a separate scheduled/manual job runs the real
  Testcontainers + Ollama end-to-end proof without blocking ordinary PRs on it.
- A `release` Maven profile (`-Prelease`) adding `maven-source-plugin` (sources jar),
  `maven-javadoc-plugin` (javadoc jar), `maven-gpg-plugin` (artifact signing, unconfigured
  — see below), and `central-publishing-maven-plugin` (upload to the Central Portal).
  Isolated in its own profile so an ordinary `mvn test` / `mvn install` never needs a GPG
  key — nothing about local development changed.
- **Verified**: `mvn -Prelease package -DskipTests` actually runs `javadoc:javadoc`
  end-to-end and produces `target/spring-ai-test-tools-0.1.0-javadoc.jar` and
  `-sources.jar` alongside the main jar, with **zero Javadoc errors** (100 warnings as of
  the current codebase, all "no comment" / "no @param" / "no @return" on getters, setters,
  and constructors that inherit their meaning from the class-level Javadoc — cosmetic, not
  blocking; the warning count has grown alongside the codebase but the "zero errors"
  property that actually matters for a release has been re-checked every time, not just
  assumed to still hold). This is the step most projects find out is broken only when they
  try to actually release, so it was checked here rather than assumed.
- **Version bumped: `0.1.0-SNAPSHOT` → `0.1.0`.** Central rejects SNAPSHOT versions
  outright, so this had to happen before a real release regardless. Verified afterward
  with a fresh `mvn clean verify -Prelease`, most recently on 2026-08-03 after the
  schema-"5" canonicalization fixes: **250/250 tests green**, all three required
  artifacts (jar, sources, javadoc — see below for the fourth, the signed `.asc` files)
  produced correctly under the non-SNAPSHOT filenames.
- **GPG key generated and published.** Ed25519/Cv25519 (`sec ed25519` + `ssb cv25519`,
  not RSA — a modern, equally valid choice; this doc's earlier draft suggested RSA 4096
  as *a* safe default, not the only one). Fingerprint
  `E4BD 1A1A 18AE 4942 E018 CC15 4218 547C 6455 F6B9`, UID `rifat cakir
  <rifatcakira@gmail.com>`, expires **2026-07-21 → 2029-07-21** (3 years — note the date
  somewhere durable; a signing key that's quietly expired is a nasty surprise on a future
  release day). The public half was sent to `keyserver.ubuntu.com` and independently
  re-fetched from there to confirm it actually propagated, not just that the send command
  exited zero.
- **A `central` server entry exists in local `settings.xml`** with the right `<id>`. Its
  actual username/password were not inspected or verified as real, valid tokens — only
  that the block is present and correctly named, which is as far as this can be checked
  without reading a credential.
- **Checked on 2026-08-03, from an agent sandbox — confirms the mechanics, not the
  signature itself.** `mvn clean verify -Prelease` run end to end: compiles, all 250
  tests pass, and all three unsigned artifacts (`spring-ai-test-tools-0.1.0.jar`,
  `-sources.jar`, `-javadoc.jar`) land correctly in `target/`. The build then reaches
  `gpg:sign` and fails immediately with `gpg: no default secret key: No secret key` —
  not a `pinentry` passphrase prompt, but a clean, expected failure: this sandbox has no
  GPG key material at all (`gpg --list-secret-keys` is empty here), which is exactly
  right — the real signing key lives only on the maintainer's own machine, never in an
  agent's environment. This confirms the pom's build/package/sign wiring is correct up to
  the point where it needs a real key; **still not verified, and still cannot be from
  here**: whether the actual signature `maven-gpg-plugin` produces on your machine (with
  your real key) is one Central accepts, and whether
  `central-publishing-maven-plugin`'s upload succeeds with your current token. Both only
  get proven by step 5 below, run by hand.

## What you need to do, in order

### 1. Confirm the namespace: `io.github.rifatcakir`

Sonatype's Central Portal grants `io.github.<your-github-username>` namespaces tied to
your GitHub identity.

1. Sign in to <https://central.sonatype.com> **with your GitHub account** (not a separate
   email/password signup). When you authenticate this way, the Portal already knows which
   GitHub account you own, and `io.github.rifatcakir` is granted and shown as **verified
   automatically** — there is nothing further to do for the namespace itself.
2. Double-check under the Portal's "Namespaces" page that `io.github.rifatcakir` shows a
   verified status. If it doesn't for some reason (e.g. you signed up with email/password
   first and only linked GitHub afterward), *then* fall back to the manual path: request
   the namespace, get a **verification key** from the Portal, create a new *public* GitHub
   repository literally named after that key (`github.com/rifatcakir/<verification-key>`),
   wait for Sonatype to detect it, confirm "Verified," then delete that throwaway
   repository — it has no further purpose.

Don't create a throwaway verification-key repository pre-emptively — it's only needed if
step 1's GitHub sign-in doesn't already leave the namespace verified, which is the common
case, not the exception.

This step is entirely about your GitHub account, not this codebase. Nothing in this repo
needs to change for it.

### 2. Generate a GPG key pair and publish the public half — done

Fingerprint `E4BD 1A1A 18AE 4942 E018 CC15 4218 547C 6455 F6B9` (key ID
`4218547C6455F6B9`), UID `rifat cakir <rifatcakira@gmail.com>`, Ed25519/Cv25519,
expires 2029-07-21. Sent to `keyserver.ubuntu.com` and confirmed retrievable from there.

One optional follow-up: `keys.openpgp.org` is a separate, independently-run keyserver
that doesn't automatically mirror `keyserver.ubuntu.com` — if Central's verification (or
a future consumer verifying a signature) checks that server specifically and can't find
the key, send it there too:

```bash
gpg --keyserver keys.openpgp.org --send-keys 4218547C6455F6B9
```

`keys.openpgp.org` additionally requires confirming the UID's email via a link it emails
you before the identity (not just the raw key) shows up in searches there — the key
itself still becomes fetchable immediately either way.

Keep the private key and its passphrase exactly where you keep other secrets — not in
this repository, not in a commit, not pasted into a chat. A revocation certificate was
generated alongside the key (GnuPG does this automatically) — store that somewhere
recoverable too, separately from the key itself; it's the only way to invalidate this
key later if it's ever lost or compromised.

### 3. Get Sonatype token credentials

From <https://central.sonatype.com>, under your account, generate a **user token**
(a username/password pair distinct from your login password, scoped to publishing).

### 4. Put credentials in `~/.m2/settings.xml` — not in this repo

**A `central`-id server entry already exists in local `settings.xml`.** Its presence and
`<id>` were checked; the actual username/password were deliberately not read or verified
as real tokens (nothing here should be inspecting credential values). If it's not filled
in with real Sonatype token values yet, or needs redoing, here's the shape:

`pom.xml`'s `central-publishing-maven-plugin` configuration references
`<publishingServerId>central</publishingServerId>`, which means Maven looks for a
`<server>` with `id` `central` in **your local `settings.xml`**, never in the repo. Add
an entry shaped like this (placeholders — fill in your own token values, do not commit
this file anywhere):

```xml
<!-- ~/.m2/settings.xml -->
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_SONATYPE_TOKEN_USERNAME</username>
      <password>YOUR_SONATYPE_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

If you'd rather not put a plaintext token on disk at all, Maven supports encrypting
`settings.xml` passwords with `mvn --encrypt-password` and a master password — worth
doing if this machine is shared or backed up somewhere you don't fully control.

### 5. A real release build

Once 1–4 are done — and the version bump below is already committed:

```bash
mvn clean verify -Prelease
```

The version is already `0.1.0`, not `0.1.0-SNAPSHOT` — Central rejects SNAPSHOT versions
outright, so this was bumped (`mvn versions:set -DnewVersion=0.1.0`) and verified ahead of
time, rather than left as a step to remember mid-release.

`verify` (not just `package`) matters here: `maven-gpg-plugin`'s `sign` execution is
bound to the `verify` phase deliberately, so this is the first command that will actually
attempt to sign anything — if your key isn't set up right, this is where you'll find out,
before anything is uploaded.

**Run this one yourself, in your own terminal.** Signing needs to unlock the private key,
which — unless `gpg-agent` already has it cached from something else you did moments
before — means a `pinentry` prompt for the key's passphrase, either a small GUI dialog or
a terminal prompt depending on how GnuPG is configured on this machine. That prompt has
to reach *you* interactively; it can't be answered from here, and nothing about this
workflow should be typing a passphrase anywhere on your behalf. If it hangs or a dialog
appears, that's `pinentry` waiting for you, not a stuck command.

Then, to actually upload:

```bash
mvn deploy -Prelease
```

`autoPublish` is set to `false` in `pom.xml` on purpose: this uploads and validates a
bundle on the Central Portal, but does **not** publish it automatically. Go to
<https://central.sonatype.com>, find the pending deployment, review it, and click
"Publish" yourself. Once published, a version on Central is permanent — it cannot be
deleted or overwritten, only superseded by a new version. That irreversibility is exactly
why the manual click exists as the last gate rather than automating it away.

**Done, 2026-08-03.** `mvn deploy -Prelease` uploaded the bundle, the Portal validated it
(Deployment ID `b12ff567-ec49-4324-9141-337fe3958e07`, 1/1 components validated), and the
"Publish" click was made by hand the same day. `io.github.rifatcakir:spring-ai-test-tools:0.1.0`
is now a permanent, live Maven Central coordinate. The next release (`0.1.1` or later)
repeats steps 3–5 above with a bumped version — steps 1 and 2 (namespace, GPG key) don't
need to be redone.

## Why this is a manual walkthrough

Every credential, key, and irreversible action from here on is something only you should
create and hold — an agent generating a GPG key, inventing Sonatype credentials, writing
real values into `settings.xml`, or running an actual `mvn deploy` would mean secrets
passing through a chat transcript and an irreversible public action (a permanent Central
release) taken without you directly in the loop at the moment it happens. This document
exists so you have the exact steps and commands; running them is deliberately left to you.
