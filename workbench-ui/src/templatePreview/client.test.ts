// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { LiveTemplatePreviewClient, TemplatePreviewError } from './client'
import {
  diagnosticSource,
  firstCase,
  formulaDiagnostic,
  templatePreview,
  templateRequest,
  templateSources,
} from './fixtures.test.helpers'

beforeEach(() => {
  const token = document.createElement('meta')
  token.name = 'mantra-session-token'
  token.content = 'test-preview-session'
  document.head.append(token)
})

afterEach(() => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function reply(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

it('captures opaque source handles and the full revision map, including dependencies not represented by editable DSL', async () => {
  const source = templateSources()
  const fetch = vi.fn().mockResolvedValue(reply(source))
  vi.stubGlobal('fetch', fetch)
  expect(await new LiveTemplatePreviewClient().sources(firstCase)).toEqual(source)
  expect(fetch.mock.calls[0][0]).toBe('/api/v1/cases/' + encodeURIComponent(firstCase) + '/template-sources')
  expect(source.data.baseRevisions['data/example.csv']).toBe('a'.repeat(64))
  expect(source.data.documents.some((document) => !document.editable)).toBe(true)
})

it('refuses to submit a draft without a live session token', async () => {
  document.querySelector('meta[name="mantra-session-token"]')?.remove()
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  await expect(new LiveTemplatePreviewClient().preview(firstCase, templateRequest())).rejects.toThrow(
    'Start the live Mantra workbench',
  )
  expect(fetch).not.toHaveBeenCalled()
})

it('sends exact source and input text with the session token and complete captured revisions', async () => {
  const request = templateRequest()
  const fetch = vi.fn().mockResolvedValue(reply(templatePreview(firstCase, request)))
  vi.stubGlobal('fetch', fetch)
  expect((await new LiveTemplatePreviewClient().preview(firstCase, request)).data.draftSequence).toBe(3)
  const options = fetch.mock.calls[0][1]
  expect(options.method).toBe('POST')
  expect(options.headers['X-Mantra-Token']).toBe('test-preview-session')
  expect(JSON.parse(options.body)).toEqual(request)
  expect(JSON.parse(options.body).inputs[0].text).toBe(' 1.234,56 ')
})

it('rejects replies for another case, draft sequence, graph revision or incomplete and extra source revisions', async () => {
  const request = templateRequest()
  const valid = templatePreview(firstCase, request)
  const missing = { ...request.baseRevisions }
  delete missing['data/example.csv']
  const mismatches = [
    { ...valid, revision: 'b'.repeat(64) },
    { ...valid, data: { ...valid.data, document: 'another.mantra' } },
    { ...valid, data: { ...valid.data, draftSequence: request.draftSequence + 1 } },
    { ...valid, data: { ...valid.data, baseRevisions: missing } },
    { ...valid, data: { ...valid.data, baseRevisions: { ...request.baseRevisions, unexpected: 'c'.repeat(64) } } },
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const mismatch of mismatches) {
    fetch.mockResolvedValueOnce(reply(mismatch))
    await expect(new LiveTemplatePreviewClient().preview(firstCase, request)).rejects.toThrow(
      'does not match this draft',
    )
  }
})

it('preserves real 422 diagnostic ranges and 409 current revisions without replacing them with guessed locations', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(reply({ error: { message: 'Invalid draft', diagnostics: [formulaDiagnostic] } }, 422))
    .mockResolvedValueOnce(reply({ error: { message: 'Changed sources', currentRevision: 'd'.repeat(64) } }, 409))
  vi.stubGlobal('fetch', fetch)
  const client = new LiveTemplatePreviewClient()
  const rejected = await client.preview(firstCase, templateRequest()).catch((failure: unknown) => failure)
  expect(rejected).toBeInstanceOf(TemplatePreviewError)
  expect(rejected).toMatchObject({
    status: 422,
    diagnostics: [formulaDiagnostic],
  })
  expect(diagnosticSource.slice(formulaDiagnostic.location!.startOffset!, formulaDiagnostic.location!.endOffset!)).toBe(
    'allocated-totl',
  )
  await expect(client.preview(firstCase, templateRequest())).rejects.toMatchObject({
    status: 409,
    currentRevision: 'd'.repeat(64),
  })
})

it('rejects unsupported envelopes and source snapshots with missing identities or corrupt revision hashes', async () => {
  const source = templateSources()
  const malformed = [
    { ...source, contract: 'unrecognized/1' },
    {
      ...source,
      data: { ...source.data, documents: [{ handle: 'opaque', text: '(source)' }] },
    },
    {
      ...source,
      data: { ...source.data, baseRevisions: { ...source.data.baseRevisions, 'data/example.csv': 'not-a-hash' } },
    },
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const value of malformed) {
    fetch.mockResolvedValueOnce(reply(value))
    await expect(new LiveTemplatePreviewClient().sources(firstCase)).rejects.toThrow()
  }
})

it('rejects successful preview bodies that would fail when rendering columns, row kinds or row flags', async () => {
  const request = templateRequest()
  const malformed = [
    { columns: [null] },
    { rows: [{ ...templatePreview(firstCase, request).data.paper.tables[0].rows[0], kind: null }] },
    { rows: [{ ...templatePreview(firstCase, request).data.paper.tables[0].rows[0], flags: 'not-an-array' }] },
  ]
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  for (const tableFields of malformed) {
    const valid = templatePreview(firstCase, request)
    fetch.mockResolvedValueOnce(
      reply({
        ...valid,
        data: {
          ...valid.data,
          paper: { ...valid.data.paper, tables: [{ ...valid.data.paper.tables[0], ...tableFields }] },
        },
      }),
    )
    await expect(new LiveTemplatePreviewClient().preview(firstCase, request)).rejects.toThrow()
  }
})
