import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
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
const sourceResponses = []
const recoveryContextResponses = []
const settingsResponses = []
const styleResponses = []
const schemaPropertyResponses = []
const screenshots = []
const recoveryEvidence = []
const browserErrors = []
const network = new Map()
const pausedResponses = []
const cancelledRequests = []
const cancelledSourceRequests = []
const cancelledRecoveryContextRequests = []
const cancelledSettingsRequests = []
const cancelledStyleRequests = []
const externalChanges = []
let directory, workspace, service, browser, session, baseUrl, workspaceId
let interrupted = false
let closing = false
let filesVerified = 0
let failurePage
let downloadSequence = 0
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
      ((params.request.method === 'GET' &&
        (params.request.url.endsWith('/template-sources') ||
          params.request.url.endsWith('/template-recovery-context'))) ||
        (params.request.method === 'POST' &&
          (params.request.url.endsWith('/template-preview') ||
            params.request.url.endsWith('/template-layout-settings') ||
            params.request.url.endsWith('/template-style-classes') ||
            params.request.url.endsWith('/template-schema-properties'))))
    ) {
      // Keep only JSON body evidence, never session-token request headers.
      network.set(params.requestId, {
        request: params.request.method === 'GET' ? null : JSON.parse(params.request.postData),
        sources: params.request.url.endsWith('/template-sources'),
        recoveryContext: params.request.url.endsWith('/template-recovery-context'),
        settings: params.request.url.endsWith('/template-layout-settings'),
        styles: params.request.url.endsWith('/template-style-classes'),
        schemaProperties: params.request.url.endsWith('/template-schema-properties'),
      })
    }
    if (response.method === 'Network.responseReceived' && network.has(params.requestId)) {
      network.get(params.requestId).status = params.response.status
    }
    if (response.method === 'Network.loadingFailed' && network.has(params.requestId)) {
      const captured = network.get(params.requestId)
      network.delete(params.requestId)
      const cancellations = captured.recoveryContext
        ? cancelledRecoveryContextRequests
        : captured.sources
          ? cancelledSourceRequests
          : captured.settings
            ? cancelledSettingsRequests
            : captured.styles
              ? cancelledStyleRequests
              : cancelledRequests
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
          ;(captured.recoveryContext
            ? recoveryContextResponses
            : captured.sources
              ? sourceResponses
              : captured.settings
                ? settingsResponses
                : captured.styles
                  ? styleResponses
                  : captured.schemaProperties
                    ? schemaPropertyResponses
                    : responses
          ).push(captured)
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
  // A reload may replace the select and its handles between browser protocol calls.
  // Keep selecting the current captured option until that same document's editor is committed.
  await until(
    () =>
      evaluate(`(() => {
    if (${button('Reload saved source')}?.disabled) return false;
    const select = document.querySelector('.template-preview-source select');
    const option = select && [...select.options].find(item => item.textContent.startsWith(${JSON.stringify(`${name} ·`)}));
    if (!option) return false;
    if (select.value === option.value && ${field(`Draft source ${name}`)}) return true;
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return false;
  })()`),
    `selected captured source ${name}`,
  )
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

async function styles(status, action = 'Refresh style classes') {
  const index = styleResponses.length
  await click(action)
  const captured = await until(() => styleResponses[index], `real template-style-classes HTTP ${status}`)
  assert.equal(captured.status, status, JSON.stringify(captured.body))
  if (status === 200)
    await until(() => evaluate(`!${button('Refresh style classes')}?.disabled`), 'settled style classes UI')
  if (status === 200) {
    assert.equal(captured.body.contract, 'mantra.workbench/4')
    assert.equal(captured.body.revision, captured.request.baseRevision)
    assert.equal(captured.body.data.draftSequence, captured.request.draftSequence)
    assert.equal(captured.body.data.source.handle, captured.request.handle)
    assert.deepEqual(captured.body.data.baseRevisions, captured.request.baseRevisions)
    assert.equal(captured.body.data.usageScope, 'submitted-layout')
    let reconstructed = captured.request.text
    for (const patch of captured.body.data.patches) {
      assert.equal(reconstructed.slice(patch.startOffset, patch.endOffset), patch.removedText)
      reconstructed = reconstructed.slice(0, patch.startOffset) + patch.text + reconstructed.slice(patch.endOffset)
    }
    assert.equal(reconstructed, captured.body.data.source.text, 'Style patches reconstruct the exact source')
    for (const patch of [...captured.body.data.patches].reverse())
      reconstructed =
        reconstructed.slice(0, patch.startOffset) +
        patch.removedText +
        reconstructed.slice(patch.startOffset + patch.text.length)
    assert.equal(reconstructed, captured.request.text, 'Inverse style patches restore the exact submitted source')
  }
  return captured
}

async function styleChoice(property, value) {
  const element = field(property === 'class' ? 'Style class' : `Style class ${property}`)
  await until(() => evaluate(`!!${element} && !${element}.disabled`), `editable style class ${property}`)
  await evaluate(`(() => {
    const select = ${element};
    if (![...select.options].some(option => option.value === ${JSON.stringify(value)})) throw new Error('Missing style class option');
    select.value = ${JSON.stringify(value)};
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  assert.equal(await evaluate(`${element}.value`), value)
  if (property === 'class')
    await until(
      () =>
        evaluate(
          `document.querySelector('.template-style-selected h3')?.textContent === ${JSON.stringify(`:${value}`)}`,
        ),
      `committed selection for style class ${value}`,
    )
}

async function paperAnswerStyle(captured, expected) {
  const row = captured.body.data.paper.tables.flatMap((table) => table.rows).find((item) => item.node === 'answer')
  assert.ok(row, 'Actual candidate Paper contains the answer row')
  const cell = row.cells.find((item) => item.address?.node === 'answer')
  assert.ok(cell, 'Actual candidate Paper contains an addressable answer value')
  for (const [property, value] of Object.entries(expected))
    assert.equal(cell.style[property], value, `Actual Paper answer ${property}`)
  const appearance = await evaluate(`(() => {
    const section = document.querySelector('[aria-label="Draft preview"]');
    const button = [...section.querySelectorAll('.paper-table button')].find(item => item.textContent.trim() === '20,00');
    if (!button) return null;
    const cell = button.closest('td');
    return { classes: cell.className, weight: cell.style.fontWeight, tone: cell.style.color, fill: cell.style.backgroundColor };
  })()`)
  assert.ok(appearance, 'The real browser rendered the answer value cell')
  if (expected.tone) assert.ok(appearance.classes.includes(`tone-${expected.tone}`))
  if (expected.fill) assert.ok(appearance.classes.includes(`fill-${expected.fill}`))
  if (expected.weight) assert.equal(appearance.weight, expected.weight === 'bold' ? '600' : '400')
  assert.equal(answer(captured.body), '20', 'Style editing preserves exact calculated values')
}

async function sourceHistory(action, name, expected) {
  await click(action)
  await until(
    () =>
      evaluate(
        `!!${field(`Draft source ${name}`)} && ${field(`Draft source ${name}`)}.value === ${JSON.stringify(expected)}`,
      ),
    `${action} restores exact ${name} and selects its source`,
  )
}

async function historyBlockedByFields() {
  await until(
    () => evaluate(`${button('Undo source')}.disabled && ${button('Redo source')}.disabled`),
    'unapplied fields disable both source history actions',
  )
  assert.equal(await evaluate(`${button('Undo source')}.disabled`), true)
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  const reason = await evaluate(`(() => {
    const button = ${button('Undo source')};
    return (button.getAttribute('aria-describedby') ?? '').split(' ').map(id => document.getElementById(id)?.textContent ?? '').join(' ');
  })()`)
  assert.match(reason, /apply|cancel|unapplied/i, 'Disabled history identifies the pending-field action required')
}

const recoveryNamespace = 'mantra.template-preview.recovery.v1.'
const recoveryKey = () => `${recoveryNamespace}${workspaceId}.${encodeURIComponent('case.mantra')}`

function plainSourceSnapshot(captured) {
  assert.equal(captured.status, 200, JSON.stringify(captured.body))
  assert.deepEqual(Object.keys(captured.body.data).sort(), ['baseRevisions', 'document', 'documents'])
  assert.equal(Object.hasOwn(captured.body.data, 'workspaceId'), false, 'The existing v4 source wire remains unchanged')
}

async function recoveryIdentity(start = 0) {
  const captured = await until(
    () => recoveryContextResponses.slice(start).find((item) => item.status === 200),
    'real optional session-bound recovery identity',
  )
  assert.equal(captured.body.contract, 'mantra.workbench/4')
  assert.deepEqual(Object.keys(captured.body.data), ['workspaceId'])
  assert.match(captured.body.data.workspaceId, /^[a-f0-9]{64}$/)
  assert.equal(captured.body.revision, captured.body.data.workspaceId)
  const canonicalIdentityBytes = Buffer.concat([
    Buffer.from('mantra.workspace/1'),
    Buffer.from([0]),
    Buffer.from(await realpath(workspace)),
  ])
  assert.equal(captured.body.data.workspaceId, sha256(canonicalIdentityBytes))
  return captured.body.data.workspaceId
}

async function openRecovery() {
  await evaluate(`(() => {
    const details = document.querySelector('[data-testid=template-recovery-panel]');
    if (!details) throw new Error('Missing recovery panel');
    if (!details.open) details.querySelector('summary').click();
  })()`)
  await until(
    () =>
      evaluate(
        `document.querySelector('[data-testid=template-recovery-panel]')?.open && ${button('Download draft backup')}?.getClientRects().length > 0`,
      ),
    'opened recovery and JSON backup panel',
  )
}

async function browserCopy() {
  return evaluate(`(() => {
    const raw = localStorage.getItem(${JSON.stringify(recoveryKey())});
    return raw === null ? null : { raw, backup: JSON.parse(raw) };
  })()`)
}

async function protectedCopy(predicate, label) {
  const copy = await until(async () => {
    const value = await browserCopy()
    return value && predicate(value.backup) ? value : undefined
  }, label)
  await until(
    () =>
      evaluate(`document.querySelector('[data-testid=template-recovery-status]')?.dataset.recoveryState === 'saved'`),
    'complete current browser copy confirmed by readback',
  )
  assert.equal(copy.backup.workspaceId, workspaceId)
  assert.equal(copy.backup.caseId, 'case.mantra')
  assert.notEqual(copy.backup.baseRevision, workspaceId, 'A recovery-context revision is never the source baseline')
  return copy
}

async function freshSourceCapture(action = 'Reload saved source') {
  const index = sourceResponses.length
  const identityIndex = recoveryContextResponses.length
  if (action === 'physical reload') await session.send('Page.reload', { ignoreCache: true })
  else await click(action)
  const captured = await until(() => sourceResponses[index], 'fresh real template source snapshot')
  plainSourceSnapshot(captured)
  assert.equal(await recoveryIdentity(identityIndex), workspaceId)
  assert.notEqual(captured.body.revision, workspaceId)
  await source('schema.mantra')
  const expected = captured.body.data.documents.find((item) => item.document === 'schema.mantra').text
  await until(
    () => evaluate(`${field('Draft source schema.mantra')}.value === ${JSON.stringify(expected)}`),
    'fresh source snapshot visible without automatic draft restoration',
  )
  await openRecovery()
  return captured
}

async function restoredWithoutEvidence() {
  await until(
    () =>
      evaluate(
        `document.querySelector('[data-testid=template-restored-status]')?.textContent.includes('Restored · not validated')`,
      ),
    'restored source remains unvalidated',
  )
  assert.equal(await evaluate(`!!document.querySelector('[aria-label="Draft preview"] .paper-table')`), false)
  assert.equal(
    await evaluate(
      `document.querySelector('[data-testid=template-preview-status]')?.textContent.startsWith('Current engine preview') ?? false`,
    ),
    false,
  )
  assert.equal(
    await evaluate(`${button('Undo source')}.disabled`),
    true,
    'Restoration does not trust an old source history',
  )
}

function backupDocument(backup, name) {
  const document = backup.documents.find((item) => item.document === name)
  assert.ok(document, `Backup contains root source ${name}`)
  return document
}

function inspectBackup(text, label) {
  const backup = JSON.parse(text)
  assert.deepEqual(
    Object.keys(backup).sort(),
    [
      'format',
      'savedAt',
      'workspaceId',
      'caseId',
      'baseRevision',
      'baseRevisions',
      'documents',
      'inputs',
      'pending',
    ].sort(),
  )
  assert.equal(backup.format, 'mantra.template-preview-recovery/1')
  assert.deepEqual(backup.documents.map((item) => item.role).sort(), ['layout', 'schema'])
  assert.ok(backup.documents.every((item) => typeof item.text === 'string' && typeof item.baseText === 'string'))
  assert.equal(new Date(backup.savedAt).toISOString(), backup.savedAt)
  recoveryEvidence.push({
    label,
    workspaceId: backup.workspaceId,
    caseId: backup.caseId,
    baseRevision: backup.baseRevision,
    bytes: Buffer.byteLength(text),
    inputs: backup.inputs,
    pending: backup.pending,
    documents: backup.documents.map((item) => ({
      document: item.document,
      role: item.role,
      sha256: item.sha256,
      baseTextSha256: sha256(Buffer.from(item.baseText)),
      textSha256: sha256(Buffer.from(item.text)),
    })),
    engineEvidenceExcluded: true,
  })
  return backup
}

async function downloadBackup(action, label) {
  await openRecovery()
  // Chrome may overwrite an existing suggested filename; isolate each actual download.
  const downloads = join(directory, 'downloads', String(++downloadSequence))
  await mkdir(downloads, { recursive: true })
  await session.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloads })
  const before = new Set(await readdir(downloads))
  await until(() => evaluate(`!!${button(action)} && !${button(action)}.disabled`), `enabled ${action}`)
  await evaluate(`${button(action)}.scrollIntoView({ block: 'center', inline: 'nearest' })`)
  const point = await evaluate(`(() => {
    const rect = ${button(action)}.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  })()`)
  await session.send('Input.dispatchMouseEvent', {
    type: 'mousePressed',
    ...point,
    button: 'left',
    buttons: 1,
    clickCount: 1,
  })
  await session.send('Input.dispatchMouseEvent', {
    type: 'mouseReleased',
    ...point,
    button: 'left',
    buttons: 0,
    clickCount: 1,
  })
  const name = await until(
    async () => (await readdir(downloads)).find((item) => item.endsWith('.json') && !before.has(item)),
    'real downloaded JSON backup',
  )
  const file = join(downloads, name)
  const text = await until(async () => {
    const value = await readFile(file, 'utf8')
    JSON.parse(value)
    return value
  }, 'complete downloaded backup bytes')
  return { file, text, backup: inspectBackup(text, label) }
}

async function importBackupFile(file) {
  await openRecovery()
  const raw = await readFile(file, 'utf8')
  const input = await session.send('Runtime.evaluate', {
    expression: field('Import draft backup file'),
    returnByValue: false,
  })
  assert.ok(input.result.objectId, 'The live page exposes a real backup file input')
  await session.send('DOM.setFileInputFiles', { objectId: input.result.objectId, files: [file] })
  await until(
    () =>
      evaluate(
        Buffer.byteLength(raw) > 2 * 1024 * 1024
          ? `document.querySelector('[data-testid=template-recovery-import-match]')?.textContent.includes('2 MiB limit')`
          : `${field('Draft backup JSON')}.value === ${JSON.stringify(raw)} && !!document.querySelector('[data-testid=template-recovery-import-match]') && !${button('Validate backup JSON')}.disabled`,
      ),
    'import file reviewed without restoration',
  )
}

async function rawExampleInput(value) {
  await until(() => evaluate(`!!${field('Example input base-amount')}`), 'editable raw example override')
  await evaluate(`(() => {
    const checkbox = ${field('Example input base-amount')}.closest('label').querySelector('input[type=checkbox]');
    if (!checkbox.checked) checkbox.click();
  })()`)
  await fill('Example input base-amount', value)
}

async function assertImportRejected(reason) {
  await until(async () => {
    const state = await evaluate(`({
        disabled: ${button('Restore imported draft')}?.disabled === true,
        reason: document.querySelector('[data-testid=template-recovery-import-match]')?.textContent ?? '',
      })`)
    return state.disabled && (!reason || reason.test(state.reason))
  }, 'invalid imported bytes cannot replace the current draft')
  assert.equal(await evaluate(`${button('Restore imported draft')}.disabled`), true)
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
  const firstSources = await until(() => sourceResponses[0], 'actual initial template source response')
  plainSourceSnapshot(firstSources)
  workspaceId = await recoveryIdentity()
  assert.notEqual(firstSources.body.revision, workspaceId)
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

  // Source owner edits must go through the real API and the same in-memory source history.
  await evaluate(`document.querySelector('.template-schema-properties-disclosure > summary').click()`)
  let propertyIndex = schemaPropertyResponses.length
  await click('Inspect schema properties')
  const propertyCatalog = await until(() => schemaPropertyResponses[propertyIndex], 'real schema property catalog')
  assert.equal(propertyCatalog.status, 200)
  assert.ok(propertyCatalog.body.data.owners.some((item) => item.node === 'zero-marker'))
  await until(() => evaluate(`!!${field('Schema property')}`), 'schema property selector')
  await evaluate(`(() => {
    const select = ${field('Schema property')};
    const option = [...select.options].find(item => item.textContent.includes('info answer · label'));
    if (!option) throw new Error('Missing real answer label owner');
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`)
  const beforeNavigation = { previews: responses.length, properties: schemaPropertyResponses.length }
  await fill('Find a declaration', 'Zero marker')
  await until(
    () =>
      evaluate(
        `!![...document.querySelectorAll('.schema-presentation-outline button')].find(item => item.getAttribute('aria-label').startsWith('Select classes of info zero-marker '))`,
      ),
    'zero declaration remains in the source outline',
  )
  assert.equal(
    await evaluate(`document.querySelector('.schema-presentation-outline').textContent.includes('section details')`),
    true,
  )
  await evaluate(
    `[...document.querySelectorAll('.schema-presentation-outline button')].find(item => item.getAttribute('aria-label').startsWith('Select classes of info zero-marker ')).click()`,
  )
  await click('Show property in source')
  assert.equal(
    await evaluate(
      `(() => { const editor = ${field('Draft source schema.mantra')}; return editor.value.slice(editor.selectionStart, editor.selectionEnd); })()`,
    ),
    '(info zero-marker "Zero marker" 0)',
  )
  assert.equal(await evaluate(`document.activeElement === ${field('Draft source schema.mantra')}`), true)
  await fill('Find a declaration', 'Answer')
  await evaluate(
    `[...document.querySelectorAll('.schema-presentation-outline button')].find(item => item.getAttribute('aria-label').startsWith('Select label of info answer ')).click()`,
  )
  await click('Show property in source')
  assert.equal(
    await evaluate(
      `(() => { const editor = ${field('Draft source schema.mantra')}; return editor.value.slice(editor.selectionStart, editor.selectionEnd); })()`,
    ),
    '"Answer"',
  )
  assert.deepEqual({ previews: responses.length, properties: schemaPropertyResponses.length }, beforeNavigation)
  assert.equal(await evaluate(`${button('Undo source')}.disabled`), true)
  await screenshot('schema-presentation-outline')
  pass(
    'real declaration outline finds zero rows with parent context and locates exact source without calculation or history',
  )
  await fill('Declared text', 'Answer 中文😀')
  await historyBlockedByFields()
  assert.equal(await evaluate(`${button('Preview draft')}.disabled`), true)
  await protectedCopy((copy) => copy.pending.schemaProperty?.text === 'Answer 中文😀', 'schema fields protected')
  propertyIndex = schemaPropertyResponses.length
  await click('Apply schema property')
  const propertyEdit = await until(() => schemaPropertyResponses[propertyIndex], 'real schema label source edit')
  assert.equal(propertyEdit.status, 200)
  assert.equal(propertyEdit.request.operations[0].op, 'setText')
  assert.equal(propertyEdit.body.data.patches[0].removedText, '"Answer"')
  const schemaLabelDraft = schema.replace('"Answer"', '"Answer 中文😀"')
  await until(
    () => evaluate(`${field('Draft source schema.mantra')}.value === ${JSON.stringify(schemaLabelDraft)}`),
    'schema label accepted',
  )
  await sourceHistory('Undo source', 'schema.mantra', schema)
  await sourceHistory('Redo source', 'schema.mantra', schemaLabelDraft)
  const labelPreview = await preview(200)
  assert.equal(answer(labelPreview.body), '20')
  assert.ok(
    labelPreview.body.data.paper.tables.some((table) =>
      table.rows.some((row) => row.cells.some((cell) => cell.text === 'Answer 中文😀')),
    ),
  )
  await unchangedFiles()
  await screenshot('schema-property-label')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: false,
  })
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
  await screenshot('schema-property-narrow')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  })
  await sourceHistory('Undo source', 'schema.mantra', schema)
  await evaluate(`document.querySelector('.template-schema-properties-disclosure > summary').click()`)
  pass('real schema owner label Apply, exact source Undo/Redo, browser backup and unchanged engine values')

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
  await until(
    () => evaluate(`${button('Apply layout settings')}.disabled`),
    'incomplete precision disables layout Apply after its field state commits',
  )
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
  await freshSourceCapture()
  await source('layout.mantra')
  await toggle('Compare with saved template', false)
  await delay(100)
  const settingsRecovered = await preview(200)
  assert.equal(answer(settingsRecovered.body), '20')
  await unchangedFiles()
  await source('schema.mantra')
  pass('a settings source conflict keeps the draft and blocks calculation until an explicit baseline recapture')

  await source('layout.mantra')
  const classLayout = `(layout browser/layout {:title "Saved title" :locale "de-DE" :style-preset :working-paper}
  ; Keep this source comment while definitions change.
  (style-class :result {:tone :muted})
  (style {:all true} {:use :result}))`
  await fill('Draft source layout.mantra', classLayout)
  await evaluate(`document.querySelector('.template-style-classes-disclosure > summary').click()`)
  await until(
    () => evaluate(`document.querySelector('.template-style-classes-disclosure').open`),
    'style classes opened before using the real class catalog',
  )
  const classInspection = await styles(200, 'Inspect style classes')
  assert.deepEqual(classInspection.request.operations, [])
  assert.deepEqual(classInspection.body.data.stylePresets, ['working-paper'])
  const klass = (captured, name) => captured.body.data.classes.find((item) => item.name === name)
  assert.equal(classInspection.body.data.classes.length, 8)
  assert.deepEqual(klass(classInspection, 'result').presets, ['working-paper'])
  assert.deepEqual(klass(classInspection, 'result').local, { weight: null, tone: 'muted', fill: null })
  assert.deepEqual(klass(classInspection, 'result').inherited, { weight: 'bold', tone: 'accent', fill: 'accent' })
  assert.deepEqual(klass(classInspection, 'result').effective, { weight: 'bold', tone: 'muted', fill: 'accent' })
  assert.equal(klass(classInspection, 'result').references[0].kind, 'use')
  const presetOverridePreview = await preview(200)
  await paperAnswerStyle(presetOverridePreview, { weight: 'bold', tone: 'muted', fill: 'accent' })
  await unchangedFiles()
  pass('real style catalog distinguishes preset contribution from the local override and actual Paper style')

  await click('Create local style class')
  await fill('Style class name', 'custom')
  await styleChoice('weight', 'bold')
  await styleChoice('tone', 'accent')
  await styleChoice('fill', 'subtle')
  await until(() => evaluate(`${button('Preview draft')}.disabled`), 'unapplied class fields block preview')
  assert.equal(await evaluate(`${button('Preview draft')}.disabled`), true)
  const classRequestsBeforeIme = styleResponses.length
  await evaluate(`(() => {
    const input = ${field('Style class name')};
    input.focus();
    input.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: '' }));
  })()`)
  await until(() => evaluate(`${button('Apply style changes')}.disabled`), 'class composition remains buffered')
  const classIme = await evaluate(`(() => {
    const input = ${field('Style class name')};
    const key = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, isComposing: true, bubbles: true, cancelable: true });
    const accepted = input.dispatchEvent(key);
    const state = { accepted, prevented: key.defaultPrevented, applying: ${button('Apply style changes')}.disabled,
      source: ${field('Draft source layout.mantra')}.value };
    input.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '类' }));
    input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
    return state;
  })()`)
  assert.equal(classIme.accepted, true)
  assert.equal(classIme.prevented, false, 'Class editing preserves native IME candidate confirmation')
  assert.equal(classIme.applying, true, 'Composition does not submit a class definition')
  assert.equal(classIme.source, classLayout)
  assert.equal(styleResponses.length, classRequestsBeforeIme)
  await click('Cancel style changes')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), classLayout)
  await until(() => evaluate(`!${button('Preview draft')}.disabled`), 'canceled style fields release preview')
  await unchangedFiles()
  pass('unapplied class fields and synthetic IME stay buffered while Cancel preserves the source')

  await click('Create local style class')
  await fill('Style class name', 'custom')
  await styleChoice('weight', 'bold')
  await styleChoice('tone', 'accent')
  await styleChoice('fill', 'subtle')
  const createdClass = await styles(200, 'Apply style changes')
  assert.deepEqual(createdClass.request.operations, [
    { op: 'upsert', name: 'custom', style: { weight: 'bold', tone: 'accent', fill: 'subtle' } },
  ])
  assert.deepEqual(klass(createdClass, 'custom').local, { weight: 'bold', tone: 'accent', fill: 'subtle' })
  assert.ok(createdClass.body.data.source.text.includes('; Keep this source comment while definitions change.'))
  const usedClassSource = createdClass.body.data.source.text.replace('{:use :result}', '{:use :custom}')
  await fill('Draft source layout.mantra', usedClassSource)
  const usedClass = await styles(200)
  await styleChoice('class', 'custom')
  assert.equal(klass(usedClass, 'custom').canRemove, false)
  assert.ok(klass(usedClass, 'custom').removeReason)
  assert.equal(await evaluate(`${button('Remove local style class')}.disabled`), true)
  const customPreview = await preview(200)
  await paperAnswerStyle(customPreview, { weight: 'bold', tone: 'accent', fill: 'subtle' })
  await unchangedFiles()
  await savedAnswer('20')
  await screenshot('style-class-applied')
  pass('creating a local class changes real Paper styling without changing values and protects hard use references')

  await styleChoice('weight', 'normal')
  await styleChoice('tone', 'default')
  await styleChoice('fill', '')
  const omittedFill = await styles(200, 'Apply style changes')
  assert.deepEqual(klass(omittedFill, 'custom').local, { weight: 'normal', tone: 'default', fill: null })
  assert.deepEqual(omittedFill.request.operations[0].style, { weight: 'normal', tone: 'default', fill: null })
  await styleChoice('fill', 'none')
  const explicitNone = await styles(200, 'Apply style changes')
  assert.deepEqual(klass(explicitNone, 'custom').local, { weight: 'normal', tone: 'default', fill: 'none' })
  const explicitNonePreview = await preview(200)
  await paperAnswerStyle(explicitNonePreview, { weight: 'normal', tone: 'default', fill: 'none' })
  await unchangedFiles()
  pass('omitting a class property stays distinct from explicit normal default and none declarations')

  const unlinkedClassSource = explicitNone.body.data.source.text.replace('{:use :custom}', '{:use :result}')
  await fill('Draft source layout.mantra', unlinkedClassSource)
  const unlinkedClass = await styles(200)
  await styleChoice('class', 'custom')
  assert.equal(klass(unlinkedClass, 'custom').canRemove, true)
  await click('Remove local style class')
  const removedClass = await styles(200, 'Apply style changes')
  assert.deepEqual(removedClass.request.operations, [{ op: 'remove', name: 'custom' }])
  assert.equal(klass(removedClass, 'custom'), undefined)
  assert.ok(removedClass.body.data.source.text.includes('; Keep this source comment while definitions change.'))
  await styleChoice('class', 'result')
  assert.equal(klass(removedClass, 'result').canRemove, true, 'The selected preset provides a same-name fallback')
  await click('Remove local style class')
  const removedOverride = await styles(200, 'Apply style changes')
  assert.equal(klass(removedOverride, 'result').local, null)
  assert.deepEqual(klass(removedOverride, 'result').effective, { weight: 'bold', tone: 'accent', fill: 'accent' })
  const fallbackPreview = await preview(200)
  await paperAnswerStyle(fallbackPreview, { weight: 'bold', tone: 'accent', fill: 'accent' })
  await unchangedFiles()
  pass('removing unused local definitions preserves comments and removing a preset override restores its contribution')

  await styleChoice('class', 'result')
  await click('Create local override')
  await styleChoice('tone', 'muted')
  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-style-classes', requestStage: 'Response' }],
  })
  await click('Apply style changes')
  const heldClassPatch = await until(() => pausedResponses.shift(), 'real class patch held at the browser boundary')
  assert.equal(heldClassPatch.status, 200)
  const newerClassSource = `${removedOverride.body.data.source.text}\n; Manual source wins over a late class patch.\n`
  await fill('Draft source layout.mantra', newerClassSource)
  await releaseHeld(heldClassPatch)
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), newerClassSource)
  assert.equal(await evaluate(`${field('Style class tone')}.value`), 'muted')
  assert.equal(await evaluate(`${button('Preview draft')}.disabled`), true)
  await unchangedFiles()
  pass('a late class patch cannot overwrite manual source or clear its retained field buffer')

  await evaluate(`(() => {
    const details = document.querySelector('.template-layout-settings-disclosure');
    if (!details.open) details.querySelector('summary').click();
  })()`)
  await settings(200, 'Inspect layout settings')
  await fill('Layout setting title', 'Cross-panel draft')
  await settings(200, 'Apply layout settings')
  assert.equal(
    await evaluate(`${button('Preview draft')}.disabled`),
    true,
    'Root settings cannot dismiss class buffers',
  )
  const refreshedClass = await styles(200)
  assert.ok(refreshedClass.body.data.source.text.includes('Cross-panel draft'))
  assert.equal(await evaluate(`${field('Style class tone')}.value`), 'muted')
  const coordinatedClass = await styles(200, 'Apply style changes')
  assert.equal(klass(coordinatedClass, 'result').local.tone, 'muted')
  const crossPanelPreview = await preview(200)
  assert.equal(crossPanelPreview.body.data.paper.title, 'Cross-panel draft')
  await paperAnswerStyle(crossPanelPreview, { weight: 'bold', tone: 'muted', fill: 'accent' })
  await unchangedFiles()
  pass('root settings and class edits keep independent unapplied buffers and coordinate through the same source')

  await fill('Draft source layout.mantra', layout)
  await evaluate(`(() => {
    for (const details of document.querySelectorAll('.template-layout-settings-disclosure, .template-style-classes-disclosure'))
      if (details.open) details.querySelector('summary').click();
  })()`)
  await source('schema.mantra')

  await freshSourceCapture()
  const historyInitial = await preview(200)
  assert.equal(answer(historyInitial.body), '20')
  assert.equal(await evaluate(`${button('Undo source')}.disabled`), true)
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  const historyBaselineStart = responses.length
  await toggle('Compare with saved template', true)
  await baselineResponse(historyBaselineStart)
  await evaluate(
    `${field('Example input base-amount')}.closest('label').querySelector('input[type="checkbox"]').click()`,
  )
  await fill('Example input base-amount', '1,25')
  const historyInput = await preview(200)
  assert.equal(answer(historyInput.body), '2.50')
  await fill('Draft source schema.mantra', candidateSchema)
  await source('layout.mantra')
  const historyManualLayout = `${layout}\n; Manual layout transaction for shared source history.\n`
  await fill('Draft source layout.mantra', historyManualLayout)
  await evaluate(`document.querySelector('.template-layout-settings-disclosure > summary').click()`)
  await settings(200, 'Inspect layout settings')
  await fill('Layout setting title', 'History title')
  const historySetting = await settings(200, 'Apply layout settings')
  const historySettingLayout = historySetting.body.data.source.text
  await evaluate(`document.querySelector('.template-style-classes-disclosure > summary').click()`)
  await styles(200, 'Inspect style classes')
  await click('Create local style class')
  await fill('Style class name', 'history-local')
  await styleChoice('weight', 'bold')
  const historyClass = await styles(200, 'Apply style changes')
  const historyClassLayout = historyClass.body.data.source.text
  const beforeHistoryMoves = {
    preview: responses.length,
    settings: settingsResponses.length,
    styles: styleResponses.length,
  }
  await sourceHistory('Undo source', 'layout.mantra', historySettingLayout)
  await sourceHistory('Undo source', 'layout.mantra', historyManualLayout)
  await sourceHistory('Undo source', 'layout.mantra', layout)
  await sourceHistory('Undo source', 'schema.mantra', schema)
  assert.equal(await evaluate(`${button('Undo source')}.disabled`), true, 'All four source transactions were reversed')
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1,25')
  await paperValue('Answer', '20,00', 'Saved template')
  const undoneCandidate = await preview(200)
  assert.equal(answer(undoneCandidate.body), '2.50')
  await sourceHistory('Redo source', 'schema.mantra', candidateSchema)
  await sourceHistory('Redo source', 'layout.mantra', historyManualLayout)
  await sourceHistory('Redo source', 'layout.mantra', historySettingLayout)
  await sourceHistory('Redo source', 'layout.mantra', historyClassLayout)
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1,25')
  assert.deepEqual(
    { preview: responses.length, settings: settingsResponses.length, styles: styleResponses.length },
    { ...beforeHistoryMoves, preview: beforeHistoryMoves.preview + 1 },
    'Undo and Redo issue no preview, settings, style or baseline requests',
  )
  const redoneCandidate = await preview(200)
  assert.equal(answer(redoneCandidate.body), '3.75')
  assert.equal(redoneCandidate.body.data.paper.title, 'History title')
  assert.deepEqual(redoneCandidate.request.inputs, [{ node: 'base-amount', text: '1,25' }])
  await paperAnswer('3,75')
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  pass(
    'manual schema layout settings and class edits share exact Undo Redo while inputs and saved baseline stay independent',
  )

  await settings(200)
  await fill('Layout setting title', 'Unapplied history setting')
  await historyBlockedByFields()
  await click('Cancel setting changes')
  await until(() => evaluate(`!${button('Undo source')}.disabled`), 'canceling settings releases source history')
  await styles(200)
  await styleChoice('class', 'history-local')
  await styleChoice('weight', 'normal')
  await historyBlockedByFields()
  await click('Cancel style changes')
  await until(() => evaluate(`!${button('Undo source')}.disabled`), 'canceling class fields releases source history')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), historyClassLayout)
  await unchangedFiles()
  pass('both unapplied property panels disable source history with a linked Apply or Cancel reason')

  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-preview', requestStage: 'Response' }],
  })
  await click('Preview draft')
  const heldHistoryPreview = await until(() => pausedResponses.shift(), 'real preview held before source Undo')
  assert.equal(heldHistoryPreview.status, 200)
  await sourceHistory('Undo source', 'layout.mantra', historySettingLayout)
  await releaseHeld(heldHistoryPreview)
  const previousHistoryStatus = await evaluate(
    `document.querySelector('[data-testid="template-preview-status"]').textContent`,
  )
  assert.ok(previousHistoryStatus.startsWith('Previous engine preview'))
  assert.ok(previousHistoryStatus.includes(`draft #${redoneCandidate.body.data.draftSequence}`))
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1,25')
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  pass('source Undo cancels an actual delayed engine response and keeps the earlier candidate explicitly previous')

  assert.equal(await evaluate(`${button('Redo source')}.disabled`), false)
  const historyNewBranch = `${historySettingLayout}\n; New edit replaces the old redo branch.\n`
  await fill('Draft source layout.mantra', historyNewBranch)
  await until(() => evaluate(`${button('Redo source')}.disabled`), 'new source text replaces the old redo branch')
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  await sourceHistory('Undo source', 'layout.mantra', historySettingLayout)
  await sourceHistory('Redo source', 'layout.mantra', historyNewBranch)
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  assert.equal(historyNewBranch.includes('(style-class :history-local'), false)
  assert.equal(
    await evaluate(`${field('Draft source layout.mantra')}.value.includes('(style-class :history-local')`),
    false,
  )
  await unchangedFiles()
  pass('a new source edit after Undo replaces the old redo branch instead of restoring the discarded class edit')

  await evaluate(`(() => {
    for (const details of document.querySelectorAll('.template-layout-settings-disclosure, .template-style-classes-disclosure'))
      if (details.open) details.querySelector('summary').click();
  })()`)
  await screenshot('source-history-branch')
  await toggle('Compare with saved template', false)
  await freshSourceCapture()
  const historyRecaptured = await preview(200)
  assert.equal(answer(historyRecaptured.body), '20')
  assert.equal(await evaluate(`${button('Undo source')}.disabled`), true)
  assert.equal(await evaluate(`${button('Redo source')}.disabled`), true)
  assert.equal(
    await evaluate(
      `${field('Example input base-amount')}.closest('label').querySelector('input[type="checkbox"]').checked`,
    ),
    false,
  )
  await unchangedFiles()
  pass('explicit saved-source recapture clears local source history and input overrides without writing the workspace')

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

  await freshSourceCapture()
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

  await toggle('Compare with saved template', false)
  await freshSourceCapture()
  await until(
    () => evaluate(`localStorage.getItem(${JSON.stringify(recoveryKey())}) === null`),
    'explicit recapture discards this browser draft',
  )
  await preview(200)
  const recoveryBaseLayout = expectedFiles.get('layout.mantra').toString('utf8')
  const recoveryLayout = recoveryBaseLayout.replace('Saved title', 'Recovered draft title')
  await fill('Draft source schema.mantra', candidateSchema)
  await source('layout.mantra')
  await fill('Draft source layout.mantra', recoveryLayout)
  await rawExampleInput('1,25')
  const protectedSource = await protectedCopy(
    (backup) =>
      backupDocument(backup, 'schema.mantra').text === candidateSchema &&
      backupDocument(backup, 'layout.mantra').text === recoveryLayout &&
      backup.inputs[0]?.text === '1,25',
    'source and raw input automatically protected together',
  )
  inspectBackup(protectedSource.raw, 'browser copy before physical reload')
  await freshSourceCapture('physical reload')
  await until(
    () => evaluate(`!${button('Restore browser draft')}.disabled`),
    'compatible browser recovery offered explicitly',
  )
  assert.equal(await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`), false)
  assert.equal(
    await evaluate(`${field('Draft source schema.mantra')}.value`),
    schema,
    'Finding a browser copy does not apply it',
  )
  assert.equal(
    (await browserCopy()).raw,
    protectedSource.raw,
    'Clean initialization did not overwrite the found source',
  )
  await click('Restore browser draft')
  await restoredWithoutEvidence()
  await source('layout.mantra')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), recoveryLayout)
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), '1,25')
  const recoveryBaselineStart = responses.length
  await toggle('Compare with saved template', true)
  await baselineResponse(recoveryBaselineStart)
  assert.equal(
    await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`),
    true,
    'A saved baseline cannot validate the restored candidate',
  )
  await screenshot('recovery-unvalidated')
  const recoveredSourcePreview = await preview(200)
  assert.equal(answer(recoveredSourcePreview.body), '3.75')
  assert.equal(recoveredSourcePreview.body.data.paper.title, 'Recovered draft title')
  assert.deepEqual(recoveredSourcePreview.request.inputs, [{ node: 'base-amount', text: '1,25' }])
  await until(
    () => evaluate(`!document.querySelector('[data-testid=template-restored-status]')`),
    'matching candidate validates the restored source',
  )
  await paperValue('Answer', '20,00', 'Saved template')
  await unchangedFiles()
  pass(
    'physical reload offers exact source and raw input recovery without restoring engine evidence or trusting a baseline',
  )

  await toggle('Compare with saved template', false)
  await freshSourceCapture()
  await preview(200)
  await fill('Draft source schema.mantra', invalidSchema)
  await rawExampleInput('nine')
  await protectedCopy(
    (backup) => backupDocument(backup, 'schema.mantra').text === invalidSchema && backup.inputs[0]?.text === 'nine',
    'technically invalid source and input remain backupable',
  )
  await freshSourceCapture('physical reload')
  await click('Restore browser draft')
  await restoredWithoutEvidence()
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), 'nine')
  assert.equal(
    await evaluate(`${field('Example input base-amount')}.disabled`),
    false,
    'An unchecked restored input remains repairable without Structure',
  )
  await preview(422)
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), invalidSchema)
  assert.equal(await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`), true)
  await fill('Draft source schema.mantra', runtimeSchema)
  await rawExampleInput('1,25')
  const recoveredRuntime = await preview(200)
  assert.equal(recoveredRuntime.body.data.succeeded, false)
  await until(
    () => evaluate(`!document.querySelector('[data-testid=template-restored-status]')`),
    'a matching technically valid runtime failure clears recovery validation',
  )
  const oversizedSource = `${schema}\n; ${'x'.repeat(70_000)}\n`
  await fill('Draft source schema.mantra', oversizedSource)
  await protectedCopy(
    (backup) => backupDocument(backup, 'schema.mantra').text === oversizedSource,
    'backup preserves source exceeding the preview budget without truncation',
  )
  await freshSourceCapture('physical reload')
  await click('Restore browser draft')
  await restoredWithoutEvidence()
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), oversizedSource)
  await preview(413)
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), oversizedSource)
  assert.equal(await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`), true)
  await unchangedFiles()
  pass(
    'recovery retains invalid and oversized drafts while only matching technical engine results clear the unvalidated marker',
  )

  await freshSourceCapture()
  await source('layout.mantra')
  await evaluate(`document.querySelector('.template-layout-settings-disclosure > summary').click()`)
  await settings(200, 'Inspect layout settings')
  await fill('Layout setting precision', '-')
  await evaluate(`document.querySelector('.template-style-classes-disclosure > summary').click()`)
  await styles(200, 'Inspect style classes')
  await click('Create local style class')
  await fill('Style class name', '未完成')
  await styleChoice('weight', 'bold')
  const protectedFields = await protectedCopy(
    (backup) =>
      backup.pending.layoutSettings.some((operation) => operation.property === 'precision' && operation.text === '-') &&
      backup.pending.styleClass?.operation.name === '未完成' &&
      backup.pending.styleClass.operation.style?.weight === 'bold',
    'fields-only edits are protected before Apply',
  )
  assert.ok(protectedFields.backup.documents.every((document) => document.text === document.baseText))
  await freshSourceCapture('physical reload')
  await click('Restore browser draft')
  await restoredWithoutEvidence()
  await evaluate(`(() => {
    for (const details of document.querySelectorAll('.template-layout-settings-disclosure, .template-style-classes-disclosure'))
      if (!details.open) details.querySelector('summary').click();
  })()`)
  await until(
    () =>
      evaluate(
        `document.querySelector('[aria-label="Uninspected restored layout changes"]')?.textContent.includes('precision') && document.querySelector('[aria-label="Uninspected restored style change"]')?.textContent.includes('未完成')`,
      ),
    'raw restored buffers visible before any new inspection',
  )
  assert.ok(
    await evaluate(
      `document.querySelector('[aria-label="Uninspected restored layout changes"]').textContent.includes('"-"')`,
    ),
  )
  await until(() => evaluate(`${button('Preview draft')}.disabled`), 'restored unapplied fields block preview')
  await screenshot('recovery-unapplied-fields')
  const settingsBeforeFields = settingsResponses.length
  const stylesBeforeFields = styleResponses.length
  const inspectedFields = await settings(200, 'Inspect layout settings')
  const inspectedStyleFields = await styles(200, 'Inspect style classes')
  assert.deepEqual(inspectedFields.request.operations, [])
  assert.deepEqual(inspectedStyleFields.request.operations, [])
  assert.equal(settingsResponses.length, settingsBeforeFields + 1)
  assert.equal(styleResponses.length, stylesBeforeFields + 1)
  assert.equal(await evaluate(`${field('Layout setting precision')}.value`), '-')
  assert.equal(await evaluate(`${field('Style class name')}.value`), '未完成')
  assert.equal(await evaluate(`${field('Style class weight')}.value`), 'bold')
  assert.equal(await evaluate(`${button('Apply layout settings')}.disabled`), true)
  assert.equal(await evaluate(`${button('Apply style changes')}.disabled`), true)
  await click('Cancel setting changes')
  await until(
    () => evaluate(`${button('Preview draft')}.disabled`),
    'the class buffer remains unapplied after canceling settings',
  )
  await click('Cancel style changes')
  await until(() => evaluate(`!${button('Preview draft')}.disabled`), 'explicit Cancel releases all restored buffers')
  const canceledRecoveredFields = await preview(200)
  assert.equal(answer(canceledRecoveredFields.body), '20')
  await until(
    () => evaluate(`!document.querySelector('[data-testid=template-restored-status]')`),
    'candidate validates after the user cancels restored fields',
  )
  await unchangedFiles()
  pass(
    'fields-only recovery retains incomplete precision and class edits visibly before Inspect and requires explicit Cancel or Apply',
  )

  await freshSourceCapture()
  await preview(200)
  const portableLayout = recoveryBaseLayout.replace('Saved title', 'Portable draft title')
  const largeRawInput = '9007199254740993,0000000001'
  const largeExactAnswer = '27021597764222979.0000000003'
  await fill('Draft source schema.mantra', candidateSchema)
  await source('layout.mantra')
  await fill('Draft source layout.mantra', portableLayout)
  await rawExampleInput(largeRawInput)
  await protectedCopy(
    (backup) =>
      backupDocument(backup, 'layout.mantra').text === portableLayout && backup.inputs[0]?.text === largeRawInput,
    'portable source and raw exact decimal protected',
  )
  const portable = await downloadBackup('Download draft backup', 'actual portable JSON export')
  assert.equal(backupDocument(portable.backup, 'schema.mantra').text, candidateSchema)
  assert.equal(backupDocument(portable.backup, 'layout.mantra').text, portableLayout)
  assert.deepEqual(portable.backup.inputs, [{ node: 'base-amount', text: largeRawInput }])
  assert.deepEqual(portable.backup.pending, { layoutSettings: [], styleClass: null })
  const foreignWorkspaceBackup = {
    ...portable.backup,
    workspaceId: workspaceId === 'f'.repeat(64) ? 'e'.repeat(64) : 'f'.repeat(64),
  }
  const foreignFile = join(directory, 'foreign-workspace-draft.json')
  await writeFile(foreignFile, JSON.stringify(foreignWorkspaceBackup))
  await freshSourceCapture()
  await importBackupFile(foreignFile)
  await until(
    () =>
      evaluate(
        `document.querySelector('[data-testid=template-recovery-import-match]')?.textContent.includes('another workspace')`,
      ),
    'explicit foreign-workspace import notice',
  )
  assert.equal(
    await evaluate(`${field('Draft source schema.mantra')}.value`),
    schema,
    'File inspection alone does not apply its sources',
  )
  assert.equal(await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`), false)
  await click('Restore imported draft')
  await restoredWithoutEvidence()
  await source('layout.mantra')
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), portableLayout)
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), largeRawInput)
  const importedPreview = await preview(200)
  assert.equal(
    answer(importedPreview.body),
    largeExactAnswer,
    'Only the real engine computes the decimal beyond binary64',
  )
  assert.deepEqual(importedPreview.request.inputs, [{ node: 'base-amount', text: largeRawInput }])
  await until(
    () => evaluate(`!document.querySelector('[data-testid=template-restored-status]')`),
    'imported source validated by its current engine preview',
  )
  await screenshot('recovery-portable-import')
  await unchangedFiles()
  pass(
    'a real JSON download and explicit matching-graph file import preserve exact decimal text across workspace identities',
  )

  const sourceBeforeInvalidImport = await evaluate(`${field('Draft source layout.mantra')}.value`)
  const storedBeforeInvalidImport = await protectedCopy(
    (backup) =>
      backupDocument(backup, 'layout.mantra').text === portableLayout && backup.inputs[0]?.text === largeRawInput,
    'current imported source protected before rejecting malformed imports',
  )
  for (const [label, raw, reason] of [
    [
      'fabricated engine evidence',
      JSON.stringify({ ...portable.backup, preview: { succeeded: true } }),
      /unsupported|inconsistent/i,
    ],
    ['malformed JSON', '{bad', /valid JSON|property name|position|unexpected|expected/i],
    [
      'duplicate JSON keys',
      `{"format":"mantra.template-preview-recovery/1",${portable.text.slice(1)}`,
      /duplicate JSON keys/i,
    ],
  ]) {
    await fill('Draft backup JSON', raw)
    await until(
      () => evaluate(`!document.querySelector('[data-testid=template-recovery-import-match]')`),
      'editing import text clears the earlier validation review',
    )
    await click('Validate backup JSON')
    await assertImportRejected(reason)
    assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), sourceBeforeInvalidImport, label)
    assert.equal(await evaluate(`${field('Example input base-amount')}.value`), largeRawInput, label)
    assert.equal((await browserCopy()).raw, storedBeforeInvalidImport.raw, `${label} cannot replace the browser copy`)
  }
  const tooLargeBackupFile = join(directory, 'over-limit-draft.json')
  await writeFile(tooLargeBackupFile, ' '.repeat(2 * 1024 * 1024 + 1))
  await importBackupFile(tooLargeBackupFile)
  await until(
    () =>
      evaluate(`document.querySelector('[data-testid=template-recovery-panel]').textContent.includes('2 MiB limit')`),
    'oversized file rejected before it changes the current draft',
  )
  await assertImportRejected()
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), sourceBeforeInvalidImport)
  assert.equal((await browserCopy()).raw, storedBeforeInvalidImport.raw)
  await unchangedFiles()
  pass('invalid evidence malformed duplicate-key and oversized JSON imports cannot replace editable or stored drafts')

  await evaluate(`(() => {
    window.mantraOriginalRecoveryWrite = Storage.prototype.setItem;
    Storage.prototype.setItem = function(key, value) {
      if (key.startsWith(${JSON.stringify(recoveryNamespace)})) throw new DOMException('Quota exhausted', 'QuotaExceededError');
      return window.mantraOriginalRecoveryWrite.call(this, key, value);
    };
  })()`)
  const quotaLayout = `${portableLayout}\n; Source remains editable when browser storage is full.\n`
  await fill('Draft source layout.mantra', quotaLayout)
  await until(
    () =>
      evaluate(`document.querySelector('[data-testid=template-recovery-status]')?.dataset.recoveryState === 'error'`),
    'browser quota failure is visible for the current source',
  )
  assert.match(
    await evaluate(`document.querySelector('[data-testid=template-recovery-status]').textContent`),
    /full.*not.*protected/i,
  )
  const quotaExport = await downloadBackup(
    'Download draft backup',
    'current source download despite browser quota failure',
  )
  assert.equal(backupDocument(quotaExport.backup, 'layout.mantra').text, quotaLayout)
  assert.equal(await evaluate(`${field('Draft source layout.mantra')}.value`), quotaLayout)
  assert.equal(
    (await browserCopy()).raw,
    storedBeforeInvalidImport.raw,
    'A failed write does not pretend the old copy protects the new source',
  )
  await evaluate(
    `Storage.prototype.setItem = window.mantraOriginalRecoveryWrite; delete window.mantraOriginalRecoveryWrite`,
  )
  await click('Retry browser backup')
  const ownCopy = await protectedCopy(
    (backup) => backupDocument(backup, 'layout.mantra').text === quotaLayout,
    'retry protects the complete current source after quota recovery',
  )
  const otherPageBackup = JSON.parse(ownCopy.raw)
  backupDocument(otherPageBackup, 'layout.mantra').text += '\n; A different page changed this stored copy.\n'
  otherPageBackup.savedAt = new Date().toISOString()
  const otherPageRaw = JSON.stringify(otherPageBackup)
  await evaluate(`localStorage.setItem(${JSON.stringify(recoveryKey())}, ${JSON.stringify(otherPageRaw)})`)
  const sourceAfterOtherPage = `${candidateSchema}\n; This editor keeps its own newer source.\n`
  await source('schema.mantra')
  await fill('Draft source schema.mantra', sourceAfterOtherPage)
  await until(
    () =>
      evaluate(`document.querySelector('[data-testid=template-recovery-status]')?.dataset.recoveryState === 'paused'`),
    'a changed browser copy pauses automatic replacement',
  )
  assert.equal((await browserCopy()).raw, otherPageRaw)
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), sourceAfterOtherPage)
  const pausedExport = await downloadBackup(
    'Download draft backup',
    'current source remains downloadable while another copy is pending',
  )
  assert.equal(backupDocument(pausedExport.backup, 'schema.mantra').text, sourceAfterOtherPage)
  await click('Retry browser backup')
  await until(() => evaluate(`!!${button('Discard browser draft')}`), 'the changed browser copy is offered for review')
  const otherDownload = await downloadBackup(
    'Download browser draft',
    'the independently changed browser copy is preserved',
  )
  assert.equal(otherDownload.text, otherPageRaw)
  await click('Discard browser draft')
  await protectedCopy(
    (backup) => backupDocument(backup, 'schema.mantra').text === sourceAfterOtherPage,
    'discarding the observed old copy allows this editor to protect its retained source',
  )
  await unchangedFiles()
  pass(
    'quota errors and independently changed browser copies preserve current edits and expose honest portable backup fallbacks',
  )

  const beforeRestoreSchema = candidateSchema.replace('(* base-amount 3)', '(* base-amount 4)')
  await fill('Draft source schema.mantra', beforeRestoreSchema)
  await rawExampleInput('2')
  const beforeRestorePreview = await preview(200)
  assert.equal(answer(beforeRestorePreview.body), '8')
  await importBackupFile(portable.file)
  await until(
    () => evaluate(`!${button('Restore imported draft')}.disabled`),
    'compatible import ready before a delayed preview',
  )
  await session.send('Fetch.enable', {
    patterns: [{ urlPattern: '*/api/v1/cases/*/template-preview', requestStage: 'Response' }],
  })
  await click('Preview draft')
  const heldRecoveryPreview = await until(
    () => pausedResponses.shift(),
    'real preview held before restoring different source',
  )
  assert.equal(heldRecoveryPreview.status, 200)
  await click('Restore imported draft')
  await restoredWithoutEvidence()
  assert.equal(await evaluate(`${field('Example input base-amount')}.value`), largeRawInput)
  await releaseHeld(heldRecoveryPreview)
  assert.equal(
    await evaluate(`!!document.querySelector('[aria-label="Draft preview"] .paper-table')`),
    false,
    'A delayed response cannot reinstate the previous Paper after Restore',
  )
  assert.equal(await evaluate(`!!document.querySelector('[data-testid=template-restored-status]')`), true)
  const restoredAfterHeld = await preview(200)
  assert.equal(answer(restoredAfterHeld.body), largeExactAnswer)
  assert.ok(restoredAfterHeld.request.documents.some((document) => document.text === candidateSchema))
  assert.equal(
    restoredAfterHeld.request.documents.some((document) => document.text === beforeRestoreSchema),
    false,
  )
  await unchangedFiles()
  pass(
    'explicit restoration cancels an actual delayed preview and calculates only the newly restored source and raw input',
  )

  const beforeBaselineChange = await protectedCopy(
    (backup) =>
      backupDocument(backup, 'schema.mantra').text === candidateSchema && backup.inputs[0]?.text === largeRawInput,
    'old-baseline source protected before the explicit external change',
  )
  const changedHelper = Buffer.from(
    `${expectedFiles.get('helpers.mantra').toString('utf8')}\n; External dependency change for recovery baseline validation.\n`,
  )
  await writeFile(join(workspace, 'helpers.mantra'), changedHelper)
  expectedFiles.set('helpers.mantra', changedHelper)
  externalChanges.push('helpers.mantra')
  const newBaseline = await freshSourceCapture('physical reload')
  assert.notEqual(newBaseline.body.revision, beforeBaselineChange.backup.baseRevision)
  await until(
    () => evaluate(`${button('Restore browser draft')}?.disabled === true`),
    'a changed full source graph blocks local restoration',
  )
  assert.equal(
    (await browserCopy()).raw,
    beforeBaselineChange.raw,
    'A fresh baseline does not replace the older recovery copy',
  )
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), schema)
  const mismatchedDownload = await downloadBackup(
    'Download browser draft',
    'incompatible baseline copy retained for manual recovery',
  )
  assert.equal(mismatchedDownload.text, beforeBaselineChange.raw)
  assert.equal(mismatchedDownload.backup.baseRevision, beforeBaselineChange.backup.baseRevision)
  const sourceAlongsideOldCopy = `${schema}\n; New editing retains the older pending recovery copy.\n`
  await fill('Draft source schema.mantra', sourceAlongsideOldCopy)
  const alongsideDownload = await downloadBackup(
    'Download draft backup',
    'new source available while an incompatible old copy is pending',
  )
  assert.equal(backupDocument(alongsideDownload.backup, 'schema.mantra').text, sourceAlongsideOldCopy)
  assert.equal(alongsideDownload.backup.baseRevision, newBaseline.body.revision)
  assert.equal((await browserCopy()).raw, beforeBaselineChange.raw)
  await screenshot('recovery-baseline-mismatch')
  await click('Discard browser draft')
  await protectedCopy(
    (backup) =>
      backup.baseRevision === newBaseline.body.revision &&
      backupDocument(backup, 'schema.mantra').text === sourceAlongsideOldCopy,
    'explicit discard allows new-baseline backup without losing current edits',
  )
  assert.equal(await evaluate(`${field('Draft source schema.mantra')}.value`), sourceAlongsideOldCopy)
  const currentBaselinePreview = await preview(200)
  assert.equal(answer(currentBaselinePreview.body), '20')
  await unchangedFiles()
  pass(
    'a changed dependency blocks automatic recovery while preserving old JSON and current edits until explicit discard',
  )
  // A captured include owns raw CRLF ranges; navigation must normalize DOM offsets without making it writable.
  const navigationHelper = Buffer.from(
    '(fragment\r\n  ;; Keep the 😀 source comment.\r\n  (defn twice [^Decimal value] (* value 2))\r\n  (note "Readonly 中文😀"))\r\n',
  )
  await writeFile(join(workspace, 'helpers.mantra'), navigationHelper)
  expectedFiles.set('helpers.mantra', navigationHelper)
  externalChanges.push('helpers.mantra')
  await freshSourceCapture()
  await evaluate(`document.querySelector('.template-schema-properties-disclosure > summary').click()`)
  propertyIndex = schemaPropertyResponses.length
  await click('Inspect schema properties')
  const includeCatalog = await until(() => schemaPropertyResponses[propertyIndex], 'real readonly include catalog')
  assert.equal(includeCatalog.status, 200)
  assert.ok(
    includeCatalog.body.data.owners.some(
      (owner) => owner.document === 'helpers.mantra' && owner.property === 'text' && !owner.editable,
    ),
  )
  await fill('Find a declaration', 'Readonly 中文😀')
  await evaluate(
    `[...document.querySelectorAll('.schema-presentation-outline button')].find(item => item.getAttribute('aria-label').startsWith('Select text of note ')).click()`,
  )
  const beforeIncludeNavigation = responses.length
  await click('Show property in source')
  assert.equal(await evaluate(`${field('Draft source helpers.mantra')}.readOnly`), true)
  assert.equal(await evaluate(`document.activeElement === ${field('Draft source helpers.mantra')}`), true)
  assert.equal(
    await evaluate(
      `(() => { const editor = ${field('Draft source helpers.mantra')}; return editor.value.slice(editor.selectionStart, editor.selectionEnd); })()`,
    ),
    '"Readonly 中文😀"',
  )
  assert.equal(responses.length, beforeIncludeNavigation)
  await screenshot('schema-include-source')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: false,
  })
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true)
  await screenshot('schema-outline-narrow')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 1000,
    deviceScaleFactor: 1,
    mobile: false,
  })
  await source('schema.mantra')
  await fill('Draft source schema.mantra', schema + '\n; Newer source makes the directory stale.\n')
  assert.equal(await evaluate(`${button('Show property in source')}.disabled`), true)
  assert.equal(
    await evaluate(
      `[...document.querySelectorAll('.schema-presentation-outline button')].every(item => item.disabled)`,
    ),
    true,
  )
  await unchangedFiles()
  pass('real readonly include navigation preserves exact CRLF Unicode source and stale catalogs disable navigation')
  for (const captured of sourceResponses) plainSourceSnapshot(captured)
  for (const captured of recoveryContextResponses) {
    assert.equal(captured.status, 200)
    assert.deepEqual(Object.keys(captured.body.data), ['workspaceId'])
    assert.equal(captured.body.data.workspaceId, workspaceId)
    assert.equal(captured.body.revision, workspaceId)
  }
  assert.deepEqual(browserErrors, [], 'No uncaught browser exceptions after recovery and backup workflows')
} catch (error) {
  failures.push(error)
  if (session)
    try {
      failurePage = await evaluate(`({
        title: document.title,
        text: document.body?.innerText.slice(0, 12000) ?? '',
        recoveryState: document.querySelector('[data-testid=template-recovery-status]')?.dataset.recoveryState ?? null,
        sources: [...document.querySelectorAll('.template-preview-source textarea')].map(item => ({ label: item.getAttribute('aria-label'), length: item.value.length })),
      })`)
    } catch {
      // Preserve the original verification failure if its browser context has already closed.
    }
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
        sourceCaptures: sourceResponses.map(({ status, body }) => ({
          status,
          revision: body.revision,
          caseId: body.data?.document ?? null,
          baseRevisions: body.data?.baseRevisions ?? null,
          documents: (body.data?.documents ?? []).map((document) => ({
            document: document.document,
            role: document.role,
            handle: document.handle,
            sha256: document.sha256,
            textSha256: sha256(Buffer.from(document.text)),
          })),
        })),
        recoveryContextCaptures: recoveryContextResponses.map(({ status, body }) => ({
          status,
          contract: body.contract,
          revision: body.revision,
          workspaceId: body.data?.workspaceId ?? null,
        })),
        recoveryEvidence,
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
        schemaPropertyResponses: schemaPropertyResponses.map(({ status, request, body }) => ({
          status,
          request,
          body,
        })),
        styleResponses: styleResponses.map(({ status, request, body }) => ({
          status,
          draftSequence: request.draftSequence,
          baseRevision: request.baseRevision,
          handle: request.handle,
          submittedSourceSha256: sha256(Buffer.from(request.text)),
          operations: request.operations,
          source: body.data?.source ?? null,
          stylePresets: body.data?.stylePresets ?? null,
          usageScope: body.data?.usageScope ?? null,
          classes: body.data?.classes ?? null,
          patches: body.data?.patches ?? null,
          error: body.error ?? null,
        })),
        sourceByteChecks: filesVerified,
        cancelledPreviewRequests: cancelledRequests,
        cancelledSourceRequests,
        cancelledRecoveryContextRequests,
        cancelledSettingsRequests,
        cancelledStyleRequests,
        originalSources: Object.fromEntries([...originals].map(([name, bytes]) => [name, sha256(bytes)])),
        authorizedExternalChanges: externalChanges,
        temporaryDirectoryRemoved: !directory || !existsSync(directory),
        taskProcessGroupsStopped: cleanup.every((result) => result.status === 'fulfilled'),
        failures: failures.map((error) => error.message),
        failurePage,
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
