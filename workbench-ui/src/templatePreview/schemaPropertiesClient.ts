import type { TemplateSchemaProperties as WireSchemaProperties } from '../generated/contract'
import type { Envelope } from '../types'
import { sameTemplateRevisions, TemplatePreviewError } from './client'

export type SchemaPropertyOperation =
  | { op: 'setText'; owner: string; text: string }
  | { op: 'setClasses'; owner: string; classes: string[] }
  | { op: 'unset'; owner: string }

export interface SchemaPropertiesRequest {
  baseRevision: string
  baseRevisions: Record<string, string>
  draftSequence: number
  handle: string
  text: string
  operations: SchemaPropertyOperation[]
}

export type SchemaProperties = WireSchemaProperties['data']
export type SchemaPropertyOwner = SchemaProperties['owners'][number]

export interface SchemaPropertiesClient {
  properties(
    caseId: string,
    request: SchemaPropertiesRequest,
    signal?: AbortSignal,
  ): Promise<Envelope<SchemaProperties>>
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function fields(value: unknown, names: string[]): value is Record<string, unknown> {
  return (
    object(value) && Object.keys(value).length === names.length && names.every((name) => Object.hasOwn(value, name))
  )
}

function hash(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
}

function revisions(value: unknown): value is Record<string, string> {
  return (
    object(value) &&
    Object.keys(value).length > 0 &&
    Object.entries(value).every(([path, revision]) => path.trim().length > 0 && hash(revision))
  )
}

interface Range {
  line: number
  column: number
  startOffset: number
  endOffset: number
}

function range(value: unknown): value is Range {
  return (
    fields(value, ['line', 'column', 'startOffset', 'endOffset']) &&
    ['line', 'column', 'startOffset', 'endOffset'].every((key) => Number.isSafeInteger(value[key])) &&
    (value.line as number) >= 1 &&
    (value.column as number) >= 1 &&
    (value.startOffset as number) >= 0 &&
    (value.endOffset as number) > (value.startOffset as number)
  )
}

const forms = [
  'section',
  'slot',
  'line',
  'subtract',
  'info',
  'formula-slot',
  'field',
  'total',
  'choice',
  'choose-min',
  'choose-max',
  'check',
  'reconcile',
  'note',
]

function owner(value: unknown, source: SchemaProperties['source'], captured: Record<string, string>): boolean {
  if (
    !fields(value, [
      'handle',
      'document',
      'node',
      'form',
      'path',
      'property',
      'type',
      'declared',
      'value',
      'editable',
      'reason',
      'canUnset',
      'range',
      'declarationRange',
    ]) ||
    !hash(value.handle) ||
    typeof value.document !== 'string' ||
    !Object.hasOwn(captured, value.document) ||
    typeof value.form !== 'string' ||
    !forms.includes(value.form) ||
    !Array.isArray(value.path) ||
    value.path.length === 0 ||
    !value.path.every((index) => Number.isSafeInteger(index) && index >= 0) ||
    typeof value.declared !== 'boolean' ||
    typeof value.editable !== 'boolean' ||
    typeof value.canUnset !== 'boolean' ||
    !range(value.declarationRange)
  )
    return false
  if (value.form === 'note' ? value.node !== null : typeof value.node !== 'string' || !value.node.trim()) return false
  const root = value.document === source.document
  if (
    value.editable !== root ||
    (root ? value.reason !== null : typeof value.reason !== 'string' || value.reason.trim().length === 0) ||
    (root && value.declarationRange.endOffset > source.text.length)
  )
    return false
  if (value.property === 'classes') {
    if (value.type !== 'classes' || value.canUnset !== (value.editable && value.declared)) return false
    if (!value.declared) return value.value === null && value.range === null
    if (
      !Array.isArray(value.value) ||
      !value.value.every(
        (entry) => fields(entry, ['kw']) && typeof entry.kw === 'string' && /^[a-z][a-z0-9-]*$/.test(entry.kw),
      ) ||
      new Set(value.value.map((entry) => (entry as { kw: string }).kw)).size !== value.value.length
    )
      return false
  } else if (
    value.property !== (value.form === 'note' ? 'text' : 'label') ||
    value.type !== 'text' ||
    !value.declared ||
    typeof value.value !== 'string' ||
    value.canUnset
  ) {
    return false
  }
  return (
    range(value.range) &&
    value.range.startOffset >= value.declarationRange.startOffset &&
    value.range.endOffset <= value.declarationRange.endOffset
  )
}

/** Check exact forward and inverse UTF-16 patches without interpreting the schema DSL. */
function patchesMatch(value: unknown, before: string, after: string): boolean {
  if (!Array.isArray(value)) return false
  const patches: Array<{ start: number; inserted: string; removed: string }> = []
  let source = before
  for (const patch of value) {
    if (
      !fields(patch, ['startOffset', 'endOffset', 'text', 'removedText']) ||
      !Number.isSafeInteger(patch.startOffset) ||
      !Number.isSafeInteger(patch.endOffset) ||
      typeof patch.text !== 'string' ||
      typeof patch.removedText !== 'string'
    )
      return false
    const start = patch.startOffset as number
    const end = patch.endOffset as number
    if (start < 0 || end < start || end > source.length || source.slice(start, end) !== patch.removedText) return false
    source = source.slice(0, start) + patch.text + source.slice(end)
    if (source.length > 65_536) return false
    patches.push({ start, inserted: patch.text, removed: patch.removedText })
  }
  if (source !== after) return false
  for (const patch of patches.reverse()) {
    if (source.slice(patch.start, patch.start + patch.inserted.length) !== patch.inserted) return false
    source = source.slice(0, patch.start) + patch.removed + source.slice(patch.start + patch.inserted.length)
  }
  return source === before
}

function matches(body: unknown, caseId: string, request: SchemaPropertiesRequest): body is Envelope<SchemaProperties> {
  if (
    !fields(body, ['contract', 'revision', 'engine', 'data']) ||
    body.contract !== 'mantra.workbench/4' ||
    body.revision !== request.baseRevision ||
    !hash(body.revision) ||
    !fields(body.engine, ['mantra', 'normein']) ||
    typeof body.engine.mantra !== 'string' ||
    typeof body.engine.normein !== 'string' ||
    !fields(body.data, [
      'document',
      'draftSequence',
      'baseRevisions',
      'scope',
      'coverage',
      'schemaId',
      'source',
      'owners',
      'patches',
    ])
  )
    return false
  const data = body.data
  if (
    data.document !== caseId ||
    !Number.isSafeInteger(data.draftSequence) ||
    (data.draftSequence as number) < 0 ||
    data.draftSequence !== request.draftSequence ||
    !revisions(data.baseRevisions) ||
    !sameTemplateRevisions(data.baseRevisions, request.baseRevisions) ||
    data.scope !== 'schema-properties' ||
    data.coverage !== 'presentation-items' ||
    typeof data.schemaId !== 'string' ||
    !data.schemaId.trim() ||
    !fields(data.source, ['handle', 'document', 'text']) ||
    data.source.handle !== request.handle ||
    !hash(data.source.handle) ||
    typeof data.source.document !== 'string' ||
    !Object.hasOwn(data.baseRevisions, data.source.document) ||
    typeof data.source.text !== 'string' ||
    data.source.text.length > 65_536 ||
    !patchesMatch(data.patches, request.text, data.source.text) ||
    !Array.isArray(data.owners)
  )
    return false
  const source = data.source as SchemaProperties['source']
  if (!data.owners.every((item) => owner(item, source, data.baseRevisions as Record<string, string>))) return false
  const owners = data.owners as SchemaPropertyOwner[]
  if (
    new Set(owners.map((item) => item.handle)).size !== owners.length ||
    new Set(owners.map((item) => JSON.stringify([item.document, item.path, item.property]))).size !== owners.length
  )
    return false
  return request.operations.length > 0 || (data.patches as unknown[]).length === 0
}

/**
 * Returns a source edit bound to an immutable copy of the submitted request, not a calculated preview.
 * The caller must still match its current editing context before applying this response to a draft.
 * Included ranges are checked structurally; only the root document text is available for bounds checks.
 */
export class LiveSchemaPropertiesClient implements SchemaPropertiesClient {
  async properties(caseId: string, request: SchemaPropertiesRequest, signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Start the live Mantra workbench to edit schema properties.')
    const sent: SchemaPropertiesRequest = {
      ...request,
      baseRevisions: { ...request.baseRevisions },
      operations: request.operations.map((operation) =>
        operation.op === 'setClasses' ? { ...operation, classes: [...operation.classes] } : { ...operation },
      ),
    }
    const reply = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/template-schema-properties`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify(sent),
      signal,
    })
    const body: unknown = await reply.json().catch(() => undefined)
    if (!reply.ok) {
      const error = object(body) && object(body.error) ? body.error : undefined
      throw new TemplatePreviewError(
        reply.status,
        typeof error?.message === 'string' ? error.message : `HTTP ${reply.status} ${reply.statusText}`,
        Array.isArray(error?.diagnostics) ? error.diagnostics : [],
        typeof error?.currentRevision === 'string' ? error.currentRevision : undefined,
      )
    }
    if (!matches(body, caseId, sent))
      throw new Error('The schema properties response does not match this draft and its captured source revisions.')
    return body
  }
}
