import { LiveData, WorkbenchReadError } from '../data'
import { addressToPath } from '../address'
import type {
  Address,
  Compare,
  Diagnostics,
  EditOperation,
  EditResult,
  Envelope,
  Explain,
  ExportPreview,
  FormulaEditResult,
  FormulaOperation,
  Paper,
  Parameters,
  Run,
  Structure,
  SourceContext,
  Workspace,
} from '../types'

// Package wire declarations are generated from packages.schema.json.
export type {
  PackageCase,
  MountedPackage,
  ParameterSource,
  MigrationPreview,
  PackageDocument,
  PackageEnvelope,
} from './contract'
import type { MountedPackage, MigrationPreview, PackageDocument, PackageEnvelope } from './contract'

/** Adapter to the existing UI and strict workbench/4 projections. No fallback to workspace files. */
export class PackageData extends LiveData {
  override previewPaper = undefined

  canManageSources() {
    return false
  }
  canAuthorCase() {
    return false
  }
  canCompareParameters() {
    return false
  }
  canCompareScenarios() {
    return true
  }
  parameterComparisonDateRequired() {
    return true
  }
  private readonly editableCases = new Map<string, boolean>()
  canEditCase(id: string) {
    return this.editableCases.get(id) === true
  }
  private path(id: string, name: string) {
    return `/api/v1/package-cases/${encodeURIComponent(id)}/${name}`
  }
  async request<T>(url: string, body?: unknown, signal?: AbortSignal): Promise<PackageEnvelope<T>> {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (body !== undefined && !token) throw new Error('A workbench server session is required')
    const response = await fetch(url, {
      signal,
      ...(body === undefined
        ? {}
        : {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token! },
            body: JSON.stringify(body),
          }),
    })
    const raw = await response.json()
    if (!response.ok)
      throw new WorkbenchReadError(
        response.status,
        raw?.error?.message ?? response.statusText,
        raw?.error?.currentRevision,
      )
    if (
      raw?.contract !== 'mantra.packages/1' ||
      Object.keys(raw).sort().join(',') !== 'contract,data,revision' ||
      (raw.revision !== null && (typeof raw.revision !== 'string' || !/^[0-9a-f]{64}$/.test(raw.revision))) ||
      !raw.data ||
      typeof raw.data !== 'object' ||
      Array.isArray(raw.data)
    ) {
      throw new Error('Unsupported package contract')
    }
    return raw as PackageEnvelope<T>
  }
  index(signal?: AbortSignal) {
    return this.request<{ packages: MountedPackage[] }>('/api/v1/packages', undefined, signal)
  }
  async wrapper<T>(id: string, name: string, signal?: AbortSignal) {
    const raw = await this.request<PackageDocument<T>>(this.path(id, name), undefined, signal)
    this.editableCases.set(id, raw.data.binding?.editableCase === true)
    return raw
  }
  private unwrap<T>(raw: PackageEnvelope<PackageDocument<T>>): Envelope<T> {
    const document = raw.data.document
    // Actual failed-run documents may contain current partial results; never reuse an old envelope.
    if (!document) throw new Error(raw.data.diagnostics?.[0]?.message ?? 'Current package calculation failed')
    if (document.contract !== 'mantra.workbench/4') throw new Error('Unsupported embedded workbench contract')
    return document
  }
  async workspace(signal?: AbortSignal): Promise<Workspace> {
    const index = await this.index(signal)
    return {
      cases: index.data.packages.flatMap((pkg) =>
        pkg.cases.map((item) => ({
          id: item.id,
          title: `${item.caseId} · ${pkg.id}@${pkg.version}`,
          schema: item.schema,
          schemaVersion: item.schemaVersion,
          period: null,
          revision: null,
          diagnostics: [],
        })),
      ),
      parameters: index.data.packages.flatMap((pkg) =>
        (pkg.parameters ?? []).map((parameter) => ({ id: parameter.id, path: parameter.id })),
      ),
      layouts: [],
    }
  }
  async structure(id: string, signal?: AbortSignal) {
    return this.unwrap(await this.wrapper<Structure>(id, 'structure', signal))
  }
  async run(id: string, signal?: AbortSignal) {
    return this.unwrap(await this.wrapper<Run>(id, 'run', signal))
  }
  async paper(id: string, panel?: string, signal?: AbortSignal, includeZero?: boolean) {
    const query = new URLSearchParams()
    if (panel) query.set('panel', panel)
    if (includeZero !== undefined) query.set('includeZero', String(includeZero))
    return this.unwrap(await this.wrapper<Paper>(id, `paper${query.size ? `?${query}` : ''}`, signal))
  }
  async parameters(id: string, signal?: AbortSignal) {
    return this.unwrap(await this.wrapper<Parameters>(id, 'parameters', signal))
  }
  async diagnostics(id: string, signal?: AbortSignal) {
    return this.unwrap(await this.wrapper<Diagnostics>(id, 'diagnostics', signal))
  }
  async sourceContext(id: string, diagnosticIndex: number, expectedRevision: string, signal?: AbortSignal) {
    const query = new URLSearchParams({ diagnostic: String(diagnosticIndex), expectedRevision })
    return this.unwrap(await this.wrapper<SourceContext>(id, `diagnostic-source?${query}`, signal))
  }
  async explain(
    id: string,
    address: Address,
    signal?: AbortSignal,
    expectedRevision?: string,
  ): Promise<Envelope<Explain>> {
    const query = new URLSearchParams({ address: addressToPath(address) })
    if (address.case) query.set('case', address.case)
    if (expectedRevision) query.set('expectedRevision', expectedRevision)
    return this.unwrap(await this.wrapper<Explain>(id, `explain?${query}`, signal))
  }
  async exportPreview(
    id: string,
    sheet?: string,
    _layout?: string,
    signal?: AbortSignal,
  ): Promise<Envelope<ExportPreview>> {
    return this.unwrap(
      await this.wrapper<ExportPreview>(
        id,
        `export-preview${sheet ? `?sheet=${encodeURIComponent(sheet)}` : ''}`,
        signal,
      ),
    )
  }
  exportUrl(id: string, format: 'xlsx' | 'html' | 'txt' | 'pdf') {
    return this.path(id, `export.${format}`)
  }
  async edit(
    id: string,
    baseRevision: string,
    operations: EditOperation[],
    preview = false,
  ): Promise<Envelope<EditResult>> {
    return this.unwrap(
      await this.request<PackageDocument<EditResult>>(this.path(id, preview ? 'preview' : 'edit'), {
        baseRevision,
        operations,
      }),
    )
  }
  async formulaEdit(
    id: string,
    baseRevision: string,
    operation: FormulaOperation,
    preview: boolean,
  ): Promise<Envelope<FormulaEditResult>> {
    return this.unwrap(
      await this.request<PackageDocument<FormulaEditResult>>(this.path(id, preview ? 'preview' : 'edit'), {
        baseRevision,
        operations: [operation],
      }),
    )
  }
  async compare(
    id: string,
    parameterSets: string[],
    signal?: AbortSignal,
    effectiveDate?: string,
  ): Promise<Envelope<Compare>> {
    if (!effectiveDate || !/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate))
      throw new Error('An explicit effective date is required for package scenarios')
    const baseline = await this.wrapper<Run>(id, 'run', signal)
    this.unwrap(baseline)
    return this.unwrap(
      await this.request<PackageDocument<Compare>>(
        this.path(id, 'compare'),
        { variantParameters: parameterSets, effectiveDate, expectedRevision: baseline.revision },
        signal,
      ),
    )
  }
  authoring(): never {
    throw new Error('Formula authoring is unavailable for this read-only package view')
  }
  async sources(id: string, signal?: AbortSignal) {
    return this.unwrap(await this.wrapper<import('../types').Sources>(id, 'sources', signal))
  }
  removeSource(): never {
    throw new Error('Captured package resources are read-only')
  }
  importInspect(): never {
    throw new Error('Capture imports in an explicitly mounted package')
  }
  importApply(): never {
    throw new Error('Captured package resources are read-only')
  }
  importTemplates(): never {
    throw new Error('Package imports use declared captured resources')
  }
  saveImportTemplate(): never {
    throw new Error('Captured package resources are read-only')
  }
  previewMigration(id: string, baseRevision: string, targetCase: string) {
    return this.request<MigrationPreview>(this.path(id, 'migration-preview'), { baseRevision, targetCase })
  }
  applyMigration(id: string, reviewToken: string) {
    return this.request<PackageDocument<Run>>(this.path(id, 'migration-apply'), { reviewToken })
  }
  restore(id: string, baseRevision: string, undo: boolean) {
    return this.request<PackageDocument<Run>>(this.path(id, undo ? 'undo' : 'redo'), { baseRevision })
  }
}
