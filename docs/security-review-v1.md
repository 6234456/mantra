# Security review v1 — evidence ledger, final release gate pending

Review date: 2026-10-04. Scope: captured packages and package workbench HTTP, explicit migration, import boundaries, LSP transport/source loading, generated workbook lifecycle and PDF exports.

**This is not a completed T5 release gate or an independent penetration-test certificate.** It combines the M6 core source review and T5 package/HTTP review with the integrated import/protocol/config repairs. The document reviewer ran no builds, server, socket, IDE, browser, dependency installation or runtime test. Actual evidence below comes from result files and logs read after the coordinator executed the checks. Presence of a test is never recorded as a pass.

The repairs described below are integrated and have passing runtime evidence. The later final-source review, selected RC/version, source-aligned CI and frozen performance acceptance remain pending; these local results do not substitute for those exits. Public Maven namespace/signing and actual publication remain a separate prerequisite. Preparation manifests pin the original narrow patches, but the current production files and executed result artifacts are the evidence for this ledger.

## Evidence status

| Evidence | Actual recorded result | Practical scope |
| --- | --- | --- |
| `PackageWorkbenchServerTest` | XML: 11 tests, zero failures/errors/skips; 2026-10-04 16:35:52 UTC | Real package sockets: Host/token/method/body caps, date/provenance/wire4, review token/replay/history, root-byte and linked-graph CAS, current technical failure, captured JAR ownership, binary PDF, strict JSON and host export budgets. |
| `WorkbenchServerTest` | XML: 21 tests, zero failures/errors/skips; 16:35:30 UTC | Legacy HTTP behavior including import/edit/revision/exports, symlink/read limits, links and bounded SSE slots. |
| `WorkbenchTransportTest` | XML: one test, zero failures/errors/skips; 16:35:53 UTC | 36 actual sockets send incomplete 100-byte request bodies under a 150 ms test body deadline; excess queued requests and stalled bodies close, followed by an actual 200 response. The HTTP suites total 33 tests. |
| Import repairs | `XlsxImportHardeningTest` 10 and `WorkbenchImportHardeningTest` 6 passes; XML at 16:33:53 and 16:36:10 UTC | All 16 import regressions execute: errors, UTF-8, ZIP bounds/views, coordinates, collisions, physical row geometry/order, date/zero/false and unchanged host-control failures. |
| Package host bounds and policy | `PackageResourceBoundsTest` 5 and `PackageDirectoryPolicyTypeTest` 2 passes; XML at 16:36:11 UTC | Artifact limits/recovery, migration-intent payload/count, re-evaluation/CAS, control failure mapping and explicit policy types. `WorkspaceExportTest` separately has 4 passes. |
| LSP test suite | Eight XML suites total 21 tests, zero failures/errors/skips; 16:33:54–55 UTC | Framing, strict UTF-8/MIME charset, duplicate/trailing JSON, envelope/notification separation, source bounds and language behavior. This count includes the five new protocol/encoding regressions. |
| UI package/generated boundary | `/private/tmp/mantra-m6-ui-final-test.log`: 103 passes across 20 files; final UI check succeeds | Includes actual `effectiveLayer` projection and capability regressions; types are not exhaustive nested runtime JSON validation. |
| IntelliJ protocol bridge with installed LSP | Four current editor XML suites total 13 passes, including external-process `InstalledLspTest`; 16:59:24–25 UTC | Actual UTF-16 handshake, unsaved overlay, versioned rename/stale edit rejection, cancellation frame, healthy follow-up, shutdown/exit and cleanup. No IntelliJ IDE was started; this does not prove plugin UI behavior. |
| Installed package workbench CLI smoke | `/private/tmp/mantra-m6-package-smoke-final.log`: actual success marker | Real `serve apps --directory-policy trusted-local --port 0`: ten manifest-discovered mounts/cases, ten binary PDFs and 26 dated source checks; task-owned process group and temporary state removed before marker emission. |
| Installed-CLI slow-header provider check | Same final smoke marker: `incompleteHeadersClosed=true`, 2064 ms, startup request-limit property 2 seconds, subsequent status 200 | Fresh installed CLI process receives the explicit startup provider property before server initialization. Two seconds is the request limit, not a measured server-startup duration or the normal default. |
| Installed CLI/LSP smoke | `/private/tmp/mantra-m6-cli-smoke-final.log` and both `lsp-standalone-final`/`lsp-cli-final` logs report success | All seven CLI commands (`run`, `check`, `catalog`, `fixtures`, `diff`, `explain`, `serve`); both installed standalone LSP and CLI LSP launchers initialize/navigate/hover/find references/rename/shutdown, with owned-process/workspace cleanup. |
| Built-in Browser package flow | `build/ui-qa/package-live-verification.json`: `PASS`; coordinator confirms complete cleanup | Four read-only pages, host input 14→16→14, parameter 14→28→14, reviewed migration 14↔21, stale apply refusal, preserved host/package bytes and refresh/re-preview recovery. The task fixture is removed. |
| Local final gates | `/private/tmp/mantra-m6-complete-gates-fourth.log`: `BUILD SUCCESSFUL in 3m 10s` | Full check/test-count floors, six ABI baselines, actual binary-break counterexample, six local library publications/inspection, five fresh-cache POM-only consumers without composite substitution, rebuilt CLI/LSP/benchmark distributions and executed documentation examples. Local staging is not public publication. |

These result timestamps include retained up-to-date suites; do not turn their combined count into a claim that one fresh invocation re-executed every test. The successful fourth-gates command supersedes earlier failed integration/staging logs, without deleting their history. Its installed distributions contain POI 5.5.1; the later installed smoke logs therefore replace the earlier pre-upgrade smoke evidence. LSP request/response transcripts are in `build/lsp-smoke/{standalone,cli}-transcript.json`.

Still to record after final acceptance: frozen source commit/dirty-state identity `{{FINAL_SOURCE_COMMIT}}`, exact RC/version-aligned command and CI receipt `{{FINAL_CHECK}}`, and the actual frozen performance report. No final version/publication identity is inferred from an application manifest or a successful snapshot build.

## Findings and remediation

### S1 — package artifact generation bypassed host ceilings

Original behavior used default exporter options or only a hardcoded final XLSX limit; package HTML/Text/PDF and preview did not inherit `WorkbenchServer.exportBudget`.

Integrated locations: `mantra-server/.../WorkbenchServer.kt:31`, `PackageWorkbenchRoutes.kt:57,72`; `mantra-workbench/.../packages/PackageWorkspaceCatalog.kt:238–265,456–500`; `mantra-workbench/.../PaperExportLimits.kt:11–126`.

The old constructor and export/preview JVM signatures remain. New per-call overloads accept the actual host `ExportBudget`, and server routes pass it to all four formats and preview. XLSX inherits sheet/cell ceilings, the same declared reader controls and final encoded byte limits. HTML/Text/PDF use one owner-confined reader; detached paper text/cells and padding amplification are checked before rendering, UTF-8 retention is bounded and PDF gets explicit page/row/byte limits. Only real limit failures are mapped to `TOO_LARGE`/HTTP 413; deadline/cancellation are rethrown. The ordinary directory-workspace exports now share the paper ceilings.

Status: integrated source inspected; the package HTTP budget regression and all five `PackageResourceBoundsTest` regressions pass in actual XML, and the fourth full local gates succeeded. Final RC/source-aligned acceptance remains separate. These ceilings do not claim exact JVM heap accounting or a checkpoint inside every third-party renderer instruction. Presentation-model construction can retain its already bounded run/view before final paper preflight; output limits are not a complete allocator budget.

### S2 — count-limited migration cache retained FULL evidence graphs

Original previews retained before/after calculation views and FULL trace graphs for up to 32 entries, without a retained source-payload bound.

Integrated locations: `PackageMigrationWorkflow.kt:8–67`; `PackageWorkspaceCatalog.kt:372–399`. The cache retains immutable `MigrationPlan` intent and exact token only, bounded to 32 entries and 4 MiB encoded UTF-8 intent. Apply rebuilds the original plan under the reviewed base/target revisions and requires an identical token; it does not adopt a changed graph or reuse stale FULL results. Successful commit consumes the token, then records undo history. Cache misses/expired previews and actual stale review produce conflict.

Status: source inspected; actual HTTP reviewed-token/replay/history/byte-CAS/graph-CAS tests pass. `PackageResourceBoundsTest` also passes the actual intent-count/payload and changed-source re-evaluation tests. The 4 MiB bound counts UTF-8 intent strings, not object headers or arbitrary allocator memory; these are functional bound regressions, not measured retained-heap evidence.

### S3 — HTTP body duration and accepted-work queue were unbounded

Integrated locations: `WorkbenchServer.kt:34–43,74–158`, `WorkbenchTransport.kt:3–21`.

One shared executor now has four workers plus a 16-element queue and rejection. Accepted overload is closed by the verified JDK provider; the code does not promise an HTTP 503 body on that path. The scheduled body-read deadline is 30 seconds by default, with a shorter explicit test seam. The timer state is atomic: only `0=reading → 2=expired` closes the exchange; completed body reading changes `0 → 1=finished`. A timer already running after cancellation cannot close a completed request. Expired reads do not dispatch or write an error response. Timer/executor/server resources close on shutdown.

Actual evidence: the recorded transport test passes with 36 stalled-body sockets, 150 ms explicit test body timeout, connection closure and a subsequent 200 response. The separate final installed-package smoke starts a fresh process with `sun.net.httpserver.maxReqTime=2` and `timerMillis=100`, closes an incomplete header in 2064 ms and then serves status 200. Header parsing precedes handler/body deadlines; the two tests exercise different layers. The two-second setting is a startup request-limit override, not the 30-second production default or a measured startup duration.

Process-wide provider defaults are installed before this service creates its first JDK server: 128 connections, 100 headers, 32 KiB header bytes, 30-second requests, 300-second responses and a one-second timer tick. Explicit user JVM properties are preserved. The JDK provider reads these globals once; an embedding process that initialized another `HttpServer` first must provide equivalent JVM startup flags. They are not isolated per-workbench settings. IPv4 and optional IPv6 use separate `HttpServer` instances; do not present the provider's connection ceiling as a proven combined two-listener total. These properties were checked by the coordinator against primary OpenJDK 21 sources; this review does not generalize their lifecycle to every JDK provider/version.

### S4 — captured text replaced malformed UTF-8

Integrated locations: `BoundSources.create`, `ImportText.decodeImportUtf8` and uploaded text paths in `ImportFiles.inspect`.

The original captured CSV/JSON `bytes.toString(UTF_8)` substituted malformed bytes, unlike strict file readers. The integrated decoder uses `CodingErrorAction.REPORT` for malformed and unmappable input before constructing text adapters, preserving technical `MANTRA-DATA-UTF8` with source location on captured bindings. Captured XLSX remains binary. Browser import inspection enforces 1 byte–10 MiB before decoding; captured imports use the loader's byte limits and original source order. The shared CSV scanner checkpoints every 1024 characters and calls the host row callback before retaining each non-header row; the controlled loader charges imported rows there, without a second parsing charge. Inspection remains read-only. This is not an independent allocator/row limit on an arbitrary direct public `CsvSource` caller that supplies default callbacks.

Status: all six actual `WorkbenchImportHardeningTest` regressions pass. They exercise malformed CSV/JSON in captured sources and uploads, valid Unicode, retained numeric zero/false, detached captured bytes/provenance after backing edits, workbook errors and two-dimensional dates. The passing captured-JAR HTTP regression separately proves use after original-container deletion. These checks are attributed to executed XML, not tests run by this document reviewer.

### S5 — spreadsheet errors silently became missing/default facts

Integrated location: `mantra-excel/.../XlsxSource.kt`, capture and `valueOf`. Formula/literal `CellType.ERROR` produces a technical `MANTRA-DATA-XLSX-CELL` finding with node/coordinate or row/column ownership; invalid imports return no partial fact map. Legitimate numeric zero, false and blank retain distinct handling. Failed formula evaluation is diagnosed rather than converted to a successful default.

Host `MantraException.runFailure` from capture/preflight callbacks is rethrown unchanged before XLSX-specific failures are translated into source diagnostics. Cell evaluation also preserves Mantra/cancellation exceptions. The executed regression compares original exception and failure identity for every `RunFailureKind`; streamed preflight cancellation throws at its actual eighth checkpoint. This avoids relying on a later checkpoint to repair swallowed cancellation, deadline or budget semantics.

Status: the ten-test `XlsxImportHardeningTest` suite and six workbench import regressions pass, including literal/formula error rejection and prevention of a defaulted captured calculation. POI evaluation is not claimed to expose a checkpoint during each internal function invocation.

### S6 — compressed workbook size did not bound expanded content before POI

Integrated locations: `XlsxContainerPreflight.capture/verify/centralContents`, `XlsxSource.read` and upload preflight in `ImportFiles.inspect`.

The per-import preflight retains at most 10 MiB compressed bytes, 1000 entries, 16 MiB actual expanded bytes per entry and 64 MiB total per ZIP view. It checkpoints streamed chunks before POI construction and does not change process-global POI ZIP policy. Required OOXML parts and duplicate entries are checked. Local-header and central-directory views each stream actual expanded data under their independent ceilings; matching name/expanded-size/SHA-256 inventory is required before POI receives the archive. Both scans perform real work; the 64 MiB ceiling is not a fabricated combined-work counter. There is no extraction or temporary archive directory.

Constructor qualification: read-only inspection of the resolved POI 5.5.1 JAR and official release source found `XSSFWorkbook(InputStream) → PackageHelper/OPCPackage → ZipPackage(InputStream) → ZipHelper → ZipArchiveInputStream/ZipInputStreamZipEntrySource`: the **current InputStream constructor consumes local-header entries**. The crafted central-shadow regression demonstrates that the preflight independently rejects an oversized central view concealed inside a small local view. It hardens container ambiguity and future constructor changes; it does not show this current constructor executing a central-only expansion attack.

Status: actual compressed/per-entry/total/count limit and central-shadow regressions pass in the ten-test Excel suite; uploaded expanded-limit rejection passes in the six-test workbench suite. The test also confirms no POI global inflate-policy mutation. Fixed import ceilings are separate from host row/run controls; no shared kernel byte budget or live usage counter is invented. After preflight, POI's bounded internal parse is still not synchronously checkpointed at every XML/ZIP operation. No filesystem path is extracted from a ZIP entry, and preflight alone is not claimed to sanitize every optional OOXML relationship.

### S7 — multi-axis named inputs silently disappeared on import

Integrated locations: `XlsxNamedCoordinates.decode/orderedTableNames/insert` and `XlsxSource.read`. The old importer ignored names containing more than one member separator and ignored their base multi-cell area, allowing defaults to replace omitted facts.

The importer reads table domains first, recovers the complete canonical axis order and maps sanitized names back to exactly one original member per axis. It builds nested keyword-keyed facts. Input-ID/member/column sanitization collisions, duplicate coordinates, incomplete names and unsupported ranges become technical findings. Named table cells are ordered by physical worksheet index and row, then declared column order, independently of defined-name enumeration. A record split over worksheets/rows or multiple records sharing one physical row is rejected instead of inventing order; error row indices follow this physical record order. Source date conversion honors the workbook's 1900/1904 window, requires a finite whole-date serial and rejects fictitious 1900 serial 60. Keyword cells retain keyword identity.

Status: actual export/import round-trip checks every tested two-dimensional coordinate including numeric zero, false, keywords and dates; incomplete/scalar-range, sanitized-member collision, reverse table-name enumeration and ambiguous row-geometry regressions all pass in `XlsxImportHardeningTest`. The source supports both date windows; the cited round-trip does not claim exhaustive date-serial coverage. The named-cell format rejects shapes it cannot distinguish rather than flattening/dropping them or substituting engine constants. This is a correctness/security repair, not interoperability proof for every external naming convention.

### S8 — LSP read bound was only an earlier file-size observation

Integrated locations: `ConfinedSources.kt:45–71,125–137`. Files now stream under actual per-file/aggregate retained-byte limits with checkpoints and at most one probe byte beyond remaining capacity. The final file open uses `NOFOLLOW_LINKS`; decoder error reporting preserves strict UTF-8, and open text overlays remain versioned and bounded.

Status: integrated source inspected and existing resource test XML passed. Canonical confinement plus final no-follow is not secure-handle protection against hostile replacement of an ancestor by another same-permission process. LSP workspace paths remain an explicitly trusted/cooperative editor capability. The language server reads `.mantra` sources only; it does not calculate case facts, load arbitrary executable archives or invoke workbench runners.

### S9 — JSON-RPC framing and dispatch did not fully enforce the advertised protocol

Previously integrated: `JsonRpcFraming.kt:22–28` rejects duplicate JSON properties and trailing documents; existing header/body/depth/number ceilings remain. Actual existing framing/resource tests are recorded above.

Integrated `JsonRpcFraming.kt` parses **all** MIME parameters with whitespace/quoted values, permits only a single `utf-8` or `utf8` charset and rejects unsupported/duplicate charset declarations. Unknown quoted parameters containing a semicolon cannot masquerade as a charset. A strict REPORT UTF-8 decoder feeds Jackson's String parser; byte-array encoding sniffing can no longer accept UTF-16/32 or replace malformed bytes under an advertised UTF-8 body. The 1 MiB raw-body, 8 KiB header, depth-64 and 100-character numeric bounds and strict JSON parser options remain.

Integrated `LanguageServer.receive` distinguishes an invalid envelope from a valid notification. Invalid missing-ID envelopes return `-32600` with `id:null`; usable explicit IDs remain correlated, including the existing cancellation test's acknowledgment. Invalid ID shapes cannot be echoed as an invalid response ID. A request carrying an ID with `$/cancelRequest` or `exit` is rejected and replied to; it cannot cancel a pending token or terminate the server. Valid unknown notifications and valid cancel/exit notifications retain their normal no-response behavior.

Status: all 21 current LSP tests pass, including three charset/body and two actual-framed envelope regressions. They cover supplementary text, spaced/quoted/repeated charset, UTF-16/invalid UTF-8, null-ID errors, live request correlation, no extra notification reply and graceful exit. Both latest installed standalone and CLI LSP smoke logs separately confirm real initialization, definitions, hover, references, versioned rename, shutdown/exit 0 and process/workspace removal. The 13 editor protocol tests also pass; no IDE UI was started. The [LSP 3.17 specification](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/) remains the primary framing/encoding/lifecycle reference. Rejection may end malformed-frame transport; recovery from every invalid frame is not claimed.

### S10 — present non-text directoryPolicy silently became the default

Original location: `PackageWorkspaceConfig.kt:72–76`. `.textValue()` on a boolean/object/null produced null, selecting strict mode instead of rejecting an ill-typed config. This did not escalate to trusted-local, but violated strict field validation and could conceal a misconfigured host capability.

Integrated `PackageWorkspaceConfig.policy` distinguishes an absent field (default strict) from a present field (required text and one exact supported value). Two public config tests use an actual classpath package, preserve missing/strict/trusted textual behavior, and reject null/boolean/number/list/object on both mount and explicitly writable root policy fields.

Status: both actual `PackageDirectoryPolicyTypeTest` cases pass. No filesystem fallback, mount policy broadening, new directory authority or Native/JDK dependency is added.

## Authority and race boundaries

### Package reads and macOS policy

`PackageLoader.directory` defaults to `STRICT_HANDLES` (`PackageLoader.kt:15–33`). Secure directory handles traverse child components and final channels with no-follow, and unsupported filesystems fail closed. The default must stay strict even where a macOS provider does not offer `SecureDirectoryStream`; a platform limitation is not authorization to silently choose a weaker policy.

`TRUSTED_LOCAL` is an explicit host/CLI/config choice (`DirectoryAccess.kt:13–37`). It checks canonical components, file identity/type/size/mtime before/after capture and at final verification. It is a cooperative local-root policy and cannot defeat malicious rename-and-restore by another process with the same permissions. Explicit root aliases such as `/tmp` may be canonicalized once; captured resource paths never infer symlink permission. An explicitly supplied `DirectoryAccess` is a host capability whose security contract must be implemented by that host.

Classpath capture pins one unique local manifest container (`PackageLoader.kt:52–114`), copies bounded resource bytes and verifies declared lengths/digests (`116–144`). It does not extract a JAR or read a remote JAR URL. A dependency declaration alone does not mount a package or grant a read capability. DATA resource bindings must match authored source order and roles; captured adapters have no disk fallback. Package snapshots expose no writer. Editable cases require a separate explicit store and cannot overwrite read-only package resources.

### Migration concurrency

`MigrationCoordinator.apply` rechecks exact token, original source hash/current graph, exact target resources and recalculated candidate before authorizing a write (`Migration.kt:173–190`). The store must check original bytes under its write lock and perform atomic replacement. A misleading custom store that ignores authorization is outside this guarantee.

`FileMigrationStore` and trusted-local stores serialize cooperating writers with JVM/sidecar locks and require atomic replacement; they do not offer a transactional conditional rename against an arbitrary noncooperating same-permission writer between the final check and rename. Source-byte CAS protects the destination source, not every linked source. A graph-epoch lock covering all participating writable-source capabilities is needed for a stronger cooperative multi-source claim. One synchronized catalog instance does not synchronize another process or catalog. Captured package resources are immutable, but an editable linked host case is a separate writer capability.

The comment at `Migration.kt:76–80` about noncooperating writers being guarded by source CAS should be read with this check-to-rename limitation; final public documentation must not claim hostile filesystem isolation. Actual seam tests prove detection of the changes they introduce, not the elimination of every possible racing writer.

### HTTP and client processes

`WorkbenchServer` binds IPv4 loopback and optionally IPv6 loopback (`47–56`), enforces a single validated loopback Host and a random session token for POST (`84–98`), with no cross-origin read allowance. It is a local host service, not a remote/authenticated multi-user server. Host validation reduces DNS-rebinding exposure; it does not authenticate arbitrary local processes. GET runs and exports are readable local operations and still need bounded resource controls. The two bounded SSE streams remain a separate admission mechanism.

Installed smoke scripts and the editor bridge own only their launched process(es), log/cache/workspace and test paths. No browser/IDE binary, normal profile or shared cache is installed or changed. Actual marker emission follows request success **and verified cleanup**, not merely successful startup. Runtime interruption cleanup must remain tested/documented; a surviving process/path means incomplete work, not a pass.

### Workbook/PDF and cancellation lifecycle

The generated workbook must detach construction-only builders/readers before publication and close its reader on failure as well as success. Finished replace/rows/address operations must not re-enter a closed run context. Existing detached lifecycle and actual edited/reopened workbook tests remain required acceptance evidence; source inspection alone is not a proof of complete object-retention bounds.

PDF uses bundled fonts and a native PDF renderer; no browser/CDN/remote font renderer is required. It closes document/page streams and enforces actual page/row/output-byte ceilings. All-page visual verification is a separate evidence item, not supplied by binary framing alone. LSP synchronous compilation, POI parsing/evaluation and PDF library internals do not expose cancellation at every internal instruction; host checkpoints bound entry/chunk/iteration boundaries. No report should invent shared kernel budget usage or immediate interruption guarantees unsupported by the pinned public APIs.

## HTTP schema/runtime conformance

The package wrapper remains `mantra.packages/1`; embedded strict `mantra.workbench/4` is unchanged. Generated declarations include binding case/read-only ownership, exact schema version, source validity/provenance and target parameter-selection revision. The coordinator corrected `effectiveLayer` from an invented enum to nullable text because actual values can be the selected parameter-set ID. Date policy and selected/effective values are engine/metadata projections; they are not inferred by UI arithmetic.

Actual HTTP schema regression rejects invented fields and missing ownership/endExclusive. The UI validates the outer contract/field set/revision shape and embedded tag, then consumes generated types; TypeScript types are not an exhaustive runtime validator for every nested response. This limitation is explicit. Request routes independently reject unexpected fields, duplicate/trailing JSON and unknown query parameters before mutation; technical current failures carry current diagnostics/revision and cannot serve stale successful Explain or PDF evidence.

Package Explain currently accepts node coordinates and rejects unsupported table-cell addresses. That is a capability limit, not authorization to fabricate addresses or reuse stale evidence. Aggregate/member-map navigation must use actual public addresses and engine evidence as provided.

The identified protocol/config/import repairs are integrated with the passing evidence above. This is a focused team source review and regression ledger, not an independent third-party penetration test or proof that every HTTP/schema/resource path is vulnerability-free. Final frozen-source review and RC/CI/performance exit decisions remain with the coordinator.

## Final acceptance checklist for the coordinator

1. Completed locally: protocol/config/import repairs, full fourth-gates check, actual ABI counterexample, six local library inspections and five fresh-cache POM-only consumers. Record the selected RC/version, exact command and final source identity; later source/version changes still require source-aligned checks.
2. Completed locally: 33 HTTP, 21 LSP, 16 import, 5 package-bound and 2 policy-type regressions, installed CLI/LSP/package smokes and Browser package flow. Preserve their actual results, including 36 stalled bodies, 2064 ms incomplete-header closure/200 recovery and verified cleanup, in final CI.
3. Pending: coordinator review of the final frozen source, selected RC's CI receipt, strict final documentation/site and original frozen performance/batch/JFR acceptance. Functional or historical performance evidence cannot replace current measurements.
4. Retain explicit cooperative-filesystem, third-party checkpoint, runtime-type-validation and unsupported capability boundaries. Do not turn these tests into a broad CVE/signature audit or independent penetration-test certificate.
5. Keep Maven namespace/signing, public Normein availability and actual public publication as a separate gate. Local staging, application package versions and an RC build are not public Maven release evidence. Only the coordinator can declare the final T5/M6 exit after its remaining evidence is complete.

## Dependency review

The current integrated build pins POI 5.5.1 consistently across library, application tests and benchmarks, and the final installed-smoke evidence uses rebuilt 5.5.1 distributions. Apache lists it as the stable release with dependency security updates ([downloads](https://poi.apache.org/download.html), [project news](https://poi.apache.org/)). The current InputStream constructor was separately inspected in the actual resolved JAR and official release source; its local-header behavior qualifies the archive regression above. PDFBox 3.0.8 includes the published example-module path fixes ([security advisory](https://pdfbox.apache.org/security.html)); this project generates PDFs and does not use the embedded-file extraction example. This is a bounded dependency review, not comprehensive CVE coverage, release-signature auditing or an isolation guarantee.
