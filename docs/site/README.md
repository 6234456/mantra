# Offline documentation snapshot

This directory builds the offline M5/M6 documentation for the current checkout. The joint
`1.0.0-rc.1` candidate implementation and functional acceptance are complete. Measured source
`ae9af84bde3f82cc86787e908aab7be76ddecee9` has
[green CI 37220953941](https://github.com/6234456/mantra/actions/runs/37220953941), independently
verified frozen performance and actual batch/JFR evidence. Compiled/batch/package APIs, parameter
selection, migration, dynamic workbooks, language tooling, PDF and localization have actual tests. The [M4](../milestones/m4-work-packages.md),
[M5](../milestones/m5-work-packages.md) and [M6](../milestones/m6-work-packages.md) work packages
separate candidate checks from stable/Maven publication. The `v1.0.0-rc.1` tag and
[source-candidate report](../release-candidate.md) release receipt identify the final source revision and CI.
Hosted deployment, installed native IDE plugins and publicly uploaded Maven artifacts are not
implied by this snapshot; stable `1.0.0` has not been declared.

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
has not been performed. A strict offline build has passed; this documentation update requires a
fresh site manifest before publication.
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
`300.00`, HTML, XLSX and PDF, including the candidate's complete checks.
`embedding.md` describes the actual public compiled/batch/package APIs. `packages.md` records
their host authority, exact-version, row-capacity and publication boundaries. Directory loading
uses `STRICT_HANDLES` by default; `TRUSTED_LOCAL` is an explicit cooperative-host option, not an
automatic fallback or a malicious-rename isolation guarantee. `neutral-domains.md`
walks through the two new applications without proposing new primitives. `independent-sources.md`
links the factual/arithmetic evidence for all ten applications. Language-specification and
compatibility pages use the corresponding English repository documents; milestone acceptance
remains separate from the mere presence of those normative documents.

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

Six-library KDoc and local staging were actually generated. The final strict site regeneration/check
has no `artifact_issues` and records 2,615 checked HTML documents and 66,473 local links, with
227 source files and 3,083 output files in its byte/hash inventory. The tutorial's two PDF pages were
rendered and inspected; long/lease fixtures have automated page-bound checks. Current package
smoke produced ten binary PDFs with 26 date-source checks. Live-package acceptance covered four
read-only views, input 14→16→14, parameters 14→28→14, migration 14↔21, stale refusal and refresh
re-preview, preserving comments and cleaning all owned resources.

Current full checks, six-library ABI and five isolated consumers, conformance, security/import,
103 UI tests and recorded browser flows passed. The [RC performance report](../performance-v1.md)
retains all 390 independently verified samples within unchanged budgets. Its actual 10,000-case
stream performed 1,120,000 exact comparisons in 22,777 ms with 333,767,296 summed heap-pool peak
bytes and 22 successful closes. A separate 41.029-second Combined export JFR recording passed
scope/privacy checks (2,812 CPU samples, 1,774 allocation samples, five GC events and zero events
in the six disabled private-metadata categories). The profiler privacy revision followed the
unprofiled measurement and leaves the measured Kotlin source/harness unchanged.

The final source-publication revision and CI are identified by the `v1.0.0-rc.1` tag and the release
receipt in the [source-candidate report](../release-candidate.md). Maven Central upload has not been
performed: namespace, signing and the matching public Normein 0.3.0 artifact remain
pending the maintainer's decision/setup. Local staging and clean consumers are not an upload.
