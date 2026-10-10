import { defineConfig, loadEnv } from 'vite'

// The frontend intentionally has no dependency on the full Node.js type package.
type LocalRequest = { url?: string; method?: string; headers: Record<string, string | string[] | undefined> }

async function sessionMetadata() {
  // scripts/dev.sh disables Node's environment proxy for this local-only Vite process.
  const response = await fetch('http://127.0.0.1:8080/', {
    redirect: 'error',
    signal: AbortSignal.timeout(5000),
  })
  if (!response.ok || !response.headers.get('content-type')?.startsWith('text/html'))
    throw new Error('Start the Mantra backend with scripts/dev.sh before opening the live workbench.')
  const html = await response.text()
  if (html.length > 1024 * 1024) throw new Error('Backend session HTML exceeds 1 MiB.')
  const token = html.match(/<meta name="mantra-session-token" content="([a-f0-9]{64})">/)?.[1]
  if (!token) throw new Error('Backend session metadata is missing; restart with scripts/dev.sh.')
  const tags = [{ tag: 'meta', attrs: { name: 'mantra-session-token', content: token } }]
  if (html.includes('<meta name="mantra-package-workspace" content="on">'))
    tags.push({ tag: 'meta', attrs: { name: 'mantra-package-workspace', content: 'on' } })
  return tags
}

export default defineConfig(({ command, mode }) => ({
  plugins:
    command === 'serve' && loadEnv(mode, '.', 'VITE_').VITE_WORKBENCH_MODE === 'live'
      ? [
          {
            name: 'mantra-live-session',
            apply: 'serve',
            configureServer(server) {
              server.middlewares.use((request, response, next) => {
                const local = request as unknown as LocalRequest
                if (
                  local.url?.startsWith('/api/') &&
                  local.method === 'POST' &&
                  local.headers.origin &&
                  local.headers.origin !== `http://${local.headers.host}`
                ) {
                  response.statusCode = 403
                  response.end('Live workbench writes require the local browser origin.')
                  return
                }
                next()
              })
            },
            async transformIndexHtml() {
              return sessionMetadata()
            },
          },
        ]
      : [],
  server: { proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true } } },
  base: '/',
}))
