import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const source = resolve(here, '../../mantra-workbench/src/test/resources/golden')
const target = resolve(here, '../public/fixtures')

await rm(target, { recursive: true, force: true })
if (process.env.VITE_WORKBENCH_MODE === 'live') {
  console.log('Live mode: golden fixtures excluded')
  process.exit(0)
}
await stat(resolve(source, 'index.json'))
await mkdir(dirname(target), { recursive: true })
await cp(source, target, { recursive: true })
const manifest = JSON.parse(await readFile(resolve(target, 'index.json'), 'utf8'))
const parameterIds = new Set()
for (const entry of manifest.cases) {
  const directory = dirname(resolve(target, entry.files.structure.replace('/fixtures/', '')))
  const folder = entry.files.structure.split('/')[2]
  const comparisons = {}
  for (const filename of (await readdir(directory)).filter(name => /^compare-.*\.json$/.test(name)).sort()) {
    const document = JSON.parse(await readFile(resolve(directory, filename), 'utf8'))
    const ids = document.data?.variant?.parameters
    if (!Array.isArray(ids) || !ids.every(id => typeof id === 'string')) continue
    comparisons[JSON.stringify(ids)] = `/fixtures/${folder}/${filename}`
    ids.forEach(id => parameterIds.add(id))
  }
  if (Object.keys(comparisons).length) entry.files.compares = comparisons
}
manifest.parameters = [...parameterIds].sort().map(id => ({ id, path: '' }))
await writeFile(resolve(target, 'index.json'), `${JSON.stringify(manifest)}\n`)
console.log(`Copied workbench golden fixtures to ${target}`)
