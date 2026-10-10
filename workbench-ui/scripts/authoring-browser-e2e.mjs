import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

// Uses an installed browser and a task-only profile. Real Chinese IME remains a manual check.
const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const screenshots = resolve(uiRoot, '../docs/workbench/visual-editor/screenshots')
const chrome = process.env.MANTRA_TEST_CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
if (!existsSync(chrome))
  throw new Error(`System Chrome not found: ${chrome}. Set MANTRA_TEST_CHROME to an installed browser executable.`)

let directory, vite, browser, session
let interrupted = false
const failures = []
const passed = []
const browserErrors = []

async function freePort() {
  const server = createServer()
  await new Promise((done, reject) => server.once('error', reject).listen(0, '127.0.0.1', done))
  const port = server.address().port
  await new Promise((done) => server.close(done))
  return port
}

function start(command, args) {
  const child = spawn(command, args, {
    cwd: uiRoot,
    detached: true,
    stdio: 'ignore',
    env: {
      ...process.env,
      VITE_WORKBENCH_MODE: 'fixture',
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

function stop(child, signal = 'SIGTERM') {
  if (!child || child.startError || child.exitCode !== null || child.signalCode !== null) return
  try {
    process.kill(-child.pid, signal)
  } catch {
    child.kill(signal)
  }
}

async function stopped(child) {
  if (!child || child.startError) return
  if (child.exitCode === null && child.signalCode === null)
    await Promise.race([new Promise((done) => child.once('exit', done)), delay(3000)])
  if (child.exitCode === null && child.signalCode === null) {
    stop(child, 'SIGKILL')
    await Promise.race([new Promise((done) => child.once('exit', done)), delay(3000)])
  }
  assert.ok(child.exitCode !== null || child.signalCode !== null, `Task-owned process ${child.pid} did not stop`)
  const groupAlive = () => {
    try {
      process.kill(-child.pid, 0)
      return true
    } catch (error) {
      if (error.code === 'ESRCH') return false
      throw error
    }
  }
  async function settleGroup() {
    const deadline = Date.now() + 3000
    while (groupAlive() && Date.now() < deadline) await delay(100)
  }
  await settleGroup()
  if (groupAlive()) {
    process.kill(-child.pid, 'SIGKILL')
    await settleGroup()
  }
  assert.equal(groupAlive(), false, `Task-owned process group ${child.pid} did not stop`)
}

async function until(read, label, timeout = 15000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (interrupted) throw new Error('Authoring browser run interrupted')
    if (vite?.startError || browser?.startError) throw vite?.startError ?? browser.startError
    if ((vite && vite.exitCode !== null) || (browser && browser.exitCode !== null))
      throw new Error(`${label}: task process exited early`)
    try {
      const value = await read()
      if (value) return value
    } catch {
      // The task server or page may still be starting.
    }
    await delay(75)
  }
  const text = session ? await evaluate('document.body.innerText').catch(() => '') : ''
  throw new Error(`Timed out waiting for ${label}\n${text.slice(-6000)}`)
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
    if (response.method === 'Runtime.exceptionThrown') browserErrors.push(response.params.exceptionDetails.text)
    if (response.method === 'Page.javascriptDialogOpening') {
      // Accept the intentional beforeunload warning when a scripted test reloads an unsaved draft.
      void session?.send('Page.handleJavaScriptDialog', { accept: true }).catch((error) => failures.push(error))
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

async function textIncludes(text) {
  return evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`)
}

async function hasText(text) {
  await until(() => textIncludes(text), text)
}

const buttonExpression = (text) =>
  `[...document.querySelectorAll('button')].find(item => item.textContent.trim() === ${JSON.stringify(text)} || item.textContent.trim() === ${JSON.stringify(`${text}…`)} || item.textContent.trim().startsWith(${JSON.stringify(`${text} (`)}) || item.getAttribute('aria-label') === ${JSON.stringify(text)})`

async function click(text) {
  await until(
    () => evaluate(`!!${buttonExpression(text)} && !${buttonExpression(text)}.disabled`),
    `enabled button: ${text}`,
  )
  const clicked = await evaluate(`(() => { const button = ${buttonExpression(text)}; button.click(); return true })()`)
  assert.equal(clicked, true, `Enabled button: ${text}`)
}

async function disabled(text) {
  assert.equal(
    await evaluate(
      `[...document.querySelectorAll('button:disabled')].some(item => item.textContent.trim() === ${JSON.stringify(text)} || item.getAttribute('aria-label') === ${JSON.stringify(text)})`,
    ),
    true,
    `Disabled button: ${text}`,
  )
}

async function key(keyName, modifiers = 0, code = keyName) {
  const windowsVirtualKeyCode = {
    Enter: 13,
    Escape: 27,
    Tab: 9,
    ArrowDown: 40,
    ArrowUp: 38,
    F2: 113,
    a: 65,
    z: 90,
    s: 83,
  }[keyName]
  await session.send('Input.dispatchKeyEvent', {
    type: 'keyDown',
    key: keyName,
    code,
    modifiers,
    ...(windowsVirtualKeyCode ? { windowsVirtualKeyCode } : {}),
  })
  await session.send('Input.dispatchKeyEvent', { type: 'keyUp', key: keyName, code, modifiers })
}

const command = process.platform === 'darwin' ? 4 : 2

async function fill(label, text) {
  const focused = await evaluate(
    `(() => { const input = document.querySelector('[aria-label=' + CSS.escape(${JSON.stringify(label)}) + ']'); input?.focus(); return !!input })()`,
  )
  assert.equal(focused, true, `Editable field: ${label}`)
  await key('a', command, 'KeyA')
  await session.send('Input.insertText', { text })
}

async function selectRow(label, column = 0) {
  const selected = await evaluate(
    `(() => { const row = [...document.querySelectorAll('[role="grid"] tr')].find(item => item.innerText.includes(${JSON.stringify(label)})); const cell = row?.querySelectorAll('[role="gridcell"]')[${column}]; cell?.click(); cell?.focus(); return !!cell })()`,
  )
  assert.equal(selected, true, `Grid row selection: ${label}, column ${column}`)
}

async function current() {
  await until(() => textIncludes('Preview: current'), 'current recorded preview')
}

async function sequence() {
  return evaluate('Number(document.querySelector("[data-draft-sequence]")?.dataset.draftSequence)')
}

async function formula(text, label = 'Unallocated request') {
  await selectRow(label)
  await fill('Mantra DSL formula', text)
  await click('Apply formula')
}

async function chooseClass(previous, next) {
  await click('Style')
  await click(`Remove class ${previous}`)
  await click('Add class')
  for (const name of ['normal', 'subtle', 'highlight', 'variance'])
    assert.equal(await evaluate(`!!${buttonExpression(`Add class ${name}`)}`), true, `Preset class ${name}`)
  await hasText('dsl-reference §3.4')
  await click(`Add class ${next}`)
  await click('Apply classes')
}

async function desktopLayout() {
  return evaluate(`(() => {
    const rect = element => {
      const box = element?.getBoundingClientRect();
      return box ? { top: box.top, bottom: box.bottom, height: box.height, left: box.left, right: box.right } : null;
    };
    const center = document.querySelector('.author-center');
    const grid = document.querySelector('.author-grid-panel');
    const rows = [...document.querySelectorAll('[role="grid"] tr')].filter(row => row.querySelector('[role="gridcell"]'));
    const source = document.querySelector('.author-source-pane');
    return {
      formula: rect(document.querySelector('.author-formula')),
      grid: rect(grid), firstRow: rect(rows[0]), center: rect(center), source: rect(source),
      centerScrollTop: center?.scrollTop, centerScrollHeight: center?.scrollHeight, centerClientHeight: center?.clientHeight,
      sourceScrollerHeight: source?.querySelector('.cm-scroller')?.clientHeight,
      sourceContentHeight: source?.querySelector('.cm-scroller')?.scrollHeight,
      sourceScrollTop: source?.querySelector('.cm-scroller')?.scrollTop,
    };
  })()`)
}

async function assertDefaultLayout() {
  const layout = await desktopLayout()
  assert.ok(layout.formula && layout.firstRow && layout.center && layout.grid, 'Bounded formula and grid panes')
  assert.ok(layout.formula.height <= 150, 'Formula starts compact')
  assert.ok(
    layout.firstRow.top >= layout.formula.bottom && layout.firstRow.bottom < 900,
    'Grid rows visible below formula',
  )
  assert.ok(layout.centerScrollHeight <= layout.centerClientHeight + 1, 'Center column does not scroll as a whole')
}

async function resetCopy(port) {
  await evaluate('document.querySelector(".author-controls").open = true')
  await click('Reset prototype to recorded base')
  await until(async () => (await sequence()) === 0, 'fresh recorded-base session')
  await current()
  await session.send('Page.navigate', { url: `http://127.0.0.1:${port}/authoring` })
  await hasText('Start from a pattern')
  await click('Create editable copy')
  await click('Confirm editable copy')
  await hasText('Allocation conservation')
  await current()
}

async function screenshot(name, width = 1440, height = 900) {
  await session.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false })
  await evaluate('scrollTo(0, 0)')
  await delay(150)
  const result = await session.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
  await writeFile(join(screenshots, `${name}.png`), Buffer.from(result.data, 'base64'))
}

function pass(name) {
  passed.push(name)
  console.log(`PASS ${name}`)
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    stop(browser)
    stop(vite)
  })
}

try {
  directory = await mkdtemp(join(tmpdir(), 'mantra-authoring-browser-'))
  await mkdir(screenshots, { recursive: true })
  const port = await freePort()
  vite = start(process.execPath, [
    join(uiRoot, 'node_modules/vite/bin/vite.js'),
    '--host',
    '127.0.0.1',
    '--port',
    String(port),
    '--strictPort',
  ])
  await until(async () => (await fetch(`http://127.0.0.1:${port}/authoring`)).ok, 'fixture Vite server')
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
  }, 'Chrome task page')
  session = await connect(target.webSocketDebuggerUrl)
  await session.send('Page.enable')
  await session.send('Runtime.enable')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  })
  await session.send('Page.navigate', { url: `http://127.0.0.1:${port}/authoring` })
  await hasText('Start from a pattern')
  await screenshot('entry')
  await click('Create editable copy')
  await hasText('Confirm editable copy')
  await click('Confirm editable copy')
  await hasText('Allocation conservation')
  await current()
  assert.equal(await evaluate('document.querySelectorAll("[role=grid]").length'), 1)
  assert.equal(
    await evaluate(`(() => {
      const outline = document.querySelector('[aria-label="Outline"]');
      const resources = outline.querySelector('section[aria-label="Template resources"]');
      const panel = outline.querySelector('section[aria-label="Allocation conservation"]');
      return !resources?.innerText.includes('Allocation conservation') && [...panel.querySelectorAll('button')].some(button => button.innerText.startsWith('Allocation conservation'));
    })()`),
    true,
    'Section owner is grouped with its panel instead of template resources',
  )
  await assertDefaultLayout()
  pass('entry and simulated fork')

  await click('Split')
  const splitBefore = await desktopLayout()
  await selectRow('Request not allocated', 1)
  await until(
    () => evaluate(`!!document.querySelector('.author-source-pane .cm-owner-primary')`),
    'source owner decoration while grid keeps focus',
  )
  const splitAfter = await desktopLayout()
  assert.ok(splitAfter.source && splitAfter.grid, 'Split contains grid and source panes')
  assert.ok(splitAfter.grid.right <= splitAfter.source.left, 'Split panes remain side by side')
  assert.ok(
    splitAfter.sourceScrollerHeight < splitAfter.sourceContentHeight,
    'Source editor has a bounded inner scroller',
  )
  assert.equal(
    splitAfter.centerScrollTop,
    splitBefore.centerScrollTop,
    'Owner location never scrolls whole center column',
  )
  assert.equal(splitAfter.grid.top, splitBefore.grid.top, 'Owner selection keeps grid in the viewport')
  assert.equal(
    await evaluate(`!!document.activeElement?.closest('[role="grid"]')`),
    true,
    'Source location keeps grid focus',
  )
  assert.equal(
    await evaluate(`(() => {
      const owner = document.querySelector('.author-source-pane .cm-owner-primary');
      const rect = owner.getBoundingClientRect();
      const source = document.querySelector('.author-source-pane').getBoundingClientRect();
      return rect.height > 0 && rect.bottom > source.top && rect.top < source.bottom && getComputedStyle(owner).backgroundColor !== 'rgba(0, 0, 0, 0)';
    })()`),
    true,
    'Blurred source owner has a visible painted decoration',
  )
  await screenshot('split-independent')
  await click('Grid')
  await assertDefaultLayout()
  pass('compact formula, visible grid and independent Split source highlighting')

  await selectRow('Request not allocated', 0)
  await evaluate(
    `document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'Process', code: 'Process', keyCode: 229, bubbles: true }))`,
  )
  assert.equal(
    await evaluate('document.activeElement?.getAttribute("aria-label")'),
    'Property text',
    'Browsing IME key opens an editor synchronously for composition',
  )
  await key('Escape')
  await selectRow('Request not allocated', 0)
  await key('F2')
  assert.equal(await evaluate('document.activeElement?.getAttribute("aria-label")'), 'Property text')
  const beforeIme = await evaluate('document.body.innerText')
  await evaluate(
    `document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true }))`,
  )
  assert.equal(await evaluate('document.body.innerText'), beforeIme, 'IME candidate Enter neither saves nor navigates')
  await evaluate(
    `document.activeElement.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 229, bubbles: true }))`,
  )
  assert.equal(
    await evaluate('document.body.innerText'),
    beforeIme,
    'Legacy IME keyCode 229 neither saves nor navigates',
  )
  await key('Escape')
  await selectRow('Request not allocated', 0)
  await key('Tab')
  assert.equal(await evaluate('!!document.activeElement?.closest("[role=grid]")'), false, 'Tab exits browsing grid')
  await selectRow('Request not allocated', 0)
  await key('Enter')
  assert.equal(await evaluate('document.activeElement?.getAttribute("aria-label")'), 'Property text')
  await fill('Property text', '')
  await key('Enter')
  assert.equal(await evaluate('document.activeElement?.getAttribute("aria-label")'), 'Property text')
  await hasText('Preview: current · draft #0')
  await fill('Property text', 'Unallocated request')
  await key('Enter')
  await current()
  await hasText('Unallocated request')
  await screenshot('label-edit')
  pass('grid F2/Enter/Tab, synthetic IME guard and label transaction')

  await selectRow('Capacity not consumed', 0)
  const rowBeforeClass = await evaluate(
    `(() => { const row = document.activeElement.closest('tr'); return { value: row.querySelector('[data-column="1"]').innerText, appearance: [...row.querySelectorAll('td')].map(cell => [cell.className, cell.getAttribute('style')]) } })()`,
  )
  await chooseClass('subtotal', 'result')
  await current()
  const styledRow = await evaluate(
    `(() => { const row = [...document.querySelectorAll('[role=grid] tr')].find(item => item.innerText.includes('Capacity not consumed')); return { value: row.querySelector('[data-column="1"]').innerText, appearance: [...row.querySelectorAll('td')].map(cell => [cell.className, cell.getAttribute('style')]) } })()`,
  )
  assert.equal(styledRow.value, rowBeforeClass.value, 'Class changes keep engine-rendered value')
  assert.notDeepEqual(styledRow.appearance, rowBeforeClass.appearance, 'Recorded result class changes Paper appearance')
  await hasText('Final cell style · Paper')
  for (const name of ['Weight', 'Tone', 'Fill']) await hasText(name)
  assert.equal(
    await evaluate(`!!document.querySelector('[aria-label="Classes"]')`),
    false,
    'Style selection uses chips',
  )
  await screenshot('class-style')
  pass('class style changes presentation without changing the recorded value')

  await selectRow('Unallocated request', 1)
  const compactFormulaHeight = (await desktopLayout()).formula.height
  await key('F2')
  assert.equal(await evaluate('document.activeElement?.getAttribute("aria-label")'), 'Mantra DSL formula')
  assert.ok(
    (await desktopLayout()).formula.height > compactFormulaHeight,
    'Focused formula expands without hiding the grid',
  )
  assert.ok((await desktopLayout()).grid.top < 900, 'Expanded formula leaves visible grid space')
  await fill('Mantra DSL formula', '(- request allocated-total)')
  await key('Enter')
  assert.equal(await evaluate('document.querySelectorAll(".authoring-code-editor .cm-line").length > 1'), true)
  await hasText('Preview: current · draft #2')
  await fill('Mantra DSL formula', '(- request allocated-totl)')
  await key('Enter', command)
  await hasText('Invalid draft')
  await hasText('Previous valid preview (draft #2)')
  await disabled('Save')
  await click('Problems')
  await hasText('DSL-REF-UNKNOWN-SYMBOL')
  await hasText('DSL-TYPE-CALL-ARGUMENT')
  assert.ok(
    await evaluate(
      `[...document.querySelectorAll('.cm-lintRange-error')].some(item => item.textContent.includes('allocated-totl'))`,
    ),
  )
  await screenshot('invalid-draft')
  pass('invalid draft retains valid Paper, precise formula range and both recorded diagnostics')

  await formula('(- request total-capacity)')
  await current()
  await hasText('(60.00)')
  assert.ok(await evaluate('document.querySelector("[role=grid]").innerText.includes("✗")'))
  assert.equal(await evaluate(`${buttonExpression('Save')}?.disabled`), false)
  await screenshot('business-finding')
  pass('business finding remains saveable with engine-rendered value and check failure')

  await evaluate(`document.querySelector('[role=gridcell][tabindex="0"]')?.focus()`)
  await key('z', command, 'KeyZ')
  await hasText('Invalid draft')
  await key('z', command, 'KeyZ')
  await current()
  await key('z', command | 8, 'KeyZ')
  await hasText('Invalid draft')
  await key('z', command | 8, 'KeyZ')
  await current()
  await hasText('(60.00)')
  pass('source undo and redo restore each recorded draft')

  await evaluate('document.querySelector(".author-controls summary")?.click()')
  await click('Delay next preview by 2 s')
  await formula('(/ request 0)')
  await evaluate(`document.querySelector('[role=gridcell][tabindex="0"]')?.focus()`)
  await key('z', command, 'KeyZ')
  await current()
  await hasText('(60.00)')
  await until(
    async () => /ignored|discarded/i.test(await evaluate('document.body.innerText')),
    'late preview response ignored',
    5000,
  )
  await hasText('(60.00)')
  assert.equal(await textIncludes('calculated with a runtime failure'), false)
  pass('late runtime-failure preview cannot replace a newer undo preview')

  await formula('(/ request 0)')
  await hasText('calculated with a runtime failure')
  await hasText('undefined')
  assert.equal(await textIncludes('Errors: 0'), false, 'Runtime failure contributes to error count')
  assert.ok(
    await evaluate(`${buttonExpression('Problems')}.textContent.includes('(')`),
    'Runtime problem badge is counted',
  )
  assert.equal(
    await evaluate(
      `[...document.querySelectorAll('[role="grid"] [aria-label]')].some(item => item.getAttribute('aria-label').includes('previous valid preview'))`,
    ),
    false,
    'Current runtime-failure markers do not claim a previous preview',
  )
  await screenshot('runtime-failure')
  await evaluate(`document.querySelector('[role=gridcell][tabindex="0"]')?.focus()`)
  await key('z', command, 'KeyZ')
  await current()

  await click('Simulate external edit to layout.mantra')
  await hasText('Conflict')
  await hasText('old source revision')
  await click('Save')
  await hasText('Re-preview on the new base')
  assert.equal(
    await evaluate(`document.querySelector('[role="dialog"]').innerText.includes('(layout')`),
    false,
    'Conflict dialog displays an external diff instead of the unchanged document',
  )
  await screenshot('conflict')
  const conflictSequence = await sequence()
  await click('Re-preview on the new base')
  await until(async () => (await sequence()) > conflictSequence, 'rebase creates a fresh source draft')
  await current()
  assert.equal(await textIncludes('Conflict · changed outside this editor'), false)
  await hasText('Capacity, allocation and residue')
  await click('Save')
  await hasText('Saved in this prototype session — no file was written')
  pass('runtime failure, conflict, re-preview on new base and simulated save')

  // Recovery gets a separate unsaved draft on the immutable recorded base.
  await resetCopy(port)
  await formula('(/ request 0)', 'Request not allocated')
  await hasText('calculated with a runtime failure')
  await until(
    () =>
      evaluate(
        `Object.keys(localStorage).some(key => key.startsWith('mantra.authoring.prototype.') && localStorage.getItem(key).includes('(/ request 0)'))`,
      ),
    'stored unsaved source draft',
  )
  await session.send('Page.reload')
  await hasText('It has not been validated or saved.')
  assert.equal(
    await evaluate(
      `/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}/.test(document.querySelector('.author-recovery').innerText)`,
    ),
    false,
    'Recovery timestamp is localized for display',
  )
  await screenshot('recovery')
  await click('Restore')
  await hasText('Restored · not validated')
  await hasText('calculated with a runtime failure')
  assert.equal(await textIncludes('Restored · not validated'), false)
  assert.equal(
    await evaluate(`${buttonExpression('Save')}.disabled`),
    false,
    'Restored runtime failure is technically validated and saveable',
  )
  pass('reload localizes recovery time and accepts a matched runtime-failure preview as validation')

  await resetCopy(port)
  await click('Example input')
  assert.equal(
    await evaluate(`document.querySelector('.author-definition').textContent.includes('Example input')`),
    true,
  )
  await fill('Example input', '9')
  await click('Preview example input')
  await hasText('90.00')
  await fill('Example input', 'nine')
  await click('Preview example input')
  await hasText('Invalid decimal text')
  assert.equal(await evaluate(`document.querySelector('[aria-label="Example input"]').value`), 'nine')
  pass('example candidate uses recorded Paper and preserves rejected decimal text')

  await click('Build')
  await hasText('recorded base state')
  await disabled('Build')
  await disabled('Publish')
  await disabled('Open in Template Engine')
  for (const label of ['Build', 'Publish', 'Open in Template Engine'])
    assert.equal(
      await evaluate(`(() => {
        const button = [...document.querySelectorAll('button:disabled')].find(item => item.textContent.trim() === ${JSON.stringify(label)});
        const ids = button?.getAttribute('aria-describedby')?.split(' ') ?? [];
        return ids.some(id => document.getElementById(id)?.textContent.trim());
      })()`),
      true,
      `Disabled ${label} has a connected reason`,
    )
  await screenshot('build-panel')
  pass('build report is recorded; build, publish and Template Engine actions are disabled')

  await resetCopy(port)
  await selectRow('Rounded request to allocate', 0)
  await key('F2')
  await fill('Property text', 'Edited label outside the recording')
  await key('Enter')
  await hasText('No engine preview')
  assert.equal(
    await evaluate(`!!document.activeElement?.closest('[role="grid"]')`),
    true,
    'Accepted unrecorded label moves focus to grid',
  )
  await selectRow('Capacity not consumed', 0)
  await chooseClass('subtotal', 'result')
  await hasText('No engine preview')
  await click('Source changes')
  assert.equal(
    await evaluate(
      `[...document.querySelectorAll('.author-diff-del, .author-diff-add')].some(item => item.textContent.includes('Sum of supplied capacities') || item.textContent.includes('Sum of member allocations'))`,
    ),
    false,
    'Separated edits never mark intermediate unchanged declarations as deleted and added',
  )
  assert.ok(
    await evaluate(`document.querySelectorAll('[data-source-hunk]').length >= 2`),
    'Separated edits produce multiple source hunks',
  )
  await click('Problems')
  await selectRow('Capacity not consumed', 0)
  const drawerBeforeEscape = await evaluate(`document.querySelector('[aria-label="Authoring drawer"]').innerText`)
  await key('Escape')
  assert.equal(
    await evaluate(`document.querySelector('[aria-label="Authoring drawer"]').innerText`),
    drawerBeforeEscape,
    'Grid Escape without a range keeps drawer state',
  )
  pass('unrecorded text navigation, true separated source hunks and no-op browsing Escape')

  await resetCopy(port)
  await selectRow('Allocated plus unallocated equals the request', 1)
  await click('Property')
  assert.equal(
    await evaluate(`document.querySelector('.author-definition').textContent.startsWith('Read-only')`),
    true,
    'Readonly owner has its own channel badge',
  )
  await click('Split')
  await screenshot('narrow-390', 390, 844)
  assert.equal(
    await evaluate(`!!document.querySelector('.author-source-pane')`),
    false,
    'Narrow Split falls back to Grid',
  )
  assert.equal(
    await evaluate(`${buttonExpression('Grid')}.getAttribute('aria-pressed')`),
    'true',
    'Grid segment reflects effective narrow view',
  )
  assert.equal(
    await evaluate('document.documentElement.scrollWidth <= innerWidth + 1'),
    true,
    '390 px viewport has no page horizontal overflow',
  )
  assert.equal(
    await evaluate(`document.querySelector('[aria-label="Outline"]')?.getBoundingClientRect().height > 0`),
    false,
    'Narrow Outline starts closed',
  )
  assert.ok(
    await evaluate(
      `document.querySelector('[role="grid"] tr:has([role="gridcell"])').getBoundingClientRect().bottom < innerHeight`,
    ),
    'Narrow grid appears in first viewport',
  )
  await click('Open outline')
  await until(
    () => evaluate(`!!document.querySelector('[role="dialog"][aria-label="Outline"]')`),
    'mobile Outline drawer',
  )
  await key('Escape')
  await selectRow('Request not allocated', 0)
  await click('Open inspector')
  await until(
    () => evaluate(`!!document.querySelector('[role="dialog"][aria-label="Inspector"]')`),
    'full mobile Inspector',
  )
  assert.ok(
    await evaluate(
      `document.querySelector('[role="dialog"][aria-label="Inspector"]').getBoundingClientRect().height >= innerHeight - 32`,
    ),
    'Mobile Inspector uses the viewport',
  )
  await screenshot('narrow-inspector', 390, 844)
  await click('Close Inspector')
  assert.equal(
    await evaluate(`!!document.activeElement?.closest('[role="grid"]')`),
    true,
    'Closing mobile Inspector returns focus to selected cell',
  )
  await selectRow('Request not allocated', 1)
  const beforeMobileFormula = await sequence()
  await key('F2')
  await until(
    () =>
      evaluate(`!!document.querySelector('[role="dialog"][aria-label="Inspector"] [aria-label="Mantra DSL formula"]')`),
    'mobile formula editor inside full Inspector',
  )
  assert.equal(
    await evaluate(`document.querySelectorAll('[aria-label="Mantra DSL formula"]').length`),
    1,
    'Mobile formula editing has one CodeMirror instance',
  )
  assert.equal(
    await evaluate('document.activeElement?.getAttribute("aria-label")'),
    'Mantra DSL formula',
    'Mobile F2 focuses formula inside Inspector',
  )
  await fill('Mantra DSL formula', '(- request total-capacity)')
  // Let the completion query settle; Escape first dismisses an active completion menu.
  await delay(200)
  if (await evaluate(`!!document.querySelector('[role="dialog"] .cm-tooltip-autocomplete')`)) await key('Escape')
  await key('Escape')
  assert.equal(
    await evaluate(`!!document.querySelector('[role="dialog"][aria-label="Inspector"]')`),
    false,
    'Cancelling mobile formula closes Inspector',
  )
  assert.equal(
    await evaluate(`!!document.activeElement?.closest('[role="grid"]')`),
    true,
    'Mobile formula cancel returns selected cell focus',
  )
  assert.equal(await sequence(), beforeMobileFormula, 'Cancelling mobile formula keeps source draft unchanged')
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  })
  await until(
    () => evaluate(`!!document.querySelector('.author-source-pane')`),
    'Split restored after widening viewport',
  )
  assert.equal(
    await evaluate(`${buttonExpression('Split')}.getAttribute('aria-pressed')`),
    'true',
    'Widening restores the original Split choice',
  )
  await click('Grid')
  pass('responsive Split fallback and mobile formula Inspector focus and cancellation')
  await evaluate('document.documentElement.dataset.theme = "dark"')
  await screenshot('dark')
  assert.equal(await evaluate('document.documentElement.dataset.theme'), 'dark')
  pass('390 px page layout and dark theme screenshots')

  assert.deepEqual(browserErrors, [], 'No uncaught browser exceptions')
} catch (error) {
  failures.push(error)
} finally {
  try {
    session?.close()
  } catch {
    // Continue to process/profile cleanup even if the debugging socket has closed.
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
if (failures.length > 1) throw new AggregateError(failures, 'Authoring browser verification and cleanup failed')
console.log(`Authoring browser verification passed: ${passed.length} scenarios; physical IME requires manual review`)
