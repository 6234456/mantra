// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FixtureData, LiveData } from './data'

afterEach(() => {
  vi.unstubAllGlobals()
  document.head.innerHTML = ''
})

describe('live comparison transport', () => {
  it('sends the selected parameter ids with the server session token', async () => {
    document.head.innerHTML = '<meta name="mantra-session-token" content="secret">'
    const response = {
      contract: 'mantra.workbench/4',
      revision: '1234567890abcdef',
      engine: { mantra: 'test', normein: 'test' },
      data: { variant: { parameters: ['next'] }, mainline: [], changes: [], parameterChanges: [] },
    }
    const fetch = vi.fn(async (_url: string, _request: RequestInit) => ({ ok: true, json: async () => response }))
    vi.stubGlobal('fetch', fetch)
    expect((await new LiveData().compare('sample/case.mantra', ['next'])).data.variant.parameters).toEqual(['next'])
    const [url, request] = fetch.mock.calls[0]
    expect(url).toBe('/api/v1/cases/sample%2Fcase.mantra/compare')
    expect(request.method).toBe('POST')
    expect((request.headers as Record<string, string>)['X-Mantra-Token']).toBe('secret')
    expect(JSON.parse(request.body as string)).toEqual({ variant: { parameters: ['next'] } })
  })

  it('does not send a write-shaped request without a server session token', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    await expect(new LiveData().compare('sample/case.mantra', ['next'])).rejects.toThrow('server session')
    expect(fetch).not.toHaveBeenCalled()
  })
})

describe('workbench v4 transport', () => {
  it('rejects an older wire contract for both live and fixture documents', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => ({
        ok: true,
        json: async () =>
          url === '/fixtures/index.json'
            ? { cases: [{ id: 'case', files: { run: '/old-run.json' } }] }
            : {
                contract: 'mantra.workbench/1',
                revision: 'old',
                engine: { mantra: 'test', normein: 'test' },
                data: {},
              },
      })),
    )
    await expect(new LiveData().run('case')).rejects.toThrow('Unsupported workbench contract')
    await expect(new FixtureData().run('case')).rejects.toThrow('Unsupported workbench contract')
  })

  it('accepts a saved edit whose calculation succeeded while business checks remain open', async () => {
    document.head.innerHTML = '<meta name="mantra-session-token" content="secret">'
    const finding = {
      caseRevision: null,
      severity: 'error',
      category: 'business',
      code: 'MANTRA-CHECK-FAILED',
      message: 'Needs review',
      location: null,
      address: { case: null, node: 'check' },
      related: [],
      rowIndex: null,
      column: null,
    }
    const response = {
      contract: 'mantra.workbench/4',
      revision: 'new',
      engine: { mantra: 'test', normein: 'test' },
      data: {
        run: {
          caseGraph: null,
          usage: null,
          failure: null,
          succeeded: true,
          validationPassed: false,
          members: {},
          values: {},
          diagnostics: [finding],
        },
        diagnostics: [finding],
        difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] },
      },
    }
    const fetch = vi.fn(async () => ({ ok: true, json: async () => response }))
    vi.stubGlobal('fetch', fetch)
    const saved = await new LiveData().edit('case', 'base', [
      { op: 'setInput', address: { case: null, node: 'amount' }, text: '0' },
    ])
    expect(saved.data.run.succeeded).toBe(true)
    expect(saved.data.run.validationPassed).toBe(false)
    expect(saved.data.diagnostics[0].category).toBe('business')
  })
})

describe('linked Explain snapshot transport', () => {
  it('keeps the root resource while sending the exact source case and revision', async () => {
    const fetch = vi.fn(async (_url: string) => ({
      ok: true,
      json: async () => ({
        contract: 'mantra.workbench/4',
        revision: 'source-revision',
        engine: { mantra: 'test', normein: 'test' },
        data: {},
      }),
    }))
    vi.stubGlobal('fetch', fetch)
    await new LiveData().explain(
      'root/case.mantra',
      {
        case: 'source/case.mantra',
        node: 'closing',
        coord: ['A/B'],
      },
      undefined,
      'source-revision',
    )
    const url = new URL(String(fetch.mock.calls[0][0]), 'http://localhost')
    expect(url.pathname).toBe('/api/v1/cases/root%2Fcase.mantra/explain')
    expect(url.searchParams.get('case')).toBe('source/case.mantra')
    expect(url.searchParams.get('expectedRevision')).toBe('source-revision')
    expect(url.searchParams.get('address')).toBe('closing@A%2FB')
  })

  it('uses the source fixture instead of a same-named root node and rejects another revision', async () => {
    const fetch = vi.fn(async (url: string) => ({
      ok: true,
      json: async () =>
        url === '/fixtures/index.json'
          ? {
              cases: [
                { id: 'root/case.mantra', files: { explains: { closing: '/root-closing.json' } } },
                { id: 'source/case.mantra', files: { explains: { closing: '/source-closing.json' } } },
              ],
            }
          : {
              contract: 'mantra.workbench/4',
              revision: 'source-revision',
              engine: { mantra: 'test', normein: 'test' },
              data: { result: { display: url } },
            },
    }))
    vi.stubGlobal('fetch', fetch)
    const data = new FixtureData()
    const address = { case: 'source/case.mantra', node: 'closing' }
    const response = await data.explain('root/case.mantra', address, undefined, 'source-revision')
    expect(response.data.result.display).toBe('/source-closing.json')
    expect(fetch).not.toHaveBeenCalledWith('/root-closing.json', expect.anything())
    await expect(data.explain('root/case.mantra', address, undefined, 'old-revision')).rejects.toThrow('stale')
  })
})
