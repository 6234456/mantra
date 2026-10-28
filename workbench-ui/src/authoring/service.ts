import type {
  Diagnostic,
  Diagnostics,
  Envelope,
  Explain,
  ExportPreview,
  Paper,
  PreviewPaper,
  Run,
  Structure,
  Workspace,
} from '../types'

/** Prototype-only contracts. These methods do not represent implemented server capabilities. */
export type DocumentTexts = Record<string, string>
export type SourceRevisions = Record<string, string>
export interface SourcePatch {
  document: string
  start: number
  end: number
  text: string
  inverse: string
}
export interface SourceRange {
  start: number
  end: number
}
export type OwnerProperty =
  | 'label'
  | 'formula'
  | 'classes'
  | 'title'
  | 'precision'
  | 'hide-zero'
  | 'section-title'
  | 'note'
  | 'left-operand'
  | 'right-operand'
  | 'condition'
  | 'table-style'
  | 'columns'
  | 'declaration'
export interface SourceOwner {
  handle: string
  document: string
  declaration: string
  declarationRange: SourceRange
  property: OwnerProperty
  kind: string
  nodeId?: string
  panelId?: string
  label?: string
  range: SourceRange
  editable: boolean
  reason?: string
  value: string | string[] | boolean | number
  channel: 'Template definition' | 'Example input'
}
export interface SourceOutline {
  handle: string
  document: string
  label: string
  kind: string
  nodeId?: string
  panelId?: string
  children: SourceOutline[]
}
export interface SemanticOperation {
  handle: string
  op: 'setText' | 'setFormula' | 'setClasses' | 'setLayoutOption'
  value: string | string[] | boolean | number
}
export type ApplyResult = { ok: true; patches: SourcePatch[]; owners: SourceOwner[] } | { ok: false; reason: string }
export interface RecordedResponses {
  workspace?: Workspace
  structure?: Envelope<Structure>
  run?: Envelope<Run>
  paper?: Envelope<Paper>
  diagnostics?: Envelope<Diagnostics>
  explains: Record<string, Envelope<Explain>>
  errors: Array<{ path: string; status: number; body: unknown }>
}
export interface PreviewRequest {
  draftSequence: number
  baseRevisions: SourceRevisions
  documents: DocumentTexts
}
export interface PreviewResult {
  draftSequence: number
  baseRevisions: SourceRevisions
  kind: 'valid' | 'invalid' | 'runtimeFailure' | 'unrecorded'
  responses: RecordedResponses
  diagnostics: Diagnostic[]
  stateId?: string
  digest: string
  reason?: string
}
export interface OpenResult {
  simulated: true
  identity: { case: string; panel: string; workspace: string; title: string }
  documents: DocumentTexts
  baseRevisions: SourceRevisions
  owners: SourceOwner[]
  outline: SourceOutline[]
  preview: PreviewResult
  dependencies: Array<{ document: string; sha256: string; editable: boolean }>
}
export interface CommitRequest {
  baseRevisions: SourceRevisions
  documents: DocumentTexts
}
export type CommitResult =
  | { ok: true; simulated: true; documents: DocumentTexts; baseRevisions: SourceRevisions; message: string }
  | {
      ok: false
      kind: 'conflict'
      changed: string[]
      currentRevisions: SourceRevisions
      documents: DocumentTexts
    }
  | { ok: false; kind: 'invalid'; reason: string }
export interface ExternalChange {
  documents: DocumentTexts
  baseRevisions: SourceRevisions
  changed: string[]
}
export interface ExampleInputResult {
  kind: 'valid' | 'invalid' | 'unrecorded'
  status?: number
  response?: Envelope<PreviewPaper> | unknown
  reason?: string
}
export interface AuthoringService {
  open(): Promise<OpenResult>
  owners(documents: DocumentTexts): SourceOwner[]
  apply(operation: SemanticOperation, documents: DocumentTexts): ApplyResult
  preview(request: PreviewRequest): Promise<PreviewResult>
  commit(request: CommitRequest): Promise<CommitResult>
  previewExampleInput(text: string): Promise<ExampleInputResult>
  simulateExternalChange(): Promise<ExternalChange>
  delayNextPreview(milliseconds: number): void
  exportReport(): Envelope<ExportPreview> | undefined
}

export interface RecordedDocument {
  sha256: string
  text: string
}
export interface RecordingEdit {
  description: string
  document: string
  find: string
  replace: string
}
export interface RecordingExchange {
  request: { method: string; path: string; body?: unknown }
  response: { status: number; body: { $blob: string } }
}
export interface RecordingState {
  id: string
  edits: string[]
  digest: string
  documents: Partial<Record<string, RecordedDocument>>
  exchanges: RecordingExchange[]
}
export interface AuthoringRecording {
  format: string
  notice: string
  recordedAt: string
  source: { repository: string; commit: string; patternsDirty: boolean }
  contract: string
  engine: Record<string, unknown>
  workspace: string
  case: string
  panel: string
  documents: string[]
  base: Record<string, RecordedDocument>
  edits: Record<string, RecordingEdit>
  states: RecordingState[]
  blobs: Record<string, unknown>
}
