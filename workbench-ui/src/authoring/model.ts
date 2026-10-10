import type { Diagnostic } from '../types'
import type { DocumentTexts, PreviewResult, SemanticOperation, SourcePatch, SourceRevisions } from './service'

export interface SourceTransaction {
  label: string
  patches: SourcePatch[]
  ownerHandles: string[]
  operation?: SemanticOperation
  before: DocumentTexts
  after: DocumentTexts
  blockedReason?: string
}

export interface SourceHistory {
  past: SourceTransaction[]
  future: SourceTransaction[]
}

export interface SourceConflict {
  documents: DocumentTexts
  baseRevisions: SourceRevisions
  changed: string[]
  overlapping?: string[]
}

export interface AuthoringState {
  baseDocuments: DocumentTexts
  documents: DocumentTexts
  baseRevisions: SourceRevisions
  draftSequence: number
  validity: 'unchecked' | 'valid' | 'invalid' | 'runtimeFailure'
  preview: {
    status: 'none' | 'calculating' | 'current' | 'previous' | 'unavailable'
    current?: PreviewResult
    previousValid?: PreviewResult
  }
  diagnostics: Diagnostic[]
  diagnosticsSequence?: number
  history: SourceHistory
  conflict?: SourceConflict
  restored: boolean
  readOnlyReason?: string
  connection: 'online' | 'offline' | 'reconnecting'
  saving?: { draftSequence: number; baseRevisions: SourceRevisions; documents: DocumentTexts }
  saved: boolean
}

export type AuthoringEvent =
  | {
      type: 'transaction'
      label: string
      patches: SourcePatch[]
      ownerHandles?: string[]
      operation?: SemanticOperation
    }
  | { type: 'undo' | 'redo' | 'saveStarted' | 'discard' }
  | { type: 'previewStarted'; draftSequence: number; baseRevisions: SourceRevisions }
  | { type: 'previewReceived'; result: PreviewResult }
  | { type: 'saveSucceeded'; draftSequence: number; baseRevisions: SourceRevisions; documents?: DocumentTexts }
  | {
      type: 'saveFailed'
      draftSequence: number
      kind: 'invalid' | 'offline' | 'conflict'
      diagnostics?: Diagnostic[]
      conflict?: SourceConflict
    }
  | ({ type: 'externalChanged' | 'rebase' } & SourceConflict)
  | { type: 'restore'; documents: DocumentTexts; history?: SourceHistory }
  | { type: 'accessChanged'; readOnlyReason?: string }
  | { type: 'connectionChanged'; connection: AuthoringState['connection'] }

export interface SaveCapsule {
  kind: 'readonly' | 'conflict' | 'saving' | 'restored' | 'invalid' | 'unsaved' | 'saved' | 'clean'
  label: string
}

/** Exact string equality preserves comments, whitespace, CRLF and all unrelated source bytes. */
export function sameDocuments(left: DocumentTexts, right: DocumentTexts): boolean {
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every((key) => left[key] === right[key])
}

export function sameRevisions(left: SourceRevisions, right: SourceRevisions): boolean {
  return sameDocuments(left, right)
}

/** Every offset refers to the starting document, rather than an already modified intermediate string. */
export function applySourcePatches(documents: DocumentTexts, patches: SourcePatch[]): DocumentTexts {
  const result = { ...documents }
  const grouped = new Map<string, SourcePatch[]>()
  for (const patch of patches) grouped.set(patch.document, [...(grouped.get(patch.document) ?? []), patch])
  for (const [document, edits] of grouped) {
    const source = documents[document]
    if (source === undefined) throw new RangeError('Source document is unavailable')
    let endLimit = source.length
    let text = source
    for (const patch of [...edits].sort((left, right) => right.start - left.start)) {
      if (
        !Number.isSafeInteger(patch.start) ||
        !Number.isSafeInteger(patch.end) ||
        patch.start < 0 ||
        patch.end < patch.start ||
        patch.end > endLimit ||
        source.slice(patch.start, patch.end) !== patch.inverse
      ) {
        throw new RangeError('Source patch is stale, overlapping or outside its document')
      }
      text = text.slice(0, patch.start) + patch.text + text.slice(patch.end)
      endLimit = patch.start
    }
    result[document] = text
  }
  return result
}

export function invertSourcePatches(patches: SourcePatch[]): SourcePatch[] {
  const shifts = new Map<string, number>()
  return [...patches]
    .sort((left, right) => left.document.localeCompare(right.document) || left.start - right.start)
    .map((patch) => {
      const start = patch.start + (shifts.get(patch.document) ?? 0)
      shifts.set(patch.document, (shifts.get(patch.document) ?? 0) + patch.text.length - (patch.end - patch.start))
      return {
        document: patch.document,
        start,
        end: start + patch.text.length,
        text: patch.inverse,
        inverse: patch.text,
      }
    })
}

export function createAuthoringState(input: {
  documents: DocumentTexts
  baseRevisions: SourceRevisions
  preview?: PreviewResult
  readOnlyReason?: string
}): AuthoringState {
  const state: AuthoringState = {
    baseDocuments: { ...input.documents },
    documents: { ...input.documents },
    baseRevisions: { ...input.baseRevisions },
    draftSequence: 0,
    validity: 'unchecked',
    preview: { status: 'none' },
    diagnostics: [],
    history: { past: [], future: [] },
    restored: false,
    readOnlyReason: input.readOnlyReason,
    connection: 'online',
    saved: false,
  }
  return input.preview ? authoringReducer(state, { type: 'previewReceived', result: input.preview }) : state
}

export function isDirty(state: AuthoringState): boolean {
  return !sameDocuments(state.documents, state.baseDocuments)
}

export function diagnosticsAreStale(state: AuthoringState): boolean {
  return state.diagnosticsSequence !== undefined && state.diagnosticsSequence !== state.draftSequence
}

export function canUndo(state: AuthoringState): boolean {
  return (
    !state.saving && !state.readOnlyReason && !!state.history.past.length && !state.history.past.at(-1)?.blockedReason
  )
}

export function canRedo(state: AuthoringState): boolean {
  return (
    !state.saving &&
    !state.readOnlyReason &&
    !!state.history.future.length &&
    !state.history.future.at(-1)?.blockedReason
  )
}

export function saveReason(state: AuthoringState): string | undefined {
  if (state.readOnlyReason) return `Read-only: ${state.readOnlyReason}`
  if (state.conflict) return 'Resolve the source revision conflict before saving'
  if (state.saving) return 'Saving this prototype session'
  if (state.restored) return 'The restored draft has not been validated'
  if (!isDirty(state)) return 'No unsaved source changes'
  if (state.connection !== 'online') return 'An online preview is required before saving'
  if (state.validity === 'invalid') return 'Fix the technical errors before saving'
  const current = state.preview.current
  if (
    state.preview.status !== 'current' ||
    !current ||
    current.draftSequence !== state.draftSequence ||
    !sameRevisions(current.baseRevisions, state.baseRevisions) ||
    (current.kind !== 'valid' && current.kind !== 'runtimeFailure')
  ) {
    return 'A current recorded engine preview is required before saving'
  }
  return undefined
}

export function canSave(state: AuthoringState): boolean {
  return saveReason(state) === undefined
}

export function saveCapsule(state: AuthoringState): SaveCapsule {
  if (state.readOnlyReason) return { kind: 'readonly', label: `Read-only · ${state.readOnlyReason}` }
  if (state.conflict) return { kind: 'conflict', label: 'Conflict' }
  if (state.saving) return { kind: 'saving', label: 'Saving…' }
  if (state.restored) return { kind: 'restored', label: 'Restored · not validated' }
  if (state.validity === 'invalid') return { kind: 'invalid', label: 'Invalid draft' }
  if (isDirty(state)) return { kind: 'unsaved', label: 'Unsaved changes' }
  if (state.saved) return { kind: 'saved', label: 'Saved in this prototype session — no file was written' }
  return { kind: 'clean', label: '' }
}

export function currentPreview(state: AuthoringState): PreviewResult | undefined {
  const current = state.preview.current
  return current?.kind === 'valid' || current?.kind === 'runtimeFailure' ? current : state.preview.previousValid
}

export function previewStatus(state: AuthoringState): string {
  if (state.preview.status === 'calculating') return `Calculating draft #${state.draftSequence}…`
  if (state.preview.current?.kind === 'runtimeFailure') {
    return `Draft #${state.draftSequence} calculated with a runtime failure`
  }
  if (state.preview.status === 'current') return `Preview: current · draft #${state.draftSequence}`
  if (state.preview.status === 'unavailable') return 'No engine preview for this draft (not recorded in this prototype)'
  const previous = state.preview.previousValid
  return previous ? `Previous valid preview (draft #${previous.draftSequence})` : 'No engine preview for this draft'
}

function invalidate(state: AuthoringState, documents: DocumentTexts): AuthoringState {
  return {
    ...state,
    documents: { ...documents },
    draftSequence: state.draftSequence + 1,
    validity: 'unchecked',
    preview: { status: state.preview.previousValid ? 'previous' : 'none', previousValid: state.preview.previousValid },
    saved: false,
  }
}

function matches(state: AuthoringState, sequence: number, revisions: SourceRevisions): boolean {
  return sequence === state.draftSequence && sameRevisions(revisions, state.baseRevisions)
}

/** Store conflict data independently of the reducer event that carried it. */
function conflictSnapshot(external: SourceConflict): SourceConflict {
  return {
    documents: { ...external.documents },
    baseRevisions: { ...external.baseRevisions },
    changed: [...external.changed],
    ...(external.overlapping ? { overlapping: [...external.overlapping] } : {}),
  }
}

function receivePreview(state: AuthoringState, result: PreviewResult): AuthoringState {
  if (!matches(state, result.draftSequence, result.baseRevisions)) return state
  const valid = result.kind === 'valid'
  const current = valid || result.kind === 'runtimeFailure'
  return {
    ...state,
    validity: result.kind === 'unrecorded' ? 'unchecked' : result.kind,
    preview: {
      status: current ? 'current' : result.kind === 'unrecorded' ? 'unavailable' : 'previous',
      current: current ? result : undefined,
      previousValid: valid ? result : state.preview.previousValid,
    },
    diagnostics: [...result.diagnostics],
    diagnosticsSequence: state.draftSequence,
    restored: valid ? false : state.restored,
  }
}

function rebaseHistory(history: SourceHistory, external: SourceConflict): SourceHistory {
  const update = (transaction: SourceTransaction): SourceTransaction => {
    const touched = external.changed.some((document) => transaction.before[document] !== transaction.after[document])
    if (touched) return { ...transaction, blockedReason: 'Cannot undo: source changed outside this editor' }
    const before = { ...transaction.before }
    const after = { ...transaction.after }
    for (const document of external.changed) {
      if (document in external.documents) {
        before[document] = external.documents[document]
        after[document] = external.documents[document]
      } else {
        delete before[document]
        delete after[document]
      }
    }
    return { ...transaction, before, after }
  }
  return { past: history.past.map(update), future: history.future.map(update) }
}

function rebase(state: AuthoringState, external: SourceConflict): AuthoringState {
  const overlapping = external.changed.filter((document) => state.documents[document] !== state.baseDocuments[document])
  if (overlapping.length) {
    return { ...state, conflict: { ...conflictSnapshot(external), overlapping }, saving: undefined }
  }
  const documents = { ...external.documents }
  for (const document of Object.keys(state.documents)) {
    if (state.documents[document] !== state.baseDocuments[document]) documents[document] = state.documents[document]
  }
  return {
    ...invalidate(state, documents),
    baseDocuments: { ...external.documents },
    baseRevisions: { ...external.baseRevisions },
    history: rebaseHistory(state.history, external),
    conflict: undefined,
    saving: undefined,
  }
}

export function authoringReducer(state: AuthoringState, event: AuthoringEvent): AuthoringState {
  switch (event.type) {
    case 'transaction': {
      if (state.saving || state.readOnlyReason) return state
      let documents: DocumentTexts
      try {
        documents = applySourcePatches(state.documents, event.patches)
      } catch {
        return state
      }
      if (sameDocuments(documents, state.documents)) return state
      const transaction: SourceTransaction = {
        label: event.label,
        patches: event.patches.map((patch) => ({ ...patch })),
        ownerHandles: [...(event.ownerHandles ?? [])],
        operation: event.operation,
        before: { ...state.documents },
        after: { ...documents },
      }
      return {
        ...invalidate(state, documents),
        history: { past: [...state.history.past, transaction], future: [] },
      }
    }
    case 'undo': {
      if (!canUndo(state)) return state
      const transaction = state.history.past.at(-1)!
      return {
        ...invalidate(state, transaction.before),
        history: { past: state.history.past.slice(0, -1), future: [...state.history.future, transaction] },
      }
    }
    case 'redo': {
      if (!canRedo(state)) return state
      const transaction = state.history.future.at(-1)!
      return {
        ...invalidate(state, transaction.after),
        history: { past: [...state.history.past, transaction], future: state.history.future.slice(0, -1) },
      }
    }
    case 'previewStarted':
      return matches(state, event.draftSequence, event.baseRevisions)
        ? { ...state, preview: { ...state.preview, status: 'calculating', current: undefined } }
        : state
    case 'previewReceived':
      return receivePreview(state, event.result)
    case 'saveStarted':
      return canSave(state)
        ? {
            ...state,
            saving: {
              draftSequence: state.draftSequence,
              baseRevisions: { ...state.baseRevisions },
              documents: { ...state.documents },
            },
          }
        : state
    case 'saveSucceeded': {
      const saving = state.saving
      if (!saving || event.draftSequence !== saving.draftSequence || state.conflict) return state
      const documents = { ...(event.documents ?? saving.documents) }
      return {
        ...invalidate(state, documents),
        baseDocuments: documents,
        baseRevisions: { ...event.baseRevisions },
        saving: undefined,
        restored: false,
        saved: true,
      }
    }
    case 'saveFailed': {
      if (!state.saving || event.draftSequence !== state.saving.draftSequence) return state
      const next: AuthoringState = { ...state, saving: undefined, saved: false }
      if (event.kind === 'conflict')
        return { ...next, conflict: event.conflict ? conflictSnapshot(event.conflict) : state.conflict }
      if (event.kind === 'offline') return { ...next, connection: 'offline' }
      return {
        ...next,
        validity: 'invalid',
        diagnostics: [...(event.diagnostics ?? state.diagnostics)],
        diagnosticsSequence: state.draftSequence,
        preview: { status: 'previous', previousValid: state.preview.previousValid },
      }
    }
    case 'externalChanged':
      if (sameRevisions(state.baseRevisions, event.baseRevisions)) return state
      return isDirty(state) || state.saving
        ? { ...state, conflict: conflictSnapshot(event), saving: undefined }
        : rebase(state, event)
    case 'rebase':
      return state.saving ? state : rebase(state, event)
    case 'discard': {
      if (state.saving || state.readOnlyReason) return state
      const baseline = state.conflict
      return {
        ...invalidate(state, baseline?.documents ?? state.baseDocuments),
        baseDocuments: { ...(baseline?.documents ?? state.baseDocuments) },
        baseRevisions: { ...(baseline?.baseRevisions ?? state.baseRevisions) },
        history: { past: [], future: [] },
        conflict: undefined,
        restored: false,
      }
    }
    case 'restore': {
      if (state.saving || state.readOnlyReason || state.conflict) return state
      const before = { ...state.documents }
      const after = { ...event.documents }
      return {
        ...invalidate(state, after),
        restored: true,
        history: event.history ?? {
          past: [...state.history.past, { label: 'Restored draft', patches: [], ownerHandles: [], before, after }],
          future: [],
        },
      }
    }
    case 'accessChanged':
      return { ...state, readOnlyReason: event.readOnlyReason }
    case 'connectionChanged':
      return { ...state, connection: event.connection }
  }
}
