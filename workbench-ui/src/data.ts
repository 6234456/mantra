import type {
  CaseSummary,
  Envelope,
  Explain,
  ExportPreview,
  Paper,
  Run,
  Structure,
  Workspace,
  Address,
  Parameters,
  Diagnostics,
  Compare,
  EditOperation,
  EditResult,
  AuthoringTarget,
  AuthoringCheck,
  AuthoringCompletion,
  AuthoringHover,
  FormulaOperation,
  FormulaEditResult,
  Sources,
  ImportInspection,
  ImportTemplate,
} from './types'
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
  exportPreview(caseId: string, sheet?: string, layout?: string, signal?: AbortSignal): Promise<Envelope<ExportPreview>>
  exportUrl(caseId: string, format: 'xlsx' | 'html' | 'txt', layout?: string): string | undefined
  edit(
    caseId: string,
    baseRevision: string,
    operations: EditOperation[],
    preview?: boolean,
  ): Promise<Envelope<EditResult>>
  authoring(
    caseId: string,
    action: 'complete',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringCompletion>>
  authoring(
    caseId: string,
    action: 'hover',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringHover>>
  authoring(caseId: string, action: 'check', target: AuthoringTarget, source: string): Promise<Envelope<AuthoringCheck>>
  formulaEdit(
    caseId: string,
    baseRevision: string,
    operation: FormulaOperation,
    preview: boolean,
  ): Promise<Envelope<FormulaEditResult>>
  sources(caseId: string, signal?: AbortSignal): Promise<Envelope<Sources>>
  removeSource(caseId: string, baseRevision: string, index: number): Promise<Envelope<Sources>>
  importInspect(
    caseId: string,
    name: string,
    format: string,
    contentBase64: string,
  ): Promise<Envelope<ImportInspection>>
  importApply(
    caseId: string,
    name: string,
    format: string,
    contentBase64: string,
    baseRevision: string,
    options: Record<string, unknown>,
  ): Promise<Envelope<EditResult>>
  importTemplates(signal?: AbortSignal): Promise<Envelope<{ templates: ImportTemplate[] }>>
  saveImportTemplate(template: ImportTemplate): Promise<Envelope<{ templates: ImportTemplate[] }>>
}

async function json<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}: ${url}`)
  return response.json() as Promise<T>
}

function contract<T>(raw: Envelope<T>): Envelope<T> {
  if (raw.contract !== 'mantra.workbench/3') throw new Error('Unsupported workbench contract')
  return raw
}

const apiCase = (id: string) => `/api/v1/cases/${encodeURIComponent(id)}`

export class LiveData implements WorkbenchData {
  private async post<T>(url: string, body: unknown): Promise<Envelope<T>> {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('A workbench server session is required')
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify(body),
    })
    const payload = await response.json()
    if (!response.ok)
      throw new Error(
        payload?.error?.diagnostics?.[0]?.message ??
          payload?.error?.message ??
          `${response.status} ${response.statusText}`,
      )
    return contract(payload as Envelope<T>)
  }
  sources(id: string, signal?: AbortSignal) {
    return json<Envelope<Sources>>(`${apiCase(id)}/sources`, signal).then(contract)
  }
  removeSource(id: string, baseRevision: string, index: number) {
    return this.post<Sources>(`${apiCase(id)}/sources/remove`, { baseRevision, index })
  }
  importInspect(id: string, name: string, format: string, contentBase64: string) {
    return this.post<ImportInspection>(`${apiCase(id)}/imports/inspect`, { name, format, contentBase64 })
  }
  importApply(
    id: string,
    name: string,
    format: string,
    contentBase64: string,
    baseRevision: string,
    options: Record<string, unknown>,
  ) {
    return this.post<EditResult>(`${apiCase(id)}/imports/apply`, { name, format, contentBase64, baseRevision, options })
  }
  importTemplates(signal?: AbortSignal) {
    return json<Envelope<{ templates: ImportTemplate[] }>>('/api/v1/import-templates', signal).then(contract)
  }
  saveImportTemplate(template: ImportTemplate) {
    return this.post<{ templates: ImportTemplate[] }>('/api/v1/import-templates', template)
  }
  async edit(id: string, baseRevision: string, operations: EditOperation[], preview = false) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Editing requires a workbench server session')
    const response = await fetch(`${apiCase(id)}/${preview ? 'preview' : 'edits'}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify({ baseRevision, operations }),
    })
    const payload = await response.json()
    if (!response.ok) {
      const detail = payload?.error
      const diagnostic = detail?.diagnostics?.[0]?.message
      throw new Error(diagnostic ?? detail?.message ?? `${response.status} ${response.statusText}`)
    }
    return contract(payload as Envelope<EditResult>)
  }
  authoring(
    id: string,
    action: 'complete',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringCompletion>>
  authoring(
    id: string,
    action: 'hover',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringHover>>
  authoring(id: string, action: 'check', target: AuthoringTarget, source: string): Promise<Envelope<AuthoringCheck>>
  authoring(
    id: string,
    action: 'complete' | 'hover' | 'check',
    target: AuthoringTarget,
    source: string,
    cursorOffset?: number,
  ): Promise<Envelope<AuthoringCompletion | AuthoringHover | AuthoringCheck>> {
    return this.post(`${apiCase(id)}/authoring/${action}`, {
      target,
      source,
      ...(cursorOffset === undefined ? {} : { cursorOffset }),
    })
  }
  formulaEdit(id: string, baseRevision: string, operation: FormulaOperation, preview: boolean) {
    return this.post<FormulaEditResult>(`${apiCase(id)}/${preview ? 'preview' : 'edits'}`, {
      baseRevision,
      operations: [operation],
    })
  }
  workspace(signal?: AbortSignal) {
    return json<Envelope<Workspace>>('/api/v1/workspace', signal).then((response) => contract(response).data)
  }
  structure(id: string, signal?: AbortSignal) {
    return json<Envelope<Structure>>(`${apiCase(id)}/structure`, signal).then(contract)
  }
  run(id: string, signal?: AbortSignal) {
    return json<Envelope<Run>>(`${apiCase(id)}/run`, signal).then(contract)
  }
  paper(id: string, panelId?: string, signal?: AbortSignal) {
    const query = panelId ? `?panel=${encodeURIComponent(panelId)}` : ''
    return json<Envelope<Paper>>(`${apiCase(id)}/paper${query}`, signal).then(contract)
  }
  explain(id: string, address: Address, signal?: AbortSignal) {
    return json<Envelope<Explain>>(
      `${apiCase(id)}/explain?address=${encodeURIComponent(addressToPath(address))}`,
      signal,
    ).then(contract)
  }
  parameters(id: string, signal?: AbortSignal) {
    return json<Envelope<Parameters>>(`${apiCase(id)}/parameters`, signal).then(contract)
  }
  diagnostics(id: string, signal?: AbortSignal) {
    return json<Envelope<Diagnostics>>(`${apiCase(id)}/diagnostics`, signal).then(contract)
  }
  async compare(id: string, parameterSets: string[], signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Comparison requires a workbench server session')
    const response = await fetch(`${apiCase(id)}/compare`, {
      method: 'POST',
      signal,
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify({ variant: { parameters: parameterSets } }),
    })
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}: comparison`)
    return contract((await response.json()) as Envelope<Compare>)
  }
  exportPreview(id: string, sheet?: string, layout?: string, signal?: AbortSignal) {
    const query = new URLSearchParams()
    if (sheet) query.set('sheet', sheet)
    if (layout) query.set('layout', layout)
    return json<Envelope<ExportPreview>>(`${apiCase(id)}/export-preview${query.size ? `?${query}` : ''}`, signal).then(
      contract,
    )
  }
  exportUrl(id: string, format: 'xlsx' | 'html' | 'txt', layout?: string) {
    return `${apiCase(id)}/export.${format}${layout ? `?layout=${encodeURIComponent(layout)}` : ''}`
  }
}

/** WP3 writes public/fixtures/index.json and one directory per case. No sample values live in UI source. */
export class FixtureData implements WorkbenchData {
  importTemplates(): Promise<Envelope<{ templates: ImportTemplate[] }>> {
    return Promise.resolve({
      contract: 'mantra.workbench/3',
      revision: '',
      engine: { mantra: '', normein: '' },
      data: { templates: [] },
    })
  }
  saveImportTemplate(_template: ImportTemplate): Promise<Envelope<{ templates: ImportTemplate[] }>> {
    return Promise.reject(new Error('Saving templates requires a workbench server session'))
  }
  sources(_id: string): Promise<Envelope<Sources>> {
    return Promise.resolve({
      contract: 'mantra.workbench/3',
      revision: '',
      engine: { mantra: '', normein: '' },
      data: { sources: [] },
    })
  }
  removeSource(_id: string, _baseRevision: string, _index: number): Promise<Envelope<Sources>> {
    return Promise.reject(new Error('Editing requires a workbench server session'))
  }
  importInspect(
    _id: string,
    _name: string,
    _format: string,
    _contentBase64: string,
  ): Promise<Envelope<ImportInspection>> {
    return Promise.reject(new Error('Import requires a workbench server session'))
  }
  importApply(
    _id: string,
    _name: string,
    _format: string,
    _contentBase64: string,
    _baseRevision: string,
    _options: Record<string, unknown>,
  ): Promise<Envelope<EditResult>> {
    return Promise.reject(new Error('Import requires a workbench server session'))
  }
  edit(
    _id: string,
    _baseRevision: string,
    _operations: EditOperation[],
    _preview = false,
  ): Promise<Envelope<EditResult>> {
    return Promise.reject(new Error('Editing requires a workbench server session'))
  }
  authoring(
    _id: string,
    action: 'complete',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringCompletion>>
  authoring(
    _id: string,
    action: 'hover',
    target: AuthoringTarget,
    source: string,
    cursorOffset: number,
  ): Promise<Envelope<AuthoringHover>>
  authoring(_id: string, action: 'check', target: AuthoringTarget, source: string): Promise<Envelope<AuthoringCheck>>
  authoring(): Promise<never> {
    return Promise.reject(new Error('Authoring requires a workbench server session'))
  }
  formulaEdit(
    _id: string,
    _baseRevision: string,
    _operation: FormulaOperation,
    _preview: boolean,
  ): Promise<Envelope<FormulaEditResult>> {
    return Promise.reject(new Error('Editing requires a workbench server session'))
  }
  private manifest?: Promise<{
    cases: Array<
      CaseSummary & {
        files: {
          structure: string
          run: string
          paper: string
          parameters?: string
          diagnostics?: string
          compares?: Record<string, string>
          explains?: Record<string, string>
          'export-preview'?: string
          [key: string]: string | Record<string, string> | undefined
        }
      }
    >
    parameters?: Array<{ id: string; path: string }>
  }>
  private index() {
    // Cache the manifest independently of view cancellation. React may abort an initial
    // effect before rerunning it (including StrictMode's development remount).
    this.manifest ??= json('/fixtures/index.json')
    return this.manifest
  }
  private async file(id: string, name: 'structure' | 'run' | 'paper' | 'parameters' | 'diagnostics') {
    const entry = (await this.index()).cases.find((item) => item.id === id)
    if (!entry) throw new Error(`Unknown fixture case: ${id}`)
    const path = entry.files[name]
    if (!path) throw new Error(`Fixture ${name} is unavailable for ${id}`)
    return path
  }
  workspace(_signal?: AbortSignal) {
    return this.index().then((index) => ({ cases: index.cases, parameters: index.parameters ?? [] }))
  }
  async structure(id: string, signal?: AbortSignal) {
    return contract(await json<Envelope<Structure>>(await this.file(id, 'structure'), signal))
  }
  async run(id: string, signal?: AbortSignal) {
    return contract(await json<Envelope<Run>>(await this.file(id, 'run'), signal))
  }
  async paper(id: string, _panelId?: string, signal?: AbortSignal) {
    return contract(await json<Envelope<Paper>>(await this.file(id, 'paper'), signal))
  }
  async explain(id: string, address: Address, signal?: AbortSignal) {
    const entry = (await this.index()).cases.find((item) => item.id === id)
    const path = entry?.files.explains?.[addressToPath(address)]
    if (!path) throw new Error('Explain fixture unavailable')
    return contract(await json<Envelope<Explain>>(path, signal))
  }
  async parameters(id: string, signal?: AbortSignal) {
    return contract(await json<Envelope<Parameters>>(await this.file(id, 'parameters'), signal))
  }
  async diagnostics(id: string, signal?: AbortSignal) {
    return contract(await json<Envelope<Diagnostics>>(await this.file(id, 'diagnostics'), signal))
  }
  async compare(id: string, parameterSets: string[], signal?: AbortSignal) {
    const entry = (await this.index()).cases.find((item) => item.id === id)
    const path = entry?.files.compares?.[JSON.stringify(parameterSets)]
    if (!path) throw new Error(`Comparison fixture unavailable for ${parameterSets.join(', ')}`)
    return contract(await json<Envelope<Compare>>(path, signal))
  }
  async exportPreview(id: string, sheet?: string, _layout?: string, signal?: AbortSignal) {
    const entry = (await this.index()).cases.find((item) => item.id === id)
    const path = entry?.files[sheet ? `export-preview:${sheet}` : 'export-preview']
    if (typeof path !== 'string') throw new Error('Export preview fixture unavailable')
    return contract(await json<Envelope<ExportPreview>>(path, signal))
  }
  exportUrl(_id: string, _format: 'xlsx' | 'html' | 'txt', _layout?: string) {
    return undefined
  }
}

export function configuredData(): WorkbenchData {
  return import.meta.env.VITE_WORKBENCH_MODE === 'live' ? new LiveData() : new FixtureData()
}
