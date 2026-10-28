import { LiveData } from '../data'
import type {
  TemplateSources as WireTemplateSources,
  TemplatePreview as WireTemplatePreview,
} from '../generated/contract'
import type { Compare, Diagnostic, Envelope, Explain, Paper, Run, Structure } from '../types'

export type TemplateSources = WireTemplateSources['data'] & { workspaceId?: string }
export type TemplateSource = TemplateSources['documents'][number]

export interface TemplatePreviewRequest {
  baseRevision: string
  baseRevisions: Record<string, string>
  draftSequence: number
  documents: Array<{ handle: string; text: string }>
  inputs: Array<{ node: string; text: string }>
  panel?: string
  includeZero?: boolean
  explain?: { node: string; coord?: string[] }
}

export type TemplatePreview = Omit<
  WireTemplatePreview['data'],
  'structure' | 'run' | 'difference' | 'paper' | 'explain'
> & {
  structure: Structure
  run: Run
  difference: Compare
  paper: Paper
  explain: Explain | null
}

export interface TemplatePreviewClient {
  workspace: LiveData['workspace']
  sources(caseId: string, signal?: AbortSignal): Promise<Envelope<TemplateSources>>
  preview(caseId: string, request: TemplatePreviewRequest, signal?: AbortSignal): Promise<Envelope<TemplatePreview>>
}

export class TemplatePreviewError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly diagnostics: Diagnostic[] = [],
    public readonly currentRevision?: string,
  ) {
    super(message)
  }
}

export function sameTemplateRevisions(left: Record<string, string>, right: Record<string, string>) {
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every((key) => left[key] === right[key])
}

const path = (caseId: string) => `/api/v1/cases/${encodeURIComponent(caseId)}`

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function revisions(value: unknown): value is Record<string, string> {
  return (
    object(value) &&
    Object.keys(value).length > 0 &&
    Object.values(value).every((hash) => typeof hash === 'string' && /^[a-f0-9]{64}$/.test(hash))
  )
}

function wireValue(value: unknown): boolean {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return true
  if (Array.isArray(value)) return value.every(wireValue)
  if (!object(value) || Object.keys(value).length !== 1) return false
  if ('map' in value)
    return (
      Array.isArray(value.map) &&
      value.map.every((pair) => Array.isArray(pair) && pair.length === 2 && pair.every(wireValue))
    )
  return ['n', 'kw', 'date'].some((key) => typeof value[key] === 'string')
}

function comparisonChange(value: unknown): boolean {
  if (!object(value) || !object(value.display)) return false
  const display = value.display
  return (
    typeof value.node === 'string' &&
    Array.isArray(value.coord) &&
    value.coord.every((coordinate) => typeof coordinate === 'string') &&
    wireValue(value.base) &&
    wireValue(value.variant) &&
    typeof value.basePresent === 'boolean' &&
    typeof value.variantPresent === 'boolean' &&
    (value.delta === null || (object(value.delta) && typeof value.delta.n === 'string')) &&
    ['base', 'variant', 'delta'].every((key) => display[key] === null || typeof display[key] === 'string') &&
    ['baseSource', 'variantSource'].every((key) => value[key] === undefined || typeof value[key] === 'string')
  )
}

function comparison(value: unknown): boolean {
  return (
    object(value) &&
    Array.isArray(value.mainline) &&
    value.mainline.every(comparisonChange) &&
    Array.isArray(value.parameterChanges) &&
    value.parameterChanges.every(comparisonChange) &&
    Array.isArray(value.changes) &&
    value.changes.every(
      (group) =>
        object(group) &&
        (group.panel === null || typeof group.panel === 'string') &&
        Array.isArray(group.items) &&
        group.items.every(comparisonChange),
    )
  )
}

function renderable(preview: TemplatePreview) {
  return (
    typeof preview.succeeded === 'boolean' &&
    typeof preview.validationPassed === 'boolean' &&
    /^[a-f0-9]{64}$/.test(preview.proposedRevision) &&
    Array.isArray(preview.diagnostics) &&
    preview.diagnostics.every(
      (item) => object(item) && typeof item.code === 'string' && typeof item.message === 'string',
    ) &&
    object(preview.structure) &&
    Array.isArray(preview.structure.panels) &&
    preview.structure.panels.every(
      (panel) => object(panel) && typeof panel.id === 'string' && typeof panel.title === 'string',
    ) &&
    object(preview.structure.nodes) &&
    Object.values(preview.structure.nodes).every(
      (node) =>
        object(node) &&
        (node.label === undefined || node.label === null || typeof node.label === 'string') &&
        (node.dims === undefined || Array.isArray(node.dims)),
    ) &&
    object(preview.run) &&
    object(preview.run.values) &&
    comparison(preview.difference) &&
    object(preview.paper) &&
    typeof preview.paper.title === 'string' &&
    Array.isArray(preview.paper.tables) &&
    preview.paper.tables.every(
      (table) =>
        object(table) &&
        typeof table.id === 'string' &&
        Array.isArray(table.columns) &&
        table.columns.every(
          (column) => object(column) && typeof column.id === 'string' && typeof column.header === 'string',
        ) &&
        Array.isArray(table.rows) &&
        table.rows.every(
          (row) =>
            object(row) &&
            typeof row.kind === 'string' &&
            typeof row.depth === 'number' &&
            (row.flags === undefined ||
              (Array.isArray(row.flags) && row.flags.every((flag) => typeof flag === 'string'))) &&
            Array.isArray(row.cells) &&
            row.cells.every((cell) => object(cell) && typeof cell.text === 'string'),
        ),
    ) &&
    (preview.explain === null ||
      (object(preview.explain) &&
        object(preview.explain.address) &&
        object(preview.explain.result) &&
        ['steps', 'branches', 'references', 'parts', 'options'].every((key) =>
          Array.isArray(preview.explain?.[key as keyof Explain]),
        )))
  )
}

async function response<T>(reply: Response): Promise<Envelope<T>> {
  const body = await reply.json().catch(() => undefined)
  if (!reply.ok) {
    throw new TemplatePreviewError(
      reply.status,
      body?.error?.message ?? `HTTP ${reply.status} ${reply.statusText}`,
      Array.isArray(body?.error?.diagnostics) ? body.error.diagnostics : [],
      body?.error?.currentRevision,
    )
  }
  if (body?.contract !== 'mantra.workbench/4' || !/^[a-f0-9]{64}$/.test(body?.revision ?? '') || !body?.data) {
    throw new Error('The server returned an unsupported template preview response.')
  }
  return body as Envelope<T>
}

/** This transport never loads recorded responses or submits a file save. */
export class LiveTemplatePreviewClient implements TemplatePreviewClient {
  private async recoveryContext(signal?: AbortSignal): Promise<string | undefined> {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) return
    try {
      const reply = await fetch('/api/v1/template-recovery-context', {
        signal,
        headers: { 'X-Mantra-Token': token },
      })
      if (!reply.ok) return
      const body: unknown = await reply.json()
      if (
        !object(body) ||
        body.contract !== 'mantra.workbench/4' ||
        !object(body.engine) ||
        typeof body.engine.mantra !== 'string' ||
        typeof body.engine.normein !== 'string' ||
        !object(body.data) ||
        Object.keys(body.data).length !== 1 ||
        typeof body.data.workspaceId !== 'string' ||
        !/^[a-f0-9]{64}$/.test(body.data.workspaceId) ||
        body.revision !== body.data.workspaceId
      )
        return
      return body.data.workspaceId
    } catch {
      // Older/offline hosts can still provide ordinary source preview without browser recovery.
      return
    }
  }

  async workspace(signal?: AbortSignal) {
    const workspace = await new LiveData().workspace(signal)
    const normalized = { ...workspace }
    delete normalized.workspaceId
    const identity = await this.recoveryContext(signal)
    if (identity) normalized.workspaceId = identity
    return normalized
  }

  async sources(caseId: string, signal?: AbortSignal) {
    const reply = await fetch(`${path(caseId)}/template-sources`, { signal })
    const source = await response<TemplateSources>(reply)
    if (
      source.data.document !== caseId ||
      !revisions(source.data.baseRevisions) ||
      !Array.isArray(source.data.documents) ||
      !source.data.documents.length ||
      source.data.documents.some(
        (item) =>
          !object(item) ||
          typeof item.handle !== 'string' ||
          !item.handle ||
          typeof item.document !== 'string' ||
          !item.document ||
          typeof item.sha256 !== 'string' ||
          !/^[a-f0-9]{64}$/.test(item.sha256) ||
          typeof item.text !== 'string' ||
          !['schema', 'layout', 'case', 'included', 'parameters'].includes(item.role) ||
          typeof item.editable !== 'boolean' ||
          (item.editable && !['schema', 'layout'].includes(item.role)) ||
          (item.reason !== null && typeof item.reason !== 'string') ||
          source.data.baseRevisions[item.document] !== item.sha256,
      ) ||
      new Set(source.data.documents.map((item) => item.handle)).size !== source.data.documents.length ||
      new Set(source.data.documents.map((item) => item.document)).size !== source.data.documents.length
    ) {
      throw new Error('The server returned an inconsistent template source snapshot.')
    }
    const data = { ...source.data }
    delete data.workspaceId
    const identity = await this.recoveryContext(signal)
    if (identity) data.workspaceId = identity
    return { ...source, data }
  }

  async preview(caseId: string, request: TemplatePreviewRequest, signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Start the live Mantra workbench to preview template drafts.')
    const reply = await fetch(`${path(caseId)}/template-preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify(request),
      signal,
    })
    const preview = await response<TemplatePreview>(reply)
    if (
      preview.revision !== request.baseRevision ||
      preview.data.document !== caseId ||
      preview.data.preview !== true ||
      preview.data.draftSequence !== request.draftSequence ||
      !revisions(preview.data.baseRevisions) ||
      !sameTemplateRevisions(preview.data.baseRevisions, request.baseRevisions) ||
      !renderable(preview.data)
    ) {
      throw new Error('The preview does not match this draft and its captured source revisions.')
    }
    return preview
  }
}
