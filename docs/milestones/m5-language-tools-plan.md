# M5 language tools: implementation and protocol test plan

Status: implementation preparation, not delivered. This plan adds no server, client, dependency or
public API. It was checked against the current Mantra sources and the locked Normein 0.3.0 public
API. M3 acceptance and M4 delivery remain independent of this work.

The [roadmap](../roadmap.md#m5-开发者体验v06) requires `mantra lsp` diagnostics, host/formula completion,
hover, definition navigation, references and node rename, with thin VS Code and IntelliJ clients.
[RFC 0001-H](../rfc/0001-normein-dsl-kernel-extensions.md#h-authoring-services-for-embedded-expressions-withdrawn-domain-layer-depends-on-b)
places the host language tooling in Mantra. No Normein modification is proposed.

## 1. What the current sources actually provide

| Existing mechanism | Evidence | Reuse and limit |
| --- | --- | --- |
| Public form tree and document reader | [`Forms.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/read/Forms.kt): `Document.read`, `location`, `slice`, `formula` | Every projected form has source offsets. `readDocument` returns failure diagnostics, not a partial recovery tree. |
| Public diagnostic model | [`Diagnostics.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/Diagnostics.kt): `SourceLocation`, `Diagnostic`, `DiagnosticSink`, `MantraException` | Locations include source name, one-based line/column and optional offsets. Preserve code, severity, category and owning document. A missing span must not become an invented precise token range. |
| Schema model with locations | [`SchemaReader.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/read/SchemaReader.kt), [`Schema.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/model/Schema.kt) | Inputs, parameters, dimensions, items and `defn` retain locations; `Formula` retains exact source, location and `DslForm`. Most declaration locations cover the whole form, not only its name. |
| Case values and authored extensions | [`CaseReader.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/read/CaseReader.kt) | `inputLocations`, `paramLocations` and `inputCells` locate supplied values, rows and columns. They are not a declaration/reference token index. Bindings, extension formulas and named definitions retain their own formula locations. |
| Typed links | [`CaseLinks.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/model/CaseLinks.kt), [`LinkReader.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/read/LinkReader.kt) | A mapping distinguishes source `:node` from destination `:input` and pins the source schema/version. Model locations cover the mapping; exact value-token spans still require the public form tree. |
| Parameter and presentation readers | [`ParameterSetReader.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/read/ParameterSetReader.kt), [`LayoutReader.kt`](../../mantra-render/src/main/kotlin/com/xqiou/mantra/render/layout/LayoutReader.kt) | Validate these document kinds with their real readers. Parameter keys, layout targets, dimensions and node columns must enter the same typed host reference index. |
| Static compilation | [`Mantra.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/Mantra.kt): `inspect` | Public inspection compiles without evaluating formulas. Its returned view does not expose compiled occurrence-level references or arbitrary formula authoring contexts. |
| Static case graph checks | [`CaseGraphInspector.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/api/CaseGraphInspector.kt) | Does not evaluate formulas or materialize link values. Its resolver still determines what files/data are loaded; the current workbench loader imports declared external facts. It is not automatically a safe lightweight editor loader. |
| Current formula tooling | [`FormulaAuthoring.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/api/FormulaAuthoring.kt) | Public `complete`, `hover`, `check`; factories only for formula slots and extension lines. The current catalog-only authoring service does not establish complete typed local-variable assistance. |
| Host compiler mapping | [`FormulaCompiler.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/engine/FormulaCompiler.kt), [`PrevLowering.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/engine/PrevLowering.kt) | Core already passes `hostPosition`, rewrites `mantra/` without changing length, and retains an authored source overlay for `prev`. These internals must remain behind a public core projection. |
| Catalog discovery | [`FunctionCatalog.kt`](../../mantra-core/src/main/kotlin/com/xqiou/mantra/core/api/FunctionCatalog.kt), [`Main.kt`](../../mantra-cli/src/main/kotlin/com/xqiou/mantra/cli/Main.kt): `catalog` | Public function summaries exist. Host-form descriptions are currently private CLI literals, and there is no shared structured host catalog. Extract that catalog before duplicating it in an editor. |

The locked public `DslCompiledExpression` exposes `normalizedAst`, `references`,
`deduplicatedReferences`, `lexicalReferences` and `sourceIndex`. `DslReference` retains occurrence
span, root/static path, type, origin and dynamic status. `DslLexicalReference` identifies a lexical
binding by `DslLocalId`. The public source index distinguishes expressions from named definitions.
These are suitable inputs for a core-owned static projection; runtime `NodeTrace.references` and
Explain event IDs are unsuitable as a complete editor index because execution skips branches.

Normalized lambda parameters and let bindings identify locals but do not carry a separate exact
binder-name span. Pair their semantic identity with the original public form tree. Do not manufacture
definition ranges from reference occurrences or assume a trace node ID is a persistent symbol ID.

## 2. Implementation boundaries

Use three layers. Names below are proposals, not currently callable APIs.

1. **Core authoring projection:** extend `FormulaAuthoring` through `core.api`, with a typed formula
   target and immutable analysis result. Core owns compiler scopes, named definitions, reference
   identity, `prev` source overlays and lexical binding projection. External modules never import
   `core.engine`, `CalculationPlan`, `Planner` or executable caches.
2. **Transport-neutral language service:** a proposed `mantra-language` module owns document
   overlays, URI/source mapping, host-form classification, the workspace symbol graph, diagnostics,
   completion/navigation and safe rename edits. It consumes public core/read/render contracts.
3. **Protocol adapter and clients:** a proposed `mantra-lsp` module maps that service to standard LSP;
   the CLI launches it. VS Code and IntelliJ manage one server process and translate editor state,
   capabilities and edits. Clients contain no calculation, host type checking or reference binding.

An initial public projection should cover ordinary lines, choices/options, conditions, checks,
reconciliation sides, required-column conditions, dimension member conditions, case-bound slots,
extension lines and named definitions. A target identifies the authored host owner and formula role,
not a private vertex class. The proposed result needs:

| Projection | Required contents |
| --- | --- |
| Formula assistance | Existing completion/hover/check plus the same result type, dimensions, row scope and `:uses` restrictions as calculation compilation. |
| Static occurrences | Authored URI/range, exact binding identity, reference kind, root/path or named callable, lexical owner, inferred type, and definite/dynamic status. |
| Definitions | Exact name-token range, owning schema identity and source, host declaration kind, or lexical binder identity within its source owner. |
| Diagnostics | Real reader/compiler diagnostics with owning source and coordinate space; optional original structured kernel detail only when actually available. |
| Analysis state | Source versions/hashes, selected exact schema context and an explicit completeness flag. A partial index cannot claim complete references or authorize rename. |

Do not expose synthetic previous roots or `mantra-internal/previous-select`. A global host `prev`
target is an authored node reference; a local or named callable called `prev` retains its lexical or
named identity. Named-definition diagnostics belong to their actual defining file. Never add an
expression's start offset a second time after `hostPosition` has already projected it.

The first spike must verify completion/hover within typed `let` and `fn`, named definitions,
`calc/converge` callbacks and `prev` fallbacks. Normein's environment/scope authoring constructor
provides semantic assistance; the existing filtered catalog constructor alone does not. A working
blend must preserve Mantra's host lowering and reference restrictions. If a precise required local
query cannot be implemented using the locked public APIs, report the concrete failing case before
proposing an RFC; do not guess the type or expose a helper as a user callable.

Extract a structured host catalog shared by CLI, docs and tooling. Core owns schema/case/parameter
forms and options; render owns presentation forms, presets and columns. Reader/catalog agreement
tests should cover new forms and retain generic application attributes. Catalog prose must describe
actual semantics and versions, including `mantra.calc@2`; it must not promise M4 APIs prematurely.

## 3. Document snapshots and static analysis

Maintain immutable `(canonical URI, text, document version, content hash)` snapshots. Open buffers
override disk content, including included fragments and linked cases. Apply every incremental change
in notification order to the previous version, then capture one workspace epoch for a request.
Analysis/publication must record that epoch. Discard superseded diagnostic work rather than mixing
old schema scopes with current references or replacing a current failure with an old successful tree.

Use a source registry to map `SourceLocation.source` to canonical URIs; a basename alone is
ambiguous. Preserve source bytes/text and line endings. Kotlin string offsets are UTF-16 code units;
the locked `DslSourceCursor` starts lines/columns at one and advances through string indices. Prefer
offsets, rebuild the line index from the exact snapshot, and convert once. LSP uses zero-based
positions and negotiates their encoding; the initial implementation selects supported UTF-16.
[Official position specification](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/position.md).

Workspace discovery is confined to explicit workspace roots and file URIs, with canonical symlink
checks. Unsaved `untitled:` buffers may get syntax/host assistance; relative include/link resolution
requires an explicit saved/context root. Bound file count, source bytes, analysis queue and retained
snapshots. Resolve schema/version, parameter/layout IDs and include/link dependencies from real
metadata; use exact identities, never a convenient sibling or implicit latest version.

Use a public editor resolver/projection that reads document metadata and declared types without
importing CSV/XLSX facts. Existing `CaseGraphInspector` can be reused with that resolver for relevant
static graph checks. Do not launch `CaseGraphRunner`, a calculation session, exports or callback bodies
on keystrokes. Dynamic member existence, actual linked nil values and BUSINESS outcomes are execution
concerns, not fabricated static errors. Shared fragments and parameter/layout documents may have
multiple schema contexts: index their owners and require an explicit context when typed assistance
is ambiguous.

For incomplete edits, the strict reader remains the diagnostic authority. A small host context
classifier may recognize lexical delimiter/comment/string states and already projected form spans
solely to choose syntactic completion. It is not a second accepting grammar. If a formula owner or
binding is ambiguous, offer applicable host syntax and withhold typed navigation/rename. Do not
publish a last-valid semantic index as if it described the broken current document.

Cancellation and bounded scans belong to the request, with checkpoints before/after compilation and
before publication. The current synchronous compiler/authoring call has no per-call cancellation
parameter; do not claim instant interruption or invent a consumed-kernel-budget counter. Coalesce
pending edits and bound source/analysis work. A requirement for a hard in-call compiler deadline
would need a separately proven public control or process isolation.

## 4. Feature behavior

| Feature | Server behavior and acceptance boundary |
| --- | --- |
| Diagnostics | Reader parsing/structural findings first, then actual static formula/graph checks. Preserve MANTRA/DSL code, message and severity; carry category and source ownership as optional diagnostic data. Do not extract invented cause structure by parsing message strings. Empty diagnostics clear the previous published version. No implicit runtime/BUSINESS evaluation. |
| Completion | Host heads/options from the shared catalog; values/types from the current document kind; formula roots/fields/functions from the precise typed context. Preserve replacement ranges and `:uses`; hide private helpers. Convert function snippets to plain text unless the client supports snippets. |
| Hover | Catalog documentation, declared/inferred type, dimensions and source declaration, with a range from the current snapshot. No incidental execution for a current value. Hover on a library function documents it; unresolved host references do not navigate to a guessed declaration. |
| Definition | Exact declaration name or meaningful file target for include/link paths and pinned schema/parameter/layout identities. Formula roots, `mantra/<id>`, `all.<id>`, and global `prev` targets resolve by semantic identity; local binders resolve lexically. Never navigate to generated roots. |
| References | Combine compiled authored occurrences and typed host references, including unexecuted branches and named bodies. Respect `includeDeclaration`. Dynamic paths without one proven target remain explicitly ambiguous; they cannot be converted to a definite edit range. |
| Rename | Initially node/input identifiers, including formula-slot nodes, within a fully indexed exact schema context and its confined consumers. Return versioned edits after speculative reanalysis; never perform a text search/replace or write the user's files directly. Other declaration classes and local-variable rename can be later extensions. |

Host references include the identifier in `(field ...)`, `:headline`, `:per`, aggregate operands,
boundary axes, table/dimension options, case `(inputs ...)` keys, `(bind ...)`/`(extend ...)` targets,
layout target/node columns, and typed link mapping fields. Associate each with its actual category and
schema owner. A quoted label or arbitrary literal that happens to equal a node name is not a use.
Case parameter keys refer to parameters; dimension/member keys are not automatically node references.

For typed function/lexical references, use the compiled binding and original name-token spans.
`(let [closing 7] closing)` does not reference the schema's `closing` node. Root, member-map field,
table column, library callable, named callable and lexical local are separate identities. Selected
named definitions in one compiled expression are not an inventory of every unused function: the
language service must analyze authored definition bodies to establish complete references.

## 5. Rename as a semantic operation

`prepareRename` requires a definite editable declaration, a complete current ownership/reference
graph and a supported identifier class. `rename` validates the new name against Mantra's identifier
and reserved-name rules, checks duplicates and lexical capture, and applies candidate edits to
temporary in-memory snapshots. Reanalyze every affected schema/consumer and compare binding
identities before returning edits. A valid compile alone is insufficient: a formerly rooted
reference must not become a same-spelled local variable.

Preserve syntactic representation: edit a keyword's identifier without removing its colon, retain
quoted address strings/escaping, and change only the named component of `mantra/<id>` or `all.<id>`.
Do not rewrite a whole dotted expression when only its root component changes. Dynamic access,
unresolved includes, unknown consumers, read-only sources or an incomplete analysis block rename
with a precise reason rather than produce a partial refactor.

Version identity is part of the index. Equal IDs in two schema versions are not the same node. A
shared included declaration has multiple owning contexts; deduplicate its edit range and validate
all known owners. If that graph is not complete, refuse the operation. Cross-case `:from :node` uses
the pinned source's schema, while `:to :input` uses the consumer's schema. Only matching owners change.
Node rename does not invent a version bump, rewrite a case's schema version, or claim to implement
M4 migration of existing cases/packages.

Return nonoverlapping `WorkspaceEdit.documentChanges` with open-buffer versions. LSP's versioned
document edits let clients reject an edit against the wrong document version; annotations are
capability-gated. Before returning edits, recheck all source versions/hashes. The standard optional
version for a closed file cannot atomically pin its on-disk content hash. Initially, require all
affected documents to be synchronized before producing rename edits; otherwise return the affected
URIs and a reason, not a partial edit. A thin client can open/synchronize the declaration and reference
documents, then reissue rename without creating visible editor tabs. No custom hash field is assumed
to exist in the standard protocol. A future guarded closed-file extension needs explicit negotiation
and real client tests before lifting this restriction.
[Official document-edit specification](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/textDocumentEdit.md),
[official rename specification](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/language/rename.md).

## 6. Protocol and thin clients

Start with a pinned LSP 3.17-compatible capability subset; this does not claim that 3.17 is the latest
specification. Select a protocol library/version during the transport spike and verify its JVM
baseline and license before adding it. Proposed launch: `mantra lsp --stdio`. Standard output is
exclusively JSON-RPC frames, UTF-8 bodies and byte-counted `Content-Length`; diagnostics/logs use
standard error. Implement initialization, shutdown/exit, cancellation and synchronized-document
notifications together. Advertise only implemented capabilities.
[Official protocol baseline](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/specification.md).

Support `textDocument/completion`, `hover`, `definition`, `references`, `prepareRename`, `rename` and
versioned push diagnostics initially. Pull diagnostics can reuse the same diagnostic snapshot when
needed for client compatibility; it must not create a second semantic path. Clients without an
optional capability receive its plain supported form, not undocumented custom fields masquerading
as standard protocol. Request IDs may be strings or integers; notifications receive no response.

**VS Code:** register the `mantra` file type and `.mantra` selector, use a pinned language-client
adapter, spawn the configured installed command with argument vectors, and forward workspace roots,
documents, requests and edits. The extension supplies activation/configuration, cancellation,
process disposal and useful launch errors. It does not download a kernel or silently install a
runtime. Use the official separation of client and server, not a second TypeScript analyzer.
[VS Code language server guide](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide).

**IntelliJ, including Community/open-source builds:** the default approach uses public IntelliJ
Platform APIs and the same stdio server, without a dependency on the commercial-only LSP integration
SDK. A small language/file-type and PSI/token facade serves editor ranges and native UI. Completion,
documentation, definition navigation, find usages and rename translate protocol results through
public extension points. `PsiReference`/reference contributor plumbing can represent navigation;
completion uses a platform contributor. Neither adapter binds identifiers itself.
[Public PSI reference APIs](https://plugins.jetbrains.com/docs/intellij/psi-references.html),
[completion extension points](https://plugins.jetbrains.com/docs/intellij/code-completion.html).

The IntelliJ spike must prove asynchronous/background protocol requests, cancellation and
version-checked edits integrated with one undoable write command and native refactor preview. Never
wait for server I/O on the event-dispatch thread or retain invalid PSI objects across a request;
capture text/version first and map current results back to valid document/PSI ranges. The facade's
lexer is for token/range presentation, not a duplicate host grammar/type checker. Resolve null or
ambiguous protocol targets honestly.
[Platform threading rules](https://plugins.jetbrains.com/docs/intellij/threading-model.html),
[rename integration](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html).

Pin and compile against the tested Community-compatible SDK before promising a minimum IDE version.
An optional official LSP-SDK adapter may be offered for supported IDEs, but is not the universal
client: JetBrains documents exclusions for IntelliJ open-source builds/Android Studio and a
2026.1.4 API refactor. Do not infer compatibility with those environments from an SDK-only test.
[Official LSP integration limits and API changes](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html).

## 7. Work packages and ownership

| Package | Owned scope | Concrete completion evidence |
| --- | --- | --- |
| L5.1 snapshots/transport spike | Proposed language snapshot store and LSP transport; CLI launch integration separately coordinated | Stdio handshake, UTF-16 conversion, incremental edits, stale publication and shutdown tests; select/pin protocol library. |
| L5.2 shared catalog | Core/render public catalog additions; CLI/docs switch to those projections | Every accepted host form has one description; internal helpers excluded; catalog/reader/CLI fixtures agree. |
| L5.3 core static authoring projection | `core.api` facade plus core-private compiler bridge | Ordinary formula roles, named sources, local identities and `prev` overlay regressions; no execution; no external engine imports. |
| L5.4 language analysis/features | Transport-neutral host index, resolver and six feature methods | All document kinds and shared/linked contexts; exact ranges and complete/incomplete state; protocol-independent tests. |
| L5.5 safe rename | Identity-based workspace edit builder and speculative reanalysis | Rename parity/capture/version/closed-file guards; one complete edit or explicit failure, no partial write. |
| L5.6 protocol server | Mapping layer and `mantra lsp --stdio` | Real framed subprocess scenarios cover all advertised capabilities and cleanup. |
| L5.7 thin clients | Independent VS Code and Community-compatible IntelliJ adapters | Both compile/package; process lifecycle and identical diagnostic/completion/navigation/refactor results from the same server. |
| L5.8 docs/exit verification | User-facing setup and first-schema editor walkthrough | Reusable tutorial, all eight apps and newly authored neutral files; IDE compatibility matrix with actually tested versions. |

L5.1 and L5.2 can proceed independently. L5.3 supplies L5.4; complete L5.4/L5.5 before advertising
rename. Clients can begin process/lifecycle scaffolding after L5.1, but feature delivery waits for
server parity. Reuse M4 compilation handles only after their actual API lands; do not block the
initial static service on an invented handle shape.

## 8. Protocol and semantic test plan

Use deterministic in-memory snapshots and independent expected token/range fixtures. Do not normalize
incorrect ranges or approximate reference ownership to make fixtures pass. No browser is needed.

| Test group | Required scenarios and assertions |
| --- | --- |
| P1 framing/lifecycle | Multibyte Unicode JSON byte length; split header/body reads; multiple frames; string/integer IDs; notifications without replies; invalid/oversized messages; initialize/shutdown/exit; stderr log isolation; EOF disposes task-owned process/workers. |
| P2 synchronization | `didOpen`, sequential range edits, full replacements, multiple edits in one event, CRLF/LF, close/reopen/disk fallback, included unsaved buffer overlays, stale versions and concurrent disk change. Current source owns all results. |
| P3 positions | Emoji/non-BMP before a token, combining marks, escaped strings, tabs, multiline expressions, EOF/missing closers, nonzero host column, fragment-local URI, and named definition in another file. Expected ranges must slice exactly the intended authored token. |
| P4 diagnostics | Syntax recovery to valid document clears old diagnostics; duplicate/reserved IDs; invalid root/type/scope; forbidden slot refs; malformed periods/links; exact version mismatch; stable code/category/source. `/ 1 0` in an otherwise well-typed static formula is not a fabricated execution error from LSP. |
| P5 completion/hover | Host head/option contexts, schema/case/params/layout, root vs library name collision, row columns, member/period fields, `:uses` filtering above 100 roots, typed locals/HOF callbacks/named helpers, global and shadowed `prev`. Private roots absent; documentation/type/range correct; no run values or callback execution. |
| P6 definition/references | Input plus presentation field, bare/qualified/all/prev uses, unexecuted conditional branch, unused named definition body, local shadow, explicit link source vs destination, included fragment, pinned schema versions, layout references, and arbitrary identical strings. Exact expected declarations/occurrences; no text-match extras. |
| P7 rename | Longer/shorter ID; keywords/quoted link addresses; multiple formula/host references; local capture; reserved/duplicate target; same ID in two versions; shared include; dynamic/unresolved scope; read-only file; edit during computation. Reanalysis preserves binding identity and outputs nonoverlapping edits, or returns no edit with reason. |
| P8 controls/concurrency | Canceled request before/after compile, coalesced typing, stale queued response, scan/source/index cap, worker failure/recovery, reader/session ownership if used. No old successful semantic result is published as current; no claim of an unprovided in-call cancel mechanism. |
| P9 real subprocess | Temp workspace with neutral four-file tutorial, include and linked versioned cases; framed requests execute the installed real CLI/server; completion/hover/goto/refs/rename and diagnostic clearing verified without a fake service. Shut down and verify no owned process/socket/profile remains. |
| P10 thin-client parity | VS Code and Community SDK compile tests; adapter tests for selected range, diagnostics and versioned edits; launch errors and restart; canceled jobs; IDE disposal. IntelliJ edits use undo/preview and do not block EDT. Smoke actual user-available IDEs before recording supported versions. |

For P6/P7, include a declaration `closing` and `(let [closing 7] closing)` plus real root uses
`mantra/closing`, `all.closing` and `(prev closing opening)`. Renaming the root changes only its
bound references, including host link/layout references, while the local declaration/use, labels and
unrelated version stay identical. Apply the returned edit to snapshots, compare bindings and compile
again. This is a semantic acceptance example, not a string substitution test.

Future JVM tasks will be coordinated additions such as `:mantra-language:test`, `:mantra-lsp:test`
and CLI subprocess tests, with ordinary public-boundary/source/catalog gates. Client checks use
their pinned lockfiles/SDK and already available runtimes; no application/browser installation is
implied by this plan. No tests, builds, installations or daemon launches were performed to produce
this document. Implementation is delivered only after the corresponding real tests pass.
