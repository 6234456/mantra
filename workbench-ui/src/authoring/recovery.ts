import { applySourcePatches, sameDocuments } from './model'
import type { AuthoringState, SourceHistory, SourceTransaction } from './model'
import type { AuthoringRecording, DocumentTexts, SemanticOperation, SourcePatch, SourceRevisions } from './service'
import { documentsForState, sourceDigest, sourceRevisions } from './simulated/recordedService'

export interface RecoveryDraft {
  baseDocuments: DocumentTexts
  baseRevisions: SourceRevisions
  documents: DocumentTexts
  history: SourceHistory
  inputs: Record<string, string>
  savedAt: string
  baseDigest: string
}

export type RecoveryState = Pick<
  AuthoringState,
  'baseDocuments' | 'baseRevisions' | 'documents' | 'history' | 'draftSequence'
>

export const recoveryByteLimit = 2 * 1024 * 1024

export type RecoveryFailure = {
  status: 'unavailable' | 'invalid'
  reason: string
}

export type RecoveryBackupResult =
  { status: 'ready'; draft: RecoveryDraft; json: string; bytes: number; draftSequence: number } | RecoveryFailure

export type RecoveryImportResult = { status: 'ready'; draft: RecoveryDraft } | RecoveryFailure

export type RecoveryWriteResult =
  | { status: 'written'; draftSequence: number; savedAt: string; key: string }
  | { status: 'superseded'; draftSequence: number }
  | (RecoveryFailure & { draftSequence: number })

const format = 'mantra.authoring-recovery/1'
const pendingWrites = new Map<string, number>()

function prefix(recording: AuthoringRecording): string {
  return `mantra.authoring.prototype.${recording.case}.`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function onlyKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).every((key) => keys.includes(key))
}

function strings(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((item) => typeof item === 'string')
}

function documents(value: unknown, recording: AuthoringRecording): value is DocumentTexts {
  return (
    strings(value) &&
    Object.keys(value).length === recording.documents.length &&
    recording.documents.every((document) => Object.hasOwn(value, document))
  )
}

function stringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function operation(value: unknown): value is SemanticOperation {
  if (!isRecord(value) || !onlyKeys(value, ['handle', 'op', 'value']) || typeof value.handle !== 'string') return false
  if (value.op === 'setClasses') return stringArray(value.value)
  if (value.op === 'setText' || value.op === 'setFormula') return typeof value.value === 'string'
  return (
    value.op === 'setLayoutOption' &&
    (typeof value.value === 'string' ||
      typeof value.value === 'boolean' ||
      (typeof value.value === 'number' && Number.isFinite(value.value)))
  )
}

function patch(value: unknown): value is SourcePatch {
  return (
    isRecord(value) &&
    onlyKeys(value, ['document', 'start', 'end', 'text', 'inverse']) &&
    typeof value.document === 'string' &&
    Number.isSafeInteger(value.start) &&
    Number.isSafeInteger(value.end) &&
    typeof value.text === 'string' &&
    typeof value.inverse === 'string'
  )
}

function transaction(value: unknown, recording: AuthoringRecording): value is SourceTransaction {
  if (
    !isRecord(value) ||
    !onlyKeys(value, ['label', 'patches', 'ownerHandles', 'operation', 'before', 'after', 'blockedReason']) ||
    typeof value.label !== 'string' ||
    !stringArray(value.ownerHandles) ||
    !Array.isArray(value.patches) ||
    !value.patches.every(patch) ||
    !documents(value.before, recording) ||
    !documents(value.after, recording) ||
    (value.operation !== undefined && !operation(value.operation)) ||
    (value.blockedReason !== undefined && typeof value.blockedReason !== 'string')
  ) {
    return false
  }
  try {
    return sameDocuments(applySourcePatches(value.before, value.patches), value.after)
  } catch {
    return false
  }
}

/** Blocked entries retain their byte evidence but form barriers beyond which undo or redo cannot replay. */
function history(
  value: unknown,
  recording: AuthoringRecording,
  current: DocumentTexts,
  baseline: DocumentTexts,
): value is SourceHistory {
  if (!isRecord(value) || !onlyKeys(value, ['past', 'future']) || !Array.isArray(value.past)) return false
  if (!Array.isArray(value.future) || ![...value.past, ...value.future].every((item) => transaction(item, recording))) {
    return false
  }
  const allPast = value.past as SourceTransaction[]
  const allFuture = value.future as SourceTransaction[]
  const pastBarrier = allPast.findLastIndex((item) => !!item.blockedReason)
  const futureInReplayOrder = [...allFuture].reverse()
  const futureBarrier = futureInReplayOrder.findIndex((item) => !!item.blockedReason)
  const past = allPast.slice(pastBarrier + 1)
  const future = futureBarrier < 0 ? futureInReplayOrder : futureInReplayOrder.slice(0, futureBarrier)
  let previous = past[0]?.before ?? current
  let includesBaseline = sameDocuments(previous, baseline)
  for (const item of past) {
    if (!sameDocuments(previous, item.before)) return false
    previous = item.after
    includesBaseline ||= sameDocuments(previous, baseline)
  }
  if (!sameDocuments(previous, current)) return false
  for (const item of future) {
    if (!sameDocuments(previous, item.before)) return false
    previous = item.after
    includesBaseline ||= sameDocuments(previous, baseline)
  }
  // An external revision can disconnect archived history from the new saved baseline. Its source hash is
  // checked separately; only the reachable history must connect to the unvalidated current draft.
  return includesBaseline || pastBarrier >= 0 || futureBarrier >= 0
}

async function validate(
  recording: AuthoringRecording,
  key: string,
  value: unknown,
): Promise<RecoveryDraft | undefined> {
  if (
    !isRecord(value) ||
    !onlyKeys(value, [
      'format',
      'case',
      'baseDocuments',
      'baseRevisions',
      'documents',
      'history',
      'inputs',
      'savedAt',
      'baseDigest',
    ]) ||
    value.format !== format ||
    value.case !== recording.case ||
    typeof value.baseDigest !== 'string' ||
    !/^[a-f0-9]{64}$/.test(value.baseDigest) ||
    key !== prefix(recording) + value.baseDigest ||
    !documents(value.baseDocuments, recording) ||
    !documents(value.baseRevisions, recording) ||
    !documents(value.documents, recording) ||
    !strings(value.inputs) ||
    typeof value.savedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.savedAt)) ||
    new Date(value.savedAt).toISOString() !== value.savedAt
  ) {
    return undefined
  }
  const recorded = recording.states.find((state) => state.digest === value.baseDigest)
  if (
    !recorded ||
    recorded.exchanges.some((exchange) => exchange.request.method === 'GET' && exchange.response.status === 422) ||
    !sameDocuments(documentsForState(recording, recorded), value.baseDocuments)
  )
    return undefined
  if ((await sourceDigest(value.baseDocuments)) !== value.baseDigest) return undefined
  if (!sameDocuments(await sourceRevisions(value.baseDocuments), value.baseRevisions)) return undefined
  if (!history(value.history, recording, value.documents, value.baseDocuments)) return undefined
  return {
    baseDocuments: value.baseDocuments,
    baseRevisions: value.baseRevisions,
    documents: value.documents,
    history: value.history,
    inputs: value.inputs,
    savedAt: value.savedAt,
    baseDigest: value.baseDigest,
  }
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).byteLength
}

function budgetFailure(): RecoveryFailure {
  return { status: 'invalid', reason: 'Draft backup exceeds the 2 MiB limit; no source or history was truncated.' }
}

function cryptoFailure(): RecoveryFailure {
  return { status: 'unavailable', reason: 'WebCrypto is unavailable; this draft backup could not be verified.' }
}

/** A portable backup uses the same source and history proof as browser recovery, with no preview evidence. */
export async function createRecoveryBackup(
  recording: AuthoringRecording,
  state: RecoveryState,
  inputs: Record<string, string>,
): Promise<RecoveryBackupResult> {
  const draftSequence = state.draftSequence
  let snapshot: Record<string, unknown>
  try {
    const serialized = JSON.stringify({
      format,
      case: recording.case,
      baseDocuments: state.baseDocuments,
      baseRevisions: state.baseRevisions,
      documents: state.documents,
      history: state.history,
      inputs,
      savedAt: new Date().toISOString(),
      baseDigest: '0'.repeat(64),
    })
    if (byteLength(serialized) > recoveryByteLimit) return budgetFailure()
    snapshot = JSON.parse(serialized)
    if (
      !documents(snapshot.baseDocuments, recording) ||
      !documents(snapshot.baseRevisions, recording) ||
      !documents(snapshot.documents, recording) ||
      !strings(snapshot.inputs) ||
      !history(snapshot.history, recording, snapshot.documents, snapshot.baseDocuments)
    )
      return { status: 'invalid', reason: 'The source draft, saved baseline or source history is invalid.' }
  } catch {
    return { status: 'invalid', reason: 'The source draft could not be serialized as a complete JSON backup.' }
  }
  let draft: RecoveryDraft | undefined
  try {
    snapshot.baseDigest = await sourceDigest(snapshot.baseDocuments as DocumentTexts)
    draft = await validate(recording, prefix(recording) + snapshot.baseDigest, snapshot)
  } catch {
    return cryptoFailure()
  }
  if (!draft) return { status: 'invalid', reason: 'The source draft, saved baseline or source history is invalid.' }
  const json = JSON.stringify(snapshot)
  return { status: 'ready', draft, json, bytes: byteLength(json), draftSequence }
}

/** Importing never restores a trusted engine preview or technical validity. */
export async function importRecoveryBackup(recording: AuthoringRecording, json: string): Promise<RecoveryImportResult> {
  if (byteLength(json) > recoveryByteLimit) return budgetFailure()
  let value: unknown
  try {
    value = JSON.parse(json)
  } catch {
    return { status: 'invalid', reason: 'The draft backup is not valid JSON.' }
  }
  let draft: RecoveryDraft | undefined
  try {
    const digest = isRecord(value) && typeof value.baseDigest === 'string' ? value.baseDigest : ''
    draft = await validate(recording, prefix(recording) + digest, value)
  } catch {
    return cryptoFailure()
  }
  return draft
    ? { status: 'ready', draft }
    : {
        status: 'invalid',
        reason: 'The backup does not match this template or contains invalid source, revisions or history.',
      }
}

/** Browser-only recovery is untrusted source data, never recovered validation or preview evidence. */
export async function readRecovery(recording: AuthoringRecording): Promise<RecoveryDraft | undefined> {
  let latest: RecoveryDraft | undefined
  try {
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
    for (const key of keys) {
      if (!key?.startsWith(prefix(recording))) continue
      try {
        const raw = localStorage.getItem(key)
        if (raw === null || byteLength(raw) > recoveryByteLimit) continue
        const candidate = await validate(recording, key, JSON.parse(raw))
        if (candidate && localStorage.getItem(key) === raw && (!latest || candidate.savedAt > latest.savedAt)) {
          latest = candidate
        }
      } catch {
        // Ignore malformed or stale records without losing another valid draft for this template.
      }
    }
  } catch {
    // Private browsing, blocked storage and unavailable WebCrypto do not prevent editing.
  }
  return latest
}

/** Only active user drafts call this helper; opening a clean editor must not erase an existing recovery. */
export async function writeRecovery(
  recording: AuthoringRecording,
  state: RecoveryState,
  inputs: Record<string, string>,
): Promise<RecoveryWriteResult> {
  const scope = prefix(recording)
  const generation = (pendingWrites.get(scope) ?? 0) + 1
  pendingWrites.set(scope, generation)
  const draftSequence = state.draftSequence
  const superseded = (): RecoveryWriteResult => ({ status: 'superseded', draftSequence })
  const backup = await createRecoveryBackup(recording, state, inputs)
  if (pendingWrites.get(scope) !== generation) return superseded()
  if (backup.status !== 'ready') return { ...backup, draftSequence }
  const key = scope + backup.draft.baseDigest
  try {
    localStorage.setItem(key, backup.json)
    if (localStorage.getItem(key) !== backup.json)
      return { status: 'unavailable', draftSequence, reason: 'Browser storage did not retain this draft backup.' }
    return { status: 'written', draftSequence, savedAt: backup.draft.savedAt, key }
  } catch (failure) {
    if (pendingWrites.get(scope) !== generation) return superseded()
    return {
      status: 'unavailable',
      draftSequence,
      reason:
        failure instanceof DOMException && failure.name === 'QuotaExceededError'
          ? 'Browser storage is full; this draft was not backed up in this browser.'
          : 'Browser storage is unavailable; this draft was not backed up in this browser.',
    }
  }
}

export function clearRecovery(recording: AuthoringRecording, baseDigest?: string): void {
  const scope = prefix(recording)
  pendingWrites.set(scope, (pendingWrites.get(scope) ?? 0) + 1)
  try {
    if (baseDigest !== undefined) {
      localStorage.removeItem(scope + baseDigest)
      return
    }
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
    for (const key of keys) if (key?.startsWith(scope)) localStorage.removeItem(key)
  } catch {
    // Restricted browser storage does not block an explicit discard in the current session.
  }
}
