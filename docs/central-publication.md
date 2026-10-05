# Central publication: offline preparation and remaining configuration

The current build defaults to **1.0.0-rc.1**, unsigned local staging, and the public
`com.xqiou:normein-dsl:0.3.0` dependency from Maven Central. The public kernel prerequisite is
resolved; [kernel integration](normein-publication.md) records its downloaded identity and the
difference from the historical source-built kernel. No Mantra Central deployment or production
signing identity has been verified. The roadmap already authorizes the release work; the remaining
operational prerequisites are publisher configuration and release evidence.

**On 2026-10-05 the maintainer explicitly deferred adding Mantra release secrets and the formal
Central publication.** The independent Mantra `maven-central` environment has been created with
deployment restricted to `main`; its signing/token secrets and public fingerprint policy are not
configured. This document records prepared capabilities, not a request to resume publication.

The repository now contains an [offline bundle preparer](../scripts/central-bundle.py). It does
not use HTTP, read Central credentials, upload, publish, or install a verifier. The root build
supports explicit local release signing and a prepare-only manual workflow, with no remote
publishing repository or Central upload workflow.
An offline receipt is not evidence of namespace ownership or public publication.

## Publication scope and configuration

All six libraries use group `com.xqiou.mantra` and the same selected version:

| Artifact | Public compile dependencies within Mantra |
| --- | --- |
| `mantra-core` | None; exposes the public Normein DSL dependency |
| `mantra-render` | `mantra-core` |
| `mantra-excel` | `mantra-render` |
| `mantra-workbench` | `mantra-core`, `mantra-render`, `mantra-packages` |
| `mantra-server` | `mantra-workbench` |
| `mantra-packages` | `mantra-core` |

Each publication includes its POM, binary, source JAR and meaningful Dokka API HTML in the
Javadoc JAR. Applications, CLI, LSP, benchmarks, conformance adapter and Normein are excluded.
The validator also checks configured runtime dependencies: PDFBox for rendering; Excel/POI/Jackson
for the workbench; Jackson for server and packages. Public Kotlin/POI dependencies retain their
compile scope. The POMs identify the Apache-2.0 license, project, SCM and the maintainer's public
GitHub handle/profile; no personal name or email is inferred.

Sonatype requires source/documentation artifacts, checksum sidecars, detached signatures and POM
metadata. Its version restriction excludes `-SNAPSHOT`; this project's bundle tool additionally
requires canonical stable `major.minor.patch`, so it rejects `1.0.0-rc.1`. This stricter rule is a
Mantra policy, not a claim that Central rejects prereleases.
[Central requirements](https://central.sonatype.org/publish/requirements/)

The following configuration names are reserved for secure release setup:

| Name | Purpose | Current verification |
| --- | --- | --- |
| `MANTRA_SIGNING_KEY_ID` | Explicit 8- or 16-hex-digit OpenPGP signing key/subkey ID | Production identity not verified |
| `MANTRA_SIGNING_KEY` | ASCII-armored, password-protected private signing key | Mantra production binding not verified; key value not inspected |
| `MANTRA_SIGNING_PASSWORD` | Private-key passphrase | Production signing not verified |
| `MANTRA_CENTRAL_USERNAME` | Central Portal user-token username | Read only by the explicitly invoked manual client; no live access verified |
| `MANTRA_CENTRAL_PASSWORD` | Central Portal user-token password | Read only by the explicitly invoked manual client; no live access verified |

The maintainer reports existing **environment-scoped secrets in the Normein repository** named
`SIGNING_KEY`, `SIGNING_PASSWORD`, `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`. GitHub
environment secrets are scoped to their repository/environment; a Mantra workflow cannot directly
reference another repository's environment secrets.
[GitHub environment scope](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
The maintainer selected the independent Mantra environment and its explicit mapping to the
`MANTRA_*` interface; secret provisioning is now deferred. No workflow reads another
repository's secrets. The signing key ID and full public fingerprint still need explicit
configuration and verification for production.

When configuration is resumed, the Mantra `maven-central` environment is the location for
`SIGNING_KEY`, `SIGNING_PASSWORD`, `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` secrets,
plus the public `SIGNING_FINGERPRINT` environment variable. The prepare-only workflow maps just
the signing key/password to the `MANTRA_*` interface; Central token aliases are not exposed to it.
Its preparer derives the signing key ID from the fingerprint-bound imported public key rather
than asking for another secret. None of these production values has been validated here.

The publisher account must have a verified namespace covering `com.xqiou.mantra`. Public Normein
artifacts and GitHub repository ownership do not establish that the configured token can publish
this group. Keep the actual namespace/account verification receipt with release evidence.
[Namespace verification](https://central.sonatype.org/register/namespace/)

The expected **full public fingerprint**, reviewed public keyring and current signer/revocation
status are separate from the short signing ID. These public evidence items remain unverified for
the production release. Store private keys and token values in secure release configuration;
never commit them, paste them into documentation, put them on command lines, or log them.

## Explicit local signing

`-PmantraReleaseVersion=<stable-version>` selects a stable version without changing the default
RC version. It rejects source composites: release staging must resolve the public kernel.
`-PmantraSignRelease=true` requires that explicit version and all three named signing settings.
Only then does the build apply Gradle's signing plugin to the six existing Maven publications.
`-PmantraStagingPath=<directory>` selects an isolated local Maven repository; omission uses
`build/staging`. None of these options configures a remote repository.

Signing uses `useInMemoryPgpKeys(keyId, key, password)` and signs the Maven publication, including
its generated POM. It does not select a default private keyring or invoke a host signing agent.
This follows the documented in-memory key/subkey and publication interfaces; the repository
wrapper is Gradle 9.4.0, and actual wrapper execution remains a separate check from reading the
live documentation. [Gradle signing plugin](https://docs.gradle.org/current/userguide/signing_plugin.html)

For a release selected as `1.0.0`, after secure signing configuration and release gates exist:

```sh
env -u NORMEIN_BUILD_PATH ./gradlew --no-daemon --max-workers=1 \
  -PmantraReleaseVersion=1.0.0 \
  -PmantraSignRelease=true \
  -PmantraStagingPath=/physical/path/to/new/staging \
  stageLibraries
```

This command writes local artifacts only. The example does not claim that stable `1.0.0` has
been selected or released. Use a fresh physical staging directory and retain its source/version,
public dependency identities, gate results and signed-byte digests. Temporary TEST-key exercises
can validate the mechanism, but cannot establish production signing authority or namespace access.
This is the low-level Gradle signing interface; use the canonical preparer below for its complete
task-owned process and cache cleanup.

## Offline bundle preparation

The preparer requires an explicitly installed physical `gpgv` executable, a reviewed public
keyring and a full expected uppercase fingerprint. It discovers no default keyring and offers no
skip-verification flag. Supply physical paths: macOS `/tmp` and `/var` may be symlinks, which this
tool rejects. Create the output parent first; the ZIP output must not already exist.

```sh
python3 scripts/central-bundle.py \
  --repository /physical/path/to/new/staging \
  --version 1.0.0 \
  --normein-version 0.3.0 \
  --gpgv /physical/path/to/installed/gpgv \
  --public-keyring /physical/path/to/reviewed-public-keyring.gpg \
  --signer-fingerprint '<full-uppercase-reviewed-public-fingerprint>' \
  --output /physical/path/to/new/mantra-1.0.0-central.zip
```

The fingerprint placeholder intentionally fails until replaced with reviewed public evidence.
No private key or Central token is needed by this command. It captures primary files once,
verifies the signatures/checksums over those bytes, and bundles those same captured bytes.
It does not rewrite a signed POM or choose a latest/timestamped filename.

The validator requires exactly six library directories and the selected exact GAVs. It checks
metadata, compile/runtime dependency scopes, matching internal versions and the exact Normein
version. Local explicit Kotlin dependency management is supported; parent/BOM indirection,
profiles, exclusions, system paths, unresolved versions, ranges, snapshots and repository
overrides require separate review or are refused. Real class/source/API HTML entries are checked
without extraction, with CRC, duplicate/path/symlink/encryption and expansion checks.

All 24 primary files require detached ASCII-armored signatures. `gpgv` must exit successfully and
report exactly one valid expected signer/primary-key fingerprint using a SHA-2 signature digest.
Verifier time/output are bounded and processes are waited/reaped. A reviewed keyring still matters:
GnuPG documents that `gpgv` trusts its supplied keys and does not itself enforce expired/revoked-key
policy. Byte/fingerprint verification does not prove current release authority.
[GnuPG verifier](https://www.gnupg.org/documentation/manuals/gnupg/gpgv.html)

Existing MD5/SHA-1 sidecars are required and verified. Existing SHA-256/SHA-512 sidecars are
verified too; absent SHA-2 sidecars are generated from already captured, signature-verified bytes.
The result has 144 Maven-layout entries: 24 primaries, 24 signatures and 96 checksums. The receipt
is printed outside the ZIP, records exact GAVs/hashes, and says
`LOCAL_VERIFIED_BUNDLE_NOT_UPLOADED` with `published=false`. Its offline
`publicNormeinAvailability=NOT_VERIFIED` means this invocation did not probe Central; the separate
recorded public dependency verification remains valid. Namespace/signing authority likewise
requires external release evidence. [Bundle layout](https://central.sonatype.org/publish/publish-portal-upload/)

Limits are 64 MiB per primary, 1 MiB per POM, 128 KiB per signature, 4 MiB keyring, 256 MiB total
captured input and total inflated JAR bytes, 20,000 entries per JAR, 128 entries per selected
directory, 64 KiB verifier status and five seconds per verifier call. Sorted stored ZIP entries
have fixed 1980 timestamps and regular-file 0644 mode, so identical captured signed files produce
identical bundle bytes. This is not a guarantee of reproducible compiler output or newly generated
signatures. Directory capture assumes a cooperative host; path/fstat checks do not provide native
race-safe protection against a same-permission adversary renaming ancestor directories.

## Consumer checks on the exact bundle

The ZIP deliberately excludes `.module` metadata and lists those exclusions in its receipt. The
generated POM may retain Gradle's metadata preference marker. A POM-only consumer is therefore
necessary but insufficient to prove ordinary Gradle resolution. Do not edit the marker after
signing; use the selected publication unchanged for both consumer modes.

In an isolated temporary Maven repository, unpack only the validated bundle. Run the existing
Java/Kotlin consumers with fresh caches in both modes, using an already installed Gradle:

```sh
python3 scripts/check-clean-consumer.py \
  --gradle /physical/path/to/installed/gradle/bin/gradle \
  --version 1.0.0 \
  --mantra-repository /physical/path/to/unpacked-bundle \
  --metadata-mode pom

python3 scripts/check-clean-consumer.py \
  --gradle /physical/path/to/installed/gradle/bin/gradle \
  --version 1.0.0 \
  --mantra-repository /physical/path/to/unpacked-bundle \
  --metadata-mode gradle
```

`pom` is the compatible default and ignores Gradle metadata redirection. `gradle` leaves the
repositories' metadata strategies unchanged, exercising normal Gradle behavior on this ZIP.
Mantra resolves only from the explicit temporary repository; Normein and other dependencies
resolve from Central, with no `mavenLocal` or source composite. The subprocess environment removes
source-path selection, all `MANTRA_SIGNING*`/`MANTRA_CENTRAL*` settings, and the four existing
Normein secret aliases listed above. Task cache/daemon cleanup
runs on failure too. Actual signed staging, real `gpgv` verification and these executed bundle
consumers must accompany the selected release; synthetic unit tests alone do not establish them.

## Later Central deployment

Formal publication and secret provisioning remain deferred. No upload workflow is installed.
The manual [Publisher API client](../scripts/central-publish.py) already provides explicit `upload`,
`status` and `publish` operations. Its **26 tests use mocked HTTP**; no live Portal request or actual
token validation has been performed. It is not invoked by the prepare-only workflow. After work
resumes and namespace/token/signer policy plus the exact-bundle gates are verified, deployment can
use this client or the Portal.

Credentials enter only through `MANTRA_CENTRAL_USERNAME`/`MANTRA_CENTRAL_PASSWORD` or the
`CENTRAL_TOKEN_USERNAME`/`CENTRAL_TOKEN_PASSWORD` aliases. Incomplete or conflicting mappings are
refused. Every operation binds `--deployment-receipt`, `--version`, `--source-commit` and
`--bundle-sha256`; `upload` also requires `--bundle` and `--review-receipt`, while `status`/`publish`
require the stored `--deployment-id`. Status is a single bounded observation, not polling. Publish
requires a freshly observed `VALIDATED` deployment and records request acceptance separately from
`PUBLISHED`. Uncertain remote outcomes retain a receipt and do not trigger automatic retries or
deletions. The API uses a Portal token in a Bearer header containing base64 of `username:password`;
avoid verbose HTTP output or credentials in process arguments.

Use `POST /api/v1/publisher/upload` with multipart part `bundle` and explicit
`publishingType=USER_MANAGED`. Retain the returned deployment ID. `POST /api/v1/publisher/status?id=…`
reports validation; `VALIDATED` is still unpublished. Test the validated deployment's exact
repository, then `POST /api/v1/publisher/deployment/<id>` advances publication. Require
`PUBLISHED` and fresh public exact-GAV/hash/consumer verification before reporting success.
Preserve failed deployment evidence. Upload, validation and publication are distinct states;
`AUTOMATIC` is not this flow's default. [Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

## Evidence scope

On 2026-10-05, the offline bundle validator passed **28 synthetic Python standard-library tests**,
including signature-status failures, changed bytes/checksums, bounded/corrupt archives, runtime
dependency omission, profiles/exclusions/system paths and output nonreplacement. Consumer
isolation passed **five mocked-process tests**, including both metadata modes and removal of
release credentials. These tests use deliberately synthetic signatures and do not execute GPG,
Gradle, JVM consumers or HTTP. Actual signing/consumer execution and production namespace/key
configuration remain distinct evidence; none of this document announces a Central release.

An earlier local exercise signed all six publications with a disposable TEST key, verified the
bundle and ran five Java/Kotlin consumers in each metadata mode. The canonical
[`prepare-central-release.py`](../scripts/prepare-central-release.py) then passed its own actual
TEST-key run on `a1e6e65a01aa39c792681a138beda31ab66af172`, selecting local `1.0.0` staging only.
Its 144-entry bundle passed all GPGv signature checks and had SHA-256
`e927a29bd6f6912f4ee310118f517c7911f1dd21817792591b13b9ef1493929e`. The run used the public
Normein JAR with SHA-256 `83a101ac90ae0c2a50bdc9a4b103d1c3de015df8bcbcafa4f63bbe5aa1562cc8`.
Five fresh POM-only and five fresh ordinary Gradle consumers passed. The receipt confirms temporary
agent shutdown and private key-home removal; the outer TEST key and bundle were also removed.
The coordinator retained `build/release-checks/public-normein/canonical-signing-test.json`.

The **98-test Python suite** passed, including seven canonical-preparer boundary regressions;
this evidence is separate from the real signing/consumer run. The manual client has 26 mocked HTTP
tests, which do not establish live Portal behavior. No production signer, namespace/token authorization, hosted preparation or
Mantra upload/publication has been verified. The local TEST run and historical candidate
measurements retain their own source identities. The [separate public-kernel report](performance-public-kernel.md)
records actual CI/performance verification for the measured `a1e6e65` revision. None of these checks
resumes the deferred formal publication.

## Prepare-only workflow

[publish.yml](../.github/workflows/publish.yml) exposes only `workflow_dispatch`, with three inputs:
an exact lowercase 40-hex `sourceCommit`, stable `version`, and full uppercase
`signerFingerprint`. There is no push/PR trigger, upload phase, publish phase or deployment ID
input. The job uses Mantra's `maven-central` environment and read-only contents/actions permissions.

Before checking out or reading a signing key, the workflow requires the Mantra repository,
`refs/heads/main`, input SHA equal to the dispatch SHA and current `main` HEAD, the matching
environment fingerprint policy, and a successful latest push CI run for that exact main SHA.
After checkout/setup it repeats the source/CI gate immediately before signing. Untrusted branch
code never receives a signing key. Inputs enter through environment variables and quoted CLI
arguments; the prep step receives only signing key/password, not a Central token.

The future hosted call is `scripts/prepare-central-release.py --version … --source-commit …
--signer-fingerprint … --output-dir build/central-release`. The output directory must be new.
Public review artifacts include the signed bundle, CI/bundle/preparation receipts, public signer
keyring and isolated-consumer logs. Private key directories and the broader signing log are not
artifact paths. The workflow does not upload the output directory wholesale or publish to Central.
Seven offline source-gate regressions cover revisions/refs, policy mismatch, failed/newer CI,
foreign PRs, main updates and redirect refusal; these do not execute a hosted workflow.

Without the deferred environment secrets and public policy, hosted preparation intentionally
cannot pass its configuration gates. The actual local canonical TEST run does not establish
hosted execution or public publication. The preparer remains responsible for bounded key import/verification, isolated
signed staging/consumers and cleanup of its own GPG material.

Gradle 9.4 accepts a short signing ID. A supplied 16-digit ID is normalized to its final
eight digits; bundle verification independently requires the full fingerprint.

## Task-owned process cleanup

The canonical preparer and isolated-consumer script require POSIX process groups and fail
preflight on unsupported hosts. They use already installed tools and fresh task-owned Gradle
homes, without stopping daemons in the user's shared home. On success, timeout, interruption or
catchable SIGTERM, they terminate and wait for launcher process groups, then run Gradle's stop
command against each owned home and verify that its recorded daemon PIDs have exited. Gradle
daemons can use a separate process group, so launcher exit alone is insufficient.

An unverified stop prevents success: the scripts retain a failure receipt and the task directory
for investigation; stopped GPG private-key state is removed where possible. Uncatchable SIGKILL
or machine failure cannot execute Python cleanup. This behavior is distinct from the low-level
Gradle command above and from production publishing authority.

The updated scripts passed **114 Python tests** and a real, offline Gradle blocking-task timeout
test. That test used no signing key, verified both launcher and separate daemon exit, and removed
its temporary project/cache without stopping unrelated processes. The earlier 98-test suite and
canonical signing receipt above remain evidence for their dated `a1e6e65` revision.

The hardened preparer then passed a new actual canonical TEST-key run on
`75d0258a377d27bff828a3e7217bb75c61a391a5`. Six signed local publications produced a 144-entry
GPGv-verified bundle with SHA-256
`53f0b4596e4ddc8c62ae3349fb54430463be488f4b470adb34823b107b04c031`.
Five fresh POM-only and five fresh ordinary Gradle consumers passed. Owned daemon exit,
GPG shutdown, private staging removal and outer TEST key/bundle removal were verified.
[The retained local receipts](evidence/release-process-cleanup/README.md) distinguish the unit
suite, real timeout test and real signed-consumer run. Nothing was uploaded or published.
