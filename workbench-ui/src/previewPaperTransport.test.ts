// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FixtureData, LiveData, WorkbenchRequestError, type WorkbenchData } from './data'
import { PackageData } from './packages/PackageData'
import type { EditOperation, FormulaOperation } from './types'

const baseRevision = 'a'.repeat(64)
const proposedRevision = 'b'.repeat(64)
const caseId = 'demo/cases/sample case.mantra'
const draftSequence = 7
const decimalText = '9007199254740993.123456789012345678901234567890'
const operations: Array<EditOperation | FormulaOperation> = [
  { op: 'setInput', address: { case: null, node: 'amount' }, text: decimalText },
  {
    op: 'updateRow',
    table: 'items',
    index: 0,
    row: { map: [[{ kw: 'amount' }, { n: decimalText }]] },
  },
  { op: 'bindFormula', id: 'answer', formula: '(+ :amount 0.000000000000000000000000000001)' },
]

function candidate(sequence = draftSequence, revision = baseRevision) {
  return {
    contract: 'mantra.workbench/4',
    revision,
    engine: { mantra: 'test', normein: 'test' },
    data: {
      document: 'case/sample',
      preview: true,
      draftSequence: sequence,
      proposedRevision,
      succeeded: true,
      validationPassed: true,
      diagnostics: [],
      run: {
        succeeded: true,
        validationPassed: true,
        members: {},
        values: {},
        diagnostics: [],
        caseGraph: null,
        usage: null,
        failure: null,
      },
      difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] },
      paper: { title: 'Candidate', tables: [], audit: [], browsing: { includeZero: true, hideZero: true } },
    },
  }
}

function response(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeEach(() => {
  document.head.innerHTML = '<meta name="mantra-session-token" content="test-session-token">'
})
afterEach(() => {
  vi.unstubAllGlobals()
  document.head.innerHTML = ''
})

describe('candidate Paper transport', () => {
  it('posts exact operations, draft identity and visibility with the session token and abort signal', async () => {
    const expected = candidate()
    const fetch = vi.fn().mockResolvedValue(response(expected))
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const result = await new LiveData().previewPaper!(
      caseId,
      baseRevision,
      operations,
      { draftSequence, panel: 'main panel', includeZero: true },
      controller.signal,
    )

    expect(result).toEqual(expected)
    expect(fetch).toHaveBeenCalledOnce()
    const [url, request] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/v1/cases/demo%2Fcases%2Fsample%20case.mantra/preview-paper')
    expect(request.method).toBe('POST')
    expect(request.headers).toEqual({ 'Content-Type': 'application/json', 'X-Mantra-Token': 'test-session-token' })
    expect(request.signal).toBe(controller.signal)
    expect(JSON.parse(request.body as string)).toEqual({
      baseRevision,
      operations,
      draftSequence,
      panel: 'main panel',
      includeZero: true,
    })
    expect(request.body).toContain(`"n":"${decimalText}"`)
    expect(request.body).toContain(`"text":"${decimalText}"`)
  })

  it.each([0, Number.MAX_SAFE_INTEGER])('accepts safe draft sequence %s and omits unspecified options', async (seq) => {
    const fetch = vi.fn().mockResolvedValue(response(candidate(seq)))
    vi.stubGlobal('fetch', fetch)
    await new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence: seq })
    const [, request] = fetch.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(request.body as string)).toEqual({ baseRevision, operations, draftSequence: seq })
    expect(request).not.toHaveProperty('signal')
  })

  it('keeps an explicit false includeZero value', async () => {
    const fetch = vi.fn().mockResolvedValue(response(candidate()))
    vi.stubGlobal('fetch', fetch)
    await new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence, includeZero: false })
    expect(JSON.parse(fetch.mock.calls[0][1].body).includeZero).toBe(false)
  })

  it.each([-1, 0.5, Number.MAX_SAFE_INTEGER + 1, NaN, Infinity, -Infinity])(
    'rejects invalid draft sequence %s before fetching',
    async (seq) => {
      const fetch = vi.fn()
      vi.stubGlobal('fetch', fetch)
      await expect(
        new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence: seq }),
      ).rejects.toBeInstanceOf(RangeError)
      expect(fetch).not.toHaveBeenCalled()
    },
  )

  it('requires a server session before sending a preview', async () => {
    document.head.innerHTML = ''
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await expect(new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })).rejects.toThrow(
      'server session',
    )
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each([
    ['draft', candidate(draftSequence + 1)],
    ['baseline', candidate(draftSequence, 'c'.repeat(64))],
  ])('rejects a response belonging to another %s', async (_identity, payload) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(payload)))
    await expect(new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })).rejects.toThrow(
      'does not match the requested draft and baseline',
    )
  })

  it('rejects an unsupported envelope contract', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...candidate(), contract: 'mantra.workbench/3' })))
    await expect(new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })).rejects.toThrow(
      'Unsupported workbench contract',
    )
  })

  it('preserves calculation and business-validation flags without substituting a saved Paper', async () => {
    const expected = candidate()
    expected.data.succeeded = false
    expected.data.validationPassed = false
    expected.data.run.succeeded = false
    expected.data.run.validationPassed = false
    const fetch = vi.fn().mockResolvedValue(response(expected))
    vi.stubGlobal('fetch', fetch)
    const result = await new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })
    expect(result).toEqual(expected)
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('propagates cancellation of the real POST without a fallback request', async () => {
    const fetch = vi.fn(
      (_url: string, request: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          request.signal!.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
            once: true,
          })
        }),
    )
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const pending = new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence }, controller.signal)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('retains conflict status, current revision and the first engine diagnostic', async () => {
    const currentRevision = 'd'.repeat(64)
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response(
          {
            error: {
              message: 'Workspace changed',
              currentRevision,
              diagnostics: [{ code: 'MANTRA-CONFLICT', message: 'Participating source changed' }],
            },
          },
          409,
        ),
      ),
    )
    const pending = new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })
    await expect(pending).rejects.toBeInstanceOf(WorkbenchRequestError)
    await expect(pending).rejects.toMatchObject({
      status: 409,
      currentRevision,
      message: 'Participating source changed',
    })
  })

  it('retains HTTP status when the transport returns a non-JSON error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Proxy unavailable', { status: 502 })))
    const pending = new LiveData().previewPaper!(caseId, baseRevision, operations, { draftSequence })
    await expect(pending).rejects.toBeInstanceOf(WorkbenchRequestError)
    await expect(pending).rejects.toMatchObject({ status: 502 })
  })

  it.each([
    ['fixture', new FixtureData()],
    ['package', new PackageData()],
  ])('never falls back to the file-workspace route for an unsupported %s host', async (_mode, host) => {
    const data: WorkbenchData = host
    const fetch = vi.fn().mockResolvedValue(response(candidate()))
    vi.stubGlobal('fetch', fetch)
    expect(data.previewPaper).toBeUndefined()
    expect(fetch).not.toHaveBeenCalled()
  })
})
