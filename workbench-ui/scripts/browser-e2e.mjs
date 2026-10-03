import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const chrome = process.env.MANTRA_TEST_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
if (!existsSync(chrome))
  throw new Error(`System Chrome not found: ${chrome}. Set MANTRA_TEST_CHROME to an installed browser executable.`)

const scenarios = [
  {
    id: 'de-est/case-mustermann.mantra',
    heading: 'Einkommensteuer 2025',
    result: '1.462,24',
    panel: 'zve',
    cell: '112.380,00',
    address: 'summe-einkuenfte',
  },
  {
    id: 'ifrs-impairment/case-demo.mantra',
    heading: 'IAS 36 – Impairment test with corporate assets',
    result: '121',
    panel: 'step-1',
    cell: '120',
    address: 'carrying-amount@A',
  },
  {
    id: 'cost-accounting/case-demo.mantra',
    heading: 'Product cost by manufacturing order',
    result: '1,140.00',
    panel: 'cost-sources',
    cell: '12,400.00',
    address: 'direct-primary-total',
  },
]

async function freePort() {
  const server = createServer()
  await new Promise((done, reject) => server.once('error', reject).listen(0, '127.0.0.1', done))
  const port = server.address().port
  await new Promise((done) => server.close(done))
  return port
}

async function until(read, label, timeout = 15000) {
  const start = Date.now()
  while (Date.now() - start < timeout) {
    if (interrupted) throw new Error('Browser run interrupted')
    if (vite?.startError || browser?.startError) throw vite?.startError ?? browser.startError
    if ((vite && vite.exitCode !== null) || (browser && browser.exitCode !== null))
      throw new Error(`${label}: task process exited early`)
    try {
      const value = await read()
      if (value) return value
    } catch {
      /* The server or browser may still be starting. */
    }
    await delay(100)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

function stop(child) {
  if (!child || child.startError || child.exitCode !== null || child.signalCode !== null) return
  try {
    process.kill(-child.pid, 'SIGTERM')
  } catch {
    try {
      child.kill('SIGTERM')
    } catch {
      /* Already exited. */
    }
  }
}

async function stopped(child) {
  if (!child || child.startError || child.exitCode !== null || child.signalCode !== null) return
  await Promise.race([new Promise((done) => child.once('exit', done)), delay(3000)])
  if (child.exitCode === null && child.signalCode === null) {
    try {
      process.kill(-child.pid, 'SIGKILL')
    } catch {
      try {
        child.kill('SIGKILL')
      } catch {
        /* Already exited. */
      }
    }
    await Promise.race([new Promise((done) => child.once('exit', done)), delay(3000)])
  }
  assert.ok(child.exitCode !== null || child.signalCode !== null, `Task-owned process ${child.pid} did not stop`)
}

async function connect(url) {
  const ws = new WebSocket(url)
  await new Promise((done, reject) => {
    ws.addEventListener('open', done, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })
  let nextId = 1
  const pending = new Map()
  ws.addEventListener('message', (event) => {
    const response = JSON.parse(event.data)
    if (response.id && pending.has(response.id)) {
      const { done, reject } = pending.get(response.id)
      pending.delete(response.id)
      response.error ? reject(new Error(response.error.message)) : done(response.result)
    }
  })
  return {
    send(method, params = {}) {
      const id = nextId++
      return new Promise((done, reject) => {
        pending.set(id, { done, reject })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    close() {
      ws.close()
    },
  }
}

let vite, browser, session, directory
const failures = []
let interrupted = false
function start(command, args) {
  const child = spawn(command, args, {
    detached: true,
    stdio: 'ignore',
    env: {
      ...process.env,
      XDG_CACHE_HOME: join(directory, 'cache'),
      XDG_CONFIG_HOME: join(directory, 'config'),
      CHROME_LOG_FILE: join(directory, 'chrome.log'),
      TMPDIR: directory,
    },
  })
  child.on('error', (error) => {
    child.startError = error
  })
  return child
}
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    stop(browser)
    stop(vite)
  })
}

try {
  directory = await mkdtemp(join(tmpdir(), 'mantra-wp8-browser-'))
  const port = await freePort()
  vite = start(process.execPath, [
    resolve('node_modules/vite/bin/vite.js'),
    '--host',
    '127.0.0.1',
    '--port',
    String(port),
    '--strictPort',
  ])
  await until(async () => (await fetch(`http://127.0.0.1:${port}/`)).ok, 'Vite')
  browser = start(chrome, [
    '--headless=new',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--remote-debugging-port=0',
    `--user-data-dir=${join(directory, 'profile')}`,
    `--disk-cache-dir=${join(directory, 'cache')}`,
    'about:blank',
  ])
  const debugPort = await until(async () => {
    const value = await readFile(join(directory, 'profile', 'DevToolsActivePort'), 'utf8')
    return Number(value.split('\n')[0]) || null
  }, 'Chrome debugging endpoint')
  const target = await until(async () => {
    const list = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()
    return list.find((item) => item.type === 'page')
  }, 'Chrome page')
  session = await connect(target.webSocketDebuggerUrl)
  await session.send('Page.enable')
  await session.send('Runtime.enable')

  async function evaluate(expression) {
    const result = await session.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
    return result.result.value
  }

  for (const scenario of scenarios) {
    if (interrupted) throw new Error('Browser run interrupted')
    const base = `/cases/${encodeURIComponent(scenario.id).replace(/\./g, '%2E')}`
    await session.send('Page.navigate', { url: `http://127.0.0.1:${port}${base}/overview` })
    await until(
      async () =>
        await evaluate(
          `document.querySelector('.page-heading h1')?.textContent === ${JSON.stringify(scenario.heading)}`,
        ),
      `${scenario.id} overview`,
    )
    assert.equal(await evaluate("document.querySelector('.result-card strong')?.textContent"), scenario.result)
    const panelUrl = `${base}/panels/${scenario.panel}`
    assert.equal(
      await evaluate(
        `document.querySelector('.mainline-map a[href=${JSON.stringify(panelUrl)}]')?.click() || location.pathname`,
      ),
      panelUrl,
    )
    await until(
      async () =>
        await evaluate(`location.pathname === ${JSON.stringify(panelUrl)} && !!document.querySelector('.paper-table')`),
      `${scenario.id} panel`,
    )
    const clicked = await evaluate(
      `(() => { const cell = [...document.querySelectorAll('.paper-table .cell-button')].find(item => item.textContent === ${JSON.stringify(scenario.cell)}); cell?.click(); return !!cell })()`,
    )
    assert.equal(clicked, true, `${scenario.id} selectable Paper cell`)
    await until(
      async () => await evaluate("!!document.querySelector('.paper-table .cell-button.selected')"),
      `${scenario.id} selected cell`,
    )
    assert.equal(await evaluate("new URLSearchParams(location.search).get('cell')"), scenario.address)
    const exportUrl = `${base}/export`
    assert.equal(
      await evaluate(
        `document.querySelector('.app-bar a[href=${JSON.stringify(exportUrl)}]')?.click() || location.pathname`,
      ),
      exportUrl,
    )
    await until(
      async () =>
        await evaluate(
          "!!document.querySelector('.export-sheets button') && !!document.querySelector('.export-metrics')",
        ),
      `${scenario.id} export`,
    )
    const secondSheet = await evaluate(
      "document.querySelectorAll('.export-sheets button')[1]?.querySelector('b')?.textContent",
    )
    assert.ok(secondSheet, `${scenario.id} has a second worksheet`)
    await evaluate("document.querySelectorAll('.export-sheets button')[1].click()")
    await until(
      async () =>
        await evaluate(`document.querySelector('.export-preview h2')?.textContent === ${JSON.stringify(secondSheet)}`),
      `${scenario.id} worksheet preview`,
    )
    assert.equal(await evaluate("document.querySelectorAll('.export-metrics strong').length"), 4)
    console.log(`PASS ${scenario.id}: overview → panel → addressed cell → export → worksheet`)
  }
} catch (error) {
  failures.push(error)
} finally {
  try {
    session?.close()
  } catch {
    /* Process cleanup still takes precedence. */
  }
  stop(browser)
  stop(vite)
  const stops = await Promise.allSettled([stopped(browser), stopped(vite)])
  const removal = await Promise.allSettled(
    directory
      ? [
          rm(directory, { recursive: true, force: true }).then(() =>
            assert.equal(existsSync(directory), false, 'Task browser profile was not removed'),
          ),
        ]
      : [],
  )
  for (const result of [...stops, ...removal]) if (result.status === 'rejected') failures.push(result.reason)
  if (failures.length === 0) console.log('Browser and Vite processes stopped; temporary profile removed')
}
if (failures.length === 1) throw failures[0]
if (failures.length > 1) throw new AggregateError(failures, 'Browser verification and cleanup failed')
