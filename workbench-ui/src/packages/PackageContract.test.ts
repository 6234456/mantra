// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PackageData } from './PackageData'
import type { PackageEnvelope, PackageDocument, ParameterSource } from './contract'

const envelope = (data: unknown) => ({ contract: 'mantra.packages/1', revision: 'a'.repeat(64), data })
afterEach(() => vi.unstubAllGlobals())
describe('schema-generated package boundary', () => {
  it('rejects malformed wrapper revision data shape and extra fields before casting generated types', async () => {
    for (const body of [
      { ...envelope({ packages: [] }), revision: 3 },
      { ...envelope({ packages: [] }), revision: 'short' },
      envelope([]),
      envelope(null),
      { ...envelope({ packages: [] }), invented: true },
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))))
      await expect(new PackageData().index()).rejects.toThrow('Unsupported package contract')
    }
  })
  it('preserves generated dated source fields and explicit null declared eligibility', async () => {
    const source: ParameterSource = {
      case: 'sample/cases/demo.mantra',
      key: 'rate',
      set: 'annual',
      packageId: 'sample',
      packageVersion: '1.0.0',
      packageRevision: 'b'.repeat(64),
      schema: 'sample/schema',
      schemaVersion: '1.0.0',
      resource: 'parameters/annual.mantra',
      sha256: 'c'.repeat(64),
      effectiveDate: null,
      validFrom: '2026-01-01',
      validUntil: '2027-01-01',
      endExclusive: true,
      mode: 'declared',
      validForDate: null,
      reference: null,
      effectiveLayer: 'parameters',
      effectiveValue: { n: '0' },
      selectedSetValue: { n: '0' },
      overriddenByCase: false,
    }
    const expected: PackageEnvelope<PackageDocument<unknown>> = {
      contract: 'mantra.packages/1',
      revision: 'a'.repeat(64),
      data: {
        case: source.case,
        succeeded: true,
        parameterSources: [source],
        document: {
          contract: 'mantra.workbench/4',
          revision: 'a'.repeat(64),
          engine: { mantra: '0.4.0-SNAPSHOT', normein: 'locked' },
          data: { parameters: [] },
        },
      },
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(expected))))
    const document = await new PackageData().wrapper('sample/cases/demo.mantra', 'parameters')
    expect(document.data.parameterSources?.[0]).toEqual(source)
    expect(document.data.parameterSources?.[0].validForDate).toBeNull()
    expect(document.data.parameterSources?.[0].effectiveValue).toEqual({ n: '0' })
  })
})
