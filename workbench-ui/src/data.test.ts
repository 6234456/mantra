// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LiveData } from './data'

afterEach(() => { vi.unstubAllGlobals(); document.head.innerHTML = '' })

describe('live comparison transport', () => {
  it('sends the selected parameter ids with the server session token', async () => {
    document.head.innerHTML = '<meta name="mantra-session-token" content="secret">'
    const response = { contract: 'mantra.workbench/1', revision: '1234567890abcdef', engine: { mantra: 'test', normein: 'test' }, data: { variant: { parameters: ['next'] }, mainline: [], changes: [], parameterChanges: [] } }
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
