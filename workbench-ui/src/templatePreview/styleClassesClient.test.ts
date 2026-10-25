// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Diagnostic, Envelope } from '../types'
import { TemplatePreviewError } from './client'
import { LiveStyleClassesClient } from './styleClassesClient'
import type { StyleClasses, StyleClassesRequest, StyleSpec } from './styleClassesClient'

const revision = 'a'.repeat(64)
const handle = 'b'.repeat(64)
const caseId = 'examples/样式.mantra'
const original =
  '(layout example/paper {:title "老😀" :style-preset [:utilities :working-paper]}\r\n' +
  '  ;; Keep the 📄 note and exact CRLF.\r\n' +
  '  (style-class :reviewed {:weight :bold})\r\n' +
  '  (style {:class :reviewed} {:use :reviewed}))\r\n'

beforeEach(() => {
  const token = document.createElement('meta')
  token.name = 'mantra-session-token'
  token.content = 'style-session'
  document.head.append(token)
})

afterEach(() => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  vi.unstubAllGlobals()
})

function style(fields: Partial<StyleSpec> = {}): StyleSpec {
  return { weight: null, tone: null, fill: null, ...fields }
}

function request(): StyleClassesRequest {
  return {
    baseRevision: revision,
    baseRevisions: { 'layout.mantra': 'c'.repeat(64), 'helpers.mantra': 'd'.repeat(64) },
    draftSequence: 12,
    handle,
    text: original,
    operations: [],
  }
}

function reference(kind: 'use' | 'selector', text: string) {
  const marker = kind === 'use' ? ':use :reviewed' : ':class :reviewed'
  const startOffset = text.indexOf(marker) + marker.lastIndexOf(':reviewed')
  return { kind, startOffset, endOffset: startOffset + ':reviewed'.length }
}

function response(sent = request()): Envelope<StyleClasses> {
  return {
    contract: 'mantra.workbench/4',
    revision: sent.baseRevision,
    engine: { mantra: '1', normein: '0.3.0' },
    data: {
      document: caseId,
      draftSequence: sent.draftSequence,
      baseRevisions: sent.baseRevisions,
      scope: 'layout-style-classes',
      layoutId: 'example/paper',
      stylePresets: ['utilities', 'working-paper'],
      usageScope: 'submitted-layout',
      source: { handle: sent.handle, document: 'layout.mantra', text: sent.text },
      classes: [
        {
          name: 'strong',
          presets: ['utilities'],
          local: null,
          inherited: style({ weight: 'bold' }),
          effective: style({ weight: 'bold' }),
          references: [],
          editable: true,
          reason: null,
          canRemove: false,
          removeReason: 'No local definition is declared.',
        },
        {
          name: 'note',
          presets: ['working-paper'],
          local: null,
          inherited: style({ tone: 'muted' }),
          effective: style({ tone: 'muted' }),
          references: [],
          editable: true,
          reason: null,
          canRemove: false,
          removeReason: 'No local definition is declared.',
        },
        {
          name: 'reviewed',
          presets: [],
          local: style({ weight: 'bold' }),
          inherited: style(),
          effective: style({ weight: 'bold' }),
          references: [reference('selector', sent.text), reference('use', sent.text)],
          editable: true,
          reason: null,
          canRemove: false,
          removeReason: 'The submitted layout still uses this class and has no selected preset fallback.',
        },
      ],
      patches: [],
    },
  }
}

function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

it('sends exact source, typed operations, full revisions, encoded case and session token', async () => {
  const sent = request()
  sent.operations = [{ op: 'upsert', name: 'reviewed', style: style({ weight: 'bold' }) }]
  const expected = response(sent)
  const fetch = vi.fn().mockResolvedValue(reply(expected))
  vi.stubGlobal('fetch', fetch)
  const abort = new AbortController()
  expect(await new LiveStyleClassesClient().classes(caseId, sent, abort.signal)).toEqual(expected)
  expect(fetch).toHaveBeenCalledOnce()
  const [path, options] = fetch.mock.calls[0]
  expect(path).toBe(`/api/v1/cases/${encodeURIComponent(caseId)}/template-style-classes`)
  expect(options.method).toBe('POST')
  expect(options.headers).toMatchObject({ 'Content-Type': 'application/json', 'X-Mantra-Token': 'style-session' })
  expect(options.signal).toBe(abort.signal)
  expect(JSON.parse(options.body)).toEqual(sent)
})

it('does not submit authored source without a live workbench session', async () => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  await expect(new LiveStyleClassesClient().classes(caseId, request())).rejects.toThrow('Start the live Mantra')
  expect(fetch).not.toHaveBeenCalled()
})

it('keeps an aborted transport request aborted without replacing it with an inspection result', async () => {
  const abort = new AbortController()
  abort.abort()
  const error = new DOMException('The request was aborted.', 'AbortError')
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(error))
  await expect(new LiveStyleClassesClient().classes(caseId, request(), abort.signal)).rejects.toBe(error)
})

it('keeps omitted properties separate from explicit normal default and none declarations', async () => {
  const sent = request()
  sent.text = original.replace(':weight :bold', ':weight :normal :tone :default :fill :none')
  const expected = response(sent)
  const reviewed = expected.data.classes.find((entry) => entry.name === 'reviewed')!
  reviewed.local = style({ weight: 'normal', tone: 'default', fill: 'none' })
  reviewed.effective = style({ weight: 'normal', tone: 'default', fill: 'none' })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  const actual = await new LiveStyleClassesClient().classes(caseId, sent)
  expect(actual.data.classes[0].local).toBeNull()
  expect(actual.data.classes[2].local).toEqual({ weight: 'normal', tone: 'default', fill: 'none' })
  expect(actual.data.classes[2].inherited).toEqual({ weight: null, tone: null, fill: null })
  expect(actual.data.usageScope).toBe('submitted-layout')
  expect(actual.data.classes[2].references).toEqual([reference('selector', sent.text), reference('use', sent.text)])
})

it('accepts a true no-op edit or an inspection without inventing source patches', async () => {
  const sent = request()
  const expected = response(sent)
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(reply(expected)))
  vi.stubGlobal('fetch', fetch)
  expect((await new LiveStyleClassesClient().classes(caseId, sent)).data.source.text).toBe(original)
  sent.operations = [{ op: 'upsert', name: 'reviewed', style: style({ weight: 'bold' }) }]
  expect((await new LiveStyleClassesClient().classes(caseId, sent)).data.patches).toEqual([])
})

it('retains non-simple selector tags as read-only entries instead of rejecting the whole catalogue', async () => {
  const sent = request()
  sent.text = original.replace(
    '))\r\n',
    ')\r\n  (style {:class FutureTag} {:tone :accent})\r\n  (style {:class :future/tag} {:fill :subtle}))\r\n',
  )
  const expected = response(sent)
  for (const name of ['FutureTag', 'future/tag']) {
    const token = name === 'FutureTag' ? name : `:${name}`
    const startOffset = sent.text.indexOf(token)
    expected.data.classes.push({
      name,
      presets: [],
      local: null,
      inherited: style(),
      effective: style(),
      references: [{ kind: 'selector', startOffset, endOffset: startOffset + token.length }],
      editable: false,
      reason: 'Only simple lower-case class names can be edited here.',
      canRemove: false,
      removeReason: 'No local definition is declared.',
    })
  }
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  const actual = await new LiveStyleClassesClient().classes(caseId, sent)
  expect(actual.data.classes.slice(-2).map(({ name, editable }) => ({ name, editable }))).toEqual([
    { name: 'FutureTag', editable: false },
    { name: 'future/tag', editable: false },
  ])
})

it('rejects a bare symbol token reported as a use reference even though selectors accept symbols', async () => {
  const sent = request()
  sent.text = original.replace(':class :reviewed', ':class reviewed')
  const expected = response(sent)
  const startOffset = sent.text.indexOf(':class reviewed') + ':class '.length
  expected.data.classes[2].references = [{ kind: 'use', startOffset, endOffset: startOffset + 'reviewed'.length }]
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  await expect(new LiveStyleClassesClient().classes(caseId, sent)).rejects.toThrow()
})

it('accepts sequential UTF-16 patches that preserve surrounding emoji comments and CRLF', async () => {
  const sent = request()
  sent.operations = [{ op: 'upsert', name: 'reviewed', style: style({ weight: 'normal', tone: 'accent' }) }]
  const start = original.indexOf(':weight :bold') + ':weight '.length
  const first = { startOffset: start, endOffset: start + ':bold'.length, text: ':normal', removedText: ':bold' }
  const afterWeight = original.replace(':weight :bold', ':weight :normal')
  const insertAt = afterWeight.indexOf(':weight :normal') + ':weight :normal'.length
  const second = { startOffset: insertAt, endOffset: insertAt, text: ' :tone :accent', removedText: '' }
  const after = afterWeight.slice(0, insertAt) + second.text + afterWeight.slice(insertAt)
  const expected = response({ ...sent, text: after })
  expected.data.patches = [first, second]
  expected.data.classes[2].local = style({ weight: 'normal', tone: 'accent' })
  expected.data.classes[2].effective = style({ weight: 'normal', tone: 'accent' })
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(expected)))
  const actual = await new LiveStyleClassesClient().classes(caseId, sent)
  expect(actual.data.source.text).toBe(after)
  let restored = actual.data.source.text
  for (const patch of [...actual.data.patches].reverse()) {
    restored =
      restored.slice(0, patch.startOffset) + patch.removedText + restored.slice(patch.startOffset + patch.text.length)
  }
  expect(restored).toBe(original)
})

it('rejects replies for a different draft case revision map layout handle or usage scope', async () => {
  const valid = response()
  const incomplete = { ...valid.data.baseRevisions }
  delete incomplete['helpers.mantra']
  const variants: Record<string, unknown>[] = [
    { ...valid, contract: 'unknown/1' },
    { ...valid, revision: 'e'.repeat(64) },
    { ...valid, engine: null },
    ...[
      { document: 'other.mantra' },
      { draftSequence: 13 },
      { draftSequence: 0.5 },
      { baseRevisions: incomplete },
      { baseRevisions: { ...valid.data.baseRevisions, extra: 'f'.repeat(64) } },
      { scope: 'schema' },
      { layoutId: '' },
      { usageScope: 'all-schema-documents' },
      { source: { ...valid.data.source, handle: 'e'.repeat(64) } },
      { source: { ...valid.data.source, document: 'outside.mantra' } },
      { source: { ...valid.data.source, text: 'x'.repeat(65_537) } },
    ].map((data) => ({ ...valid, data: { ...valid.data, ...data } })),
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const body of variants) {
    fetch.mockResolvedValueOnce(reply(body))
    await expect(new LiveStyleClassesClient().classes(caseId, request())).rejects.toThrow()
  }
})

it('rejects malformed finite declarations duplicate class names and unsupported preset identities', async () => {
  const valid = response()
  const first = valid.data.classes[0]
  const variants: Record<string, unknown>[] = [
    { stylePresets: ['utilities', 'utilities'] },
    { stylePresets: ['unknown'] },
    { classes: null },
    { classes: [null] },
    { classes: [...valid.data.classes, first] },
    ...[
      { name: ':strong' },
      { name: 'Strong' },
      { name: '' },
      { presets: ['working-paper', 'working-paper'] },
      { presets: ['unknown'] },
      { local: false },
      { local: { weight: null, tone: null } },
      { inherited: { weight: 'light', tone: null, fill: null } },
      { effective: { weight: 'normal', tone: null, fill: 'red' } },
      { effective: { weight: 'normal', tone: null, fill: null, color: 'red' } },
      { canRemove: 'true' },
      { removeReason: 0 },
      { editable: 'true' },
      { reason: 0 },
    ].map((fields) => ({ classes: [{ ...first, ...fields }, ...valid.data.classes.slice(1)] })),
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const data of variants) {
    fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, ...data } }))
    await expect(new LiveStyleClassesClient().classes(caseId, request())).rejects.toThrow()
  }
})

it('rejects references outside returned source or with invalid kinds and offsets', async () => {
  const valid = response()
  const reviewed = valid.data.classes[2]
  const use = reference('use', original)
  const variants = [
    null,
    [null],
    [{ ...use, kind: 'schema-tag' }],
    [{ ...use, startOffset: -1 }],
    [{ ...use, startOffset: use.startOffset + 0.5 }],
    [{ ...use, endOffset: original.length + 1 }],
    [{ ...use, startOffset: use.endOffset, endOffset: use.startOffset }],
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const references of variants) {
    const classes = [...valid.data.classes.slice(0, 2), { ...reviewed, references }]
    fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, classes } }))
    await expect(new LiveStyleClassesClient().classes(caseId, request())).rejects.toThrow()
  }
})

it('rejects forged or nonsequential patches and every patch returned for inspection', async () => {
  const sent = request()
  sent.operations = [{ op: 'remove', name: 'reviewed' }]
  const valid = response(sent)
  const start = original.indexOf(':weight :bold') + ':weight '.length
  const patch = { startOffset: start, endOffset: start + ':bold'.length, text: ':normal', removedText: ':bold' }
  const after = original.replace(':weight :bold', ':weight :normal')
  const variants = [
    { patches: null, source: { ...valid.data.source, text: after } },
    { patches: [patch], source: { ...valid.data.source, text: original } },
    { patches: [{ ...patch, removedText: ':wrong' }], source: { ...valid.data.source, text: after } },
    { patches: [{ ...patch, startOffset: -1 }], source: { ...valid.data.source, text: after } },
    { patches: [{ ...patch, endOffset: original.length + 1 }], source: { ...valid.data.source, text: after } },
    { patches: [patch, patch], source: { ...valid.data.source, text: after } },
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const data of variants) {
    fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, ...data } }))
    await expect(new LiveStyleClassesClient().classes(caseId, sent)).rejects.toThrow()
  }
  const noOpPatch = { startOffset: start, endOffset: start + ':bold'.length, text: ':bold', removedText: ':bold' }
  fetch.mockResolvedValueOnce(reply({ ...valid, data: { ...valid.data, patches: [noOpPatch] } }))
  await expect(new LiveStyleClassesClient().classes(caseId, request())).rejects.toThrow()
})

it('retains source diagnostics and current revision from rejected style operations', async () => {
  const diagnostic: Diagnostic = {
    severity: 'error',
    code: 'MANTRA-TEMPLATE-STYLE',
    message: 'A style rule still references this local class.',
    location: { document: 'layout.mantra', line: 4, column: 33, startOffset: 150, endOffset: 159 },
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
      .mockResolvedValueOnce(
        reply({ error: { message: 'Cannot remove referenced definition', diagnostics: [diagnostic] } }, 422),
      )
      .mockResolvedValueOnce(
        reply({ error: { message: 'Participating sources changed', currentRevision: 'f'.repeat(64) } }, 409),
      ),
  )
  const client = new LiveStyleClassesClient()
  const invalid = await client.classes(caseId, request()).catch((failure: unknown) => failure)
  expect(invalid).toBeInstanceOf(TemplatePreviewError)
  expect(invalid).toMatchObject({
    status: 422,
    message: 'Cannot remove referenced definition',
    diagnostics: [diagnostic],
  })
  await expect(client.classes(caseId, request())).rejects.toMatchObject({
    status: 409,
    currentRevision: 'f'.repeat(64),
  })
})

it('reports invalid JSON success bodies as malformed and keeps non-JSON HTTP failures typed', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response('not JSON', { status: 200 }))
      .mockResolvedValueOnce(new Response('unavailable', { status: 503 })),
  )
  const client = new LiveStyleClassesClient()
  await expect(client.classes(caseId, request())).rejects.toThrow()
  await expect(client.classes(caseId, request())).rejects.toMatchObject({ status: 503, diagnostics: [] })
})
