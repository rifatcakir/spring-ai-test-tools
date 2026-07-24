# Problem-Oriented Documentation Site — Proposal

Status: **evaluation only — recommendation below, no scaffold, no code, no deploy.**
Requested as a future idea (`docs/ROADMAP.md`'s "Considered and set aside" table already
names this: "Problem-oriented documentation site (MapStruct-style)... post-publish/
post-adoption investment... revisit once the library is published"). This note makes that
evaluation concrete instead of a one-line deferral.

## Tool choice: mkdocs-material

**Recommendation: [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/),
not Docusaurus, not a hand-rolled GitHub Pages site.**

| | mkdocs-material | Docusaurus | Hand-rolled GitHub Pages |
|---|---|---|---|
| Toolchain | Python + one YAML file (`mkdocs.yml`) + Markdown | Node.js, npm, a React build step | None, but no generator either |
| Fits this project's stack | This is a Java/Maven project with **zero existing Node.js footprint** — adding one just for docs is a second language toolchain to maintain, patch, and keep secure, for a project with one maintainer | Same objection, worse: Docusaurus's real strengths (multi-version docs, MDX/React components, i18n) solve problems a single-maintainer `0.1.0` Java library doesn't have | Lowest maintenance, but loses the navigation/search/structure a "MapStruct-style" site implies — the whole point of this ask |
| Out-of-the-box polish | Search, dark/light toggle, code-copy buttons, admonition callouts, versioned nav — all built into the theme, zero custom CSS/JS needed | Comparable polish, but earned at the cost of the toolchain above | None of this without building it by hand |
| Ongoing maintenance | Near-zero: edit Markdown, `mkdocs build` | Real: npm dependency drift/audit noise, a JS build that can break independently of any content change | Near-zero, but "site" degrades to "a slightly reformatted README" |
| Deploy | `mkdocs gh-deploy` (one command) or a 10-line GitHub Actions step | A dedicated Docusaurus GitHub Action, more moving parts | Whatever you build |

For a solo maintainer of a Java/Maven library with no other frontend code in the repo,
mkdocs-material gets the "MapStruct-caliber, problem-first" result the ask is actually
about, at the lowest realistic maintenance cost. Docusaurus's extra power (versioned docs
across multiple releases, React components) isn't a need this project has yet, and
importing a second toolchain to get theme polish this project doesn't otherwise require
would be exactly the kind of speculative infrastructure this project's own stated
discipline (`docs/ROADMAP.md` §6) already argues against building ahead of a demonstrated
need.

## Structure

**Landing page: problem → fix, in that order** — directly adaptable from README's
existing "The problem" section and quick-start snippet, which are already written in
almost exactly this voice (concrete pain first, code second).

**Feature pages, one per capability, each substantially lifted from an existing README
section:**

| Site page | Primary source | Reuse estimate |
|---|---|---|
| Home / "The problem, the fix" | README "The problem" + "Quick start" | ~90% reusable as-is |
| Record & Replay | README's core sections + `docs/VISION.md` Layer 1 | ~80% — mostly restructuring, not rewriting |
| Tool calling | README "Tool calling" | ~90% |
| Structured output | README "Structured output" | ~90% |
| Streaming | README "Streaming" | ~90% |
| Embeddings | README "Embeddings" | ~90% |
| Assertions | README "Assertions" | ~85% |
| Evaluator | README "Evaluator" + `docs/VISION.md` Layer 3 | ~75% — VISION's narrative needs trimming, it's written for a maintainer audience, not a first-time reader |
| Stubbing | README "Stubbing" | ~90% |
| "Why not WireMock for AI" | `docs/VISION.md` "Positioning" section | ~90%, works almost verbatim as a positioning/FAQ page |

**Not migrated to the public site:** `docs/ROADMAP.md`, `docs/STATUS.md`,
`docs/BRAINSTORM.md`, and the per-capability PRDs (`*-PRD.md`). These are maintainer-facing
design ledgers — sizing estimates, rejected alternatives, internal disagreements worked
through on the page, "known risks" lists — genuinely useful in the GitHub repo for a
contributor or a curious reader who clicks through, but wrong tone and wrong audience for
a curated, problem-first public site. This is also why the site's Markdown source should
**not** live inside the existing `docs/` folder (see "Hosting" below) — MkDocs builds a
whole folder into a site by convention, and pointing it at `docs/` as-is would publish
internal design deliberation as if it were user-facing documentation, which is not the
same content a first-time visitor should land on.

Effort estimate for the initial build: **small.** Most content already exists in the
voice this ask wants; the work is reformatting and splitting an already-good README into
separate pages plus writing one new landing page, not authoring documentation from
scratch. Realistically a single focused session, not a multi-week undertaking.

## Hosting

**GitHub Pages, from a new, separate source directory — not the existing `docs/`
folder** — e.g. `site/docs/` (MkDocs' own convention is a `docs/` folder relative to
`mkdocs.yml`; naming the parent `site/` avoids colliding with this repo's existing,
differently-purposed `docs/`). `mkdocs.yml` lives at the repo root alongside `pom.xml`.

Deploy via a **new, separate GitHub Actions workflow** (`.github/workflows/docs.yml`),
decoupled from the existing `ci.yml` test/e2e pipeline — a docs build failing must never
block or get confused with the library's own test gate, and vice versa. Triggered on push
to `main` when the site source or `mkdocs.yml` changes; runs `pip install
mkdocs-material` then `mkdocs gh-deploy --force` (publishes to a `gh-pages` branch GitHub
Pages serves from) or the `actions/deploy-pages` artifact-based flow if branch-based
deploys are undesirable later. Either way, this is a well-trodden, low-risk path — MkDocs'
own documentation covers this deploy exactly, nothing bespoke to design here.

Resulting URL: `https://rifatcakir.github.io/spring-ai-test-tools/` — no custom domain
needed or suggested.

## Timing: after Central publish, not before

**Agreed with the instinct stated in the request: build this after the library is
actually on Maven Central, not before.** Three reasons, not one:

1. **A docs site's own calls-to-action only make sense once there's a real coordinate to
   point at.** "Get started" on a polished, professional-looking site that then says
   "actually, install from source, it's not on Central yet" undercuts exactly the
   impression a docs site exists to create.
2. **This project's own recurring theme is "build side of publishing done, nothing
   published"** (`docs/STATUS.md`, `docs/ROADMAP.md`'s publishing item) — finishing that
   is a more valuable next external-facing milestone than a docs site for a library
   nobody can actually `mvn install` from Central yet.
3. **The deferral costs nothing and is fully reversible.** Nothing about the code or the
   README needs to change differently depending on when the site gets built — unlike a
   code decision that might need forward-compatibility, "build the site later" has no
   downside to waiting.

**Suggested framing going forward:** treat this as a natural "day one of being publicly
installable" launch companion — something to build right after (or in the same push as)
the actual Central release — rather than a pre-launch prerequisite blocking anything else
on the current roadmap.
