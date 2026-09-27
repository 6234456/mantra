import { cp, mkdir, rm, stat } from 'node:fs/promises'
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
console.log(`Copied workbench golden fixtures to ${target}`)
