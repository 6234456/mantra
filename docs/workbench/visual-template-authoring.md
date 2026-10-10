# Visual template authoring

> Status: **PLANNED — design proposal, not implemented.** This extends the current
> workbench scope; it does not describe an available editor or API.

## Purpose and current foundation

Let ordinary users develop reusable calculation templates through a spreadsheet-like
grid with prompt feedback. Layout remains controlled: the grid represents calculation
structure and its supported presentation properties.

This is Mantra's independent visual DSL editor. It owns the source-editing workflow and
can author templates for both Mantra and derived Excel delivery. Template Engine receives
the latter as a downstream consumer; reuse of its neutral UI modules is optional.

The current [workbench contract](contract.md) supports typed case inputs, formula-slot
bindings and extension rows. [WorkspaceAuthoring](../../mantra-workbench/src/main/kotlin/com/xqiou/mantra/workbench/WorkspaceAuthoring.kt)
provides case-formula assistance;
[WorkspaceEditing](../../mantra-workbench/src/main/kotlin/com/xqiou/mantra/workbench/WorkspaceEditing.kt)
previews and commits validated case edits. Reusable schema/layout editing requires new work.

[WorkingPaper](../../mantra-render/src/main/kotlin/com/xqiou/mantra/render/paper/WorkingPaper.kt)
and its [JSON projection](schema/paper.schema.json) provide the preview foundation.
Its cells and addresses identify results, but do not establish source-editing ownership.

## Reusing Template Engine

Mantra's independent editor provides the [common authoring path for both delivery targets](../template-directions.md):
visual edits update the original DSL, Mantra compiles/validates it and provides the preview,
then the build can produce a Mantra runtime template and a derived Excel template. The
fork occurs at execution/delivery, after the shared source workflow. Authoring has one
DSL definition and source history; downstream instances keep their own inputs and history.

Source review of [Template Engine](https://github.com/6234456/paramita-v2) establishes a
practical reuse path. The latest reviewed remote `main` revision is
[`6443c688`](https://github.com/6234456/paramita-v2/commit/6443c68875a8592e79ea2099637545fe96a18ec0),
dated 2026-10-10. It includes the application toolkit previously absent from the reviewed
snapshot. No integration or cross-project runtime compatibility test has been completed.

The Office V2 implementation separates reusable presentation and headless geometry from
application orchestration:

| Existing module | Optional reuse in Mantra's editor | Required adaptation if reused |
| --- | --- | --- |
| `office-workbook-ui` | `CellEditorOverlay`, `FormulaBar`, `FormatToolbar`, worksheet tabs and clipboard transport | Bind raw-input callbacks to Mantra operations; make formula assistance language-configurable; expose supported formatting actions |
| `office-layout-core` | Sheet metrics, viewport skeleton, cell geometry and editor placement | Map controlled Paper rows, columns and widths into an authorized display projection |
| `office-render-core` and `render-engine` | Shared sheet paint operations, hit testing and Canvas execution | Own one projection per draft revision; preserve engine-rendered text and styles |
| `office-interaction-core` | Selection and pointer/keyboard interaction | Translate selected positions into Mantra semantic addresses and authoring handles |
| `office-ui` and application controls in `office-workbook-ui` | Docked side panels, issue queues, status pills and export controls | Adapt Office cell targets and manual-review status vocabulary; callbacks retain Mantra authority |

These packages do not currently provide a standalone React grid. The full Canvas host is
in `apps/manifest-runtime/src/office-v2`; its controller, permissions and input hooks are
coupled to Office runtime orchestration. Extract or adapt the reusable host boundary
before embedding it. Installing `office-workbook-ui` alone cannot supply the grid.

If UI reuse is selected, use public UI/layout/render modules with a Mantra authoring adapter.
Keep the bounded host extraction and language-configurable controls in the source project,
while Mantra owns its editor host and source workflow. An external result provider is a
separate downstream mechanism, not a substitute for the shared DSL authoring path.

If the Office rendering reuse route is selected, its first proof converts one actual scalar
Paper panel into a valid,
temporary `OfficePackageModelV2` containing already-rendered scalar text and supported
styles. Use the normal `createWorkbookRenderProjection` factory, Sheet skeleton and
RenderCore/Canvas path. This model is a disposable display cache rebuilt from Mantra,
never a saved workbook definition or an Office backend authority claim. The render
projection carries factory-owned runtime capabilities: a structurally similar object or
TypeScript cast cannot replace the factory. A narrower neutral display factory can be
considered after this proof, rather than weakening existing projection checks.

The integration must retain these ownership boundaries:

- **Calculation:** Mantra evaluates DSL, exact values and business findings for the common
  authoring preview. Excel formula translation occurs for the downstream delivery target;
  it does not replace the editor's DSL calculation. Office's scalar
  numeric type is JavaScript `number`; preserve Mantra decimal encodings as strings in
  Mantra-owned sidecar metadata, and display Paper text without parsing it back into a
  number. Keep exact values, semantic addresses and source-owner handles in this mapping;
  do not add arbitrary fields to the strict Office model or treat A1 as write authority.
  Disable Office-derived selection totals or obtain exact totals from Mantra.
- **Editing:** Raw input maps to a permitted Mantra operation and base revision; template
  changes additionally require the source-owner handles specified below. Positions are
  used for navigation only. A display refresh replaces derived cache state; it is not an
  Office document mutation. If an independently persisted Office document is offered,
  its changes still use the existing Office Command/Session authority.
- **Assistance:** `FormulaBar` currently inserts Excel function syntax, and
  `CellEditorOverlay` has built-in Excel completion. A host completion callback alone
  does not establish a Mantra language mode. Add an explicit configuration to replace or
  disable these behaviors and connect Mantra completion, hover and diagnostics.
- **Formatting and clipboard:** Expose only mapped classes and layout properties.
  Pasted raw text undergoes Mantra type validation. Excel formula fill, relative A1
  transformation, merges and unrestricted format commands require separate language
  semantics before becoming editable actions.
- **History and persistence:** DSL/source transactions own template undo/redo and saving.
  Existing Mantra case edits keep their own revision semantics. Do not maintain an
  independent writable Office history for the same definition, or claim a multi-service
  transaction without a specified commit protocol.

The reviewed UI packages declare React 18 peers and `workspace:*` dependencies, while
Mantra uses React 19. Their package entry points expose TypeScript source. Establish a
pinned dependency closure, build/package strategy, shared theme assets and React
compatibility before production reuse; they cannot yet be treated as drop-in published
dependencies. Keep extraction in the source project so future improvements remain shared.

Mantra's existing [reusable patterns](../reusable-patterns.md) supply starter templates for
Mantra's editor. Existing `style-class` names can be projected into named-style controls,
but applying one must update its mapped DSL declaration through the authoring contract.
An Office named style is not a second persisted definition of the Mantra class.

Verified source entry points:

- [Workbook UI exports](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-workbook-ui/src/index.ts)
  and [package dependencies](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-workbook-ui/package.json).
- [Render projection factory](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-workbook-editor-core/src/workbook-render-projection.ts)
  and [factory-owned cell access](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-workbook-editor-core/src/workbook-render-projection-cells.ts).
- [Sheet skeleton source](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-layout-core/src/sheet-layout/sheet-skeleton-source.ts)
  and [sheet render scene](https://github.com/6234456/paramita-v2/blob/6443c68875a8592e79ea2099637545fe96a18ec0/packages/office-render-core/src/render-scene/sheet-render-scene.ts).

## Source and editing identity

DSL documents remain the single persisted definition. A lossless source projection and
authoring metadata connect the grid to existing forms, preserving comments, whitespace,
order and source locations. The projection is derived state, not another UI DSL.

The server issues opaque edit handles for an authorized owner document, node or column,
and editable property. Handles remain stable across harmless layout changes and are
revalidated against both exact source-document and participating graph revisions.
Deletion or ambiguous rebinding invalidates a handle. A selected cell's row/column
position and text are navigation hints, never write authority. Client-supplied paths or
offsets cannot select a write target. Repeated dimension cells share declaration ownership;
an authoring projection retains definitions hidden by zero suppression or inactivity.

Included formulas retain their fragment owners. Show shared scope before editing.
Imported values navigate to their owner or an explicit, language-supported local override;
never flatten includes or overwrite consumers. Unavailable owners remain read-only.

## Interaction and feedback

Open a rendered DSL template or start from a built-in pattern. Edit text inline; a property
panel offers allowed settings, and a formula bar edits expressions. The same engine drives
immediate feedback.

| Selection | Proposed action and source target |
| --- | --- |
| Table title or row label | Edit the mapped layout title or schema label; show which declaration supplies it |
| Column header | Edit the corresponding authored `col` header; generated member labels require their own owner |
| Note or reference | Edit the mapped schema text property or note form |
| Calculated value | Open its formula editor with completion, type information and current result |
| Input value | Edit typed demonstration-case data, clearly separated from reusable template definitions |
| Style control | Assign existing item `:class` tags or edit mapped layout rules, `style-class` and `:use` declarations |

Expose the existing [style vocabulary](../reusable-patterns.md), widths and formats with
effective styles and originating rules. Styling never changes numeric or validation policy.
WYSIWYG covers engine-rendered structure, content and style. Target-specific pagination and
font metrics require actual export previews; pixel-identical exports are not promised.

Each draft change follows source patch → compile/validate → evaluate demonstration case
→ build Paper. Debounce requests, cancel superseded work and attach a monotonically
increasing draft sequence. Apply a response only when its draft sequence and source/graph
revisions still match. Cancellation alone cannot prevent late responses from replacing
newer results.

While a draft is incomplete, keep the last valid preview with an explicit “previous valid
preview” state. Highlight current diagnostics at their actual owner and disable saving
technically invalid drafts. Syntax, type, reference and cycle failures reject a commit.
Business findings remain visible alongside successfully calculated values; they do not
erase results or masquerade as compilation failures. Runtime partial results must retain
their failure state under the eventual authoring contract.

## Drafts, review and publication

Use semantic operations for labels, formulas and classes. The server resolves handles,
makes minimal patches, validates the candidate graph and groups changes into undo/redo
transactions. Undo restores exact source. Text and grid edits converge on one definition.

Save an authored draft as a new content revision with a readable source diff. Retain its
schema identity and version unless the author explicitly chooses a new version or fork;
never silently migrate existing cases. A dry run shows the affected documents, calculation
differences and findings before commit. Commit rechecks all participating revisions and
rejects stale candidates; multi-document changes require a specified transaction strategy.

[Captured packages](../site/packages.md) remain immutable and read-only. Authoring requires
an explicitly writable workspace fork with a bounded, authorized dependency closure.
Forking must preserve includes, mappings and exact dependency identities; it does not
grant write access to package resources or unrelated storage. Define operation schemas,
limits, preview/commit responses and compatibility rules before changing public model
constructors or adding endpoints.

## Delivery slices and future acceptance

The first slice should cover one existing scalar panel: labels and notes, formulas,
class assignment and limited layout properties. Provide a guided starter pattern with
a schema, demonstration case and layout, then let the user rename meaningful fields and
change a formula. Existing templates stay readable in source form. Structural row insertion,
reordering, dimensions, transposed tables and shared-pattern composition follow separately.

Start with one real Paper panel in Mantra's own editor:
selection opens Explain, scrolling and keyboard navigation use the same geometry, and
formatted decimal text and supported styles match the existing workbench. Then bind
existing typed case edits and reload the committed Paper. Full template editing follows
only after owner metadata, lossless source patches and draft-Paper responses are defined.
The editor can use the existing Mantra presentation components. An Office rendering proof
is needed only if those modules are selected for reuse, and does not gate the independent
authoring workflow.
The current formula preview returns Run/differences, not a draft Paper; adding a grid
does not by itself implement immediate template feedback.

Future acceptance must demonstrate:

- Visual edits produce readable DSL, preserve unrelated bytes and reproduce the same
  values and Paper after discarding editor state and reopening the workspace.
- Included declarations, repeated projections and resolved styles map to their actual
  owners; ambiguous or unauthorized targets cannot be committed.
- Stale responses and external source changes cannot replace current previews or edits;
  failed drafts visibly retain the previous valid preview.
- Technical failures leave files unchanged; business failures retain computed values;
  grouped undo/redo restores exact text and the expected results.
- Published packages stay unchanged, and a writable fork retains exact dependencies.
  Ordinary users complete the starter exercise with keyboard navigation and immediate
  feedback, without needing to locate DSL files manually.
