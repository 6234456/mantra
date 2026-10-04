# Offline documentation snapshot

This directory builds the offline M5/M6 documentation for the current checkout. M3 is complete;
M4–M6 and v1.0 still require final acceptance. Compiled/batch/package APIs, parameter selection,
migration, dynamic workbooks, language tooling, PDF and localization are implemented and have
recorded runtime tests. The [M4](../milestones/m4-work-packages.md),
[M5](../milestones/m5-work-packages.md) and [M6](../milestones/m6-work-packages.md) work packages
separate those completed checks from the remaining final gates. Hosted deployment, installed IDE
plugins and publicly uploaded Maven artifacts are not implied by this snapshot.

Build with Python 3.10+; no package installation, browser, server, Java execution or network is needed:

```sh
python3 scripts/generate-docs-site.py
python3 scripts/generate-docs-site.py --check-only
python3 -m unittest discover -s docs/site/tests -p 'test_*.py'
```

Open `build/docs-site/index.html`. The generated guide/gallery pages use local CSS, system fonts
and ordinary links, without JavaScript or a CDN. Copied Dokka pages retain their own local assets. Its generator renders a deliberately small Markdown subset: headings, paragraphs,
fenced code, lists, tables, inline code, bold text and links. Raw HTML is escaped.

The generator reads the current English DSL and diagnostic documentation. The diagnostic inventory
must pass the real repository code-to-directory rule in `scripts/check-diagnostics.py`. Its calculation-function
directory is extracted from the actual public Mantra registrations, excludes lowering helpers, and
must match the existing DSL function table. The declaration index identifies actual public Kotlin declarations and links their defining source.
A separate KDoc page copies real `dokkaHtml` output for all six libraries, including model/read,
layout and package APIs. The declaration index is not a replacement for KDoc or the full ABI gate.
`build-manifest.json` records source and copied artifact SHA-256 hashes, extraction metadata and link
validation counts. Neither timestamps nor invented calculation values enter the output.

To additionally check the executable catalog, after a coordinated CLI build:

```sh
mantra-cli/build/install/mantra/bin/mantra catalog > build/mantra-catalog.txt
python3 scripts/generate-docs-site.py --catalog build/mantra-catalog.txt
```

For a release artifact build, first run all ten application test tasks and independent reference
checks, which generate their working papers and workbooks. Generate the actual six-library KDoc
after the coordinated build, then require all configured showcase HTML/Text/XLSX and KDoc files:

```sh
./gradlew --no-daemon :mantra-core:dokkaHtml :mantra-render:dokkaHtml :mantra-excel:dokkaHtml :mantra-workbench:dokkaHtml :mantra-server:dokkaHtml :mantra-packages:dokkaHtml
python3 scripts/generate-docs-site.py --require-artifacts --catalog build/mantra-catalog.txt
```

This generator does not run Gradle or change CI. The existing `verification-results` CI artifact
retains application outputs, tutorial exports and PDF checks. A hosted documentation deployment
has not been performed; the final strict offline build is a separate release gate.
Without `--require-artifacts`, missing outputs receive explicit unavailable labels and generation
commands. An existing HTML output with broken internal links is also withheld and recorded under
`artifact_issues` in the manifest; strict mode rejects it. No dead download link or stand-in workbook
is emitted. Presence checks are not proof of
financial correctness or a successful current build: the application acceptance and independent
reference checks provide that evidence.

The tutorial inputs under `examples/invoice/` and the Kotlin source under `examples/` are real files
downloaded into the site. Execute the tutorial using the commands in `tutorial.md` after the pinned
kernel and CLI are available. The generator itself does not validate the DSL or compile Kotlin. The actual CLI tutorial check
and `:mantra-cli:verifyDocumentationExamples` have run and produced the independently expected
`300.00`, HTML, XLSX and PDF; the final source revision must repeat those checks.
`embedding.md` describes the actual public compiled/batch/package APIs. `packages.md` records
their host authority, exact-version, row-capacity and publication boundaries. `neutral-domains.md`
walks through the two new applications without proposing new primitives. `independent-sources.md`
links the factual/arithmetic evidence for all ten applications. Language-specification and
compatibility pages use the corresponding English repository documents; milestone acceptance
remains separate from the presence of those normative drafts.

`applications.json` must cover every immediate `apps/*/README.md`; adding an application without a
showcase entry fails generation. Paths remain confined to the repository, and private kernel checkout
files are never copied into the site. All local generated HTML links and HTML anchors are checked.
External documentation links are preserved, but are not fetched or declared verified.

Use a dedicated empty output directory, or reuse an unmodified output generated by this script.
In-repository output must stay under `build/` or `out/`. Generation validates a staged copy before
replacing an existing site; failed extraction, missing required artifacts or invalid links leave the
last successful output intact. Unowned files, edited generated files and symbolic outputs are
rejected rather than overwritten. The manifest owns exactly the files this generator wrote.

## Recorded checks and remaining gates

The generator's 26 Python tests have passed. They cover source confinement, catalogue consistency,
real file copying/hashes, missing artifacts, malformed OOXML downloads, broken links/anchors and
safe replacement. The latest application fixtures and a 13-flow installed-browser run have passed,
with task-owned browser/Vite processes and the temporary profile removed. These are separate
runtime records; generator tests alone do not establish financial or browser correctness.

Six-library KDoc and local staging were actually generated. The tutorial PDF's two pages were
rendered and inspected; long/lease PDF fixtures have automated page-bound checks. A final strict
site still needs freshly verified showcase/KDoc files, matching catalogue and source hashes,
all local-link checks and recorded artifact inspection. Final full tests, security/import regression,
frozen-source performance and remote CI remain separate M6 exits. Maven Central publication is
pending its actual release prerequisites; local staging and clean consumers are not an upload.
