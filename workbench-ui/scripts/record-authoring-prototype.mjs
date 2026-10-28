import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { cp, mkdir, mkdtemp, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { request as httpRequest } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const patterns = resolve(repository, 'docs/patterns')
const defaultOutput = resolve(repository, 'workbench-ui/src/authoring/recording/recording.json')
const contract = 'mantra.workbench/4'
const maxBytes = 3_000_000
let interrupted
let activeServer

function checkInterrupted() {
  if (interrupted)
    throw new Error(`Recording interrupted by ${interrupted}; owned server and temporary files were cleaned`)
}

export const documents = [
  'capped-allocation/case-demo.mantra',
  'capped-allocation/layout.mantra',
  'capped-allocation/schema.mantra',
  'common/formulas.mantra',
]

export const edits = {
  label: {
    description: 'Rename the displayed unallocated request label.',
    document: documents[2],
    find: '(info unallocated "Request not allocated"',
    replace: '(info unallocated "Unallocated request"',
  },
  class: {
    description: 'Use the result class for the unused total capacity row.',
    document: documents[2],
    find: '(dim/sum all.unused-capacity) {:class :subtotal}',
    replace: '(dim/sum all.unused-capacity) {:class :result}',
  },
  'formula:typo': {
    description: 'Reference an unknown symbol to demonstrate an invalid draft.',
    document: documents[2],
    find: '(- request allocated-total) {:class :result}',
    replace: '(- request allocated-totl) {:class :result}',
  },
  'formula:finding': {
    description: 'Calculate a valid draft with a business reconciliation finding.',
    document: documents[2],
    find: '(- request allocated-total) {:class :result}',
    replace: '(- request total-capacity) {:class :result}',
  },
  'formula:runtime': {
    description: 'Demonstrate a genuine runtime division failure.',
    document: documents[2],
    find: '(- request allocated-total) {:class :result}',
    replace: '(/ request 0) {:class :result}',
  },
  external: {
    description: 'Simulate an external edit to the saved layout title.',
    document: documents[1],
    find: ':title "Capacity and conservation"',
    replace: ':title "Capacity, allocation and residue"',
  },
}

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex')
}

export function sourceDigest(source) {
  return sha256(
    JSON.stringify(
      Object.keys(source)
        .sort()
        .map((path) => [path, sha256(source[path])]),
    ),
  )
}

export function editedSource(base, names) {
  const source = { ...base }
  for (const name of names) {
    const edit = edits[name]
    assert.ok(edit, `Unknown scripted edit: ${name}`)
    assert.equal(source[edit.document].split(edit.find).length - 1, 1, `Edit ${name} must match exactly once`)
    source[edit.document] = source[edit.document].replace(edit.find, edit.replace)
  }
  return source
}

export function scriptedStates() {
  const states = []
  for (const label of [false, true]) {
    for (const classes of [false, true]) {
      for (const formula of [null, 'formula:typo', 'formula:finding', 'formula:runtime']) {
        for (const external of [false, true]) {
          const names = [label && 'label', classes && 'class', formula, external && 'external'].filter(Boolean)
          states.push({ id: names.join('+') || 'base', edits: names })
        }
      }
    }
  }
  return states
}

async function currentSource() {
  return Object.fromEntries(
    await Promise.all(documents.map(async (path) => [path, await readFile(resolve(patterns, path), 'utf8')])),
  )
}

function blob(recording, exchange) {
  const value = recording.blobs[exchange.response.body.$blob]
  assert.ok(value, `Missing response blob for ${exchange.request.path}`)
  return value
}

function response(recording, state, action) {
  const exchange = state.exchanges.find((entry) => entry.request.path.split('?')[0].endsWith(`/${action}`))
  assert.ok(exchange, `Missing ${action} request in ${state.id}`)
  return { exchange, body: blob(recording, exchange) }
}

function sourceSlice(location, source) {
  const text = source[location.document]
  assert.equal(typeof text, 'string', `Location document is outside the recorded source: ${location.document}`)
  assert.ok(Number.isInteger(location.startOffset) && Number.isInteger(location.endOffset), 'Missing source offsets')
  assert.ok(
    location.startOffset >= 0 && location.endOffset >= location.startOffset && location.endOffset <= text.length,
  )
  const preceding = text.slice(0, location.startOffset).split('\n')
  assert.equal(location.line, preceding.length, 'Location line does not match source offset')
  assert.equal(location.column, preceding.at(-1).length + 1, 'Location column does not match source offset')
  return text.slice(location.startOffset, location.endOffset)
}

function paperRow(paper, node) {
  const row = paper.tables.flatMap((table) => table.rows).find((entry) => entry.node === node)
  assert.ok(row, `Paper is missing row ${node}`)
  return row
}

export function validateRecording(recording) {
  assert.equal(recording.format, 'mantra.authoring-recording/1')
  assert.equal(recording.contract, contract)
  assert.deepEqual(recording.documents, documents)
  assert.equal(recording.states.length, 32, 'All scripted states must be recorded')
  assert.equal(new Set(recording.states.map((state) => state.id)).size, 32)
  assert.deepEqual(
    recording.states.map(({ id, edits }) => ({ id, edits })),
    scriptedStates(),
  )
  const base = Object.fromEntries(
    documents.map((path) => {
      const document = recording.base[path]
      assert.equal(document.sha256, sha256(document.text), `Base checksum changed: ${path}`)
      return [path, document.text]
    }),
  )
  assert.deepEqual(recording.edits, edits, 'Scripted edit definitions changed')
  const revisions = new Set()
  for (const state of recording.states) {
    const source = editedSource(base, state.edits)
    assert.equal(state.id, state.edits.join('+') || 'base')
    assert.equal(state.digest, sourceDigest(source), `Source digest changed: ${state.id}`)
    for (const path of documents) {
      if (source[path] === base[path]) assert.equal(state.documents[path], undefined)
      else assert.deepEqual(state.documents[path], { sha256: sha256(source[path]), text: source[path] })
    }
    const invalid = state.edits.includes('formula:typo')
    for (const exchange of state.exchanges) {
      const body = blob(recording, exchange)
      assert.ok([200, 422].includes(exchange.response.status), `Unexpected response in ${state.id}`)
      if (exchange.response.status === 200) {
        assert.equal(body.contract, contract, `Contract changed in ${state.id}`)
        assert.deepEqual(body.engine, recording.engine, `Engine changed in ${state.id}`)
      }
      if (exchange.response.status === 422) {
        assert.ok(body.error?.diagnostics?.length, `Missing rejection diagnostics in ${state.id}`)
        for (const diagnostic of body.error.diagnostics) {
          if (diagnostic.location) sourceSlice(diagnostic.location, source)
          for (const related of diagnostic.related ?? []) sourceSlice(related, source)
        }
      }
    }
    for (const action of ['structure', 'run', 'paper', 'diagnostics']) {
      assert.equal(response(recording, state, action).exchange.response.status, invalid ? 422 : 200)
    }
    if (invalid) {
      const { body } = response(recording, state, 'structure')
      const unknown = body.error.diagnostics.find((diagnostic) => diagnostic.message.includes('DSL-REF-UNKNOWN-SYMBOL'))
      assert.ok(unknown, `Unknown-symbol diagnostic missing in ${state.id}`)
      assert.equal(unknown.code, 'MANTRA-FORMULA')
      assert.equal(sourceSlice(unknown.location, source), 'allocated-totl')
      assert.ok(body.error.diagnostics.some((diagnostic) => diagnostic.message.includes('DSL-TYPE-CALL-ARGUMENT')))
      continue
    }
    const structure = response(recording, state, 'structure').body
    const run = response(recording, state, 'run').body
    const paper = response(recording, state, 'paper').body
    const diagnostics = response(recording, state, 'diagnostics').body
    for (const exchange of state.exchanges) {
      if (/\/(structure|run|paper|diagnostics|explain)(\?|$)/.test(exchange.request.path)) {
        assert.equal(blob(recording, exchange).revision, structure.revision, `Mixed source revisions in ${state.id}`)
      }
    }
    assert.ok(!revisions.has(structure.revision), `Different source states share a revision: ${state.id}`)
    revisions.add(structure.revision)
    for (const node of Object.values(structure.data.nodes)) {
      if (node.formula?.location) assert.equal(sourceSlice(node.formula.location, source), node.formula.text)
    }
    const nodes = structure.data.panels.find((panel) => panel.id === recording.panel)?.nodes
    assert.ok(nodes?.length, 'Recorded authoring panel is missing')
    for (const node of nodes) {
      assert.ok(
        state.exchanges.some((entry) => entry.request.path.endsWith(`/explain?address=${encodeURIComponent(node)}`)),
      )
    }
    if (state.edits.includes('label')) {
      assert.equal(structure.data.nodes.unallocated.label, 'Unallocated request')
      assert.ok(paperRow(paper.data, 'unallocated').cells.some((cell) => cell.text === 'Unallocated request'))
    }
    if (state.edits.includes('class'))
      assert.ok(paperRow(paper.data, 'unused-total-capacity').classes.includes('result'))
    assert.equal(
      paper.data.title,
      state.edits.includes('external') ? 'Capacity, allocation and residue' : 'Capacity and conservation',
    )
    if (state.edits.includes('formula:finding')) {
      assert.equal(run.data.succeeded, true)
      assert.equal(run.data.validationPassed, false)
      assert.ok(paperRow(paper.data, 'unallocated').cells.some((cell) => cell.text === '(60.00)'))
      assert.ok(paperRow(paper.data, 'conserved').cells.some((cell) => cell.text === '✗'))
      assert.ok(diagnostics.data.diagnostics.some((diagnostic) => diagnostic.code === 'MANTRA-RECONCILE-FAILED'))
    } else if (state.edits.includes('formula:runtime')) {
      assert.equal(run.data.succeeded, false)
      assert.ok(paperRow(paper.data, 'unallocated').cells.some((cell) => cell.text === 'undefined'))
      assert.ok(
        diagnostics.data.diagnostics.some((diagnostic) => diagnostic.message.includes('DSL-RUNTIME-DIVIDE-BY-ZERO')),
      )
    } else {
      assert.equal(run.data.succeeded, true)
      assert.equal(run.data.validationPassed, true)
    }
  }
  const state = recording.states.find((entry) => entry.id === 'base')
  const run = response(recording, state, 'run').body
  const examples = state.exchanges.filter((entry) => entry.request.method === 'POST')
  assert.equal(examples.length, 2)
  const valid = examples.find((entry) => entry.request.body.operations[0].text === '9')
  const invalid = examples.find((entry) => entry.request.body.operations[0].text === 'nine')
  assert.equal(valid.response.status, 200)
  const preview = blob(recording, valid)
  assert.equal(preview.revision, run.revision)
  assert.equal(preview.data.draftSequence, 1)
  assert.notEqual(preview.data.proposedRevision, run.revision)
  assert.equal(preview.data.run.values['requested-value'][''].display, '90.00')
  assert.equal(preview.data.run.values['allocated-total'][''].display, '90.00')
  assert.equal(preview.data.run.values['unused-total-capacity'][''].display, '50.00')
  assert.equal(invalid.response.status, 422)
  assert.ok(
    blob(recording, invalid).error.diagnostics.some(
      (diagnostic) =>
        diagnostic.code === 'MANTRA-WORKBENCH-EDIT' && diagnostic.message.includes('Invalid decimal text'),
    ),
  )
}

export async function checkRecording(output = defaultOutput) {
  const recording = JSON.parse(await readFile(output, 'utf8'))
  validateRecording(recording)
  const current = await currentSource()
  for (const path of documents)
    assert.equal(sha256(current[path]), recording.base[path].sha256, `Pattern drift: ${path}`)
  for (const state of recording.states) {
    assert.equal(sourceDigest(editedSource(current, state.edits)), state.digest, `State drift: ${state.id}`)
  }
  assert.ok((await stat(output)).size <= maxBytes, 'Recording exceeds the 3 MB limit')
  console.log(`Verified ${recording.states.length} recorded states and current pattern source digests.`)
}

function request(base, path, { method = 'GET', body, token } = {}) {
  return new Promise((resolveRequest, reject) => {
    const payload = body === undefined ? undefined : JSON.stringify(body)
    const headers = payload === undefined ? {} : { 'Content-Type': 'application/json', 'X-Mantra-Token': token }
    const outgoing = httpRequest(new URL(path, base), { method, headers }, (incoming) => {
      const chunks = []
      let size = 0
      incoming.on('data', (chunk) => {
        size += chunk.length
        if (size > maxBytes) outgoing.destroy(new Error('Response exceeds recorder size budget'))
        else chunks.push(chunk)
      })
      incoming.on('error', reject)
      incoming.on('end', () =>
        resolveRequest({ status: incoming.statusCode, text: Buffer.concat(chunks).toString('utf8') }),
      )
    })
    outgoing.setTimeout(30_000, () => outgoing.destroy(new Error('Local engine request timed out')))
    outgoing.on('error', reject)
    outgoing.end(payload)
  })
}

const pause = (duration) => new Promise((resolvePause) => setTimeout(resolvePause, duration))

async function startServer(cli, workspace, ui) {
  const server = spawn(cli, ['serve', workspace, '--port', '0', '--ui', ui, '--directory-policy', 'trusted-local'], {
    cwd: repository,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let output = ''
  let failure
  let exited = false
  server.on('error', (error) => {
    failure = error
  })
  server.on('exit', () => {
    exited = true
  })
  const collect = (chunk) => {
    output = (output + chunk.toString()).slice(-1_000_000)
  }
  server.stdout.on('data', collect)
  server.stderr.on('data', collect)
  const stopOwnedProcess = async () => {
    if (exited || !server.pid) return
    const exitedPromise = new Promise((resolveExit) => server.once('exit', resolveExit))
    const kill = (signal) => {
      try {
        if (process.platform === 'win32') server.kill(signal)
        else process.kill(-server.pid, signal)
      } catch (error) {
        if (error.code !== 'ESRCH') throw error
      }
    }
    kill('SIGTERM')
    for (let attempt = 0; attempt < 100 && !exited; attempt++) await pause(100)
    if (!exited) kill('SIGKILL')
    await exitedPromise
  }
  let stopping
  const stop = () => {
    stopping ??= stopOwnedProcess()
    return stopping
  }
  try {
    for (let attempt = 0; attempt < 300; attempt++) {
      checkInterrupted()
      if (failure) throw failure
      if (exited) throw new Error('Mantra server exited before becoming ready; check the installed CLI and Java 21')
      const base = output.match(/at (http:\/\/127\.0\.0\.1:\d+\/)/)?.[1]
      if (base) return { base, stop }
      await pause(100)
    }
    throw new Error('Mantra server did not become ready within 30 seconds')
  } catch (error) {
    await stop()
    throw error
  }
}

async function recordState(recording, state, source, cli, ui) {
  const temporary = await mkdtemp(resolve(tmpdir(), 'mantra-authoring-record-'))
  let server
  try {
    const workspace = resolve(temporary, 'patterns')
    await cp(patterns, workspace, { recursive: true })
    for (const path of documents) await writeFile(resolve(workspace, path), source[path])
    server = await startServer(cli, workspace, ui)
    activeServer = server
    checkInterrupted()
    const html = await request(server.base, '/')
    assert.equal(html.status, 200, 'A built UI is required to obtain the server session metadata')
    const token = html.text.match(/<meta name="mantra-session-token" content="([a-f0-9]{64})">/)?.[1]
    assert.ok(token, 'The UI response is missing local session metadata')
    const capture = async (path, body) => {
      checkInterrupted()
      const method = body === undefined ? 'GET' : 'POST'
      const result = await request(server.base, path, { method, body, token })
      const key = sha256(result.text)
      const parsed = JSON.parse(result.text)
      recording.blobs[key] = parsed
      state.exchanges.push({
        request: { method, path, ...(body === undefined ? {} : { body }) },
        response: { status: result.status, body: { $blob: key } },
      })
      if (result.status === 200) {
        assert.equal(parsed.contract, contract)
        if (!recording.engine) recording.engine = parsed.engine
      }
      return { status: result.status, body: parsed }
    }
    await capture('/api/v1/workspace')
    const api = `/api/v1/cases/${encodeURIComponent(recording.case)}`
    const structure = await capture(`${api}/structure`)
    const run = await capture(`${api}/run`)
    await capture(`${api}/paper`)
    await capture(`${api}/diagnostics`)
    if (structure.status === 200) {
      const panel = structure.body.data.panels.find((entry) => entry.id === recording.panel)
      assert.ok(panel, 'The requested authoring panel does not exist')
      for (const node of panel.nodes) await capture(`${api}/explain?address=${encodeURIComponent(node)}`)
    }
    if (state.id === 'base') {
      await capture(`${api}/export-preview`)
      for (const [draftSequence, text] of [
        [1, '9'],
        [2, 'nine'],
      ]) {
        await capture(`${api}/preview-paper`, {
          baseRevision: run.body.revision,
          draftSequence,
          panel: recording.panel,
          operations: [{ op: 'setInput', address: { node: 'requested-units' }, text }],
        })
      }
      const unchanged = await request(server.base, `${api}/run`)
      assert.equal(JSON.parse(unchanged.text).revision, run.body.revision, 'Example preview mutated the saved case')
    }
    for (const path of documents) {
      assert.equal(
        await readFile(resolve(workspace, path), 'utf8'),
        source[path],
        'Recording mutated a source document',
      )
    }
  } finally {
    try {
      if (server) await server.stop()
    } finally {
      if (activeServer === server) activeServer = undefined
      await rm(temporary, { recursive: true, force: true })
    }
  }
}

async function main(args) {
  const options = {
    cli: resolve(repository, 'mantra-cli/build/install/mantra/bin/mantra'),
    ui: resolve(repository, 'workbench-ui/dist'),
    output: defaultOutput,
    check: false,
  }
  for (let index = 0; index < args.length; index++) {
    const name = args[index]
    if (name === '--check') options.check = true
    else if (name === '--help') {
      console.log(
        'Usage: node scripts/record-authoring-prototype.mjs [--check] [--cli PATH] [--ui PATH] [--output PATH]',
      )
      return
    } else if (['--cli', '--ui', '--output'].includes(name)) {
      assert.ok(args[index + 1] && !args[index + 1].startsWith('--'), `Missing value for ${name}`)
      options[name.slice(2)] = resolve(args[++index])
    } else throw new Error(`Unknown option: ${name}`)
  }
  if (options.check) return checkRecording(options.output)
  await stat(options.cli)
  await stat(resolve(options.ui, 'index.html'))
  const base = await currentSource()
  const recording = {
    format: 'mantra.authoring-recording/1',
    notice:
      'Real Mantra engine responses for scripted source states. Owners, source patches, save, conflict and fork are simulated; no source file is written by the prototype.',
    recordedAt: new Date().toISOString(),
    source: {
      repository: 'https://github.com/6234456/mantra',
      commit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repository, encoding: 'utf8' }).trim(),
      patternsDirty:
        execFileSync('git', ['status', '--porcelain', '--', 'docs/patterns'], { cwd: repository, encoding: 'utf8' })
          .length > 0,
    },
    contract,
    engine: null,
    workspace: 'docs/patterns',
    case: documents[0],
    panel: 'controls',
    documents,
    base: Object.fromEntries(documents.map((path) => [path, { sha256: sha256(base[path]), text: base[path] }])),
    edits,
    states: [],
    blobs: {},
  }
  for (const entry of scriptedStates()) {
    checkInterrupted()
    const source = editedSource(base, entry.edits)
    const state = {
      ...entry,
      digest: sourceDigest(source),
      documents: Object.fromEntries(
        documents
          .filter((path) => source[path] !== base[path])
          .map((path) => [path, { sha256: sha256(source[path]), text: source[path] }]),
      ),
      exchanges: [],
    }
    await recordState(recording, state, source, options.cli, options.ui)
    recording.states.push(state)
    console.log(`Recorded ${recording.states.length}/32: ${state.id}`)
  }
  checkInterrupted()
  assert.deepEqual(await currentSource(), base, 'Pattern source changed while recording; retry with stable sources')
  validateRecording(recording)
  const text = `${JSON.stringify(recording)}\n`
  const size = Buffer.byteLength(text, 'utf8')
  assert.ok(
    size <= maxBytes,
    `Recording is ${size} bytes; reduce documented states rather than omitting response fields`,
  )
  await mkdir(dirname(options.output), { recursive: true })
  const temporary = `${options.output}.${process.pid}.tmp`
  try {
    await writeFile(temporary, text)
    checkInterrupted()
    await rename(temporary, options.output)
  } finally {
    await rm(temporary, { force: true })
  }
  console.log(
    `Wrote ${recording.states.length} states, ${Object.keys(recording.blobs).length} full response blobs, ${size} bytes.`,
  )
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => {
      interrupted = signal
      process.exitCode = signal === 'SIGINT' ? 130 : 143
      // The normal finally block removes the temporary source directory after the server closes.
      activeServer?.stop().catch(() => {})
    })
  }
  main(process.argv.slice(2)).catch((error) => {
    console.error(`Authoring recording failed: ${error.message}`)
    if (!interrupted) process.exitCode = 1
  })
}
