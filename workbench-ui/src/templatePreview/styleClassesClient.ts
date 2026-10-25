import type { Envelope } from '../types'
import type { TemplateStyleClasses as WireStyleClasses, TemplateStyleClassesStyleSpec } from '../generated/contract'
import { sameTemplateRevisions, TemplatePreviewError } from './client'

export type StyleSpec = TemplateStyleClassesStyleSpec
export type StyleClassOperation = { op: 'upsert'; name: string; style: StyleSpec } | { op: 'remove'; name: string }

export interface StyleClassesRequest {
  baseRevision: string
  baseRevisions: Record<string, string>
  draftSequence: number
  handle: string
  text: string
  operations: StyleClassOperation[]
}

export type StyleClasses = WireStyleClasses['data']
export type StyleClass = StyleClasses['classes'][number]

export interface StyleClassesClient {
  classes(caseId: string, request: StyleClassesRequest, signal?: AbortSignal): Promise<Envelope<StyleClasses>>
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

function presets(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.length <= 2 &&
    value.every((name) => name === 'utilities' || name === 'working-paper') &&
    new Set(value).size === value.length
  )
}

function style(value: unknown): value is StyleSpec {
  if (!fields(value, ['weight', 'tone', 'fill'])) return false
  return (
    (value.weight === null || value.weight === 'normal' || value.weight === 'bold') &&
    (value.tone === null || value.tone === 'default' || value.tone === 'muted' || value.tone === 'accent') &&
    (value.fill === null || value.fill === 'none' || value.fill === 'subtle' || value.fill === 'accent')
  )
}

function styleClass(value: unknown, selected: string[], source: string): value is StyleClass {
  if (
    !fields(value, [
      'name',
      'editable',
      'reason',
      'presets',
      'local',
      'inherited',
      'effective',
      'references',
      'canRemove',
      'removeReason',
    ]) ||
    typeof value.name !== 'string' ||
    value.name.length === 0 ||
    typeof value.editable !== 'boolean' ||
    !presets(value.presets) ||
    value.presets.some((name) => !selected.includes(name)) ||
    (value.local !== null && !style(value.local)) ||
    !style(value.inherited) ||
    !style(value.effective) ||
    !Array.isArray(value.references) ||
    typeof value.canRemove !== 'boolean'
  )
    return false
  if (
    value.editable
      ? value.reason !== null || !/^[a-z][a-z0-9-]*$/.test(value.name)
      : typeof value.reason !== 'string' || value.reason.trim().length === 0
  )
    return false
  for (const reference of value.references) {
    if (
      !fields(reference, ['kind', 'startOffset', 'endOffset']) ||
      (reference.kind !== 'use' && reference.kind !== 'selector') ||
      !Number.isSafeInteger(reference.startOffset) ||
      !Number.isSafeInteger(reference.endOffset)
    )
      return false
    const start = reference.startOffset as number
    const end = reference.endOffset as number
    if (start < 0 || end <= start || end > source.length) return false
    const token = source.slice(start, end)
    if (token !== `:${value.name}` && !(reference.kind === 'selector' && token === value.name)) return false
  }
  if (value.canRemove) {
    return (
      value.editable &&
      value.local !== null &&
      value.removeReason === null &&
      (value.presets.length > 0 || !value.references.some((reference) => reference.kind === 'use'))
    )
  }
  return typeof value.removeReason === 'string' && value.removeReason.trim().length > 0
}

/** Sequential UTF-16 edits are checked against exact text, without interpreting the layout DSL. */
function patchesMatch(value: unknown, before: string, after: string): boolean {
  if (!Array.isArray(value)) return false
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
  }
  return source === after
}

function matches(body: unknown, caseId: string, request: StyleClassesRequest): body is Envelope<StyleClasses> {
  if (
    !object(body) ||
    body.contract !== 'mantra.workbench/4' ||
    !hash(body.revision) ||
    body.revision !== request.baseRevision ||
    !object(body.engine) ||
    typeof body.engine.mantra !== 'string' ||
    typeof body.engine.normein !== 'string' ||
    !fields(body.data, [
      'document',
      'draftSequence',
      'baseRevisions',
      'scope',
      'layoutId',
      'stylePresets',
      'usageScope',
      'source',
      'classes',
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
    data.scope !== 'layout-style-classes' ||
    typeof data.layoutId !== 'string' ||
    !data.layoutId ||
    !presets(data.stylePresets) ||
    data.usageScope !== 'submitted-layout' ||
    !fields(data.source, ['handle', 'document', 'text']) ||
    !hash(data.source.handle) ||
    data.source.handle !== request.handle ||
    typeof data.source.document !== 'string' ||
    !Object.hasOwn(data.baseRevisions, data.source.document) ||
    typeof data.source.text !== 'string' ||
    data.source.text.length > 65_536 ||
    !patchesMatch(data.patches, request.text, data.source.text)
  )
    return false
  const sourceText = data.source.text
  if (
    !Array.isArray(data.classes) ||
    !data.classes.every((item) => styleClass(item, data.stylePresets as string[], sourceText)) ||
    new Set(data.classes.map((item) => item.name)).size !== data.classes.length
  )
    return false
  return request.operations.length > 0 || (data.patches as unknown[]).length === 0
}

/** Class declarations contribute styles; matching candidate Paper remains the authority for rendered cells. */
export class LiveStyleClassesClient implements StyleClassesClient {
  async classes(caseId: string, request: StyleClassesRequest, signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Start the live Mantra workbench to edit style classes.')
    const reply = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/template-style-classes`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Mantra-Token': token },
      body: JSON.stringify(request),
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
    if (!matches(body, caseId, request))
      throw new Error('The style classes response does not match this draft and its captured source revisions.')
    return body
  }
}
