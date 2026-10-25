// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { parseBackup, recoveryByteLimit, recoveryFormat, type RecoveryDraft } from './recovery'
import {
  clearRecoveryStorage,
  readRecoveryStorage,
  recoveryStorageKey,
  recoveryStorageNamespace,
  writeRecoveryStorage,
  type RecoveryStorage,
} from './recoveryStorage'

const workspace = 'a'.repeat(64)
const otherWorkspace = 'b'.repeat(64)
const caseId = 'examples/case 甲.mantra'
const sourceHash = 'c'.repeat(64)

function backup(overrides: Partial<RecoveryDraft> = {}): RecoveryDraft {
  return {
    format: recoveryFormat,
    savedAt: '2026-10-25T00:00:00.000Z',
    workspaceId: workspace,
    caseId,
    baseRevision: 'd'.repeat(64),
    baseRevisions: { 'schema.mantra': sourceHash, 'case.mantra': 'e'.repeat(64) },
    documents: [
      {
        handle: 'f'.repeat(64),
        document: 'schema.mantra',
        role: 'schema',
        sha256: sourceHash,
        baseText: '(schema original)',
        text: '; 原文😀\r\n(schema invalid draft',
      },
    ],
    inputs: [{ node: 'amount', text: '9007199254740993.000001' }],
    pending: { layoutSettings: [], styleClass: null },
    ...overrides,
  }
}

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    values,
    getItem: vi.fn((key: string) => values.get(key) ?? null),
    setItem: vi.fn((key: string, value: string) => void values.set(key, value)),
    removeItem: vi.fn((key: string) => void values.delete(key)),
  }
}

afterEach(() => vi.restoreAllMocks())

it('isolates case slots by opaque workspace and encoded canonical case identity', () => {
  const key = recoveryStorageKey(workspace, caseId)
  expect(key).toBe(`${recoveryStorageNamespace}${workspace}.${encodeURIComponent(caseId)}`)
  expect(recoveryStorageKey(otherWorkspace, caseId)).not.toBe(key)
  expect(recoveryStorageKey(workspace, 'examples/case%20甲.mantra')).not.toBe(key)
  expect(recoveryStorageKey(workspace, 'examples/case/甲.mantra')).not.toBe(key)
  expect(() => recoveryStorageKey('not-a-workspace-id', caseId)).toThrow()
  expect(() => recoveryStorageKey(workspace, '  ')).toThrow()
})

it('reads a clean slot without writing or removing another available draft', () => {
  const storage = memoryStorage()
  const otherKey = recoveryStorageKey(otherWorkspace, caseId)
  storage.values.set(otherKey, 'another workspace copy')
  expect(readRecoveryStorage(workspace, caseId, storage)).toEqual({ status: 'empty', raw: null })
  expect(storage.values.get(otherKey)).toBe('another workspace copy')
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('preserves exact valid, stale-revision and corrupted stored JSON for review or download', () => {
  const storage = memoryStorage()
  const key = recoveryStorageKey(workspace, caseId)
  const candidates = [
    JSON.stringify(backup()),
    JSON.stringify(backup({ baseRevision: '1'.repeat(64) })),
    '{ corrupted JSON without any complete source',
  ]
  for (const [index, raw] of candidates.entries()) {
    storage.values.set(key, raw)
    const result = readRecoveryStorage(workspace, caseId, storage)
    expect(result.status).toBe('stored')
    if (result.status !== 'stored') throw new Error('The observed raw copy should remain available')
    expect(result.raw).toBe(raw)
    expect(result.backup.status).toBe(index === 2 ? 'invalid' : 'ready')
    expect(storage.values.get(key)).toBe(raw)
  }
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('confirms a complete write by reading it back and updates only an explicitly observed copy', () => {
  const storage = memoryStorage()
  const original = backup()
  const json = JSON.stringify(original)
  const newer = JSON.stringify(backup({ savedAt: '2026-10-25T01:00:00.000Z', inputs: [{ node: 'amount', text: '' }] }))
  expect(writeRecoveryStorage(workspace, caseId, json, null, storage)).toEqual({ status: 'written', raw: json })
  expect(storage.getItem).toHaveBeenCalledTimes(2)
  expect(writeRecoveryStorage(workspace, caseId, newer, json, storage)).toEqual({ status: 'written', raw: newer })
  expect(storage.values.get(recoveryStorageKey(workspace, caseId))).toBe(newer)
  expect(JSON.stringify(original)).toBe(json)
  const read = readRecoveryStorage(workspace, caseId, storage)
  expect(read.status).toBe('stored')
  if (read.status !== 'stored' || read.backup.status !== 'ready') throw new Error('Expected complete readable source')
  expect(read.backup.draft.inputs).toEqual([{ node: 'amount', text: '' }])
})

it('refuses stale replacement and stale discard without changing a newer page copy', () => {
  const storage = memoryStorage()
  const key = recoveryStorageKey(workspace, caseId)
  const older = JSON.stringify(backup())
  const newer = JSON.stringify(backup({ savedAt: '2026-10-25T01:00:00.000Z' }))
  storage.values.set(key, newer)
  expect(writeRecoveryStorage(workspace, caseId, older, null, storage)).toMatchObject({
    status: 'conflict',
    currentRaw: newer,
  })
  expect(writeRecoveryStorage(workspace, caseId, older, older, storage)).toMatchObject({
    status: 'conflict',
    currentRaw: newer,
  })
  expect(clearRecoveryStorage(workspace, caseId, older, storage)).toMatchObject({
    status: 'conflict',
    currentRaw: newer,
  })
  expect(storage.values.get(key)).toBe(newer)
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('does not report protection when a storage implementation silently drops the write', () => {
  const storage = memoryStorage()
  storage.setItem.mockImplementation(() => undefined)
  const result = writeRecoveryStorage(workspace, caseId, JSON.stringify(backup()), null, storage)
  expect(result.status).toBe('unavailable')
  expect(result).toMatchObject({ reason: expect.stringContaining('did not retain') })
  expect(storage.values.size).toBe(0)
})

it('does not report success when another value is observed immediately after writing', () => {
  const storage = memoryStorage()
  const key = recoveryStorageKey(workspace, caseId)
  storage.setItem.mockImplementation(() => void storage.values.set(key, 'a later page copy'))
  const result = writeRecoveryStorage(workspace, caseId, JSON.stringify(backup()), null, storage)
  expect(result.status).toBe('unavailable')
  expect(storage.values.get(key)).toBe('a later page copy')
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('reports quota failure without losing the old copy or the independently downloadable draft', () => {
  const storage = memoryStorage()
  const key = recoveryStorageKey(workspace, caseId)
  const previous = JSON.stringify(backup())
  const draft = backup({ inputs: [{ node: 'amount', text: 'nine' }] })
  const raw = JSON.stringify(draft)
  storage.values.set(key, previous)
  storage.setItem.mockImplementation(() => {
    throw new DOMException('full', 'QuotaExceededError')
  })
  const result = writeRecoveryStorage(workspace, caseId, raw, previous, storage)
  expect(result).toMatchObject({ status: 'unavailable', reason: expect.stringContaining('storage is full') })
  expect(storage.values.get(key)).toBe(previous)
  expect(parseBackup(raw)).toEqual({ status: 'ready', draft })
  expect(draft.inputs).toEqual([{ node: 'amount', text: 'nine' }])
})

it('handles unavailable read and clear operations without claiming that data was removed', () => {
  const storage = memoryStorage()
  storage.getItem.mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(readRecoveryStorage(workspace, caseId, storage)).toMatchObject({ status: 'unavailable' })
  expect(writeRecoveryStorage(workspace, caseId, JSON.stringify(backup()), null, storage)).toMatchObject({
    status: 'unavailable',
  })
  expect(clearRecoveryStorage(workspace, caseId, null, storage)).toMatchObject({ status: 'unavailable' })
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('reports a blocked default browser-storage getter without preventing backup parsing', () => {
  vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  const json = JSON.stringify(backup())
  expect(readRecoveryStorage(workspace, caseId)).toMatchObject({ status: 'unavailable' })
  expect(writeRecoveryStorage(workspace, caseId, json, null)).toMatchObject({ status: 'unavailable' })
  expect(clearRecoveryStorage(workspace, caseId, null)).toMatchObject({ status: 'unavailable' })
  expect(parseBackup(json).status).toBe('ready')
})

it('rejects invalid, oversized, foreign-workspace and foreign-case backups before accessing storage', () => {
  const storage = memoryStorage()
  for (const raw of [
    '{ broken JSON',
    'x'.repeat(recoveryByteLimit + 1),
    JSON.stringify(backup({ workspaceId: otherWorkspace })),
    JSON.stringify(backup({ caseId: 'different/case.mantra' })),
  ]) {
    expect(writeRecoveryStorage(workspace, caseId, raw, null, storage).status).toBe('unavailable')
  }
  expect(storage.getItem).not.toHaveBeenCalled()
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})

it('removes only the exact observed case copy and leaves neighboring workspaces and cases intact', () => {
  const storage = memoryStorage()
  const raw = JSON.stringify(backup())
  const key = recoveryStorageKey(workspace, caseId)
  const otherWorkspaceKey = recoveryStorageKey(otherWorkspace, caseId)
  const otherCaseKey = recoveryStorageKey(workspace, 'other/case.mantra')
  storage.values.set(key, raw)
  storage.values.set(otherWorkspaceKey, 'workspace copy')
  storage.values.set(otherCaseKey, 'case copy')
  expect(clearRecoveryStorage(workspace, caseId, raw, storage)).toEqual({ status: 'cleared' })
  expect(storage.values.has(key)).toBe(false)
  expect(storage.values.get(otherWorkspaceKey)).toBe('workspace copy')
  expect(storage.values.get(otherCaseKey)).toBe('case copy')
  expect(clearRecoveryStorage(workspace, caseId, null, storage)).toEqual({ status: 'cleared' })
})

it('does not claim discard success when removal is ignored or rejected by storage', () => {
  const storage = memoryStorage()
  const raw = JSON.stringify(backup())
  const key = recoveryStorageKey(workspace, caseId)
  storage.values.set(key, raw)
  storage.removeItem.mockImplementation(() => undefined)
  expect(clearRecoveryStorage(workspace, caseId, raw, storage)).toMatchObject({ status: 'unavailable' })
  expect(storage.values.get(key)).toBe(raw)
  storage.removeItem.mockImplementation(() => {
    throw new DOMException('blocked', 'SecurityError')
  })
  expect(clearRecoveryStorage(workspace, caseId, raw, storage)).toMatchObject({ status: 'unavailable' })
  expect(storage.values.get(key)).toBe(raw)
})

it('avoids all storage mutation for invalid workspace and case identities', () => {
  const storage = memoryStorage()
  const typed: RecoveryStorage = storage
  expect(readRecoveryStorage('invalid', caseId, typed)).toMatchObject({ status: 'unavailable' })
  expect(clearRecoveryStorage(workspace, '', null, typed)).toMatchObject({ status: 'unavailable' })
  expect(storage.getItem).not.toHaveBeenCalled()
  expect(storage.setItem).not.toHaveBeenCalled()
  expect(storage.removeItem).not.toHaveBeenCalled()
})
