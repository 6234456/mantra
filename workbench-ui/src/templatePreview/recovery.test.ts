import { expect, it } from 'vitest'
import type { Envelope } from '../types'
import type { TemplateSources } from './client'
import {
  createBackup,
  emptyRecoveryPending,
  matchBackup,
  parseBackup,
  recoveryByteLimit,
  recoveryFormat,
  restoreDocuments,
  type RecoveryDraft,
  type RecoveryDraftState,
} from './recovery'

function snapshot(): Envelope<TemplateSources> {
  return {
    contract: 'mantra.workbench/4',
    revision: 'a'.repeat(64),
    engine: { mantra: 'test', normein: '0.3.0' },
    data: {
      workspaceId: 'b'.repeat(64),
      document: 'examples/case.mantra',
      baseRevisions: {
        'schema.mantra': 'c'.repeat(64),
        'layout.mantra': 'd'.repeat(64),
        'case.mantra': 'e'.repeat(64),
      },
      documents: [
        {
          handle: '1'.repeat(64),
          document: 'schema.mantra',
          role: 'schema',
          sha256: 'c'.repeat(64),
          text: '; 中文😀\r\n(schema original)\r\n',
          editable: true,
          reason: null,
        },
        {
          handle: '2'.repeat(64),
          document: 'layout.mantra',
          role: 'layout',
          sha256: 'd'.repeat(64),
          text: '(layout original)\r\n',
          editable: true,
          reason: null,
        },
        {
          handle: '3'.repeat(64),
          document: 'case.mantra',
          role: 'case',
          sha256: 'e'.repeat(64),
          text: '(case untouched)',
          editable: false,
          reason: 'Read-only source',
        },
      ],
    },
  }
}

function state(source = snapshot()): RecoveryDraftState {
  return {
    documents: Object.fromEntries(source.data.documents.map((entry) => [entry.handle, entry.text])),
    inputs: {},
    pending: emptyRecoveryPending(),
  }
}

function draft(): RecoveryDraft {
  const result = createBackup(snapshot(), state(), '2026-10-25T10:11:12.123Z')
  if (result.status !== 'ready') throw new Error(result.reason)
  return result.draft
}

it('round-trips invalid source, exact Unicode/CRLF, empty and huge decimal raw inputs and unapplied fields', () => {
  const source = snapshot()
  const active = state(source)
  active.documents = { ...active.documents, ['1'.repeat(64)]: '; 😀注释\r\n(schema unclosed', ['2'.repeat(64)]: '' }
  active.inputs = {
    empty: '',
    zero: '0',
    booleanText: 'false',
    large: '9007199254740993.1234567890123456789',
    invalid: 'nine',
    whitespace: ' 1,25 ',
  }
  active.pending = {
    layoutSettings: [
      { op: 'set', property: 'precision', text: '-' },
      { op: 'unset', property: 'zero' },
    ],
    styleClass: {
      operation: { op: 'upsert', name: '中文未完成', style: { weight: null, tone: 'default', fill: 'none' } },
      creating: true,
      selection: '',
    },
  }
  const result = createBackup(source, active, '2026-10-25T10:11:12.123Z')
  expect(result.status).toBe('ready')
  if (result.status !== 'ready') return
  expect(result.bytes).toBe(new TextEncoder().encode(result.json).byteLength)
  expect(parseBackup(result.json)).toEqual({ status: 'ready', draft: result.draft })
  expect(result.draft.documents[0].text).toBe(active.documents['1'.repeat(64)])
  expect(result.draft.documents[1].text).toBe('')
  expect(Object.fromEntries(result.draft.inputs.map(({ node, text }) => [node, text]))).toEqual(active.inputs)
  expect(result.draft.pending).toEqual(active.pending)
  expect(restoreDocuments(result.draft, source)).toEqual(active.documents)
  active.pending.layoutSettings.length = 0
  expect(result.draft.pending.layoutSettings).toHaveLength(2)
})

it('contains no engine evidence, permissions, diagnostics or source history and refuses added authority fields', () => {
  const original = draft()
  expect(Object.keys(original)).toEqual([
    'format',
    'savedAt',
    'workspaceId',
    'caseId',
    'baseRevision',
    'baseRevisions',
    'documents',
    'inputs',
    'pending',
  ])
  for (const key of ['paper', 'run', 'explain', 'diagnostics', 'validity', 'history', 'sessionToken', 'permissions']) {
    expect(parseBackup(JSON.stringify({ ...original, [key]: {} })).status, key).toBe('invalid')
  }
  expect(parseBackup(JSON.stringify({ ...original, format: 'mantra.authoring-recovery/1' })).status).toBe('invalid')
})

it('protects a 70k invalid source paste and oversized preview inputs within the separate total recovery budget', () => {
  const active = state()
  active.documents = { ...active.documents, ['1'.repeat(64)]: '(unclosed ' + '文'.repeat(70_000) }
  active.inputs = { pasted: '9'.repeat(70_000) }
  active.pending.layoutSettings = [{ op: 'set', property: 'title', text: 'x'.repeat(70_000) }]
  const result = createBackup(snapshot(), active)
  expect(result.status).toBe('ready')
  if (result.status !== 'ready') return
  expect(result.draft.documents[0].text).toBe(active.documents['1'.repeat(64)])
  expect(result.draft.inputs[0].text).toHaveLength(70_000)
  expect(result.draft.pending.layoutSettings[0]).toEqual(active.pending.layoutSettings[0])
})

it('rejects total UTF-8 overflow without truncation or changing the current source and fields', () => {
  const active = state()
  const tooBig = '😀'.repeat(recoveryByteLimit / 3)
  active.documents = { ...active.documents, ['1'.repeat(64)]: tooBig }
  const result = createBackup(snapshot(), active)
  expect(result).toMatchObject({ status: 'invalid', reason: expect.stringContaining('2 MiB') })
  expect(active.documents['1'.repeat(64)]).toBe(tooBig)
  expect(parseBackup(' '.repeat(recoveryByteLimit + 1))).toMatchObject({
    status: 'invalid',
    reason: expect.stringContaining('2 MiB'),
  })
})

it('rejects duplicate and escaped-equivalent JSON keys without confusing quotes and braces inside source strings', () => {
  const original = draft()
  original.documents[0].text = '"format": "not a JSON key", { [ ] }\\path\\"quoted"'
  const json = JSON.stringify(original)
  expect(parseBackup(json).status).toBe('ready')
  const topDuplicate = json.replace('"format":', '"format":"ignored","format":')
  expect(parseBackup(topDuplicate)).toMatchObject({
    status: 'invalid',
    reason: expect.stringContaining('duplicate JSON keys'),
  })
  const escapedDuplicate = json.replace('"format":', '"\\u0066ormat":"ignored","format":')
  expect(parseBackup(escapedDuplicate)).toMatchObject({
    status: 'invalid',
    reason: expect.stringContaining('duplicate JSON keys'),
  })
  const nestedDuplicate = json.replace('"layoutSettings":[]', '"layoutSettings":[],"layoutSettings":[]')
  expect(parseBackup(nestedDuplicate)).toMatchObject({
    status: 'invalid',
    reason: expect.stringContaining('duplicate JSON keys'),
  })
})

it('rejects malformed JSON and excessive nesting before any recovery is accepted', () => {
  for (const raw of ['', '{', '{"format":', 'null', '[]', 'true']) expect(parseBackup(raw).status).toBe('invalid')
  const deep = '{"unknown":' + '['.repeat(16) + '0' + ']'.repeat(16) + '}'
  expect(parseBackup(deep)).toMatchObject({ status: 'invalid', reason: expect.stringContaining('nesting depth') })
})

it('rejects unknown fields, duplicate identities, invalid finite styles and inconsistent baseline descriptors', () => {
  const changes: Array<(value: RecoveryDraft) => void> = [
    (value) => {
      value.savedAt = '2026-10-25T10:11:12Z'
    },
    (value) => {
      value.workspaceId = 'workspace'
    },
    (value) => {
      value.baseRevisions = {}
    },
    (value) => {
      value.baseRevisions['schema.mantra'] = 'f'.repeat(64)
    },
    (value) => {
      value.documents[1].handle = value.documents[0].handle
    },
    (value) => {
      value.documents[1].document = value.documents[0].document
    },
    (value) => {
      value.documents[1].role = 'schema'
    },
    (value) => {
      value.documents = value.documents.filter((entry) => entry.role !== 'schema')
    },
    (value) => {
      value.inputs = [
        { node: 'same', text: '1' },
        { node: 'same', text: '2' },
      ]
    },
    (value) => {
      value.inputs = [{ node: ' ', text: '1' }]
    },
    (value) => {
      value.inputs = Array.from({ length: 101 }, (_, index) => ({ node: `field${index}`, text: '' }))
    },
    (value) => {
      value.pending.layoutSettings = [
        { op: 'set', property: 'precision', text: '' },
        { op: 'unset', property: 'precision' },
      ]
    },
    (value) => {
      Object.assign(value.pending, { inspectedProperties: [] })
    },
    (value) => {
      value.pending.layoutSettings = [{ op: 'set', property: 'unknown', text: '' }]
    },
    (value) => {
      value.pending.styleClass = {
        operation: { op: 'upsert', name: '', style: { weight: 'bold', tone: null, fill: null } },
        creating: true,
        selection: '',
      }
      Object.assign(value.pending.styleClass.operation, { style: { weight: 'heavy', tone: null, fill: null } })
    },
    (value) => {
      Object.assign(value.documents[0], { editable: true })
    },
    (value) => {
      Object.assign(value.inputs, { 0: { node: 'amount', text: 0 } })
    },
  ]
  for (const change of changes) {
    const value = draft()
    change(value)
    expect(parseBackup(JSON.stringify(value)).status, String(change)).toBe('invalid')
  }
})

it('matches the entire source graph without a false 128-entry cap and rejects removed or extra revisions', () => {
  const source = snapshot()
  for (let index = 0; index < 160; index++) source.data.baseRevisions[`linked/${index}.mantra`] = 'f'.repeat(64)
  const result = createBackup(source, state(source))
  expect(result.status).toBe('ready')
  if (result.status !== 'ready') return
  expect(matchBackup(result.draft, source)).toEqual({ compatible: true, foreignWorkspace: false })
  const missing = structuredClone(source)
  delete missing.data.baseRevisions['linked/159.mantra']
  expect(matchBackup(result.draft, missing).compatible).toBe(false)
  const extra = structuredClone(source)
  extra.data.baseRevisions['linked/new.mantra'] = 'f'.repeat(64)
  expect(matchBackup(result.draft, extra).compatible).toBe(false)
})

it('requires exact editable descriptors and original text while accepting harmless document ordering', () => {
  const original = draft()
  const reordered = snapshot()
  reordered.data.documents.reverse()
  expect(matchBackup(original, reordered).compatible).toBe(true)
  const edits: Array<(source: Envelope<TemplateSources>) => void> = [
    (source) => {
      source.data.documents[0].handle = 'f'.repeat(64)
    },
    (source) => {
      source.data.documents[0].document = 'renamed.mantra'
    },
    (source) => {
      source.data.documents[0].text += '; changed original'
    },
    (source) => {
      source.data.documents[0].sha256 = 'f'.repeat(64)
    },
    (source) => {
      source.data.documents[1].editable = false
    },
  ]
  for (const edit of edits) {
    const source = snapshot()
    edit(source)
    expect(matchBackup(original, source).compatible, String(edit)).toBe(false)
    expect(restoreDocuments(original, source)).toBeUndefined()
  }
})

it('allows explicit foreign-workspace import only when case, graph and all source descriptors still match', () => {
  const original = draft()
  const foreign = snapshot()
  foreign.data.workspaceId = 'f'.repeat(64)
  expect(matchBackup(original, foreign, 'local')).toMatchObject({ compatible: false, foreignWorkspace: true })
  expect(matchBackup(original, foreign, 'import')).toEqual({ compatible: true, foreignWorkspace: true })
  expect(restoreDocuments(original, foreign, 'import')).toEqual(state(foreign).documents)
  foreign.data.document = 'different-case.mantra'
  expect(matchBackup(original, foreign, 'import').compatible).toBe(false)
  foreign.data.document = original.caseId
  foreign.data.baseRevisions['case.mantra'] = 'f'.repeat(64)
  expect(matchBackup(original, foreign, 'import').compatible).toBe(false)
  expect(original.workspaceId).toBe('b'.repeat(64))
})

it('never silently exports a missing root draft, modified read-only text or unknown source handle', () => {
  const source = snapshot()
  const missing = state(source)
  delete (missing.documents as Record<string, string>)['1'.repeat(64)]
  expect(createBackup(source, missing).status).toBe('invalid')
  const readOnly = state(source)
  readOnly.documents = { ...readOnly.documents, ['3'.repeat(64)]: 'unexpected edit' }
  expect(createBackup(source, readOnly).status).toBe('invalid')
  expect(readOnly.documents['3'.repeat(64)]).toBe('unexpected edit')
  const unknown = state(source)
  unknown.documents = { ...unknown.documents, unknown: 'preserve this' }
  expect(createBackup(source, unknown).status).toBe('invalid')
})

it('supports schema-only cases and does not restore read-only documents from a backup', () => {
  const source = snapshot()
  source.data.documents = source.data.documents.filter((entry) => entry.role !== 'layout')
  delete source.data.baseRevisions['layout.mantra']
  const active = state(source)
  active.documents = { ...active.documents, ['1'.repeat(64)]: 'edited schema' }
  const result = createBackup(source, active)
  expect(result.status).toBe('ready')
  if (result.status !== 'ready') return
  expect(result.draft.documents).toHaveLength(1)
  expect(result.draft.format).toBe(recoveryFormat)
  expect(restoreDocuments(result.draft, source)?.['3'.repeat(64)]).toBe('(case untouched)')
})

it('requires recovery capability without changing a legacy source snapshot or its raw editing state', () => {
  const original = draft()
  for (const identity of [undefined, 'not-a-hash']) {
    const legacy = snapshot()
    if (identity === undefined) delete legacy.data.workspaceId
    else legacy.data.workspaceId = identity
    const active = state(legacy)
    active.inputs = { amount: 'nine' }
    active.pending.layoutSettings = [{ op: 'set', property: 'precision', text: '-' }]
    const before = structuredClone(active)
    const sourceBefore = structuredClone(legacy)
    expect(createBackup(legacy, active)).toMatchObject({
      status: 'invalid',
      reason: expect.stringContaining('identity is unavailable'),
    })
    for (const mode of ['local', 'import'] as const) {
      expect(matchBackup(original, legacy, mode)).toMatchObject({
        compatible: false,
        foreignWorkspace: false,
        reason: expect.stringContaining('Reload or update'),
      })
      expect(restoreDocuments(original, legacy, mode)).toBeUndefined()
    }
    expect(active).toEqual(before)
    expect(legacy).toEqual(sourceBefore)
    expect(original.workspaceId).toBe('b'.repeat(64))
  }
})
