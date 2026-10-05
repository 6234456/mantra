# Normein kernel integration and publication

Normal builds resolve `com.xqiou:normein-dsl:0.3.0` from Maven Central. No Normein checkout or
private repository credential is required by the default build or CI.

On 2026-10-05, the official [API metadata](https://repo.maven.apache.org/maven2/com/xqiou/normein-api/maven-metadata.xml)
declares `latest` and `release` as `0.3.0`. The [API POM](https://repo.maven.apache.org/maven2/com/xqiou/normein-api/0.3.0/normein-api-0.3.0.pom)
depends on the separately published DSL. Mantra uses that domain-neutral
[DSL POM](https://repo.maven.apache.org/maven2/com/xqiou/normein-dsl/0.3.0/normein-dsl-0.3.0.pom)
directly; the invoice API and its invoice/export dependencies are not needed.

## Published artifact identity

The downloaded `normein-dsl-0.3.0.jar` is 3,210,862 bytes with SHA-256
`83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8`, matching the
official Gradle module metadata. Its POM exposes Kotlin stdlib 2.2.20 and RE2/J 1.8.

The public JAR and the earlier pinned source build have the same 1,431 class/Kotlin-module
entries: 1,420 are byte-identical and 11 differ. Published sources change three files covering
classpath artifact identity, standard-library identity and stricter replay verification. Existing
public/protected JVM signatures remain present; `replayArtifact()` is added. These are real
implementation differences, so the public JAR is checked through fresh functional and consumer
gates. The actual public-default whole-repository run passed 793 current JUnit records
(with zero failures/errors/skips), ABI/conformance checks, all ten application renderings and
five fresh-cache POM consumers; all 103 UI tests passed. Installed CLI, LSP, conformance and
benchmark distributions contain the exact public JAR hash above. Three new tests prove that
a caller-controlled ancestor lockfile cannot change the embedded CLI/workbench kernel version.
It is not described as the exact binary of the old source commit.

The v1 performance archive retains its original source lock and runtime JAR hashes. Those
measurements describe the earlier source-built kernel; switching the default dependency does
not relabel them as measurements of the published JAR.

## Optional source development

`normein-build.lock` retains the source baseline at
`0a3ae1de844c92635fbbc03406a13cb0e8920c03`. To assess kernel work, create an authorized checkout
with `scripts/bootstrap-normein.sh` and explicitly select `-PnormeinBuildPath=.deps/normein`,
another checkout path, or `NORMEIN_BUILD_PATH`. Merely having `.deps/normein` present does not
enable substitution. Only `com.xqiou:normein-dsl` is substituted.

Selected source builds enforce the full commit and tracked-worktree cleanliness. An explicit
source path plus `-PnormeinCandidate=true` permits RFC assessment of an unpinned candidate;
such builds must never be released. Kernel changes remain Normein work, covered by RFCs and
Mantra contract tests; Mantra does not patch the kernel.

## Mantra release prerequisites

The public kernel dependency prerequisite is now satisfied. The six Mantra libraries still
require publisher namespace verification, signing and Central publishing configuration, signed
staging, isolated consumers and actual upload/publication verification. Local staging and a GitHub
source candidate do not establish Maven Central publication.

The maintainer selected an independent Mantra `maven-central` environment. See
[Central publication](central-publication.md) for signed preparation and configuration.
Keep credentials and signing private keys in secure release configuration. The public release
contains binary, sources and meaningful API documentation for the six library modules only;
applications, CLI, LSP, benchmarks and Normein are not Mantra library publications.
