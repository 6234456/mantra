import type { CaseSummary, Envelope, Explain, ExportPreview, Paper, Run, Structure, Workspace, Address } from './types'
import { addressToPath } from './address'

export interface WorkbenchData {
  workspace(signal?: AbortSignal): Promise<Workspace>
  structure(caseId: string, signal?: AbortSignal): Promise<Envelope<Structure>>
  run(caseId: string, signal?: AbortSignal): Promise<Envelope<Run>>
  paper(caseId: string, panelId?: string, signal?: AbortSignal): Promise<Envelope<Paper>>
  explain(caseId: string, address: Address, signal?: AbortSignal): Promise<Envelope<Explain>>
  exportPreview(caseId: string, sheet?: string, layout?: string, signal?: AbortSignal): Promise<Envelope<ExportPreview>>
  exportUrl(caseId: string, format: 'xlsx' | 'html' | 'txt', layout?: string): string | undefined
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
  exportPreview(id: string, sheet?: string, layout?: string, signal?: AbortSignal) {
    const query = new URLSearchParams()
    if (sheet) query.set('sheet', sheet)
    if (layout) query.set('layout', layout)
    return json<Envelope<ExportPreview>>(`${apiCase(id)}/export-preview${query.size ? `?${query}` : ''}`, signal).then(contract)
  }
  exportUrl(id: string, format: 'xlsx' | 'html' | 'txt', layout?: string) {
    return `${apiCase(id)}/export.${format}${layout ? `?layout=${encodeURIComponent(layout)}` : ''}`
  }
}

/** WP3 writes public/fixtures/index.json and one directory per case. No sample values live in UI source. */
export class FixtureData implements WorkbenchData {
  private manifest?: Promise<{ cases: Array<CaseSummary & { files: { structure: string; run: string; paper: string; 'export-preview'?: string; explains?: Record<string, string>; [key: string]: string | Record<string, string> | undefined } }> }>
  private index() {
    // Cache the manifest independently of view cancellation. React may abort an initial
    // effect before rerunning it (including StrictMode's development remount).
    this.manifest ??= json('/fixtures/index.json')
    return this.manifest
  }
  private async file(id: string, name: 'structure' | 'run' | 'paper') {
    const entry = (await this.index()).cases.find(item => item.id === id)
    if (!entry) throw new Error(`Unknown fixture case: ${id}`)
    return entry.files[name]
  }
  workspace(_signal?: AbortSignal) { return this.index().then(index => ({ cases: index.cases })) }
  async structure(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Structure>>(await this.file(id, 'structure'), signal)) }
  async run(id: string, signal?: AbortSignal) { return contract(await json<Envelope<Run>>(await this.file(id, 'run'), signal)) }
  async paper(id: string, _panelId?: string, signal?: AbortSignal) { return contract(await json<Envelope<Paper>>(await this.file(id, 'paper'), signal)) }
  async explain(id: string, address: Address, signal?: AbortSignal) {
    const entry = (await this.index()).cases.find(item => item.id === id)
    const path = entry?.files.explains?.[addressToPath(address)]
    if (!path) throw new Error('Explain fixture unavailable')
    return contract(await json<Envelope<Explain>>(path, signal))
  }
  async exportPreview(id: string, _sheet?: string, _layout?: string, signal?: AbortSignal) {
    const entry = (await this.index()).cases.find(item => item.id === id)
    const path = entry?.files[_sheet ? `export-preview:${_sheet}` : 'export-preview']
    if (!path) throw new Error('Export preview fixture unavailable')
    return contract(await json<Envelope<ExportPreview>>(path as string, signal))
  }
  exportUrl(_id: string, _format: 'xlsx' | 'html' | 'txt', _layout?: string) { return undefined }
}

export function configuredData(): WorkbenchData {
  return import.meta.env.VITE_WORKBENCH_MODE === 'live' ? new LiveData() : new FixtureData()
}
