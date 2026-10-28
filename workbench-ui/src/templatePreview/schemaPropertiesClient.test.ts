// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Diagnostic, Envelope } from '../types'
import { TemplatePreviewError } from './client'
import { LiveSchemaPropertiesClient } from './schemaPropertiesClient'
import type { SchemaProperties, SchemaPropertiesRequest, SchemaPropertyOwner } from './schemaPropertiesClient'

const revision = 'a'.repeat(64)
const documentHandle = 'b'.repeat(64)
const textOwner = 'c'.repeat(64)
const classOwner = 'd'.repeat(64)
const caseId = 'examples/标签.mantra'
const original =
  '(schema example/demo {:version "1"}\r\n' +
  '  ;; Keep the 📄 comment and CRLF.\r\n' +
  '  (info answer "老😀" missing-binding {:class :custom})\r\n' +
  '  (include "fragment.mantra"))\r\n'

beforeEach(() => {
  const token = document.createElement('meta')
  token.name = 'mantra-session-token'
  token.content = 'schema-session'
  document.head.append(token)
})

afterEach(() => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  vi.unstubAllGlobals()
})

function request(): SchemaPropertiesRequest {
  return {
    baseRevision: revision,
    baseRevisions: { 'schema.mantra': 'e'.repeat(64), 'fragment.mantra': 'f'.repeat(64) },
    draftSequence: 9,
    handle: documentHandle,
    text: original,
    operations: [],
  }
}

function span(source: string, token: string) {
  const startOffset = source.indexOf(token)
  const prefix = source.slice(0, startOffset)
  return {
    line: prefix.split('\n').length,
    column: startOffset - prefix.lastIndexOf('\n'),
    startOffset,
    endOffset: startOffset + token.length,
  }
}

function owners(source: string, label = '老😀', classes = ['custom']): SchemaPropertyOwner[] {
  const startOffset = source.indexOf('(info')
  const declarationRange = {
    ...span(source, '(info'),
    endOffset: source.indexOf('})', startOffset) + 2,
  }
  const common = {
    document: 'schema.mantra',
    node: 'answer',
    form: 'info' as const,
    path: [0],
    declared: true,
    editable: true,
    reason: null,
    declarationRange,
  }
  return [
    {
      ...common,
      handle: textOwner,
      property: 'label',
      type: 'text',
      value: label,
      canUnset: false,
      range: span(source, JSON.stringify(label)),
    },
    {
      ...common,
      handle: classOwner,
      property: 'classes',
      type: 'classes',
      value: classes.map((kw) => ({ kw })),
      canUnset: true,
      range: span(source, source.includes('[:') ? `[${classes.map((name) => `:${name}`).join(' ')}]` : ':custom'),
    },
    {
      handle: '1'.repeat(64),
      document: 'fragment.mantra',
      node: 'included',
      form: 'total',
      path: [1],
      property: 'label',
      type: 'text',
      declared: true,
      value: 'Read only',
      editable: false,
      reason: 'Captured include is read-only.',
      canUnset: false,
      range: { line: 12, column: 8, startOffset: 600, endOffset: 611 },
      declarationRange: { line: 12, column: 1, startOffset: 580, endOffset: 620 },
    },
  ]
}

function response(sent = request()): Envelope<SchemaProperties> {
  return {
    contract: 'mantra.workbench/4',
    revision: sent.baseRevision,
    engine: { mantra: '1.0.0-rc.1', normein: '0.3.0' },
    data: {
      document: caseId,
      draftSequence: sent.draftSequence,
      baseRevisions: { ...sent.baseRevisions },
      scope: 'schema-properties',
      coverage: 'presentation-items',
      schemaId: 'example/demo',
      source: { handle: sent.handle, document: 'schema.mantra', text: sent.text },
      owners: owners(sent.text),
      patches: [],
    },
  }
}

function fetchResponse(body: unknown, status = 200) {
  const fetcher = vi.fn().mockResolvedValue({ ok: status < 400, status, statusText: '', json: async () => body })
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

it('correlates a source-only inspection and forwards the session token, encoded case and AbortSignal', async () => {
  const sent = request()
  const body = response(sent)
  const fetcher = fetchResponse(body)
  const controller = new AbortController()
  const result = await new LiveSchemaPropertiesClient().properties(caseId, sent, controller.signal)
  expect(result).toEqual(body)
  expect(result.data.owners[2].range!.endOffset).toBeGreaterThan(result.data.source.text.length)
  expect(fetcher).toHaveBeenCalledWith(`/api/v1/cases/${encodeURIComponent(caseId)}/template-schema-properties`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': 'schema-session' },
    body: JSON.stringify(sent),
    signal: controller.signal,
  })
  expect(result.data).not.toHaveProperty('paper')
})

it('accepts exact sequential UTF-16 patches after a label shifts subsequent source offsets', async () => {
  const sent = request()
  sent.operations = [
    { op: 'setText', owner: textOwner, text: '新😀文字' },
    { op: 'setClasses', owner: classOwner, classes: ['custom', 'reviewed'] },
  ]
  const label = span(original, '"老😀"')
  const afterLabel = original.slice(0, label.startOffset) + '"新😀文字"' + original.slice(label.endOffset)
  const classToken = span(afterLabel, ':custom')
  const afterClass =
    afterLabel.slice(0, classToken.startOffset) + '[:custom :reviewed]' + afterLabel.slice(classToken.endOffset)
  const body = response(sent)
  body.data.source.text = afterClass
  body.data.owners = owners(afterClass, '新😀文字', ['custom', 'reviewed'])
  body.data.owners[0].handle = '2'.repeat(64)
  body.data.owners[1].handle = '3'.repeat(64)
  body.data.owners[2].handle = '4'.repeat(64)
  body.data.patches = [
    { startOffset: label.startOffset, endOffset: label.endOffset, text: '"新😀文字"', removedText: '"老😀"' },
    {
      startOffset: classToken.startOffset,
      endOffset: classToken.endOffset,
      text: '[:custom :reviewed]',
      removedText: ':custom',
    },
  ]
  fetchResponse(body)
  expect((await new LiveSchemaPropertiesClient().properties(caseId, sent)).data.source.text).toBe(afterClass)
  expect(sent.text).toBe(original)
  expect(afterClass).toContain('📄 comment and CRLF.\r\n')
})

it('takes an immutable request snapshot before awaiting a response', async () => {
  const sent = request()
  sent.operations = [{ op: 'setClasses', owner: classOwner, classes: ['custom'] }]
  const body = response(sent)
  let resolve!: (value: unknown) => void
  const fetcher = vi.fn().mockImplementation(() => new Promise((done) => (resolve = done)))
  vi.stubGlobal('fetch', fetcher)
  const pending = new LiveSchemaPropertiesClient().properties(caseId, sent)
  sent.text = 'newer source'
  sent.baseRevisions['schema.mantra'] = '9'.repeat(64)
  sent.draftSequence++
  if (sent.operations[0].op === 'setClasses') sent.operations[0].classes.push('newer')
  resolve({ ok: true, json: async () => body })
  expect(await pending).toEqual(body)
  const payload = JSON.parse(fetcher.mock.calls[0][1].body)
  expect(payload.text).toBe(original)
  expect(payload.draftSequence).toBe(9)
  expect(payload.operations[0].classes).toEqual(['custom'])
})

it('rejects a reply rebound to a mutated request instead of the originally submitted draft', async () => {
  const sent = request()
  let resolve!: (value: unknown) => void
  vi.stubGlobal(
    'fetch',
    vi.fn(() => new Promise((done) => (resolve = done))),
  )
  const pending = new LiveSchemaPropertiesClient().properties(caseId, sent)
  sent.draftSequence++
  const rebound = response(sent)
  resolve({ ok: true, json: async () => rebound })
  await expect(pending).rejects.toThrow('does not match this draft')
})

it('rejects stale revisions, wrong case, source handle, sequence and extra response fields', async () => {
  const mutations: Array<(body: Envelope<SchemaProperties>) => void> = [
    (body) => (body.revision = '9'.repeat(64)),
    (body) => (body.data.baseRevisions['fragment.mantra'] = '9'.repeat(64)),
    (body) => delete body.data.baseRevisions['fragment.mantra'],
    (body) => (body.data.document = 'another.mantra'),
    (body) => (body.data.source.handle = '9'.repeat(64)),
    (body) => body.data.draftSequence++,
    (body) => Object.assign(body.data, { saved: true }),
  ]
  for (const mutate of mutations) {
    const body = response()
    mutate(body)
    fetchResponse(body)
    await expect(new LiveSchemaPropertiesClient().properties(caseId, request())).rejects.toThrow(
      'does not match this draft',
    )
  }
})

it('rejects malformed owner identities, root ranges and include permissions', async () => {
  const mutations: Array<(body: Envelope<SchemaProperties>) => void> = [
    (body) => body.data.owners.push({ ...body.data.owners[0] }),
    (body) => (body.data.owners[1].path = [-1]),
    (body) => (body.data.owners[0].range!.endOffset = original.length + 1),
    (body) => (body.data.owners[0].declarationRange.endOffset = original.length + 1),
    (body) => (body.data.owners[0].range!.startOffset = 0),
    (body) => (body.data.owners[0].canUnset = true),
    (body) => (body.data.owners[2].editable = true),
    (body) => (body.data.owners[2].reason = null),
    (body) => (body.data.owners[2].document = 'uncaptured.mantra'),
    (body) => (body.data.owners[0].node = null),
    (body) => (body.data.owners[0].type = 'classes'),
    (body) => (body.data.owners[0].declared = false),
  ]
  for (const mutate of mutations) {
    const body = response()
    mutate(body)
    fetchResponse(body)
    await expect(new LiveSchemaPropertiesClient().properties(caseId, request())).rejects.toThrow(
      'does not match this draft',
    )
  }
})

it('keeps absent and explicitly empty class declarations distinct and accepts existing lists over the set limit', async () => {
  const absentRequest = { ...request(), text: original.replace(':class :custom', '') }
  const absent = response(absentRequest)
  Object.assign(absent.data.owners[1], { declared: false, value: null, range: null, canUnset: false })
  fetchResponse(absent)
  expect((await new LiveSchemaPropertiesClient().properties(caseId, absentRequest)).data.owners[1].declared).toBe(false)
  const explicitRequest = { ...request(), text: original.replace(':custom', '[]') }
  const explicit = response(explicitRequest)
  explicit.data.owners[1].value = []
  explicit.data.owners[1].range = span(explicitRequest.text, '[]')
  fetchResponse(explicit)
  expect((await new LiveSchemaPropertiesClient().properties(caseId, explicitRequest)).data.owners[1].value).toEqual([])
  const names = Array.from({ length: 70 }, (_, index) => `class-${index}`)
  const literal = `[${names.map((name) => `:${name}`).join(' ')}]`
  const largeRequest = { ...request(), text: original.replace(':custom', literal) }
  const large = response(largeRequest)
  large.data.owners = owners(largeRequest.text, '老😀', names)
  fetchResponse(large)
  expect((await new LiveSchemaPropertiesClient().properties(caseId, largeRequest)).data.owners[1].value).toHaveLength(
    70,
  )
})

it('rejects wrong exact class value encodings and duplicate logical names', async () => {
  const malformed: unknown[] = [
    [{ kw: 'custom' }, { kw: 'custom' }],
    [{ kw: 'Invalid' }],
    ['custom'],
    { kw: 'custom' },
    null,
  ]
  for (const value of malformed) {
    const body = response()
    Object.assign(body.data.owners[1], { value })
    fetchResponse(body)
    await expect(new LiveSchemaPropertiesClient().properties(caseId, request())).rejects.toThrow(
      'does not match this draft',
    )
  }
})

it('rejects patches with wrong removed text, fractional offsets, omitted fields or mismatched final source', async () => {
  const sent = request()
  sent.operations = [{ op: 'setText', owner: textOwner, text: '新' }]
  const token = span(original, '"老😀"')
  const valid = { startOffset: token.startOffset, endOffset: token.endOffset, text: '"新"', removedText: '"老😀"' }
  const patches: unknown[] = [
    [{ ...valid, removedText: '"not the source"' }],
    [{ ...valid, startOffset: token.startOffset + 0.5 }],
    [{ startOffset: token.startOffset, endOffset: token.endOffset, text: '"新"' }],
    [{ ...valid, endOffset: original.length + 1 }],
    [valid],
  ]
  for (const value of patches) {
    const body = response(sent)
    Object.assign(body.data, { patches: value })
    fetchResponse(body)
    await expect(new LiveSchemaPropertiesClient().properties(caseId, sent)).rejects.toThrow('does not match this draft')
  }
})

it('does not accept an inspection response that changes source even if its patches are self-consistent', async () => {
  const body = response()
  body.data.source.text += '\r\n'
  body.data.patches = [{ startOffset: original.length, endOffset: original.length, text: '\r\n', removedText: '' }]
  fetchResponse(body)
  await expect(new LiveSchemaPropertiesClient().properties(caseId, request())).rejects.toThrow(
    'does not match this draft',
  )
})

it('preserves typed HTTP errors, source diagnostics and current revision', async () => {
  const diagnostic: Diagnostic = {
    code: 'MANTRA-TEMPLATE-SCHEMA-PROPERTY',
    message: 'Invalid source',
    severity: 'error',
    location: null,
    address: null,
    related: [],
    category: 'structural',
    rowIndex: null,
    column: null,
    caseRevision: null,
  }
  fetchResponse(
    { error: { message: 'Source changed', currentRevision: '9'.repeat(64), diagnostics: [diagnostic] } },
    409,
  )
  const failure = await new LiveSchemaPropertiesClient().properties(caseId, request()).catch((error: unknown) => error)
  expect(failure).toBeInstanceOf(TemplatePreviewError)
  expect(failure).toMatchObject({
    status: 409,
    message: 'Source changed',
    currentRevision: '9'.repeat(64),
    diagnostics: [diagnostic],
  })
})

it('requires the live session and propagates cancellation without converting it into a server error', async () => {
  const controller = new AbortController()
  const aborted = new DOMException('Aborted', 'AbortError')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(aborted))
  await expect(new LiveSchemaPropertiesClient().properties(caseId, request(), controller.signal)).rejects.toBe(aborted)
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  await expect(new LiveSchemaPropertiesClient().properties(caseId, request())).rejects.toThrow('Start the live Mantra')
  expect(fetcher).not.toHaveBeenCalled()
})
