// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FixtureData, LiveData } from './data'
import { PackageData } from './packages/PackageData'

const wire = {
  contract: 'mantra.workbench/4',
  revision: 'a'.repeat(64),
  engine: { mantra: 'test', normein: 'test' },
  data: { browsing: { includeZero: true, hideZero: true }, tables: [] },
}
afterEach(() => vi.unstubAllGlobals())

describe('paper visibility transport', () => {
  it.each(['legacy', 'package'])(
    'requests zero rows from the %s renderer with the supplied abort signal',
    async (mode) => {
      const fetch = vi.fn(async () => ({
        ok: true,
        json: async () =>
          mode === 'legacy'
            ? wire
            : { contract: 'mantra.packages/1', revision: wire.revision, data: { document: wire } },
      }))
      vi.stubGlobal('fetch', fetch)
      const controller = new AbortController()
      const data = mode === 'legacy' ? new LiveData() : new PackageData()
      expect((await data.paper('sample/case.mantra', 'main panel', controller.signal, true)).data.browsing).toEqual(
        wire.data.browsing,
      )
      const [url, options] = fetch.mock.calls[0] as unknown as [string, RequestInit]
      const parsed = new URL(url, 'http://localhost')
      expect(parsed.pathname).toBe(
        `/api/v1/${mode === 'legacy' ? 'cases' : 'package-cases'}/sample%2Fcase.mantra/paper`,
      )
      expect([...parsed.searchParams]).toEqual([
        ['panel', 'main panel'],
        ['includeZero', 'true'],
      ])
      expect(options.signal).toBe(controller.signal)
    },
  )

  it('keeps default requests unchanged and rejects expansion in static fixtures before any request', async () => {
    const fetch = vi.fn(async (_url: string, _options?: RequestInit) => ({ ok: true, json: async () => wire }))
    vi.stubGlobal('fetch', fetch)
    await new LiveData().paper('sample/case.mantra')
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/cases/sample%2Fcase.mantra/paper')
    fetch.mockClear()
    await expect(new FixtureData().paper('sample/case.mantra', undefined, undefined, true)).rejects.toThrow(
      'live workbench',
    )
    expect(fetch).not.toHaveBeenCalled()
  })
})
