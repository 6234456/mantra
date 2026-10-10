// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PackageData } from './PackageData'
import { WorkbenchReadError } from '../data'
import compareFixture from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json'

const baselineRevision = 'b'.repeat(64)
const comparisonRevision = 'c'.repeat(64)
const caseId = 'demo/cases/sample case.mantra'
const scopedParameters = ['rates-old/rates/shared', 'rates-new/rates/shared']
const effectiveDate = '2027-06-30'

function wrapper(document: unknown, revision = baselineRevision, editable = false) {
  return {
    contract: 'mantra.packages/1',
    revision,
    data: { binding: { editableCase: editable, resourcesReadOnly: true }, document, succeeded: true },
  }
}

function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

function baseline() {
  return response(wrapper({ contract: 'mantra.workbench/4', revision: baselineRevision, data: { values: {} } }))
}

function comparison() {
  const document = structuredClone(compareFixture)
  document.revision = comparisonRevision
  document.data.variant.parameters = scopedParameters
  document.data.parameterChanges[0].variantSource = scopedParameters[1]
  return document
}

function session() {
  document.head.innerHTML = '<meta name="mantra-session-token" content="test-session-token">'
}

afterEach(() => {
  vi.unstubAllGlobals()
  document.head.innerHTML = ''
})

describe('captured package scenario transport', () => {
  it('compares read-only cases using an explicit date, current revision and the existing session token', async () => {
    session()
    const expected = comparison()
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(baseline())
      .mockResolvedValueOnce(response(wrapper(expected, comparisonRevision)))
    vi.stubGlobal('fetch', fetch)
    const data = new PackageData()
    const controller = new AbortController()
    const result = await data.compare(caseId, scopedParameters, controller.signal, effectiveDate)
    expect(result).toEqual(expected)
    expect(result.data.variant.parameters).toEqual(scopedParameters)
    expect(result.data.parameterChanges[0].variantSource).toBe(scopedParameters[1])
    expect(data.canEditCase(caseId)).toBe(false)
    expect(data.canCompareScenarios()).toBe(true)
    expect(data.parameterComparisonDateRequired()).toBe(true)
    expect(data.canCompareParameters()).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[0]).toEqual([
      '/api/v1/package-cases/demo%2Fcases%2Fsample%20case.mantra/run',
      { signal: controller.signal },
    ])
    const [url, options] = fetch.mock.calls[1]
    expect(url).toBe('/api/v1/package-cases/demo%2Fcases%2Fsample%20case.mantra/compare')
    expect(options.signal).toBe(controller.signal)
    expect(options.method).toBe('POST')
    expect(options.headers).toEqual({ 'Content-Type': 'application/json', 'X-Mantra-Token': 'test-session-token' })
    expect(JSON.parse(options.body)).toEqual({
      variantParameters: scopedParameters,
      effectiveDate,
      expectedRevision: baselineRevision,
    })
  })

  it('retains package-scoped parameter ids from the catalog and accepts older catalogs without descriptors', async () => {
    const packages = scopedParameters.map((id, index) => ({
      mount: `rates-${index}`,
      id: `fictional.rates.${index}`,
      version: '1.0.0',
      readOnly: true,
      cases: [],
      parameters: [{ id, parameterId: 'rates/shared', path: 'params.mantra' }],
    }))
    const fetch = vi.fn().mockResolvedValueOnce(
      response({
        contract: 'mantra.packages/1',
        revision: baselineRevision,
        data: { packages: [...packages, { mount: 'older', id: 'older.package', version: '1.0.0', cases: [] }] },
      }),
    )
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const workspace = await new PackageData().workspace(controller.signal)
    expect(workspace.parameters).toEqual(scopedParameters.map((id) => ({ id, path: id })))
    expect(fetch.mock.calls[0]).toEqual(['/api/v1/packages', { signal: controller.signal }])
  })

  it('sends no request when the required effective date is absent or malformed', async () => {
    session()
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const data = new PackageData()
    for (const date of [undefined, '', '2027/06/30', '2027-6-30']) {
      await expect(data.compare(caseId, scopedParameters, undefined, date)).rejects.toThrow('explicit effective date')
    }
    expect(fetch).not.toHaveBeenCalled()
  })

  it('requires session metadata before sending the comparison POST', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(baseline())
    vi.stubGlobal('fetch', fetch)
    await expect(new PackageData().compare(caseId, scopedParameters, undefined, effectiveDate)).rejects.toThrow(
      'session',
    )
    expect(fetch).toHaveBeenCalledOnce()
    expect(fetch.mock.calls[0][0]).toContain('/run')
  })

  it('propagates stale-baseline conflicts and never substitutes a previous comparison', async () => {
    session()
    const freshRevision = 'd'.repeat(64)
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(baseline())
      .mockResolvedValueOnce(
        response(
          {
            error: { message: 'Baseline changed', currentRevision: freshRevision },
          },
          409,
        ),
      )
    vi.stubGlobal('fetch', fetch)
    const pending = new PackageData().compare(caseId, scopedParameters, undefined, effectiveDate)
    await expect(pending).rejects.toBeInstanceOf(WorkbenchReadError)
    await expect(pending).rejects.toMatchObject({
      status: 409,
      message: 'Baseline changed',
      currentRevision: freshRevision,
    })
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('aborts the baseline read without issuing a comparison POST', async () => {
    session()
    const fetch = vi.fn(
      (_url: string, options: { signal: AbortSignal }) =>
        new Promise<Response>((_resolve, reject) => {
          options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
            once: true,
          })
        }),
    )
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const pending = new PackageData().compare(caseId, scopedParameters, controller.signal, effectiveDate)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await rejected
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('uses the same cancellation signal for the active comparison POST', async () => {
    session()
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(baseline())
      .mockImplementationOnce(
        (_url: string, options: { signal: AbortSignal }) =>
          new Promise<Response>((_resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), {
              once: true,
            })
          }),
      )
    vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    const pending = new PackageData().compare(caseId, scopedParameters, controller.signal, effectiveDate)
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    expect(fetch.mock.calls[1][1].signal).toBe(controller.signal)
    controller.abort()
    await rejected
  })
})
