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
    (check positive "Nonnegative" (>= base-amount 0))))`
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
const expectedFiles = new Map(originals)
const owned = []
const failures = []
const passed = []
const responses = []
const browserErrors = []
const network = new Map()
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
    if (response.method === 'Network.requestWillBeSent' && params.request.url.endsWith('/template-preview')) {
      // Keep only JSON body evidence, never session-token request headers.
      network.set(params.requestId, { request: JSON.parse(params.request.postData) })
    }
    if (response.method === 'Network.responseReceived' && network.has(params.requestId)) {
      network.get(params.requestId).status = params.response.status
    }
    if (response.method === 'Network.loadingFinished' && network.has(params.requestId)) {
      const captured = network.get(params.requestId)
      network.delete(params.requestId)
      void session
        .send('Network.getResponseBody', { requestId: params.requestId })
        .then(({ body, base64Encoded }) => {
          captured.body = JSON.parse(base64Encoded ? Buffer.from(body, 'base64').toString('utf8') : body)
          responses.push(captured)
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
  await evaluate(`(() => {
    const select = document.querySelector('.template-preview-source select');
    const option = [...select.options].find(item => item.textContent.startsWith(${JSON.stringify(`${name} ·`)}));
    if (!option) throw new Error('Missing captured source document');
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  await until(() => evaluate(`!!${field(`Draft source ${name}`)}`), `selected source ${name}`)
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

const answer = (response) => response.data.run.values.answer[''].value.n

async function paperAnswer(expected) {
  await until(
    () =>
      evaluate(`(() => {
      const row = [...document.querySelectorAll('.paper-table tbody tr')].find(item => item.textContent.includes('Answer'));
      return row && [...row.querySelectorAll('td')].some(cell => cell.textContent.trim() === ${JSON.stringify(expected)});
    })()`),
    `engine Paper Answer ${expected}`,
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
  const result = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  await writeFile(join(artifacts, `${name}.png`), Buffer.from(result.data, 'base64'))
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
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('initial')
  pass('fresh live UI renders a real saved-template Paper with unchanged files')

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

  await evaluate(`(() => {
    const row = [...document.querySelectorAll('.paper-table tbody tr')].find(item => item.textContent.includes('Answer'));
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
  await until(
    () =>
      evaluate(
        `!!${field('Draft source layout.mantra')} && ${field('Draft source layout.mantra')}.value === ${JSON.stringify(external.toString('utf8'))}`,
      ),
    'explicit fresh saved-source capture',
  )
  await source('schema.mantra')
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), schema)
  const reloaded = await preview(200)
  assert.notEqual(reloaded.body.revision, initial.body.revision)
  assert.equal(answer(reloaded.body), '20')
  assert.equal(reloaded.body.data.paper.title, 'Saved title')
  assert.deepEqual(reloaded.request.inputs, [])
  await paperAnswer('20,00')
  await unchangedFiles()
  assert.deepEqual(browserErrors, [], 'No uncaught browser exceptions')
  pass('explicit reload discards the in-memory draft and captures the external revision')
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
        responses: responses.map(({ status, request, body }) => ({
          status,
          draftSequence: request.draftSequence,
          documents: request.documents.length,
          inputs: request.inputs,
          explain: request.explain ?? null,
          baseRevision: request.baseRevision,
          proposedRevision: body.data?.proposedRevision ?? null,
        })),
        sourceByteChecks: filesVerified,
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
