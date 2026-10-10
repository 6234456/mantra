import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { lstat, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repository = resolve(uiRoot, '..')
const evidenceDirectory = resolve(repository, 'build/ui-qa')
const reportPath = resolve(evidenceDirectory, 'live-isolation.json')
const forbiddenPath = /(?:^|\/)(?:fixtures?|recording)(?:\/|\.|$)|authoringprototype/i
const forbiddenModule = /(?:^|\/)src\/authoring\//i
const prototypeMarkers = ['AuthoringPrototype', 'Opening authoring prototype', 'Prototype · recorded engine data']

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex')
const slash = (path) => path.split(sep).join('/')

async function filesIn(directory, prefix = '') {
  const files = []
  for (const entry of (await readdir(directory, { withFileTypes: true })).sort((a, b) =>
    a.name.localeCompare(b.name),
  )) {
    const path = join(directory, entry.name)
    const name = prefix ? `${prefix}/${entry.name}` : entry.name
    assert.equal((await lstat(path)).isSymbolicLink(), false, `Build/input inventory contains a symlink: ${name}`)
    if (entry.isDirectory()) files.push(...(await filesIn(path, name)))
    else if (entry.isFile()) files.push({ path, name, bytes: await readFile(path) })
    else assert.fail(`Unsupported build/input inventory entry: ${name}`)
  }
  return files
}

/** The inventory comes from the full source recordings/goldens, never a truncated Paper preview. */
export async function isolationInventory(root = repository) {
  const recordingBytes = await readFile(resolve(root, 'workbench-ui/src/authoring/recording/recording.json'))
  const recording = JSON.parse(recordingBytes.toString('utf8'))
  const goldens = await filesIn(resolve(root, 'mantra-workbench/src/test/resources/golden'))
  return {
    fingerprints: [
      ...new Set([
        recording.format,
        ...recording.states.map((state) => state.digest),
        ...Object.keys(recording.blobs),
        ...Object.values(recording.base).map((document) => document.sha256),
      ]),
    ],
    payloadHashes: [...new Set([digest(recordingBytes), ...goldens.map((file) => digest(file.bytes))])],
    recordingStates: recording.states.length,
    goldenFiles: goldens.length,
  }
}

function artifactPath(path) {
  assert.equal(typeof path, 'string', 'Manifest artifact path must be a string')
  assert.ok(
    path && !isAbsolute(path) && !path.includes('\\') && !path.split('/').includes('..'),
    `Unsafe manifest path: ${path}`,
  )
  assert.equal(forbiddenPath.test(path), false, `Fixture/prototype artifact path: ${path}`)
  return path
}

/** Check every emitted file and the complete Vite graph, including unreferenced leftover files. */
export async function verifyLiveOutput(directory, inventory) {
  const files = await filesIn(directory)
  const byName = new Map(files.map((file) => [file.name, file]))
  assert.ok(byName.has('index.html'), 'Fresh live build is missing index.html')
  assert.ok(byName.has('.vite/manifest.json'), 'Fresh live build is missing its Vite manifest')
  const manifest = JSON.parse(byName.get('.vite/manifest.json').bytes.toString('utf8'))
  assert.ok(manifest && typeof manifest === 'object' && !Array.isArray(manifest), 'Invalid Vite manifest')
  const entries = Object.entries(manifest)
  assert.ok(entries.length > 0, 'Vite manifest is empty')
  const entrypoints = entries.filter(([, chunk]) => chunk.isEntry)
  assert.ok(entrypoints.length > 0, 'Vite manifest has no entrypoint')
  for (const [key, chunk] of entries) {
    assert.equal(forbiddenModule.test(key), false, `Prototype module remains in the live graph: ${key}`)
    assert.equal(forbiddenModule.test(chunk.src ?? ''), false, `Prototype source remains in the live graph: ${key}`)
    assert.equal(forbiddenPath.test(key), false, `Fixture/prototype manifest entry: ${key}`)
    const references = [chunk.file, ...(chunk.css ?? []), ...(chunk.assets ?? [])]
    for (const reference of references)
      assert.ok(byName.has(artifactPath(reference)), `Manifest file missing: ${reference}`)
    for (const dependency of [...(chunk.imports ?? []), ...(chunk.dynamicImports ?? [])]) {
      assert.ok(Object.hasOwn(manifest, dependency), `Manifest dependency missing: ${key} → ${dependency}`)
    }
  }
  const html = byName.get('index.html').bytes.toString('utf8')
  for (const [, entry] of entrypoints)
    assert.ok(html.includes(entry.file), `HTML does not load entrypoint: ${entry.file}`)
  const hashes = new Set(inventory.payloadHashes)
  for (const file of files) {
    assert.equal(forbiddenPath.test(file.name), false, `Fixture/prototype file remains in live output: ${file.name}`)
    assert.equal(hashes.has(digest(file.bytes)), false, `Recorded/golden payload remains in live output: ${file.name}`)
    const content = file.bytes.toString('utf8')
    for (const marker of [...prototypeMarkers, ...inventory.fingerprints]) {
      assert.equal(content.includes(marker), false, `Prototype/recording evidence remains in ${file.name}: ${marker}`)
    }
  }
  return {
    format: 'mantra.live-isolation/1',
    status: 'VERIFIED',
    mode: 'live',
    recordingStatesChecked: inventory.recordingStates,
    goldenFilesChecked: inventory.goldenFiles,
    payloadFingerprintsChecked: inventory.fingerprints.length,
    payloadHashesChecked: inventory.payloadHashes.length,
    manifest,
    files: files.map((file) => ({ path: file.name, bytes: file.bytes.length, sha256: digest(file.bytes) })),
  }
}

async function build(directory) {
  const child = spawn('npm', ['run', 'build', '--', '--outDir', directory, '--emptyOutDir', '--manifest'], {
    cwd: uiRoot,
    env: { ...process.env, VITE_WORKBENCH_MODE: 'live' },
    stdio: 'inherit',
    detached: true,
  })
  let interrupted
  const stop = (signal) => {
    interrupted = signal
    if (!child.pid) return
    try {
      process.kill(-child.pid, signal)
    } catch (error) {
      if (error.code !== 'ESRCH') throw error
    }
  }
  const onInterrupt = () => stop('SIGINT')
  const onTerminate = () => stop('SIGTERM')
  process.once('SIGINT', onInterrupt)
  process.once('SIGTERM', onTerminate)
  try {
    await new Promise((done, reject) => {
      child.once('error', reject)
      child.once('exit', (code, signal) => {
        if (code === 0 && !interrupted) done()
        else reject(new Error(`Live build failed: ${interrupted ?? signal ?? `exit ${code}`}`))
      })
    })
  } finally {
    process.removeListener('SIGINT', onInterrupt)
    process.removeListener('SIGTERM', onTerminate)
  }
}

export async function checkFreshLiveBuild() {
  await mkdir(evidenceDirectory, { recursive: true })
  await rm(reportPath, { force: true })
  const directory = await mkdtemp(join(evidenceDirectory, 'live-isolation-'))
  try {
    await build(directory)
    const report = await verifyLiveOutput(directory, await isolationInventory())
    report.freshOutput = true
    report.outputDirectory = slash(relative(repository, directory))
    await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`)
    console.log(
      `Verified fresh live build: ${report.files.length} files; complete Vite graph, recording and golden payloads excluded`,
    )
    console.log(`Evidence: ${reportPath}`)
    return report
  } catch (error) {
    await writeFile(
      reportPath,
      `${JSON.stringify({ format: 'mantra.live-isolation/1', status: 'FAILED', reason: error.message }, null, 2)}\n`,
    )
    throw error
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  assert.equal(
    process.argv.length,
    2,
    'This check always builds in a fresh owned directory; it accepts no existing dist path',
  )
  await checkFreshLiveBuild()
}
