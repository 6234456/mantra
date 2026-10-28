// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Diagnostic, Envelope } from '../types'
import { TemplatePreviewError } from './client'
import { LiveLayoutSettingsClient } from './layoutSettingsClient'
import type { LayoutProperty, LayoutSettings, LayoutSettingsRequest } from './layoutSettingsClient'

const revision = 'a'.repeat(64)
const handle = 'b'.repeat(64)
const caseId = 'examples/案例.mantra'
const original =
  '(layout example/paper\r\n  {:title "老😀" :precision 2 :grouping false :zero "" :custom :keep})\r\n; keep\r\n'

beforeEach(() => {
  const token = document.createElement('meta')
  token.name = 'mantra-session-token'
  token.content = 'layout-session'
  document.head.append(token)
})

afterEach(() => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  vi.unstubAllGlobals()
})

function request(): LayoutSettingsRequest {
  return {
    baseRevision: revision,
    baseRevisions: { 'layout.mantra': 'c'.repeat(64), 'helpers.mantra': 'd'.repeat(64) },
    draftSequence: 12,
    handle,
    text: original,
    operations: [],
  }
}

function property(fields: Partial<LayoutProperty> = {}): LayoutProperty {
  return {
    id: 'precision',
    label: 'Precision',
    group: 'numbers',
    type: 'integer',
    options: [],
    minimum: 0,
    maximum: 18,
    declared: true,
    explicitValue: { n: '2' },
    inheritedValue: { n: '2' },
    effectiveValue: { n: '2' },
    editable: true,
    reason: null,
    canUnset: true,
    ...fields,
  }
}

function response(sent = request()): Envelope<LayoutSettings> {
  return {
    contract: 'mantra.workbench/4',
    revision: sent.baseRevision,
    engine: { mantra: '1', normein: '0.3.0' },
    data: {
      document: caseId,
      draftSequence: sent.draftSequence,
      baseRevisions: sent.baseRevisions,
      scope: 'layout',
      layoutId: 'example/paper',
      preset: 'de-staffel-4',
      source: { handle: sent.handle, document: 'layout.mantra', text: sent.text },
      properties: [
        property({
          id: 'title',
          label: 'Title',
          group: 'report',
          type: 'text',
          minimum: null,
          maximum: null,
          explicitValue: '老😀',
          inheritedValue: null,
          effectiveValue: '老😀',
        }),
        property({
          id: 'subtitle',
          label: 'Subtitle',
          group: 'report',
          type: 'text',
          minimum: null,
          maximum: null,
          declared: false,
          explicitValue: null,
          inheritedValue: null,
          effectiveValue: null,
          canUnset: false,
        }),
        property({
          id: 'locale',
          label: 'Locale',
          type: 'text',
          minimum: null,
          maximum: null,
          declared: false,
          explicitValue: null,
          inheritedValue: 'de-DE',
          effectiveValue: 'de-DE',
          canUnset: false,
        }),
        property({
          id: 'language',
          label: 'Language',
          group: 'report',
          type: 'enum',
          minimum: null,
          maximum: null,
          options: ['de', 'en'],
          declared: false,
          explicitValue: null,
          inheritedValue: { kw: 'de' },
          effectiveValue: { kw: 'de' },
          canUnset: false,
        }),
        property(),
        property({
          id: 'percent-precision',
          label: 'Percent precision',
          declared: false,
          explicitValue: null,
          canUnset: false,
        }),
        property({
          id: 'negative',
          label: 'Negative numbers',
          type: 'enum',
          minimum: null,
          maximum: null,
          options: ['minus', 'parentheses'],
          declared: false,
          explicitValue: null,
          inheritedValue: { kw: 'minus' },
          effectiveValue: { kw: 'minus' },
          canUnset: false,
        }),
        property({
          id: 'zero',
          label: 'Zero text',
          type: 'text',
          minimum: null,
          maximum: null,
          explicitValue: '',
          inheritedValue: '–',
          effectiveValue: '',
        }),
        ...(['grouping', 'hide-zero', 'show-inactive', 'expand-members', 'signed'] as const).map((id) =>
          property({
            id,
            label: id,
            group: 'visibility',
            type: 'boolean',
            minimum: null,
            maximum: null,
            declared: id === 'grouping',
            explicitValue: id === 'grouping' ? false : null,
            inheritedValue: id === 'grouping',
            effectiveValue: false,
            canUnset: id === 'grouping',
          }),
        ),
        property({
          id: 'row-numbers',
          label: 'Row numbers',
          group: 'visibility',
          type: 'enum',
          options: ['global', 'table'],
          minimum: null,
          maximum: null,
          declared: false,
          explicitValue: null,
          inheritedValue: null,
          effectiveValue: null,
          canUnset: false,
        }),
      ],
      patches: [],
    },
  }
}

function replaceProperties(envelope: Envelope<LayoutSettings>, replacements: LayoutProperty[]) {
  envelope.data.properties = envelope.data.properties.map(
    (current) => replacements.find((next) => next.id === current.id) ?? current,
  )
}

function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

it('sends the exact UTF-16 source, operations, captured revisions and session token to the file-workspace endpoint', async () => {
  const sent = request()
  const expected = response(sent)
  const fetch = vi.fn().mockResolvedValue(reply(expected))
  vi.stubGlobal('fetch', fetch)
  const abort = new AbortController()
  expect(await new LiveLayoutSettingsClient().settings(caseId, sent, abort.signal)).toEqual(expected)
  expect(fetch).toHaveBeenCalledOnce()
  const [path, options] = fetch.mock.calls[0]
  expect(path).toBe(`/api/v1/cases/${encodeURIComponent(caseId)}/template-layout-settings`)
  expect(options.method).toBe('POST')
  expect(options.headers['X-Mantra-Token']).toBe('layout-session')
  expect(options.signal).toBe(abort.signal)
  expect(JSON.parse(options.body)).toEqual(sent)
  expect(JSON.parse(options.body).text).toBe(original)
})

it('refuses to send layout source without a live session', async () => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  await expect(new LiveLayoutSettingsClient().settings(caseId, request())).rejects.toThrow(
    'Start the live Mantra workbench',
  )
  expect(fetch).not.toHaveBeenCalled()
})

it('accepts sequential UTF-16 patches only when they preserve untouched Unicode, comments and CRLF bytes', async () => {
  const sent = request()
  sent.operations = [
    { op: 'set', property: 'title', text: '新😀标题' },
    { op: 'set', property: 'precision', text: '0' },
  ]
  const expected = response(sent)
  const titleStart = original.indexOf('"老😀"')
  const first = {
    startOffset: titleStart,
    endOffset: titleStart + '"老😀"'.length,
    text: '"新😀标题"',
    removedText: '"老😀"',
  }
  const afterTitle = original.replace('"老😀"', '"新😀标题"')
  const precisionStart = afterTitle.indexOf(':precision 2') + ':precision '.length
  const second = { startOffset: precisionStart, endOffset: precisionStart + 1, text: '0', removedText: '2' }
  expected.data.patches = [first, second]
  expected.data.source.text = afterTitle.replace(':precision 2', ':precision 0')
  replaceProperties(expected, [property({ explicitValue: { n: '0' }, effectiveValue: { n: '0' } })])
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  const actual = await new LiveLayoutSettingsClient().settings(caseId, sent)
  expect(actual.data.source.text).toBe(
    '(layout example/paper\r\n  {:title "新😀标题" :precision 0 :grouping false :zero "" :custom :keep})\r\n; keep\r\n',
  )
  expect(actual.data.patches).toEqual([first, second])
})

it('preserves explicit zero, false, empty strings and null independently from inheritance and edit permissions', async () => {
  const expected = response()
  replaceProperties(expected, [
    property({ explicitValue: { n: '0' }, effectiveValue: { n: '0' } }),
    property({
      id: 'grouping',
      type: 'boolean',
      minimum: null,
      maximum: null,
      explicitValue: false,
      effectiveValue: false,
      inheritedValue: true,
    }),
    property({
      id: 'zero',
      type: 'text',
      minimum: null,
      maximum: null,
      explicitValue: '',
      effectiveValue: '',
      inheritedValue: '–',
    }),
    property({
      id: 'subtitle',
      type: 'text',
      minimum: null,
      maximum: null,
      declared: true,
      explicitValue: null,
      inheritedValue: null,
      effectiveValue: null,
      editable: false,
      reason: 'A literal string is required for editing; unset restores inheritance.',
      canUnset: true,
    }),
    property({
      id: 'row-numbers',
      type: 'enum',
      options: ['global', 'table'],
      minimum: null,
      maximum: null,
      declared: false,
      explicitValue: null,
      inheritedValue: null,
      effectiveValue: null,
      canUnset: false,
    }),
  ])
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  expect((await new LiveLayoutSettingsClient().settings(caseId, request())).data.properties).toEqual(
    expected.data.properties,
  )
})

it('keeps source text unchanged for inspect and accepts a true no-op edit without inventing a patch', async () => {
  const sent = request()
  sent.operations = [{ op: 'set', property: 'precision', text: '2' }]
  const expected = response(sent)
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  expect((await new LiveLayoutSettingsClient().settings(caseId, sent)).data.source.text).toBe(original)
  expect(expected.data.patches).toEqual([])
})

it('rejects replies for a different case, sequence, source handle, full baseline graph or scope', async () => {
  const valid = response()
  const missing = { ...valid.data.baseRevisions }
  delete missing['helpers.mantra']
  const malformed = [
    { ...valid, contract: 'unknown/1' },
    { ...valid, revision: 'e'.repeat(64) },
    { ...valid, engine: null },
    ...[
      { document: 'another-case.mantra' },
      { draftSequence: 13 },
      { baseRevisions: missing },
      { baseRevisions: { ...valid.data.baseRevisions, extra: 'e'.repeat(64) } },
      { scope: 'schema' },
      { layoutId: null },
      { preset: null },
      { source: { ...valid.data.source, handle: 'e'.repeat(64) } },
      { source: { ...valid.data.source, document: 'outside.mantra' } },
    ].map((data) => ({ ...valid, data: { ...valid.data, ...data } })),
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const body of malformed) {
    fetch.mockResolvedValueOnce(reply(body))
    await expect(new LiveLayoutSettingsClient().settings(caseId, request())).rejects.toThrow(
      'does not match this draft',
    )
  }
})

it('rejects malformed or ambiguous typed catalogues instead of normalizing their values', async () => {
  const valid = response()
  const first = valid.data.properties[0]
  const invalidProperties = [
    null,
    [null],
    [...valid.data.properties.slice(0, -1), first],
    ...[
      { id: '' },
      { group: 'unknown' },
      { type: 'number' },
      { options: [null] },
      { minimum: 0.5 },
      { minimum: 10, maximum: 2 },
      { declared: null },
      { explicitValue: 0 },
      { inheritedValue: { n: 2 } },
      { effectiveValue: { map: [[null]] } },
      { editable: null },
      { reason: {} },
      { canUnset: null },
    ].map((fields) => [{ ...first, ...fields }, ...valid.data.properties.slice(1)]),
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const properties of invalidProperties) {
    fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, properties } }))
    await expect(new LiveLayoutSettingsClient().settings(caseId, request())).rejects.toThrow()
  }
})

it('rejects forged, out-of-bounds or nonsequential patches and any patch returned for a read-only inspection', async () => {
  const sent = request()
  sent.operations = [{ op: 'set', property: 'precision', text: '0' }]
  const valid = response(sent)
  const start = original.indexOf(':precision 2') + ':precision '.length
  const patch = { startOffset: start, endOffset: start + 1, text: '0', removedText: '2' }
  const after = original.replace(':precision 2', ':precision 0')
  const variants = [
    { patches: null, source: { ...valid.data.source, text: after } },
    { patches: [patch], source: { ...valid.data.source, text: original } },
    { patches: [{ ...patch, startOffset: -1 }], source: { ...valid.data.source, text: after } },
    { patches: [{ ...patch, startOffset: start + 0.5 }], source: { ...valid.data.source, text: after } },
    { patches: [{ ...patch, endOffset: original.length + 1 }], source: { ...valid.data.source, text: after } },
    { patches: [{ ...patch, removedText: 'not the captured source' }], source: { ...valid.data.source, text: after } },
    { patches: [patch, patch], source: { ...valid.data.source, text: after } },
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const data of variants) {
    fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, ...data } }))
    await expect(new LiveLayoutSettingsClient().settings(caseId, sent)).rejects.toThrow()
  }
  fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, patches: [{ ...patch, text: '2' }] } }))
  await expect(new LiveLayoutSettingsClient().settings(caseId, request())).rejects.toThrow()
})

it('retains 422 diagnostic locations and 409 revision information for the panel recovery flow', async () => {
  const diagnostic: Diagnostic = {
    severity: 'error',
    code: 'MANTRA-LAYOUT-SETTING',
    message: 'Duplicate root metadata key',
    location: { document: 'layout.mantra', line: 2, column: 4, startOffset: 25, endOffset: 35 },
    address: null,
    related: [],
    category: 'structural',
    rowIndex: null,
    column: null,
    caseRevision: null,
  }
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(reply({ error: { message: 'Invalid layout', diagnostics: [diagnostic] } }, 422))
      .mockResolvedValueOnce(
        reply({ error: { message: 'Participating sources changed', currentRevision: 'f'.repeat(64) } }, 409),
      ),
  )
  const client = new LiveLayoutSettingsClient()
  const invalid = await client.settings(caseId, request()).catch((failure: unknown) => failure)
  expect(invalid).toBeInstanceOf(TemplatePreviewError)
  expect(invalid).toMatchObject({ status: 422, diagnostics: [diagnostic] })
  await expect(client.settings(caseId, request())).rejects.toMatchObject({
    status: 409,
    currentRevision: 'f'.repeat(64),
  })
})
