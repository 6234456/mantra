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
  return sameDocuments(applySourcePatches(value.before, value.patches), value.after)
}

/** Saved history may begin before its saved baseline; future entries replay from the end of the stack. */
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
  const past = value.past as SourceTransaction[]
  const future = value.future as SourceTransaction[]
  let previous = past[0]?.before ?? current
  let includesBaseline = sameDocuments(previous, baseline)
  for (const item of past) {
    if (!sameDocuments(previous, item.before)) return false
    previous = item.after
    includesBaseline ||= sameDocuments(previous, baseline)
  }
  if (!sameDocuments(previous, current)) return false
  for (const item of [...future].reverse()) {
    if (!sameDocuments(previous, item.before)) return false
    previous = item.after
    includesBaseline ||= sameDocuments(previous, baseline)
  }
  return includesBaseline
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
  if (!recorded || !sameDocuments(documentsForState(recording, recorded), value.baseDocuments)) return undefined
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

/** Browser-only recovery is untrusted source data, never recovered validation or preview evidence. */
export async function readRecovery(recording: AuthoringRecording): Promise<RecoveryDraft | undefined> {
  let latest: RecoveryDraft | undefined
  try {
    const keys = Array.from({ length: localStorage.length }, (_, index) => localStorage.key(index))
    for (const key of keys) {
      if (!key?.startsWith(prefix(recording))) continue
      try {
        const raw = localStorage.getItem(key)
        if (raw === null) continue
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
  state: AuthoringState,
  inputs: Record<string, string>,
): Promise<void> {
  const scope = prefix(recording)
  const generation = (pendingWrites.get(scope) ?? 0) + 1
  pendingWrites.set(scope, generation)
  try {
    const snapshot = JSON.parse(
      JSON.stringify({
        format,
        case: recording.case,
        baseDocuments: state.baseDocuments,
        baseRevisions: state.baseRevisions,
        documents: state.documents,
        history: state.history,
        inputs,
        savedAt: new Date().toISOString(),
      }),
    )
    snapshot.baseDigest = await sourceDigest(snapshot.baseDocuments)
    const key = scope + snapshot.baseDigest
    if (!(await validate(recording, key, snapshot)) || pendingWrites.get(scope) !== generation) return
    localStorage.setItem(key, JSON.stringify(snapshot))
  } catch {
    // Recovery persistence is optional; no successful file save or validation is implied.
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
