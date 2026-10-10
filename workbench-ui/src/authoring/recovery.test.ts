import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applySourcePatches, authoringReducer, canRedo, canUndo, createAuthoringState } from './model'
import type { AuthoringState, SourceTransaction } from './model'
import {
  clearRecovery,
  createRecoveryBackup,
  importRecoveryBackup,
  readRecovery,
  recoveryByteLimit,
  writeRecovery,
} from './recovery'
import type { AuthoringRecording, DocumentTexts, SourcePatch } from './service'
import { sourceDigest, sourceRevisions } from './simulated/recordedService'

function memoryStorage(): Storage {
  const items = new Map<string, string>()
  return {
    get length() {
      return items.size
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => items.delete(key),
    setItem: (key, value) => items.set(key, String(value)),
  }
}

const original: DocumentTexts = {
  'schema.mantra': '; source comment\r\n(info result "Result" (- request allocated) {:class :result})\r\n',
  'layout.mantra': '(layout example {:title "Working paper" :precision 2})\n',
}

const external: DocumentTexts = {
  ...original,
  'layout.mantra': original['layout.mantra'].replace('Working paper', 'Outside title'),
}

const saved: DocumentTexts = {
  ...external,
  'schema.mantra': original['schema.mantra'].replace('"Result"', '"Remaining"'),
}

let recording: AuthoringRecording

function sourcePatch(documents: DocumentTexts, document: string, find: string, text: string): SourcePatch {
  const start = documents[document].indexOf(find)
  if (start < 0) throw new Error('Test source text was not found')
  return { document, start, end: start + find.length, text, inverse: find }
}

function sourceTransaction(before: DocumentTexts, patch: SourcePatch): SourceTransaction {
  return {
    label: 'Source edit',
    patches: [patch],
    ownerHandles: ['simulated-owner'],
    before,
    after: applySourcePatches(before, [patch]),
  }
}

async function initial(documents = original): Promise<AuthoringState> {
  return createAuthoringState({ documents, baseRevisions: await sourceRevisions(documents) })
}

function edit(state: AuthoringState, find = '"Result"', text = '"Remaining"'): AuthoringState {
  return authoringReducer(state, {
    type: 'transaction',
    label: 'Source edit',
    patches: [sourcePatch(state.documents, 'schema.mantra', find, text)],
  })
}

function stored(): { key: string; value: Record<string, unknown> } {
  const key = localStorage.key(0)!
  return { key, value: JSON.parse(localStorage.getItem(key)!) }
}

beforeEach(async () => {
  vi.stubGlobal('localStorage', memoryStorage())
  const baseRevisions = await sourceRevisions(original)
  recording = {
    format: 'mantra.authoring-recording/1',
    notice: 'Synthetic transport-free test fixture',
    recordedAt: '2026-10-10T00:00:00.000Z',
    source: { repository: 'test', commit: 'test', patternsDirty: false },
    contract: 'mantra.workbench/4',
    engine: {},
    workspace: 'test',
    case: 'example.mantra',
    panel: 'example',
    documents: Object.keys(original),
    base: Object.fromEntries(
      Object.entries(original).map(([path, text]) => [path, { text, sha256: baseRevisions[path] }]),
    ),
    edits: {},
    states: await Promise.all(
      [original, external, saved].map(async (documents, index) => {
        const revisions = await sourceRevisions(documents)
        return {
          id: `state-${index}`,
          edits: [],
          digest: await sourceDigest(documents),
          documents: Object.fromEntries(
            Object.entries(documents).map(([path, text]) => [path, { text, sha256: revisions[path] }]),
          ),
          exchanges: [],
        }
      }),
    ),
    blobs: {},
  }
})

afterEach(() => {
  clearRecovery(recording)
  vi.unstubAllGlobals()
})

describe('browser-only authoring recovery', () => {
  it('recovers exact source bytes, source transactions and unsubmitted input without preview evidence', async () => {
    const state = edit(await initial())
    await writeRecovery(recording, state, { label: '未提交', example: 'nine' })
    const recovered = await readRecovery(recording)
    expect(recovered?.documents).toEqual(state.documents)
    expect(recovered?.baseDocuments).toEqual(original)
    expect(recovered?.history).toEqual(state.history)
    expect(recovered?.inputs).toEqual({ label: '未提交', example: 'nine' })
    expect(recovered?.documents['schema.mantra']).toContain('; source comment\r\n')
    expect(recovered).not.toHaveProperty('preview')
    expect(recovered).not.toHaveProperty('validity')
    const restored = authoringReducer(await initial(), {
      type: 'restore',
      documents: recovered!.documents,
      history: recovered!.history,
    })
    expect(restored.restored).toBe(true)
    expect(restored.validity).toBe('unchecked')
    expect(restored.preview.current).toBeUndefined()
  })

  it('reopens a changed saved baseline after external rebase and preserves past and future history', async () => {
    const label = sourceTransaction(external, sourcePatch(external, 'schema.mantra', '"Result"', '"Remaining"'))
    let state: AuthoringState = { ...(await initial(saved)), history: { past: [label], future: [] } }
    state = edit(state, '(- request allocated)', '(/ request 0)')
    state = edit(state, '"Remaining"', '"Further label"')
    state = authoringReducer(state, { type: 'undo' })
    await writeRecovery(recording, state, { formula: '(/ request 0)' })
    const recovered = await readRecovery(recording)
    expect(recovered?.baseDocuments).toEqual(saved)
    expect(recovered?.baseRevisions).toEqual(await sourceRevisions(saved))
    expect(recovered?.baseDigest).toEqual(await sourceDigest(saved))
    expect(recovered?.history.past).toHaveLength(2)
    expect(recovered?.history.future).toHaveLength(1)
    const reopened = createAuthoringState({
      documents: recovered!.baseDocuments,
      baseRevisions: recovered!.baseRevisions,
    })
    const restored = authoringReducer(reopened, {
      type: 'restore',
      documents: recovered!.documents,
      history: recovered!.history,
    })
    expect(authoringReducer(restored, { type: 'redo' }).documents['schema.mantra']).toContain('Further label')
    expect(authoringReducer(restored, { type: 'undo' }).documents).toEqual(saved)
  })

  it('keeps unsubmitted input on a clean saved source baseline', async () => {
    await writeRecovery(recording, await initial(saved), { label: 'Not submitted' })
    expect((await readRecovery(recording))?.inputs).toEqual({ label: 'Not submitted' })
  })

  it('retains pending source input after a clean external refresh archives blocked redo history', async () => {
    let state = authoringReducer(await initial(), {
      type: 'transaction',
      label: 'Layout title',
      patches: [sourcePatch(original, 'layout.mantra', 'Working paper', 'My title')],
    })
    state = authoringReducer(state, { type: 'undo' })
    state = authoringReducer(state, {
      type: 'externalChanged',
      documents: external,
      baseRevisions: await sourceRevisions(external),
      changed: ['layout.mantra'],
    })
    expect(state.history.future[0].blockedReason).toContain('source changed outside')
    expect(canRedo(state)).toBe(false)
    await writeRecovery(recording, state, { schemaSource: 'An unsubmitted source draft' })
    const recovered = await readRecovery(recording)
    expect(recovered?.baseDocuments).toEqual(external)
    expect(recovered?.inputs.schemaSource).toBe('An unsubmitted source draft')
    expect(recovered?.history).toEqual(state.history)
    const reopened = createAuthoringState({
      documents: recovered!.baseDocuments,
      baseRevisions: recovered!.baseRevisions,
    })
    const restored = authoringReducer(reopened, {
      type: 'restore',
      documents: recovered!.documents,
      history: recovered!.history,
    })
    expect(canRedo(restored)).toBe(false)
    expect(authoringReducer(restored, { type: 'redo' })).toBe(restored)
    expect(restored.restored).toBe(true)
  })

  it('preserves archived blocked past entries while validating and replaying the current source draft', async () => {
    const archived = sourceTransaction(original, sourcePatch(original, 'layout.mantra', 'Working paper', 'My title'))
    archived.blockedReason = 'Cannot undo: source changed outside this editor'
    const active = sourceTransaction(external, sourcePatch(external, 'schema.mantra', '"Result"', '"Remaining"'))
    const state: AuthoringState = {
      ...(await initial(external)),
      documents: active.after,
      history: { past: [archived, active], future: [] },
    }
    await writeRecovery(recording, state, {})
    const recovered = await readRecovery(recording)
    expect(recovered?.history.past).toEqual([archived, active])
    const restored = authoringReducer(await initial(external), {
      type: 'restore',
      documents: recovered!.documents,
      history: recovered!.history,
    })
    const undone = authoringReducer(restored, { type: 'undo' })
    expect(undone.documents).toEqual(external)
    expect(canUndo(undone)).toBe(false)
    expect(authoringReducer(undone, { type: 'undo' })).toBe(undone)
    const { key, value } = stored()
    const history = value.history as AuthoringState['history']
    history.past[0].patches[0].inverse = 'Tampered archived patch'
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('ignores a malformed sibling record and selects the newest valid saved baseline', async () => {
    await writeRecovery(recording, edit(await initial()), { label: 'Older draft' })
    const older = stored()
    older.value.savedAt = '2026-10-10T00:00:00.000Z'
    localStorage.setItem(older.key, JSON.stringify(older.value))
    await writeRecovery(recording, await initial(saved), { label: 'Newer draft' })
    const newerKey = `mantra.authoring.prototype.${recording.case}.${await sourceDigest(saved)}`
    const newer = JSON.parse(localStorage.getItem(newerKey)!)
    newer.savedAt = '2026-10-10T00:01:00.000Z'
    localStorage.setItem(newerKey, JSON.stringify(newer))
    localStorage.setItem(`mantra.authoring.prototype.${recording.case}.broken`, '{invalid JSON')
    expect((await readRecovery(recording))?.inputs.label).toBe('Newer draft')
  })

  it.each(['baseDocuments', 'baseRevisions', 'documents'] as const)('rejects an altered %s closure', async (field) => {
    await writeRecovery(recording, edit(await initial()), {})
    const { key, value } = stored()
    value[field] = { ...(value[field] as Record<string, string>), unexpected: 'Injected source' }
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('rejects changed saved baseline bytes even when the attacker retains its recorded digest', async () => {
    await writeRecovery(recording, edit(await initial()), {})
    const { key, value } = stored()
    ;(value.baseDocuments as DocumentTexts)['layout.mantra'] += '; tampered'
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('rejects fabricated source revisions and mismatched storage keys', async () => {
    await writeRecovery(recording, edit(await initial()), {})
    const { key, value } = stored()
    ;(value.baseRevisions as DocumentTexts)['schema.mantra'] = 'a'.repeat(64)
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
    localStorage.clear()
    await writeRecovery(recording, edit(await initial()), {})
    const valid = stored()
    localStorage.removeItem(valid.key)
    localStorage.setItem(`mantra.authoring.prototype.${recording.case}.${'b'.repeat(64)}`, JSON.stringify(valid.value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it.each(['inverse', 'end', 'after'] as const)('rejects tampered transaction %s evidence', async (field) => {
    await writeRecovery(recording, edit(await initial()), {})
    const { key, value } = stored()
    const history = value.history as AuthoringState['history']
    if (field === 'inverse') history.past[0].patches[0].inverse = 'Wrong original'
    else if (field === 'end') history.past[0].patches[0].end = 100000
    else history.past[0].after['schema.mantra'] += '; fabricated after snapshot'
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('rejects a valid individual future patch whose before snapshot breaks the history chain', async () => {
    const state = authoringReducer(edit(edit(await initial()), '"Remaining"', '"Further label"'), { type: 'undo' })
    await writeRecovery(recording, state, {})
    const { key, value } = stored()
    const history = value.history as AuthoringState['history']
    history.future[0] = sourceTransaction(original, sourcePatch(original, 'schema.mantra', '"Result"', '"Other"'))
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('rejects injected preview evidence rather than treating it as recovered validation', async () => {
    await writeRecovery(recording, edit(await initial()), {})
    const { key, value } = stored()
    value.preview = { status: 'current', kind: 'valid' }
    localStorage.setItem(key, JSON.stringify(value))
    expect(await readRecovery(recording)).toBeUndefined()
  })

  it('does not overwrite a newer recovery when older asynchronous hashing finishes later', async () => {
    const state = edit(await initial())
    const digest = crypto.subtle.digest.bind(crypto.subtle)
    let releaseFirst!: () => void
    let delayFirst = true
    const firstHash = new Promise<void>((resolve) => {
      releaseFirst = resolve
    })
    const spy = vi.spyOn(crypto.subtle, 'digest').mockImplementation((algorithm, bytes) => {
      const result = digest(algorithm, bytes)
      if (!delayFirst) return result
      delayFirst = false
      return result.then(async (hash) => {
        await firstHash
        return hash
      })
    })
    try {
      const older = writeRecovery(recording, state, { label: 'Older input' })
      expect((await writeRecovery(recording, state, { label: 'Newest input' })).status).toBe('written')
      releaseFirst()
      expect((await older).status).toBe('superseded')
      expect((await readRecovery(recording))?.inputs.label).toBe('Newest input')
    } finally {
      releaseFirst()
      spy.mockRestore()
    }
  })

  it('discards only this template and prevents a pending write from resurrecting its recovery', async () => {
    localStorage.setItem('mantra.authoring.prototype.other.example', 'Keep another template')
    const pending = writeRecovery(recording, edit(await initial()), { label: 'Pending' })
    clearRecovery(recording)
    expect((await pending).status).toBe('superseded')
    expect(await readRecovery(recording)).toBeUndefined()
    expect(localStorage.getItem('mantra.authoring.prototype.other.example')).toBe('Keep another template')
  })

  it('can discard just one saved baseline without removing another valid draft', async () => {
    await writeRecovery(recording, edit(await initial()), {})
    await writeRecovery(recording, await initial(saved), { label: 'Retained saved baseline' })
    clearRecovery(recording, await sourceDigest(original))
    expect((await readRecovery(recording))?.baseDocuments).toEqual(saved)
  })

  it('continues safely when browser storage cannot be read or written', async () => {
    const fail = () => {
      throw new DOMException('Storage is unavailable', 'SecurityError')
    }
    vi.stubGlobal('localStorage', {
      get length() {
        return fail()
      },
      getItem: fail,
      setItem: fail,
      removeItem: fail,
    })
    await expect(writeRecovery(recording, edit(await initial()), {})).resolves.toMatchObject({
      status: 'unavailable',
      reason: 'Browser storage is unavailable; this draft was not backed up in this browser.',
    })
    await expect(readRecovery(recording)).resolves.toBeUndefined()
    expect(() => clearRecovery(recording)).not.toThrow()
  })

  it('confirms a browser write only after reading back the same complete recovery payload', async () => {
    const state = edit(await initial())
    const result = await writeRecovery(recording, state, { label: 'Pending value' })
    expect(result).toMatchObject({ status: 'written', draftSequence: state.draftSequence })
    if (result.status !== 'written') throw new Error('Recovery should be confirmed')
    const raw = JSON.parse(localStorage.getItem(result.key)!)
    expect(raw.savedAt).toBe(result.savedAt)
    expect(raw.documents).toEqual(state.documents)
    expect(raw.inputs).toEqual({ label: 'Pending value' })

    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {})
    const failed = await writeRecovery(recording, state, { label: 'Newest value' })
    expect(failed).toMatchObject({ status: 'unavailable', reason: 'Browser storage did not retain this draft backup.' })
    expect((await readRecovery(recording))?.inputs.label).toBe('Pending value')
  })

  it('reports a full browser quota without claiming the current draft can be recovered', async () => {
    const state = edit(await initial())
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
      throw new DOMException('No space', 'QuotaExceededError')
    })
    expect(await writeRecovery(recording, state, { label: 'Unsaved input' })).toEqual({
      status: 'unavailable',
      draftSequence: state.draftSequence,
      reason: 'Browser storage is full; this draft was not backed up in this browser.',
    })
    expect(localStorage.length).toBe(0)
  })

  it('reports unavailable WebCrypto instead of an apparently successful recovery write', async () => {
    const state = edit(await initial())
    const digest = vi.spyOn(crypto.subtle, 'digest').mockRejectedValue(new Error('Crypto disabled'))
    try {
      expect(await writeRecovery(recording, state, {})).toEqual({
        status: 'unavailable',
        draftSequence: state.draftSequence,
        reason: 'WebCrypto is unavailable; this draft backup could not be verified.',
      })
      expect(localStorage.length).toBe(0)
    } finally {
      digest.mockRestore()
    }
  })
})

describe('portable source draft backups', () => {
  it('round-trips exact source, undo and redo history and pending buffers without browser storage or preview evidence', async () => {
    let state = edit(await initial())
    state = edit(state, '"Remaining"', '"Further label"')
    state = authoringReducer(state, { type: 'undo' })
    const inputs = { 'source:schema.mantra': state.documents['schema.mantra'] + '; 未提交\r\n', label: '未提交文本' }
    vi.stubGlobal('localStorage', undefined)
    const backup = await createRecoveryBackup(recording, state, inputs)
    expect(backup.status).toBe('ready')
    if (backup.status !== 'ready') throw new Error('Backup should be ready')
    expect(backup.draftSequence).toBe(state.draftSequence)
    expect(backup.bytes).toBe(new TextEncoder().encode(backup.json).byteLength)
    expect(JSON.parse(backup.json)).not.toHaveProperty('preview')
    expect(JSON.parse(backup.json)).not.toHaveProperty('diagnostics')
    expect(JSON.parse(backup.json)).not.toHaveProperty('validity')
    const imported = await importRecoveryBackup(recording, backup.json)
    expect(imported).toEqual({ status: 'ready', draft: backup.draft })
    if (imported.status !== 'ready') throw new Error('Import should be ready')
    expect(imported.draft.documents).toEqual(state.documents)
    expect(imported.draft.history).toEqual(state.history)
    expect(imported.draft.inputs).toEqual(inputs)
    const restored = authoringReducer(await initial(), {
      type: 'restore',
      documents: imported.draft.documents,
      history: imported.draft.history,
    })
    expect(restored.validity).toBe('unchecked')
    expect(restored.preview.current).toBeUndefined()
    expect(authoringReducer(restored, { type: 'redo' }).documents['schema.mantra']).toContain('"Further label"')
  })

  it('snapshots source and pending buffers before asynchronous hashing so the download belongs to its requested draft', async () => {
    const state = edit(await initial())
    const inputs = { label: 'Requested buffer' }
    const pending = createRecoveryBackup(recording, state, inputs)
    const originalSequence = state.draftSequence
    state.draftSequence += 100
    state.documents['schema.mantra'] += '; Later source'
    inputs.label = 'Later buffer'
    const backup = await pending
    if (backup.status !== 'ready') throw new Error('Requested snapshot should remain valid')
    expect(backup.draftSequence).toBe(originalSequence)
    expect(backup.draft.documents['schema.mantra']).not.toContain('Later source')
    expect(backup.draft.inputs.label).toBe('Requested buffer')
  })

  it('rejects malformed JSON, a different template and injected validation fields without restoring source', async () => {
    expect(await importRecoveryBackup(recording, '{broken')).toMatchObject({
      status: 'invalid',
      reason: 'The draft backup is not valid JSON.',
    })
    const backup = await createRecoveryBackup(recording, edit(await initial()), {})
    if (backup.status !== 'ready') throw new Error('Backup should be ready')
    const value = JSON.parse(backup.json)
    expect(await importRecoveryBackup(recording, JSON.stringify({ ...value, case: 'another.mantra' }))).toMatchObject({
      status: 'invalid',
    })
    expect(
      await importRecoveryBackup(
        recording,
        JSON.stringify({ ...value, preview: { status: 'current', kind: 'valid' } }),
      ),
    ).toMatchObject({ status: 'invalid' })
    expect(await importRecoveryBackup(recording, JSON.stringify({ ...value, validity: 'valid' }))).toMatchObject({
      status: 'invalid',
    })
  })

  it('uses the same patch and revision proofs for imported files as for browser recovery', async () => {
    const backup = await createRecoveryBackup(recording, edit(await initial()), {})
    if (backup.status !== 'ready') throw new Error('Backup should be ready')
    const value = JSON.parse(backup.json)
    value.history.past[0].patches[0].inverse = 'Tampered original source'
    expect(await importRecoveryBackup(recording, JSON.stringify(value))).toMatchObject({ status: 'invalid' })
    const wrongRevision = JSON.parse(backup.json)
    wrongRevision.baseRevisions['schema.mantra'] = 'a'.repeat(64)
    expect(await importRecoveryBackup(recording, JSON.stringify(wrongRevision))).toMatchObject({ status: 'invalid' })
  })

  it('enforces the UTF-8 budget before crypto and never truncates a source draft to fit', async () => {
    const state = edit(await initial())
    const tooLarge = '未'.repeat(Math.ceil(recoveryByteLimit / 3))
    const digest = vi.spyOn(crypto.subtle, 'digest')
    try {
      expect(await createRecoveryBackup(recording, state, { label: tooLarge })).toMatchObject({ status: 'invalid' })
      expect(await writeRecovery(recording, state, { label: tooLarge })).toMatchObject({ status: 'invalid' })
      expect(await importRecoveryBackup(recording, JSON.stringify({ label: tooLarge }))).toMatchObject({
        status: 'invalid',
      })
      expect(digest).not.toHaveBeenCalled()
      expect(localStorage.length).toBe(0)
      expect(state.documents['schema.mantra']).toContain('"Remaining"')
    } finally {
      digest.mockRestore()
    }
  })

  it('identifies unavailable import verification separately from malformed JSON', async () => {
    const backup = await createRecoveryBackup(recording, edit(await initial()), {})
    if (backup.status !== 'ready') throw new Error('Backup should be ready')
    const digest = vi.spyOn(crypto.subtle, 'digest').mockRejectedValue(new Error('Crypto unavailable'))
    try {
      expect(await importRecoveryBackup(recording, backup.json)).toMatchObject({ status: 'unavailable' })
      expect(await importRecoveryBackup(recording, '{bad')).toMatchObject({ status: 'invalid' })
    } finally {
      digest.mockRestore()
    }
  })
})
