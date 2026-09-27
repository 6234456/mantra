import type { CaseSummary, Envelope, Explain, Paper, Run, Structure, Workspace, Address, Parameters, Diagnostics, Compare } from './types'
import { addressToPath } from './address'

export interface WorkbenchData {
  workspace(signal?: AbortSignal): Promise<Workspace>
  structure(caseId: string, signal?: AbortSignal): Promise<Envelope<Structure>>
  run(caseId: string, signal?: AbortSignal): Promise<Envelope<Run>>
  paper(caseId: string, panelId?: string, signal?: AbortSignal): Promise<Envelope<Paper>>
  explain(caseId: string, address: Address, signal?: AbortSignal): Promise<Envelope<Explain>>
  parameters(caseId: string, signal?: AbortSignal): Promise<Envelope<Parameters>>
  diagnostics(caseId: string, signal?: AbortSignal): Promise<Envelope<Diagnostics>>
  compare(caseId: string, parameterSets: string[], signal?: AbortSignal): Promise<Envelope<Compare>>
}

async function json<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}: ${url}`)
  return response.json() as Promise<T>
}

function contract<T>(raw: Envelope<T>): Envelope<T> {
  if (raw.contract !== 'mantra.workbench/1') throw new Error('Unsupported workbench contract')
  return raw
}

const apiCase = (id: string) => `/api/v1/cases/${encodeURIComponent(id)}`

export class LiveData implements WorkbenchData {
  workspace(signal?: AbortSignal) { return json<Envelope<Workspace>>('/api/v1/workspace', signal).then(response => contract(response).data) }
  structure(id: string, signal?: AbortSignal) { return json<Envelope<Structure>>(`${apiCase(id)}/structure`, signal).then(contract) }
  run(id: string, signal?: AbortSignal) { return json<Envelope<Run>>(`${apiCase(id)}/run`, signal).then(contract) }
  paper(id: string, panelId?: string, signal?: AbortSignal) {
    const query = panelId ? `?panel=${encodeURIComponent(panelId)}` : ''
    return json<Envelope<Paper>>(`${apiCase(id)}/paper${query}`, signal).then(contract)
  }
  explain(id: string, address: Address, signal?: AbortSignal) {
    return json<Envelope<Explain>>(`${apiCase(id)}/explain?address=${encodeURIComponent(addressToPath(address))}`, signal).then(contract)
  }
  parameters(id: string, signal?: AbortSignal) { return json<Envelope<Parameters>>(`${apiCase(id)}/parameters`, signal).then(contract) }
  diagnostics(id: string, signal?: AbortSignal) { return json<Envelope<Diagnostics>>(`${apiCase(id)}/diagnostics`, signal).then(contract) }
  async compare(id: string, parameterSets: string[], signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Comparison requires a workbench server session')
    const response = await fetch(`${apiCase(id)}/compare`, {
      method: 'POST', signal, headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify({ variant: { parameters: parameterSets } }),
    })
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}: comparison`)
    return contract(await response.json() as Envelope<Compare>)
  }
}

/** WP3 writes public/fixtures/index.json and one directory per case. No sample values live in UI source. */
export class FixtureData implements WorkbenchData {
  private manifest?: Promise<{ cases: Array<CaseSummary & { files: { structure: string; run: string; paper: string; parameters?: string; diagnostics?: string; compares?: Record<string, string>; explains?: Record<string, string> } }>; parameters?: Array<{ id: string; path: string }> }>
  private index() {
    // Cache the manifest independently of view cancellation. React may abort an initial
    // effect before rerunning it (including StrictMode's development remount).
    this.manifest ??= json('/fixtures/index.json')
    return this.manifest
  }
  private async file(id: string, name: 'structure' | 'run' | 'paper' | 'parameters' | 'diagnostics') {
    const entry = (await this.index()).cases.find(item => item.id === id)
    if (!entry) throw new Error(`Unknown fixture case: ${id}`)
    const path = entry.files[name]
    if (!path) throw new Error(`Fixture ${name} is unavailable for ${id}`)
    return path
  }
  workspace(_signal?: AbortSignal) { return this.index().then(index => ({ cases: index.cases, parameters: index.parameters ?? [] })) }
  async structure(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Structure>>(await this.file(id, 'structure'), signal)) }
  async run(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Run>>(await this.file(id, 'run'), signal)) }
  async paper(id: string, _panelId?: string, signal?: AbortSignal) { return contract(await json<Envelope<Paper>>(await this.file(id, 'paper'), signal)) }
  async explain(id: string, address: Address, signal?: AbortSignal) {
    const entry = (await this.index()).cases.find(item => item.id === id)
    const path = entry?.files.explains?.[addressToPath(address)]
    if (!path) throw new Error('Explain fixture unavailable')
    return contract(await json<Envelope<Explain>>(path, signal))
  }
  async parameters(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Parameters>>(await this.file(id, 'parameters'), signal)) }
  async diagnostics(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Diagnostics>>(await this.file(id, 'diagnostics'), signal)) }
  async compare(id: string, parameterSets: string[], signal?: AbortSignal) {
    const entry = (await this.index()).cases.find(item => item.id === id)
    const path = entry?.files.compares?.[JSON.stringify(parameterSets)]
    if (!path) throw new Error(`Comparison fixture unavailable for ${parameterSets.join(', ')}`)
    return contract(await json<Envelope<Compare>>(path, signal))
  }
}

export function configuredData(): WorkbenchData {
  return import.meta.env.VITE_WORKBENCH_MODE === 'live' ? new LiveData() : new FixtureData()
}
