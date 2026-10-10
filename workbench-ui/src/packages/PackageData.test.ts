// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PackageData } from './PackageData'

const embedded = (data: unknown) => ({
  contract: 'mantra.workbench/4',
  revision: 'a'.repeat(64),
  engine: { mantra: '0.4.0-SNAPSHOT', normein: 'locked' },
  data,
})
const wrap = (data: unknown) => ({ contract: 'mantra.packages/1', revision: 'a'.repeat(64), data })
afterEach(() => {
  vi.unstubAllGlobals()
  document.head.innerHTML = ''
})

describe('package workbench adaptor', () => {
  it('reads diagnostic source evidence only through the explicit package route', async () => {
    const wire = embedded({ lines: [] })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(wrap({ document: wire }))))
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    expect(
      await new PackageData().sourceContext('pkg/cases/demo.mantra', 2, 'a'.repeat(64), controller.signal),
    ).toEqual(wire)
    const [url, options] = fetch.mock.calls[0]
    expect(url).toBe(
      '/api/v1/package-cases/pkg%2Fcases%2Fdemo.mantra/diagnostic-source?diagnostic=2&expectedRevision=' +
        'a'.repeat(64),
    )
    expect(options.signal).toBe(controller.signal)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('reads the existing wire4 projection through the explicit package route and preserves zero false', async () => {
    const wire = embedded({ values: { charge: { '': { value: { n: '0' } } }, enabled: { '': { value: false } } } })
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(wrap({ case: 'pkg/cases/demo.mantra', succeeded: true, document: wire }))),
      )
    vi.stubGlobal('fetch', fetch)
    const result = await new PackageData().run('pkg/cases/demo.mantra')
    expect(result).toEqual(wire)
    expect(fetch.mock.calls[0][0]).toBe('/api/v1/package-cases/pkg%2Fcases%2Fdemo.mantra/run')
  })
  it('does not substitute an earlier result after a current technical failure', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(wrap({ succeeded: true, document: embedded({ values: {} }) }))),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify(
            wrap({
              succeeded: false,
              document: null,
              diagnostics: [{ code: 'DSL-DIVIDE-BY-ZERO', message: 'Division by zero' }],
            }),
          ),
        ),
      )
    vi.stubGlobal('fetch', fetch)
    const data = new PackageData()
    await data.run('pkg/cases/demo.mantra')
    await expect(data.run('pkg/cases/demo.mantra')).rejects.toThrow('Division by zero')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('uses the existing edit contract and session token without a file endpoint fallback', async () => {
    document.head.innerHTML = '<meta name="mantra-session-token" content="test-token">'
    const wire = embedded({ preview: false, difference: {}, run: {} })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(wrap({ succeeded: true, document: wire }))))
    vi.stubGlobal('fetch', fetch)
    const operations = [{ op: 'setInput' as const, address: { case: null, node: 'charge', coord: [] }, text: '0,00' }]
    await new PackageData().edit('pkg/cases/demo.mantra', 'a'.repeat(64), operations)
    expect(String(fetch.mock.calls[0][0]).endsWith('/edit')).toBe(true)
    const options = fetch.mock.calls[0][1]
    expect(options.headers['X-Mantra-Token']).toBe('test-token')
    expect(JSON.parse(options.body)).toEqual({ baseRevision: 'a'.repeat(64), operations })
  })
  it('rejects old wrapper versions and missing migration session tokens', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify({ contract: 'mantra.packages/0', revision: null, data: {} }))),
    )
    const data = new PackageData()
    await expect(data.index()).rejects.toThrow('Unsupported package contract')
    await expect(data.applyMigration('pkg/cases/demo.mantra', 'review')).rejects.toThrow('session')
  })
  it('enables inputs only from the current explicit host binding', async () => {
    const id = 'pkg/cases/demo.mantra'
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify(wrap({ binding: { editableCase: true }, document: embedded({}) }))),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(wrap({ binding: { editableCase: false }, document: embedded({}) }))),
      )
    vi.stubGlobal('fetch', fetch)
    const data = new PackageData()
    expect(data.canEditCase(id)).toBe(false)
    await data.run(id)
    expect(data.canEditCase(id)).toBe(true)
    expect(data.canManageSources()).toBe(false)
    expect(data.canAuthorCase()).toBe(false)
    expect(data.canCompareParameters()).toBe(false)
    await data.run(id)
    expect(data.canEditCase(id)).toBe(false)
  })
})
