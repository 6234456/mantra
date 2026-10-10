import { afterEach, describe, expect, it, vi } from 'vitest'
import type { IndexHtmlTransformHook, Plugin, UserConfigFnObject, ViteDevServer } from 'vite'
import configuration from '../vite.config'

const factory = configuration as UserConfigFnObject
const token = 'a'.repeat(64)

function livePlugin(command: 'serve' | 'build' = 'serve') {
  vi.stubEnv('VITE_WORKBENCH_MODE', 'live')
  const config = factory({ command, mode: 'test' })
  return (config.plugins as Plugin[]).find((plugin) => plugin.name === 'mantra-live-session')
}

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('live development session', () => {
  it('injects only verified backend session and package metadata into the Vite page', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          `<head><meta name="mantra-session-token" content="${token}"><meta name="mantra-package-workspace" content="on"></head>`,
          { headers: { 'Content-Type': 'text/html; charset=utf-8' } },
        ),
      )
    vi.stubGlobal('fetch', fetch)
    const transform = livePlugin()!.transformIndexHtml as OmitThisParameter<IndexHtmlTransformHook>
    const tags = await transform('', { path: '/', filename: 'index.html' })
    expect(tags).toEqual([
      { tag: 'meta', attrs: { name: 'mantra-session-token', content: token } },
      { tag: 'meta', attrs: { name: 'mantra-package-workspace', content: 'on' } },
    ])
    expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/', {
      redirect: 'error',
      signal: expect.any(AbortSignal),
    })
  })

  it('fails clearly when the backend is unavailable or omits its token', async () => {
    const transform = livePlugin()!.transformIndexHtml as OmitThisParameter<IndexHtmlTransformHook>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('missing', { status: 404 })))
    await expect(transform('', { path: '/', filename: 'index.html' })).rejects.toThrow('scripts/dev.sh')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('<head></head>', { headers: { 'Content-Type': 'text/html' } })),
    )
    await expect(transform('', { path: '/', filename: 'index.html' })).rejects.toThrow('metadata is missing')
  })

  it('leaves fixture development and production builds independent of a running backend', () => {
    expect(livePlugin('build')).toBeUndefined()
    vi.stubEnv('VITE_WORKBENCH_MODE', 'fixture')
    const config = factory({ command: 'serve', mode: 'test' })
    expect(config.plugins).toEqual([])
  })

  it('rejects foreign browser writes before proxying and retains legitimate same-origin writes', () => {
    type Request = { url: string; method: string; headers: { host: string; origin?: string } }
    type Response = { statusCode: number; end: ReturnType<typeof vi.fn> }
    let middleware!: (request: Request, response: Response, next: () => void) => void
    const server = {
      middlewares: {
        use: (handler: typeof middleware) => {
          middleware = handler
        },
      },
    } as unknown as ViteDevServer
    const configure = livePlugin()!.configureServer as (server: ViteDevServer) => void
    configure(server)
    const response = { statusCode: 200, end: vi.fn() }
    const next = vi.fn()
    middleware(
      {
        url: '/api/v1/package-cases/demo/edit',
        method: 'POST',
        headers: { host: '127.0.0.1:5173', origin: 'https://foreign.example' },
      },
      response,
      next,
    )
    expect(response.statusCode).toBe(403)
    expect(next).not.toHaveBeenCalled()
    middleware(
      {
        url: '/api/v1/package-cases/demo/edit',
        method: 'POST',
        headers: { host: '127.0.0.1:5173', origin: 'http://127.0.0.1:5173' },
      },
      response,
      next,
    )
    expect(next).toHaveBeenCalledOnce()
  })
})
