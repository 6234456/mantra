import type { Envelope } from '../types'
import type { TemplateSources } from './client'
import type { LayoutSettingOperation } from './layoutSettingsClient'
import type { StyleClassOperation } from './styleClassesClient'
import { parseRecoveryJson } from './recoveryJson'

export const recoveryFormat = 'mantra.template-preview-recovery/1'
export const recoveryByteLimit = 2 * 1024 * 1024

export interface RecoveryPending {
  layoutSettings: LayoutSettingOperation[]
  styleClass: { operation: StyleClassOperation; creating: boolean; selection: string } | null
}

export interface RecoveryDocument {
  handle: string
  document: string
  role: 'schema' | 'layout'
  sha256: string
  baseText: string
  text: string
}

export interface RecoveryDraft {
  format: typeof recoveryFormat
  savedAt: string
  workspaceId: string
  caseId: string
  baseRevision: string
  baseRevisions: Record<string, string>
  documents: RecoveryDocument[]
  inputs: Array<{ node: string; text: string }>
  pending: RecoveryPending
}

export interface RecoveryDraftState {
  documents: Readonly<Record<string, string>>
  inputs: Readonly<Record<string, string>>
  pending: RecoveryPending
}

export interface RecoveryFailure {
  status: 'invalid'
  reason: string
}

export type RecoveryParseResult = { status: 'ready'; draft: RecoveryDraft } | RecoveryFailure
export type RecoveryBackupResult =
  { status: 'ready'; draft: RecoveryDraft; json: string; bytes: number } | RecoveryFailure

export interface RecoveryMatch {
  compatible: boolean
  foreignWorkspace: boolean
  reason?: string
}

const propertyIds = new Set([
  'title',
  'subtitle',
  'locale',
  'language',
  'precision',
  'percent-precision',
  'negative',
  'zero',
  'grouping',
  'hide-zero',
  'show-inactive',
  'expand-members',
  'signed',
  'row-numbers',
])

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function fields(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  return object(value) && Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key))
}

function hash(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
}

function nonblank(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function revisions(value: unknown): value is Record<string, string> {
  return (
    object(value) &&
    Object.keys(value).length > 0 &&
    Object.entries(value).every(([key, entry]) => nonblank(key) && hash(entry))
  )
}

function sameRevisions(left: Record<string, string>, right: Record<string, string>): boolean {
  const keys = Object.keys(left)
  return (
    keys.length === Object.keys(right).length &&
    keys.every((key) => Object.hasOwn(right, key) && left[key] === right[key])
  )
}

function validTime(value: unknown): value is string {
  if (typeof value !== 'string') return false
  const time = new Date(value)
  return Number.isFinite(time.getTime()) && time.toISOString() === value
}

function layoutOperation(value: unknown): value is LayoutSettingOperation {
  if (!object(value) || typeof value.property !== 'string' || !propertyIds.has(value.property)) return false
  return value.op === 'unset'
    ? fields(value, ['op', 'property'])
    : value.op === 'set' && fields(value, ['op', 'property', 'text']) && typeof value.text === 'string'
}

function styleOperation(value: unknown): value is StyleClassOperation {
  if (!object(value) || typeof value.name !== 'string') return false
  if (value.op === 'remove') return fields(value, ['op', 'name'])
  return (
    value.op === 'upsert' &&
    fields(value, ['op', 'name', 'style']) &&
    fields(value.style, ['weight', 'tone', 'fill']) &&
    [null, 'normal', 'bold'].includes(value.style.weight as null | string) &&
    [null, 'default', 'muted', 'accent'].includes(value.style.tone as null | string) &&
    [null, 'none', 'subtle', 'accent'].includes(value.style.fill as null | string)
  )
}

function pending(value: unknown): value is RecoveryPending {
  if (
    !fields(value, ['layoutSettings', 'styleClass']) ||
    !Array.isArray(value.layoutSettings) ||
    value.layoutSettings.length > 14 ||
    !value.layoutSettings.every(layoutOperation) ||
    new Set(value.layoutSettings.map((entry) => entry.property)).size !== value.layoutSettings.length
  )
    return false
  return (
    value.styleClass === null ||
    (fields(value.styleClass, ['operation', 'creating', 'selection']) &&
      styleOperation(value.styleClass.operation) &&
      typeof value.styleClass.creating === 'boolean' &&
      typeof value.styleClass.selection === 'string')
  )
}

function document(value: unknown): value is RecoveryDocument {
  return (
    fields(value, ['handle', 'document', 'role', 'sha256', 'baseText', 'text']) &&
    hash(value.handle) &&
    nonblank(value.document) &&
    (value.role === 'schema' || value.role === 'layout') &&
    hash(value.sha256) &&
    typeof value.baseText === 'string' &&
    value.baseText.length <= 65_536 &&
    typeof value.text === 'string'
  )
}

function backup(value: unknown): value is RecoveryDraft {
  if (
    !fields(value, [
      'format',
      'savedAt',
      'workspaceId',
      'caseId',
      'baseRevision',
      'baseRevisions',
      'documents',
      'inputs',
      'pending',
    ]) ||
    value.format !== recoveryFormat ||
    !validTime(value.savedAt) ||
    !hash(value.workspaceId) ||
    !nonblank(value.caseId) ||
    !hash(value.baseRevision) ||
    !revisions(value.baseRevisions) ||
    !Array.isArray(value.documents) ||
    value.documents.length < 1 ||
    value.documents.length > 2 ||
    !value.documents.every(document) ||
    value.documents.filter((entry) => entry.role === 'schema').length !== 1 ||
    value.documents.filter((entry) => entry.role === 'layout').length > 1 ||
    new Set(value.documents.map((entry) => entry.handle)).size !== value.documents.length ||
    new Set(value.documents.map((entry) => entry.document)).size !== value.documents.length ||
    value.documents.some((entry) => (value.baseRevisions as Record<string, string>)[entry.document] !== entry.sha256) ||
    !Array.isArray(value.inputs) ||
    value.inputs.length > 100 ||
    !value.inputs.every(
      (entry) => fields(entry, ['node', 'text']) && nonblank(entry.node) && typeof entry.text === 'string',
    ) ||
    new Set(value.inputs.map((entry) => entry.node)).size !== value.inputs.length ||
    !pending(value.pending)
  )
    return false
  return true
}

function budget(raw: string): number | undefined {
  if (raw.length > recoveryByteLimit) return
  const bytes = new TextEncoder().encode(raw).byteLength
  return bytes <= recoveryByteLimit ? bytes : undefined
}

export function emptyRecoveryPending(): RecoveryPending {
  return { layoutSettings: [], styleClass: null }
}

/** A valid recovery contains only untrusted author text, never restored calculation or permission evidence. */
export function parseBackup(json: string): RecoveryParseResult {
  if (budget(json) === undefined)
    return { status: 'invalid', reason: 'The backup exceeds the 2 MiB limit. No source text was truncated.' }
  try {
    const value = parseRecoveryJson(json)
    return backup(value)
      ? { status: 'ready', draft: value }
      : {
          status: 'invalid',
          reason: 'The backup contains unsupported or inconsistent source, input, identity or pending-field data.',
        }
  } catch (failure) {
    return { status: 'invalid', reason: failure instanceof Error ? failure.message : 'The backup is not valid JSON.' }
  }
}

/** Creation does not parse DSL or convert any supplied raw input, including incomplete or oversized preview text. */
export function createBackup(
  snapshot: Envelope<TemplateSources>,
  state: RecoveryDraftState,
  savedAt = new Date().toISOString(),
): RecoveryBackupResult {
  try {
    const sourceHandles = new Set(snapshot.data.documents.map((entry) => entry.handle))
    if (
      Object.keys(state.documents).some((handle) => !sourceHandles.has(handle)) ||
      snapshot.data.documents.some(
        (entry) =>
          !entry.editable &&
          Object.hasOwn(state.documents, entry.handle) &&
          state.documents[entry.handle] !== entry.text,
      )
    ) {
      return {
        status: 'invalid',
        reason: 'Draft source handles or read-only text differ from the captured source snapshot.',
      }
    }
    const value = {
      format: recoveryFormat,
      savedAt,
      workspaceId: snapshot.data.workspaceId,
      caseId: snapshot.data.document,
      baseRevision: snapshot.revision,
      baseRevisions: snapshot.data.baseRevisions,
      documents: snapshot.data.documents
        .filter((entry) => entry.editable)
        .map((entry) => ({
          handle: entry.handle,
          document: entry.document,
          role: entry.role,
          sha256: entry.sha256,
          baseText: entry.text,
          text: state.documents[entry.handle],
        })),
      inputs: Object.entries(state.inputs).map(([node, text]) => ({ node, text })),
      pending: state.pending,
    }
    const json = JSON.stringify(value, null, 2)
    const parsed = parseBackup(json)
    if (parsed.status !== 'ready') return parsed
    const match = matchBackup(parsed.draft, snapshot)
    if (!match.compatible)
      return { status: 'invalid', reason: match.reason ?? 'The captured source snapshot is inconsistent.' }
    return { status: 'ready', draft: parsed.draft, json, bytes: budget(json)! }
  } catch {
    return {
      status: 'invalid',
      reason: 'The complete source draft could not be serialized. No source text was truncated.',
    }
  }
}

/** Portable imports may cross workspace identity after explicit restore; all source evidence still matches. */
export function matchBackup(
  draft: RecoveryDraft,
  snapshot: Envelope<TemplateSources>,
  mode: 'local' | 'import' = 'local',
): RecoveryMatch {
  const foreignWorkspace = draft.workspaceId !== snapshot.data.workspaceId
  const fail = (reason: string): RecoveryMatch => ({ compatible: false, foreignWorkspace, reason })
  if (!hash(snapshot.data.workspaceId)) return fail('The server did not supply a valid workspace identity.')
  if (draft.caseId !== snapshot.data.document) return fail('The backup belongs to a different example case.')
  if (foreignWorkspace && mode !== 'import') return fail('The automatic copy belongs to a different workspace.')
  if (draft.baseRevision !== snapshot.revision || !sameRevisions(draft.baseRevisions, snapshot.data.baseRevisions))
    return fail('The captured template source revisions have changed. Keep the backup for download or manual recovery.')
  const editable = snapshot.data.documents.filter((entry) => entry.editable)
  if (
    editable.length !== draft.documents.length ||
    editable.some((entry) => {
      const recovered = draft.documents.find((item) => item.handle === entry.handle)
      return (
        !recovered ||
        recovered.document !== entry.document ||
        recovered.role !== entry.role ||
        recovered.sha256 !== entry.sha256 ||
        recovered.baseText !== entry.text ||
        snapshot.data.baseRevisions[entry.document] !== entry.sha256
      )
    })
  )
    return fail('The root template documents or their captured original text do not match this source snapshot.')
  return { compatible: true, foreignWorkspace }
}

/** Read-only documents always come from the current server snapshot, never from an imported backup. */
export function restoreDocuments(
  draft: RecoveryDraft,
  snapshot: Envelope<TemplateSources>,
  mode: 'local' | 'import' = 'local',
): Record<string, string> | undefined {
  if (!matchBackup(draft, snapshot, mode).compatible) return
  const text = new Map(draft.documents.map((entry) => [entry.handle, entry.text]))
  return Object.fromEntries(
    snapshot.data.documents.map((entry) => [entry.handle, text.get(entry.handle) ?? entry.text]),
  )
}
