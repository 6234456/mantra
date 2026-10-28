import { parseBackup, type RecoveryParseResult } from './recovery'

export type RecoveryStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
export const recoveryStorageNamespace = 'mantra.template-preview.recovery.v1.'
export type RecoveryStorageFailure = { status: 'unavailable'; reason: string }
export type RecoveryStorageConflict = { status: 'conflict'; reason: string; currentRaw: string | null }
export type RecoveryStorageRead =
  | { status: 'empty'; raw: null }
  | { status: 'stored'; raw: string; backup: RecoveryParseResult }
  | RecoveryStorageFailure
export type RecoveryStorageWrite = { status: 'written'; raw: string } | RecoveryStorageConflict | RecoveryStorageFailure
export type RecoveryStorageClear = { status: 'cleared' } | RecoveryStorageConflict | RecoveryStorageFailure

export function recoveryStorageKey(workspaceId: string, caseId: string): string {
  if (!/^[a-f0-9]{64}$/.test(workspaceId) || !caseId.trim())
    throw new Error('Recovery storage needs a valid workspace and case identity.')
  return `${recoveryStorageNamespace}${workspaceId}.${encodeURIComponent(caseId)}`
}

function unavailable(failure?: unknown): RecoveryStorageFailure {
  return {
    status: 'unavailable',
    reason:
      failure instanceof DOMException && failure.name === 'QuotaExceededError'
        ? 'Browser storage is full. This draft has not been protected in this browser; download a JSON backup.'
        : 'Browser storage is unavailable. This draft has not been protected in this browser; download a JSON backup.',
  }
}

function conflict(currentRaw: string | null): RecoveryStorageConflict {
  return {
    status: 'conflict',
    currentRaw,
    reason:
      'Another page changed this browser copy. Automatic replacement is paused; review the stored copy before replacing it.',
  }
}

export function readRecoveryStorage(
  workspaceId: string,
  caseId: string,
  storage?: RecoveryStorage,
): RecoveryStorageRead {
  try {
    const actual = storage ?? window.localStorage
    const raw = actual.getItem(recoveryStorageKey(workspaceId, caseId))
    return raw === null ? { status: 'empty', raw: null } : { status: 'stored', raw, backup: parseBackup(raw) }
  } catch (failure) {
    return unavailable(failure)
  }
}

/** Expected raw text is a conflict guard, not a promise of transaction-atomic cross-tab storage. */
export function writeRecoveryStorage(
  workspaceId: string,
  caseId: string,
  json: string,
  expectedRaw: string | null,
  storage?: RecoveryStorage,
): RecoveryStorageWrite {
  try {
    const parsed = parseBackup(json)
    if (parsed.status !== 'ready' || parsed.draft.workspaceId !== workspaceId || parsed.draft.caseId !== caseId)
      return {
        status: 'unavailable',
        reason: 'An invalid or differently scoped backup was not written to browser storage.',
      }
    const actual = storage ?? window.localStorage
    const key = recoveryStorageKey(workspaceId, caseId)
    const before = actual.getItem(key)
    if (before !== expectedRaw) return conflict(before)
    actual.setItem(key, json)
    const after = actual.getItem(key)
    if (after !== json)
      return {
        status: 'unavailable',
        reason: 'Browser storage did not retain the complete draft. Download a JSON backup.',
      }
    return { status: 'written', raw: json }
  } catch (failure) {
    return unavailable(failure)
  }
}

/** Only an explicitly observed copy may be removed, preserving another page's newer source. */
export function clearRecoveryStorage(
  workspaceId: string,
  caseId: string,
  expectedRaw: string | null,
  storage?: RecoveryStorage,
): RecoveryStorageClear {
  try {
    const actual = storage ?? window.localStorage
    const key = recoveryStorageKey(workspaceId, caseId)
    const before = actual.getItem(key)
    if (before !== expectedRaw) return conflict(before)
    actual.removeItem(key)
    if (actual.getItem(key) !== null)
      return {
        status: 'unavailable',
        reason: 'Browser storage did not remove the observed copy. It may still be offered after refresh.',
      }
    return { status: 'cleared' }
  } catch (failure) {
    return unavailable(failure)
  }
}
