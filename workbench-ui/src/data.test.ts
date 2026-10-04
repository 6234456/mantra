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
      contract: 'mantra.workbench/2',
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

describe('workbench v2 transport', () => {
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
      severity: 'error',
      category: 'business',
      code: 'MANTRA-CHECK-FAILED',
      message: 'Needs review',
      location: null,
      address: { node: 'check' },
      related: [],
      rowIndex: null,
      column: null,
    }
    const response = {
      contract: 'mantra.workbench/2',
      revision: 'new',
      engine: { mantra: 'test', normein: 'test' },
      data: {
        run: { succeeded: true, validationPassed: false, members: {}, values: {}, diagnostics: [finding] },
        diagnostics: [finding],
        difference: { variant: { parameters: [] }, mainline: [], changes: [], parameterChanges: [] },
      },
    }
    const fetch = vi.fn(async () => ({ ok: true, json: async () => response }))
    vi.stubGlobal('fetch', fetch)
    const saved = await new LiveData().edit('case', 'base', [
      { op: 'setInput', address: { node: 'amount' }, text: '0' },
    ])
    expect(saved.data.run.succeeded).toBe(true)
    expect(saved.data.run.validationPassed).toBe(false)
    expect(saved.data.diagnostics[0].category).toBe('business')
  })
})
