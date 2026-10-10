import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { verifyLiveOutput } from './check-live-isolation.mjs'

const golden = Buffer.from('{"contract":"example/4","data":{"captured":true}}')
const inventory = {
  fingerprints: ['mantra.authoring-recording/1', 'd'.repeat(64)],
  payloadHashes: [createHash('sha256').update(golden).digest('hex')],
  recordingStates: 32,
  goldenFiles: 1,
}

async function put(directory, path, content) {
  const target = join(directory, path)
  await mkdir(dirname(target), { recursive: true })
  await writeFile(target, content)
}

async function output(run) {
  const directory = await mkdtemp(join(tmpdir(), 'mantra-live-isolation-test-'))
  const manifest = {
    'index.html': {
      file: 'assets/main.js',
      isEntry: true,
      dynamicImports: ['src/ui/AuthoringPages.tsx', 'src/ui/TemplateDraftPreview.tsx'],
      css: ['assets/style.css'],
    },
    'src/ui/AuthoringPages.tsx': { file: 'assets/AuthoringPages.js', isDynamicEntry: true },
    'src/ui/TemplateDraftPreview.tsx': { file: 'assets/TemplateDraftPreview.js', isDynamicEntry: true },
  }
  try {
    await put(directory, 'index.html', '<script type="module" src="/assets/main.js"></script>')
    await put(directory, 'assets/main.js', `const legacyProvider = () => fetch('/fixtures/index.json');`)
    await put(directory, 'assets/style.css', '.app { color: black; }')
    await put(directory, 'assets/AuthoringPages.js', 'export const caseFormulaEditor = true;')
    await put(directory, 'assets/TemplateDraftPreview.js', 'export const liveTemplatePreview = true;')
    await put(directory, '.vite/manifest.json', JSON.stringify(manifest))
    await run(directory, manifest)
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

test('allows real live formula/template editors and provider messages while proving payload exclusion', async () => {
  await output(async (directory) => {
    const report = await verifyLiveOutput(directory, inventory)
    assert.equal(report.status, 'VERIFIED')
    assert.equal(report.files.length, 6)
    assert.equal(report.payloadHashesChecked, 1)
    assert.deepEqual(Object.keys(report.manifest), [
      'index.html',
      'src/ui/AuthoringPages.tsx',
      'src/ui/TemplateDraftPreview.tsx',
    ])
    assert.ok(report.files.every((file) => /^[a-f0-9]{64}$/.test(file.sha256)))
  })
})

test('rejects a renamed prototype chunk from manifest source provenance', async () => {
  await output(async (directory, manifest) => {
    manifest['src/authoring/AuthoringPrototype.tsx'] = { file: 'assets/innocent.js' }
    await put(directory, 'assets/innocent.js', 'export const renamed = true;')
    await put(directory, '.vite/manifest.json', JSON.stringify(manifest))
    await assert.rejects(verifyLiveOutput(directory, inventory), /Prototype module remains/)
  })
})

test('rejects stale prototype chunks even when absent from the import graph', async () => {
  await output(async (directory) => {
    await put(directory, 'assets/AuthoringPrototype-old.js', 'export const stale = true;')
    await assert.rejects(verifyLiveOutput(directory, inventory), /Fixture\/prototype file remains/)
  })
})

test('rejects fixture directories even when their bytes were modified', async () => {
  await output(async (directory) => {
    await put(directory, 'fixtures/index.json', '{"new":"fixture"}')
    await assert.rejects(verifyLiveOutput(directory, inventory), /Fixture\/prototype file remains/)
  })
})

test('rejects renamed golden payloads independently of names or manifest references', async () => {
  await output(async (directory) => {
    await put(directory, 'assets/renamed.bin', golden)
    await assert.rejects(verifyLiveOutput(directory, inventory), /Recorded\/golden payload remains/)
  })
})

test('rejects embedded recording fingerprints without relying on a prototype filename or single UI marker', async () => {
  await output(async (directory) => {
    await put(directory, 'assets/main.js', `export const opaque = '${'d'.repeat(64)}';`)
    await assert.rejects(verifyLiveOutput(directory, inventory), /Prototype\/recording evidence remains/)
  })
})

test('rejects missing imported chunks instead of treating an incomplete build as clean', async () => {
  await output(async (directory, manifest) => {
    manifest['index.html'].dynamicImports.push('src/ui/Missing.tsx')
    await put(directory, '.vite/manifest.json', JSON.stringify(manifest))
    await assert.rejects(verifyLiveOutput(directory, inventory), /Manifest dependency missing/)
  })
})

test('rejects unsafe manifest paths and entry HTML that does not load the checked graph', async () => {
  await output(async (directory, manifest) => {
    manifest['index.html'].file = '../outside.js'
    await put(directory, '.vite/manifest.json', JSON.stringify(manifest))
    await assert.rejects(verifyLiveOutput(directory, inventory), /Unsafe manifest path/)
  })
  await output(async (directory) => {
    await put(directory, 'index.html', '<script type="module" src="/unchecked.js"></script>')
    await assert.rejects(verifyLiveOutput(directory, inventory), /HTML does not load entrypoint/)
  })
})

test('rejects symlinked output instead of skipping payload files outside the build directory', async () => {
  await output(async (directory) => {
    await symlink(join(directory, 'assets/main.js'), join(directory, 'hidden.js'))
    await assert.rejects(verifyLiveOutput(directory, inventory), /contains a symlink/)
  })
})
