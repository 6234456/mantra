import { describe, expect, it } from 'vitest'
import caseText from '../../../../docs/patterns/capped-allocation/case-demo.mantra?raw'
import layoutText from '../../../../docs/patterns/capped-allocation/layout.mantra?raw'
import schemaText from '../../../../docs/patterns/capped-allocation/schema.mantra?raw'
import fragmentText from '../../../../docs/patterns/common/formulas.mantra?raw'
import recording from './recording.json'

const current: Record<string, string> = {
  'capped-allocation/case-demo.mantra': caseText,
  'capped-allocation/layout.mantra': layoutText,
  'capped-allocation/schema.mantra': schemaText,
  'common/formulas.mantra': fragmentText,
}

const base: Record<string, { sha256: string; text: string }> = recording.base
const edits: Record<string, { document: string; find: string; replace: string }> = recording.edits

async function sha256(text: string) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

describe('real authoring recording source drift', () => {
  it.each(Object.entries(current))('matches the current raw pattern source: %s', async (path, text) => {
    expect(base[path].text).toBe(text)
    expect(await sha256(text)).toBe(base[path].sha256)
  })

  it('recreates every recorded state from precise source edits and WebCrypto digests', async () => {
    expect(recording.states).toHaveLength(32)
    expect(new Set(recording.states.map((state) => state.id)).size).toBe(32)
    for (const state of recording.states) {
      const source = { ...current }
      for (const name of state.edits) {
        const edit = edits[name]
        expect(source[edit.document].split(edit.find)).toHaveLength(2)
        source[edit.document] = source[edit.document].replace(edit.find, edit.replace)
      }
      const fingerprints = await Promise.all(
        Object.keys(source)
          .sort()
          .map(async (path) => [path, await sha256(source[path])]),
      )
      expect(await sha256(JSON.stringify(fingerprints))).toBe(state.digest)
      const changes: Record<string, { text: string; sha256: string } | undefined> = state.documents
      for (const path of Object.keys(source)) {
        if (source[path] === current[path]) expect(changes[path]).toBeUndefined()
        else expect(changes[path]).toEqual({ text: source[path], sha256: await sha256(source[path]) })
      }
    }
  })

  it('retains complete responses behind every recorded exchange reference', () => {
    const blobs: Record<string, unknown> = recording.blobs
    expect(recording.format).toBe('mantra.authoring-recording/1')
    expect(recording.contract).toBe('mantra.workbench/4')
    expect(recording.source.commit).toMatch(/^[a-f0-9]{40}$/)
    for (const state of recording.states) {
      expect(state.exchanges.length).toBeGreaterThanOrEqual(5)
      for (const exchange of state.exchanges) {
        expect(exchange.response.body.$blob).toMatch(/^[a-f0-9]{64}$/)
        expect(blobs[exchange.response.body.$blob]).toBeDefined()
        expect([200, 422]).toContain(exchange.response.status)
        if (exchange.response.status === 200) {
          expect(blobs[exchange.response.body.$blob]).toMatchObject({
            contract: recording.contract,
            engine: recording.engine,
          })
        }
      }
    }
  })
})
