import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

// Real server, freshly built live UI and an installed browser; no recorded engine responses.
// On Linux hosts without an init reaper, run this through the environment's subreaper wrapper.
const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repository = resolve(uiRoot, '..')
const artifacts = resolve(repository, 'build/ui-qa/template-preview')
const cli = resolve(
  process.env.MANTRA_TEMPLATE_PREVIEW_CLI ?? join(repository, 'mantra-cli/build/install/mantra/bin/mantra'),
)
const chrome = process.env.MANTRA_TEST_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
assert.ok(existsSync(cli), `Build the current CLI with :mantra-cli:installDist first: ${cli}`)
assert.ok(existsSync(chrome), `Set MANTRA_TEST_CHROME to an installed browser executable: ${chrome}`)
assert.equal(typeof WebSocket, 'function', 'Use the supported Node version with built-in WebSocket')

const schema = `(schema browser/preview {:version "1" :mainline [main]}
  (include "helpers.mantra")
  (input base-amount :decimal)
  (section main "Main" {:panel true}
    (field base-amount "Amount" {:op :info})
    (info answer "Answer" (* base-amount 2))
    (check positive "Nonnegative" (>= base-amount 0)))
  (section details "Details" {:panel true :display :schedule}
    (info detail "Detail value" (+ base-amount 5))
    (info zero-marker "Zero marker" 0)))`
const layout = '(layout browser/layout {:title "Saved title" :locale "de-DE"})'
const originals = new Map([
  ['schema.mantra', Buffer.from(schema)],
  ['layout.mantra', Buffer.from(layout)],
  ['helpers.mantra', Buffer.from('(fragment (defn twice [^Decimal value] (* value 2)))')],
  [
    'case.mantra',
    Buffer.from('(case example {:schema "browser/preview" :layout "browser/layout"} (inputs {:base-amount 10}))\r\n'),
  ],
])
const candidateSchema = schema.replace('(* base-amount 2)', '(* base-amount 3)')
const candidateLayout = layout.replace('Saved title', 'Draft title').replace('de-DE', 'en-US')
const invalidSchema = candidateSchema.replace('(* base-amount 3)', 'missing-symbol')
const runtimeSchema = candidateSchema.replace('(* base-amount 3)', '(/ base-amount 0)')
const emptyLayout = '(layout browser/layout {:title "Empty draft" :locale "en-US"} (hide main details))'
const expectedFiles = new Map(originals)
const owned = []
const failures = []
const passed = []
const responses = []
const settingsResponses = []
const screenshots = []
const browserErrors = []
const network = new Map()
const pausedResponses = []
const cancelledRequests = []
const cancelledSettingsRequests = []
const externalChanges = []
let directory, workspace, service, browser, session, baseUrl
let interrupted = false
let closing = false
let filesVerified = 0
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex')

function start(command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: uiRoot,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      VITE_WORKBENCH_MODE: 'live',
      XDG_CACHE_HOME: join(directory, 'cache'),
      XDG_CONFIG_HOME: join(directory, 'config'),
      CHROME_LOG_FILE: join(directory, 'chrome.log'),
      TMPDIR: directory,
    },
    ...options,
  })
  child.output = ''
  const collect = (chunk) => {
    child.output = (child.output + chunk.toString()).slice(-12000)
  }
  child.stdout.on('data', collect)
  child.stderr.on('data', collect)
  child.on('error', (error) => {
    child.startError = error
  })
  child.done = new Promise((done) => child.once('close', done))
  owned.push(child)
  return child
}

function stop(child, signal = 'SIGTERM') {
  if (!child?.pid) return
  try {
    if (process.platform === 'win32') child.kill(signal)
    else process.kill(-child.pid, signal)
  } catch (error) {
    if (error.code !== 'ESRCH') throw error
  }
}

async function stopped(child) {
  if (!child || child.startError) return
  if (child.exitCode === null && child.signalCode === null) await Promise.race([child.done, delay(3000)])
  if (child.exitCode === null && child.signalCode === null) {
    stop(child, 'SIGKILL')
    await Promise.race([child.done, delay(3000)])
  }
  assert.ok(child.exitCode !== null || child.signalCode !== null, `Task process ${child.pid} did not stop`)
  if (process.platform === 'win32') return
  const groupAlive = () => {
    try {
      process.kill(-child.pid, 0)
      return true
    } catch (error) {
      if (error.code === 'ESRCH') return false
      throw error
    }
  }
  async function settle() {
    const deadline = Date.now() + 3000
    while (groupAlive() && Date.now() < deadline) await delay(75)
  }
  await settle()
  if (groupAlive()) {
    stop(child, 'SIGKILL')
    await settle()
  }
  assert.equal(groupAlive(), false, `Task process group ${child.pid} did not stop`)
}

async function until(read, label, timeout = 20000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (interrupted) throw new Error('Template preview browser run interrupted')
    for (const child of [service, browser]) {
      if (child?.startError) throw child.startError
      if (child && (child.exitCode !== null || child.signalCode !== null))
        throw new Error(`${label}: task process exited early\n${child.output}`)
    }
    try {
      const value = await read()
      if (value) return value
    } catch {
      // A local endpoint or page can be unavailable while it starts.
    }
    await delay(75)
  }
  const body = session ? await evaluate('document.body.innerText').catch(() => '') : ''
  throw new Error(`Timed out waiting for ${label}\n${body.slice(-6000)}`)
}

async function connect(url) {
  const ws = new WebSocket(url)
  try {
    await new Promise((done, reject) => {
      const timeout = setTimeout(() => reject(new Error('Browser debugging connection timed out')), 15000)
      ws.addEventListener(
        'open',
        () => {
          clearTimeout(timeout)
          done()
        },
        { once: true },
      )
      ws.addEventListener(
        'error',
        () => {
          clearTimeout(timeout)
          reject(new Error('Browser debugging connection failed'))
        },
        { once: true },
      )
    })
  } catch (error) {
    ws.close()
    throw error
  }
  let nextId = 1
  const pending = new Map()
  ws.addEventListener('message', (event) => {
    const response = JSON.parse(event.data)
    const params = response.params
    if (response.method === 'Runtime.exceptionThrown') browserErrors.push(params.exceptionDetails.text)
    if (response.method === 'Page.javascriptDialogOpening') {
      void session?.send('Page.handleJavaScriptDialog', { accept: true }).catch((error) => {
        if (!closing) failures.push(error)
      })
    }
    if (
      response.method === 'Network.requestWillBeSent' &&
      params.request.method === 'POST' &&
      (params.request.url.endsWith('/template-preview') || params.request.url.endsWith('/template-layout-settings'))
    ) {
      // Keep only JSON body evidence, never session-token request headers.
      network.set(params.requestId, {
        request: JSON.parse(params.request.postData),
        settings: params.request.url.endsWith('/template-layout-settings'),
      })
    }
    if (response.method === 'Network.responseReceived' && network.has(params.requestId)) {
      network.get(params.requestId).status = params.response.status
    }
    if (response.method === 'Network.loadingFailed' && network.has(params.requestId)) {
      const captured = network.get(params.requestId)
      network.delete(params.requestId)
      const cancellations = captured.settings ? cancelledSettingsRequests : cancelledRequests
      cancellations.push({ request: captured.request, cancelled: !!params.canceled, reason: params.errorText })
      if (!params.canceled && !closing) failures.push(new Error(`Preview HTTP request failed: ${params.errorText}`))
    }
    if (response.method === 'Fetch.requestPaused') {
      pausedResponses.push({ requestId: params.requestId, status: params.responseStatusCode })
    }
    if (response.method === 'Network.loadingFinished' && network.has(params.requestId)) {
      const captured = network.get(params.requestId)
      network.delete(params.requestId)
      void session
        .send('Network.getResponseBody', { requestId: params.requestId })
        .then(({ body, base64Encoded }) => {
          captured.body = JSON.parse(base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body)
          ;(captured.settings ? settingsResponses : responses).push(captured)
        })
        .catch((error) => {
          if (!closing) failures.push(error)
        })
    }
    if (response.id && pending.has(response.id)) {
      const { done, reject, timeout } = pending.get(response.id)
      clearTimeout(timeout)
      pending.delete(response.id)
      response.error ? reject(new Error(response.error.message)) : done(response.result)
    }
  })
  ws.addEventListener('close', () => {
    for (const { reject, timeout } of pending.values()) {
      clearTimeout(timeout)
      reject(new Error('Browser debugging connection closed'))
    }
    pending.clear()
  })
  return {
    send(method, params = {}) {
      const id = nextId++
      return new Promise((done, reject) => {
        const timeout = setTimeout(() => {
          pending.delete(id)
          reject(new Error(`Browser command timed out: ${method}`))
        }, 15000)
        pending.set(id, { done, reject, timeout })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    close() {
      ws.close()
    },
  }
}

async function evaluate(expression) {
  const result = await session.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails)
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text)
  return result.result.value
}

const button = (text) =>
  `[...document.querySelectorAll('button')].find(item => item.textContent.trim() === ${JSON.stringify(text)})`
const field = (label) => `document.querySelector('[aria-label=' + CSS.escape(${JSON.stringify(label)}) + ']')`

async function click(text) {
  await until(() => evaluate(`!!${button(text)} && !${button(text)}.disabled`), `enabled ${text}`)
  await evaluate(`${button(text)}.click()`)
}

async function fill(label, value) {
  await until(() => evaluate(`!!${field(label)} && !${field(label)}.disabled`), label)
  await evaluate(`${field(label)}.focus()`)
  const modifiers = process.platform === 'darwin' ? 4 : 2
  await session.send('Input.dispatchKeyEvent', {
    type: 'keyDown',
    key: 'a',
    code: 'KeyA',
    modifiers,
    windowsVirtualKeyCode: 65,
  })
  await session.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'a', code: 'KeyA', modifiers })
  await session.send('Input.insertText', { text: value })
  assert.equal(await evaluate(`${field(label)}.value`), value, `Real browser input changed ${label}`)
}

async function source(name) {
  await until(
    () =>
      evaluate(
        `[...document.querySelectorAll('.template-preview-source option')].some(item => item.textContent.startsWith(${JSON.stringify(`${name} ·`)}))`,
      ),
    `captured source ${name}`,
  )
  await evaluate(`(() => {
    const select = document.querySelector('.template-preview-source select');
    const option = [...select.options].find(item => item.textContent.startsWith(${JSON.stringify(`${name} ·`)}));
    if (!option) throw new Error('Missing captured source document');
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  await until(() => evaluate(`!!${field(`Draft source ${name}`)}`), `selected source ${name}`)
}

const control = (label, selector) =>
  `(() => { const settings = document.querySelector('[aria-label="Preview settings"]'); const label = [...settings.querySelectorAll('label')].find(item => [...item.childNodes].some(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim() === ${JSON.stringify(label)})); return label?.querySelector(${JSON.stringify(selector)}); })()`

async function select(label, value) {
  const element = control(label, 'select')
  await until(() => evaluate(`!!${element} && !${element}.disabled`), `enabled ${label}`)
  await evaluate(`(() => {
    const select = ${element};
    if (![...select.options].some(option => option.value === ${JSON.stringify(value)})) throw new Error('Missing preview panel option');
    select.value = ${JSON.stringify(value)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  assert.equal(await evaluate(`${element}.value`), value)
}

async function toggle(label, checked) {
  const element = control(label, 'input[type="checkbox"]')
  await until(() => evaluate(`!!${element} && !${element}.disabled`), `enabled ${label}`)
  await evaluate(`(() => { const checkbox = ${element}; if (checkbox.checked !== ${checked}) checkbox.click(); })()`)
  assert.equal(await evaluate(`${element}.checked`), checked)
}

async function preview(status) {
  const index = responses.length
  await click('Preview draft')
  const captured = await until(() => responses[index], `real template-preview HTTP ${status}`)
  assert.equal(captured.status, status, JSON.stringify(captured.body))
  await until(
    () =>
      evaluate(
        `!document.querySelector('[data-testid="template-preview-status"]').textContent.includes('Calculating')`,
      ),
    'settled preview UI',
  )
  if (status === 200) {
    assert.equal(captured.body.contract, 'mantra.workbench/4')
    assert.equal(captured.body.revision, captured.request.baseRevision)
    assert.equal(captured.body.data.draftSequence, captured.request.draftSequence)
    assert.deepEqual(captured.body.data.baseRevisions, captured.request.baseRevisions)
    await until(
      () =>
        evaluate(
          `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Current engine preview')`,
        ),
      'current engine preview',
    )
  }
  return captured
}

async function settings(status, action = 'Refresh layout settings') {
  const index = settingsResponses.length
  await click(action)
  const captured = await until(() => settingsResponses[index], `real template-layout-settings HTTP ${status}`)
  assert.equal(captured.status, status, JSON.stringify(captured.body))
  await until(
    () =>
      evaluate(
        `!document.querySelector('[aria-label="Layout settings"]').textContent.includes('Reading layout settings…')`,
      ),
    'settled layout settings UI',
  )
  if (status === 200) {
    assert.equal(captured.body.contract, 'mantra.workbench/4')
    assert.equal(captured.body.revision, captured.request.baseRevision)
    assert.equal(captured.body.data.draftSequence, captured.request.draftSequence)
    assert.equal(captured.body.data.source.handle, captured.request.handle)
    assert.deepEqual(captured.body.data.baseRevisions, captured.request.baseRevisions)
    let reconstructed = captured.request.text
    for (const patch of captured.body.data.patches) {
      assert.equal(reconstructed.slice(patch.startOffset, patch.endOffset), patch.removedText)
      reconstructed = reconstructed.slice(0, patch.startOffset) + patch.text + reconstructed.slice(patch.endOffset)
    }
    assert.equal(
      reconstructed,
      captured.body.data.source.text,
      'Sequential UTF-16 patches reconstruct the exact source',
    )
    for (const patch of [...captured.body.data.patches].reverse())
      reconstructed =
        reconstructed.slice(0, patch.startOffset) +
        patch.removedText +
        reconstructed.slice(patch.startOffset + patch.text.length)
    assert.equal(reconstructed, captured.request.text, 'Inverse patches restore the exact submitted source')
  }
  return captured
}

async function settingChoice(id, value) {
  const element = field(`Layout setting ${id}`)
  await until(() => evaluate(`!!${element} && !${element}.disabled`), `editable layout setting ${id}`)
  await evaluate(`(() => {
    const select = ${element};
    if (![...select.options].some(option => option.value === ${JSON.stringify(value)})) throw new Error('Missing setting option');
    select.value = ${JSON.stringify(value)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  assert.equal(await evaluate(`${element}.value`), value)
}

async function resetSetting(id) {
  const element = field(`Reset layout setting ${id}`)
  await until(() => evaluate(`!!${element} && !${element}.disabled`), `resettable layout setting ${id}`)
  await evaluate(`${element}.click()`)
}

async function releaseHeld(response) {
  await session.send('Fetch.continueResponse', { requestId: response.requestId }).catch((error) => {
    if (!/interception|not found/i.test(error.message)) throw error
  })
  await session.send('Fetch.disable')
  await delay(150)
}

const answer = (response) => response.data.run.values.answer[''].value.n

async function paperValue(label, expected, region = 'Draft preview') {
  await until(
    () =>
      evaluate(`(() => {
      const section = document.querySelector('[aria-label=${JSON.stringify(region)}]');
      const row = [...section.querySelectorAll('.paper-table tbody tr')].find(item => item.textContent.includes(${JSON.stringify(label)}));
      return row && [...row.querySelectorAll('td')].some(cell => cell.textContent.trim() === ${JSON.stringify(expected)});
    })()`),
    `${region} engine Paper ${label} ${expected}`,
  )
}

const paperAnswer = (expected) => paperValue('Answer', expected)

async function baselineResponse(index, panel = '', includeZero = false) {
  const captured = await until(
    () => responses.slice(index).find(({ request }) => request.inputs.length === 0 && (request.panel ?? '') === panel),
    'real independent saved-template HTTP response',
  )
  assert.equal(captured.status, 200, JSON.stringify(captured.body))
  assert.equal(captured.request.includeZero, includeZero)
  assert.equal(captured.request.explain, undefined, 'Saved template never takes the draft Explain address')
  assert.deepEqual(
    captured.request.documents.map((document) => document.text).sort(),
    ['schema.mantra', 'layout.mantra'].map((name) => expectedFiles.get(name).toString('utf8')).sort(),
    'Saved template uses the captured original sources rather than edited text',
  )
  assert.equal(answer(captured.body), '20', 'Saved template uses saved input 10 and original formula ×2')
  assert.equal(captured.body.data.paper.title, 'Saved title')
  await until(
    () =>
      evaluate(
        `document.querySelector('[data-testid="template-baseline-status"]').textContent.startsWith('Saved-template preview')`,
      ),
    'saved-template preview UI',
  )
  assert.equal(
    await evaluate(`document.querySelectorAll('[aria-label="Saved template"] button').length`),
    0,
    'Saved Paper cells are read-only evidence and cannot select the draft Explain address',
  )
  return captured
}

async function differenceAnswer(captured) {
  const change = captured.body.data.difference.changes
    .flatMap((group) => group.items)
    .find((item) => item.node === 'answer')
  assert.ok(change, 'The actual engine Difference contains the changed answer')
  const displayed = await evaluate(`(() => {
    const table = document.querySelector('[aria-label="Changes from saved template"]');
    const code = [...table.querySelectorAll('code')].find(item => item.textContent === 'answer');
    return [...code.closest('tr').querySelectorAll('td')].map(cell => cell.textContent.trim());
  })()`)
  assert.deepEqual(
    displayed.slice(0, 3),
    [change.display.base, change.display.variant, change.display.delta ?? change.delta?.n ?? '—'],
    'Saved/draft/delta text comes directly from the engine Difference response',
  )
}

async function unchangedFiles() {
  assert.deepEqual(
    (await readdir(workspace)).sort(),
    [...expectedFiles.keys()].sort(),
    'Preview added no workspace files',
  )
  for (const [name, bytes] of expectedFiles)
    assert.deepEqual(await readFile(join(workspace, name)), bytes, `Preview did not write ${name}`)
  filesVerified++
}

async function savedAnswer(expected) {
  const response = await fetch(`${baseUrl}/api/v1/cases/case.mantra/run`, { signal: AbortSignal.timeout(15000) })
  assert.equal(response.status, 200)
  assert.equal((await response.json()).data.values.answer[''].value.n, expected, 'Saved case is independent of draft')
}

async function screenshot(name) {
  await evaluate('scrollTo(0, 0)')
  const metrics = await session.send('Page.getLayoutMetrics')
  const size = metrics.cssContentSize ?? metrics.contentSize
  const clip = { x: 0, y: 0, width: Math.ceil(size.width), height: Math.min(4000, Math.ceil(size.height)), scale: 1 }
  const result = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true, clip })
  await writeFile(join(artifacts, `${name}.png`), Buffer.from(result.data, 'base64'))
  screenshots.push({
    file: `${name}.png`,
    width: clip.width,
    height: clip.height,
    completePage: clip.height >= size.height,
  })
}

function pass(name) {
  passed.push(name)
  console.log(`PASS ${name}`)
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    for (const child of owned) stop(child)
  })
}

try {
  directory = await mkdtemp(join(tmpdir(), 'mantra-template-preview-browser-'))
  workspace = join(directory, 'workspace')
  await mkdir(workspace)
  await mkdir(artifacts, { recursive: true })
  for (const [name, bytes] of originals) await writeFile(join(workspace, name), bytes)
  const dist = join(directory, 'live-ui')
  const build = start(process.execPath, [
    '--input-type=module',
    '--eval',
    `import { build } from ${JSON.stringify(pathToFileURL(join(uiRoot, 'node_modules/vite/dist/node/index.js')).href)};
     await build({ root: ${JSON.stringify(uiRoot)}, publicDir: false,
       build: { outDir: ${JSON.stringify(dist)}, emptyOutDir: true, manifest: true } });`,
  ])
  await Promise.race([build.done, delay(60000, undefined, { ref: false })])
  if (build.startError) throw build.startError
  assert.equal(build.exitCode, 0, `Fresh live UI build failed or timed out\n${build.output}`)
  assert.ok(existsSync(join(dist, 'index.html')), 'Fresh live build has index.html')
  assert.equal(existsSync(join(dist, 'fixtures')), false, 'Test uses live output without fixture files')
  const manifest = JSON.parse(await readFile(join(dist, '.vite/manifest.json'), 'utf8'))
  assert.equal(
    Object.keys(manifest).some((name) => name.includes('/authoring/')),
    false,
    'Live graph excludes fixture authoring',
  )
  assert.ok(
    Object.keys(manifest).some((name) => name.includes('TemplateDraftPreviewPage')),
    'Live graph includes real draft preview',
  )
  service = start(cli, ['serve', workspace, '--port', '0', '--ui', dist, '--directory-policy', 'trusted-local'])
  baseUrl = await until(
    () => service.output.match(/mantra: serving .+ at (http:\/\/127\.0\.0\.1:\d+)\//)?.[1],
    'real Mantra service',
  )
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
  const debugPort = await until(
    async () => Number((await readFile(join(directory, 'profile/DevToolsActivePort'), 'utf8')).split('\n')[0]),
    'Chrome debugging endpoint',
  )
  const target = await until(
    async () =>
      (await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json()).find((item) => item.type === 'page'),
    'Chrome task page',
  )
  session = await connect(target.webSocketDebuggerUrl)
  await session.send('Page.enable')
  await session.send('Runtime.enable')
  await session.send('Network.enable')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  })
  await session.send('Page.navigate', { url: `${baseUrl}/template-preview?case=case.mantra` })
  await until(
    () =>
      evaluate(
        `!![...document.querySelectorAll('.template-preview-source option')].find(item => item.textContent.startsWith('schema.mantra ·'))`,
      ),
    'real captured source documents',
  )
  await source('schema.mantra')
  assert.equal(
    await evaluate(`!!document.querySelector('meta[name="mantra-session-token"][content]')`),
    true,
    'Actual server injected session metadata',
  )
  const initial = await preview(200)
  assert.equal(answer(initial.body), '20')
  await paperAnswer('20,00')
  assert.deepEqual(
    await evaluate(
      `${control('Preview panel', 'select')} ? [...${control('Preview panel', 'select')}.options].map(option => option.value).sort() : []`,
    ),
    ['', 'details', 'main'],
    'Panel choices come from the real candidate Structure',
  )
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('initial')
  pass('fresh live UI renders a real saved-template Paper with unchanged files')

  await source('layout.mantra')
  await evaluate(`document.querySelector('.template-layout-settings-disclosure > summary').click()`)
  await until(
    () => evaluate(`document.querySelector('.template-layout-settings-disclosure').open`),
    'advanced layout settings opened before using its real browser fields',
  )
  const inspected = await settings(200, 'Inspect layout settings')
  assert.equal(inspected.body.data.scope, 'layout')
  assert.equal(inspected.body.data.source.document, 'layout.mantra')
  assert.equal(inspected.body.data.source.text, layout)
  assert.deepEqual(inspected.request.operations, [])
  assert.deepEqual(inspected.body.data.patches, [])
  assert.equal(inspected.body.data.properties.length, 14)
  const property = (captured, id) => captured.body.data.properties.find((item) => item.id === id)
  assert.deepEqual(property(inspected, 'precision').effectiveValue, { n: '2' })
  assert.equal(property(inspected, 'precision').declared, false)
  assert.equal(property(inspected, 'title').explicitValue, 'Saved title')
  await unchangedFiles()
  pass('real root-layout inspection exposes fourteen typed settings and the engine preset defaults')

  await fill('Layout setting title', 'Unapplied title')
  await until(() => evaluate(`${button('Preview draft')}.disabled`), 'unapplied settings block a misleading preview')
  await fill('Layout setting precision', '-')
  assert.equal(await evaluate(`${button('Apply layout settings')}.disabled`), true, 'Incomplete numbers stay buffered')
  const beforeIme = settingsResponses.length
  const ime = await evaluate(`(() => {
    const input = ${field('Layout setting title')};
    input.focus();
    input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: '' }));
    const key = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, isComposing: true, bubbles: true, cancelable: true });
    const accepted = input.dispatchEvent(key);
    const state = { accepted, prevented: key.defaultPrevented, applying: ${button('Apply layout settings')}.disabled,
      source: ${field('Draft source layout.mantra')}.value };
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '标题' }));
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    return state;
  })()`)
  assert.equal(ime.accepted, true, 'The native IME confirmation event stays accepted')
  assert.equal(ime.prevented, false, 'Settings do not cancel the native IME candidate confirmation')
  assert.equal(ime.applying, true)
  assert.equal(ime.source, layout)
  assert.equal(settingsResponses.length, beforeIme, 'Composition and partial input submit no source edit')
  await click('Cancel setting changes')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), layout)
  await until(() => evaluate(`!${button('Preview draft')}.disabled`), 'cancel leaves the applied source previewable')
  pass('unapplied settings and synthetic IME stay buffered while Cancel preserves source and native confirmation')

  await fill('Layout setting title', 'Structured draft title')
  await fill('Layout setting precision', '0')
  await settingChoice('grouping', 'false')
  await until(
    () =>
      evaluate(
        `!!${field('Set explicit layout setting zero')} && !${field('Set explicit layout setting zero')}.disabled`,
      ),
    'an explicit empty override can be requested without inventing intermediate text',
  )
  await evaluate(`${field('Set explicit layout setting zero')}.click()`)
  const applied = await settings(200, 'Apply layout settings')
  assert.deepEqual(
    Object.fromEntries(applied.request.operations.map((operation) => [operation.property, operation.text])),
    { title: 'Structured draft title', precision: '0', grouping: 'false', zero: '' },
    'False, zero and empty text remain explicit requests rather than unset operations',
  )
  assert.deepEqual(property(applied, 'precision').explicitValue, { n: '0' })
  assert.equal(property(applied, 'grouping').explicitValue, false)
  assert.equal(property(applied, 'zero').explicitValue, '')
  await until(
    () => evaluate(`${field('Draft source layout.mantra')}.value === ${JSON.stringify(applied.body.data.source.text)}`),
    'structured settings applied to the exact same source draft',
  )
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Previous engine preview')`,
    ),
    true,
  )
  const savedBeforeSettingsPreview = responses.length
  await toggle('Compare with saved template', true)
  await baselineResponse(savedBeforeSettingsPreview)
  const precisionZero = await preview(200)
  assert.equal(answer(precisionZero.body), '20', 'Display precision leaves the exact calculated value unchanged')
  assert.equal(precisionZero.body.data.paper.title, 'Structured draft title')
  await paperAnswer('20')
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('layout-settings-applied')
  pass(
    'structured zero false and empty overrides update only the draft while exact Run and saved baseline stay independent',
  )

  await resetSetting('precision')
  const inherited = await settings(200, 'Apply layout settings')
  assert.deepEqual(inherited.request.operations, [{ op: 'unset', property: 'precision' }])
  assert.equal(property(inherited, 'precision').declared, false)
  assert.deepEqual(property(inherited, 'precision').effectiveValue, { n: '2' })
  assert.equal(/:precision\b/.test(inherited.body.data.source.text), false)
  const inheritedPreview = await preview(200)
  assert.equal(answer(inheritedPreview.body), '20')
  await paperAnswer('20,00')
  await unchangedFiles()
  pass('Reset removes the source override and restores the engine preset instead of writing an explicit default')

  const manualNil = layout.replace('})', ' :grouping nil})')
  await fill('Draft source layout.mantra', manualNil)
  const nilSetting = await settings(200)
  assert.equal(property(nilSetting, 'grouping').editable, false)
  assert.equal(property(nilSetting, 'grouping').canUnset, true)
  assert.equal(await evaluate(`${field('Layout setting grouping')}.disabled`), true)
  assert.equal(
    await evaluate(`${field('Layout setting grouping')}.selectedOptions[0].textContent`),
    'Unsupported source value',
    'A manual nil does not visually become an unrequested true setting',
  )
  await resetSetting('grouping')
  const nilReset = await settings(200, 'Apply layout settings')
  assert.equal(property(nilReset, 'grouping').declared, false)
  assert.equal(property(nilReset, 'grouping').effectiveValue, true)
  await unchangedFiles()
  pass('unsupported manual declarations remain read-only and visible until an explicit inherited reset')

  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-layout-settings', requestStage: 'Response' }],
  })
  await click('Refresh layout settings')
  const heldInspection = await until(
    () => pausedResponses.shift(),
    'real settings inspection held at the browser boundary',
  )
  assert.equal(heldInspection.status, 200)
  const inspectedThenEdited = `${nilReset.body.data.source.text}\n; newer source while inspecting\n`
  await fill('Draft source layout.mantra', inspectedThenEdited)
  await releaseHeld(heldInspection)
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), inspectedThenEdited)
  assert.equal(await evaluate(`${field('Layout setting title')}.disabled`), true)
  await unchangedFiles()
  pass('a real delayed settings inspection cannot restore stale properties over manually edited source')

  await settings(200)
  await fill('Layout setting title', 'Held source edit')
  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-layout-settings', requestStage: 'Response' }],
  })
  await click('Apply layout settings')
  const heldSettingEdit = await until(() => pausedResponses.shift(), 'real settings edit held at the browser boundary')
  assert.equal(heldSettingEdit.status, 200)
  const newerManualSource = `${inspectedThenEdited}; later manual source wins\n`
  await fill('Draft source layout.mantra', newerManualSource)
  await releaseHeld(heldSettingEdit)
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), newerManualSource)
  assert.equal(await evaluate(`${field('Layout setting title')}.value`), 'Held source edit')
  assert.equal(
    await evaluate(`${button('Preview draft')}.disabled`),
    true,
    'Retained unapplied fields still block preview',
  )
  await click('Cancel setting changes')
  await unchangedFiles()
  pass('a real delayed source patch cannot overwrite newer manual source and retains its unapplied field buffer')

  await fill('Draft source layout.mantra', layout)
  await settings(200)
  const settingsExternal = Buffer.from(`${layout}\n; external edit during settings\n`)
  await writeFile(join(workspace, 'layout.mantra'), settingsExternal)
  expectedFiles.set('layout.mantra', settingsExternal)
  externalChanges.push('layout.mantra')
  await settings(409)
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), layout)
  assert.equal(await evaluate(`${button('Preview draft')}.disabled`), true)
  await unchangedFiles()
  await writeFile(join(workspace, 'layout.mantra'), originals.get('layout.mantra'))
  expectedFiles.set('layout.mantra', originals.get('layout.mantra'))
  await click('Reload saved source')
  await until(() => evaluate(`!!${field('Draft source layout.mantra')}`), 'source recaptured after settings conflict')
  await toggle('Compare with saved template', false)
  await delay(100)
  const settingsRecovered = await preview(200)
  assert.equal(answer(settingsRecovered.body), '20')
  await unchangedFiles()
  await source('schema.mantra')
  pass('a settings source conflict keeps the draft and blocks calculation until an explicit baseline recapture')

  await fill('Draft source schema.mantra', candidateSchema)
  await source('layout.mantra')
  await fill('Draft source layout.mantra', candidateLayout)
  await evaluate(
    `${field('Example input base-amount')}.closest('label').querySelector('input[type="checkbox"]').click()`,
  )
  await fill('Example input base-amount', '1.25')
  const candidate = await preview(200)
  assert.equal(answer(candidate.body), '3.75')
  assert.equal(candidate.body.data.paper.title, 'Draft title')
  assert.deepEqual(candidate.request.inputs, [{ node: 'base-amount', text: '1.25' }])
  assert.ok(candidate.request.documents.some((item) => item.text === candidateSchema))
  assert.ok(candidate.request.documents.some((item) => item.text === candidateLayout))
  assert.notEqual(candidate.body.data.proposedRevision, candidate.body.revision)
  assert.ok(candidate.body.data.difference.changes.length > 0)
  await paperAnswer('3.75')
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('candidate')
  pass('schema formula, candidate locale and raw scalar input produce one real candidate')

  const comparisonStart = responses.length
  await toggle('Compare with saved template', true)
  const baseline = await baselineResponse(comparisonStart)
  assert.equal(baseline.body.revision, candidate.body.revision)
  await paperValue('Answer', '20,00', 'Saved template')
  await differenceAnswer(candidate)
  await paperAnswer('3.75')
  await unchangedFiles()
  await screenshot('saved-versus-draft')
  pass('comparison renders original sources and saved inputs beside exact engine Difference values')

  const beforeInputEdit = responses.length
  await fill('Example input base-amount', '2')
  await until(
    () =>
      evaluate(
        `document.querySelector('[aria-label="Draft comparison"]').textContent.includes('Previous draft comparison')`,
      ),
    'input edit marks the old comparison previous',
  )
  await paperValue('Answer', '20,00', 'Saved template')
  assert.equal(responses.length, beforeInputEdit, 'Editing a draft input does not rebind or recalculate saved inputs')
  const changedInput = await preview(200)
  assert.equal(answer(changedInput.body), '6')
  await paperAnswer('6.00')
  await differenceAnswer(changedInput)
  await paperValue('Answer', '20,00', 'Saved template')
  await fill('Example input base-amount', '1.25')
  await preview(200)
  await paperAnswer('3.75')
  pass('changing candidate input preserves the independent saved-template preview and marks old differences previous')

  await evaluate(`(() => {
    const row = [...document.querySelectorAll('[aria-label="Draft preview"] .paper-table tbody tr')].find(item => item.textContent.includes('Answer'));
    const cell = [...row.querySelectorAll('button')].find(item => item.textContent.trim() === '3.75');
    if (!cell) throw new Error('Missing real Answer cell');
    cell.click();
  })()`)
  const explained = await preview(200)
  assert.equal(explained.request.explain.node, 'answer')
  assert.equal(answer(explained.body), '3.75')
  assert.equal(explained.body.data.explain.result.value.n, '3.75')
  assert.equal(explained.body.data.explain.revision, explained.body.data.proposedRevision)
  assert.ok(explained.body.data.explain.steps.length > 0)
  await until(
    () =>
      evaluate(
        `!![...document.querySelectorAll('h2')].find(item => item.textContent.trim() === 'Draft Explain') && !!document.querySelector('.calculation-step')`,
      ),
    'real candidate Explain evidence',
  )
  await unchangedFiles()
  pass('selected Paper cell requests Explain from the same candidate revision')

  await toggle('Compare with saved template', false)
  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-preview', requestStage: 'Response' }],
  })
  await click('Preview draft')
  const held = await until(() => pausedResponses.shift(), 'real engine response held at the browser network boundary')
  assert.equal(held.status, 200, 'The held response was actually calculated by the server')
  await select('Preview panel', 'details')
  await session.send('Fetch.continueResponse', { requestId: held.requestId }).catch((error) => {
    if (!/interception|not found/i.test(error.message)) throw error
  })
  await session.send('Fetch.disable')
  await delay(150)
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Previous engine preview')`,
    ),
    true,
    'A held older-panel response cannot become the current preview after a context change',
  )
  const details = await preview(200)
  assert.equal(details.request.panel, 'details')
  assert.equal(details.request.explain, undefined, 'Panel changes clear the old draft Explain target')
  assert.equal(details.body.data.explain, null)
  assert.equal(details.body.data.run.values.detail[''].value.n, '6.25')
  assert.deepEqual(
    details.body.data.paper.tables.map((table) => table.id),
    ['details'],
  )
  await paperValue('Detail value', '6.25')
  assert.equal(
    await evaluate(`document.querySelector('[aria-label="Draft preview"]').textContent.includes('Answer')`),
    false,
    'Panel Paper shows no fallback rows from the previous main panel',
  )
  assert.equal(
    details.body.data.paper.tables
      .flatMap((table) => table.rows)
      .some((row) => row.cells.some((cell) => cell.text === 'Zero marker')),
    false,
  )
  await unchangedFiles()
  pass('panel selection cancels a real delayed response, clears Explain and renders only the requested panel')

  const detailsBaselineStart = responses.length
  await toggle('Compare with saved template', true)
  await baselineResponse(detailsBaselineStart, 'details')
  await paperValue('Detail value', '15,00', 'Saved template')
  const visibleZeroStart = responses.length
  await toggle('Show zero rows', true)
  await baselineResponse(visibleZeroStart, 'details', true)
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Previous engine preview')`,
    ),
    true,
    'Changing includeZero invalidates the previous candidate context',
  )
  const withZero = await preview(200)
  assert.equal(withZero.request.panel, 'details')
  assert.equal(withZero.request.includeZero, true)
  assert.equal(withZero.request.explain, undefined)
  assert.ok(
    withZero.body.data.paper.tables
      .flatMap((table) => table.rows)
      .some((row) => row.cells.some((cell) => cell.text === 'Zero marker')),
  )
  await paperValue('Zero marker', '0.00')
  await paperValue('Zero marker', '0,00', 'Saved template')
  await unchangedFiles()
  await screenshot('panel-with-zero-rows')
  pass('panel and zero-row controls use matching independent real preview contexts without changing numeric values')

  let baselineStart = responses.length
  await select('Preview panel', 'main')
  await baselineResponse(baselineStart, 'main', true)
  baselineStart = responses.length
  await toggle('Show zero rows', false)
  await baselineResponse(baselineStart, 'main')
  await source('schema.mantra')
  await fill('Draft source schema.mantra', runtimeSchema)
  const runtime = await preview(200)
  assert.equal(runtime.body.data.succeeded, false)
  assert.ok(runtime.body.data.diagnostics.some((item) => item.category === 'evaluation'))
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.includes('runtime failure')`,
    ),
    true,
  )
  const runtimeRow = runtime.body.data.paper.tables
    .flatMap((table) => table.rows)
    .find((row) => row.cells.some((cell) => cell.text === 'Answer'))
  assert.ok(runtimeRow, 'A runtime-failure Paper retains the failed source row')
  assert.deepEqual(
    await evaluate(
      `(() => { const row = [...document.querySelectorAll('[aria-label="Draft preview"] tbody tr')].find(item => item.textContent.includes('Answer')); return [...row.querySelectorAll('td')].map(cell => cell.textContent.trim()); })()`,
    ),
    runtimeRow.cells.map((cell) => cell.text),
    'The current runtime-failure Paper retains actual engine text rather than saved values',
  )
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  await screenshot('runtime-with-saved-template')
  pass('runtime failures retain current engine failure text while the saved template remains independent')

  baselineStart = responses.length
  await select('Preview panel', '')
  await baselineResponse(baselineStart)
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Previous engine preview')`,
    ),
    true,
    'A runtime result becomes previous when its panel context changes',
  )
  await fill('Draft source schema.mantra', candidateSchema)
  await source('layout.mantra')
  await fill('Draft source layout.mantra', emptyLayout)
  const empty = await preview(200)
  assert.equal(empty.body.data.succeeded, true)
  assert.equal(empty.body.data.paper.tables.flatMap((table) => table.rows).length, 0)
  assert.equal(
    await evaluate(`document.querySelectorAll('[aria-label="Draft preview"] .paper-table tbody tr').length`),
    0,
    'An empty engine Paper stays empty instead of copying saved or previous rows',
  )
  assert.equal(
    await evaluate(
      `document.querySelector('[aria-label="Draft preview"]').textContent.includes('No visible rows in this preview.')`,
    ),
    true,
    'An empty result has an explicit explanation without substituting saved rows',
  )
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  await screenshot('empty-draft-with-saved-template')
  pass('an empty candidate Paper does not fall back to the saved template or a previous nonempty result')

  await fill('Draft source layout.mantra', candidateLayout)
  await preview(200)
  await paperAnswer('3.75')
  await source('schema.mantra')
  await fill('Draft source schema.mantra', invalidSchema)
  const rejected = await preview(422)
  assert.ok(rejected.body.error.diagnostics.some((item) => item.location?.document === 'schema.mantra'))
  await until(() => evaluate(`!!document.querySelector('[role="alert"]')`), 'technical draft diagnostic')
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), invalidSchema)
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1.25')
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid="template-preview-status"]').textContent.startsWith('Previous engine preview')`,
    ),
    true,
  )
  await paperAnswer('3.75')
  await unchangedFiles()
  await savedAnswer('20')
  await paperValue('Answer', '20,00', 'Saved template')
  await screenshot('invalid-retains-paper')
  pass('HTTP 422 retains invalid source, raw input and the previous real Paper')

  await fill('Draft source schema.mantra', candidateSchema)
  await preview(200)
  const external = Buffer.from(`${layout}\n; external source change\n`)
  await writeFile(join(workspace, 'layout.mantra'), external)
  expectedFiles.set('layout.mantra', external)
  externalChanges.push('layout.mantra')
  await preview(409)
  await until(() => evaluate(`document.body.innerText.includes('Your draft is retained')`), 'external-change conflict')
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), candidateSchema)
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1.25')
  assert.equal(await evaluate(`${button('Preview draft')}.disabled`), true)
  await paperAnswer('3.75')
  await source('layout.mantra')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), candidateLayout)
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('conflict-retains-draft')
  pass('HTTP 409 retains both source drafts, raw input and Paper without writing files')

  await click('Reload saved source')
  await source('layout.mantra')
  await until(
    () =>
      evaluate(
        `!!${field('Draft source layout.mantra')} && ${field('Draft source layout.mantra')}.value === ${JSON.stringify(external.toString('utf8'))}`,
      ),
    'explicit fresh saved-source capture',
  )
  await source('schema.mantra')
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), schema)
  await until(
    () =>
      evaluate(
        `document.querySelector('[data-testid="template-baseline-status"]').textContent.startsWith('Saved-template preview')`,
      ),
    'independent saved-template preview after source reload',
  )
  const reloaded = await preview(200)
  assert.notEqual(reloaded.body.revision, initial.body.revision)
  assert.equal(answer(reloaded.body), '20')
  assert.equal(reloaded.body.data.paper.title, 'Saved title')
  assert.deepEqual(reloaded.request.inputs, [])
  await paperAnswer('20,00')
  await unchangedFiles()
  pass('explicit reload discards the in-memory draft and captures the external revision')

  await source('layout.mantra')
  await fill('Draft source layout.mantra', layout.replace('Saved title', 'Title only draft'))
  const titleOnly = await preview(200)
  assert.equal(answer(titleOnly.body), '20')
  assert.notEqual(titleOnly.body.data.proposedRevision, titleOnly.body.revision)
  assert.equal(titleOnly.body.data.paper.title, 'Title only draft')
  assert.equal(
    await evaluate(`document.querySelector('[aria-label="Draft preview"]').innerText.includes('Title only draft')`),
    true,
    'The preview renders the candidate Paper title, not just the title text in the source editor',
  )
  assert.deepEqual(titleOnly.body.data.difference.mainline, [])
  assert.deepEqual(titleOnly.body.data.difference.changes, [])
  assert.deepEqual(titleOnly.body.data.difference.parameterChanges, [])
  await until(
    () =>
      evaluate(
        `document.querySelector('[aria-label="Draft comparison"]').textContent.includes('No value changes reported for this draft.')`,
      ),
    'source-only edit reported as no value changes',
  )
  await paperValue('Answer', '20,00', 'Saved template')
  await paperAnswer('20,00')
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('source-only-change')
  assert.deepEqual(browserErrors, [], 'No uncaught browser exceptions')
  pass('a source-only title change reports no value changes while keeping distinct draft and saved titles')
} catch (error) {
  failures.push(error)
} finally {
  closing = true
  session?.close()
  for (const child of owned) stop(child)
  const cleanup = await Promise.allSettled(owned.map(stopped))
  for (const result of cleanup) if (result.status === 'rejected') failures.push(result.reason)
  if (directory) {
    try {
      await rm(directory, { recursive: true, force: true })
      assert.equal(existsSync(directory), false, 'Temporary UI, workspace and browser profile were removed')
    } catch (error) {
      failures.push(error)
    }
  }
  await mkdir(artifacts, { recursive: true })
  await writeFile(
    join(artifacts, 'report.json'),
    `${JSON.stringify(
      {
        format: 'mantra.template-preview-browser/1',
        status: failures.length ? 'FAILED' : 'VERIFIED',
        scenarios: passed,
        screenshots,
        responses: responses.map(({ status, request, body }) => ({
          status,
          draftSequence: request.draftSequence,
          documents: request.documents.length,
          inputs: request.inputs,
          panel: request.panel ?? null,
          includeZero: request.includeZero,
          explain: request.explain ?? null,
          baseRevision: request.baseRevision,
          proposedRevision: body.data?.proposedRevision ?? null,
        })),
        settingsResponses: settingsResponses.map(({ status, request, body }) => ({
          status,
          draftSequence: request.draftSequence,
          baseRevision: request.baseRevision,
          handle: request.handle,
          submittedSourceSha256: sha256(Buffer.from(request.text)),
          operations: request.operations,
          source: body.data?.source ?? null,
          properties: body.data?.properties ?? null,
          patches: body.data?.patches ?? null,
          error: body.error ?? null,
        })),
        sourceByteChecks: filesVerified,
        cancelledPreviewRequests: cancelledRequests,
        cancelledSettingsRequests,
        originalSources: Object.fromEntries([...originals].map(([name, bytes]) => [name, sha256(bytes)])),
        authorizedExternalChanges: externalChanges,
        temporaryDirectoryRemoved: !directory || !existsSync(directory),
        taskProcessGroupsStopped: cleanup.every((result) => result.status === 'fulfilled'),
        failures: failures.map((error) => error.message),
      },
      null,
      2,
    )}\n`,
  )
}
if (failures.length === 1) throw failures[0]
if (failures.length > 1) throw new AggregateError(failures, 'Template preview browser verification and cleanup failed')
console.log(
  `Template preview browser verification passed: ${passed.length} scenarios; task processes and temporary resources cleaned`,
)
