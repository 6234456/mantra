import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applySourcePatches, authoringReducer, createAuthoringState } from './model'
import type { AuthoringState, SourceTransaction } from './model'
import { clearRecovery, readRecovery, writeRecovery } from './recovery'
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
      await writeRecovery(recording, state, { label: 'Newest input' })
      releaseFirst()
      await older
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
    await pending
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
    await expect(writeRecovery(recording, edit(await initial()), {})).resolves.toBeUndefined()
    await expect(readRecovery(recording)).resolves.toBeUndefined()
    expect(() => clearRecovery(recording)).not.toThrow()
  })
})
