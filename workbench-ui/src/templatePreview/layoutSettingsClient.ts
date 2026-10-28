import type { Envelope, Value } from '../types'
import type { TemplateLayoutSettings as WireLayoutSettings } from '../generated/contract'
import { sameTemplateRevisions, TemplatePreviewError } from './client'

export type LayoutSettingOperation = { op: 'set'; property: string; text: string } | { op: 'unset'; property: string }

export interface LayoutSettingsRequest {
  baseRevision: string
  baseRevisions: Record<string, string>
  draftSequence: number
  handle: string
  text: string
  operations: LayoutSettingOperation[]
}

export type LayoutSettings = WireLayoutSettings['data']
export type LayoutProperty = LayoutSettings['properties'][number]

const propertyIds: readonly LayoutProperty['id'][] = [
  'title',
  'subtitle',
  'locale',
  'language',
  'precision',
  'percent-precision',
  'negative',
  'zero',
  'grouping',
  'hide-zero',
  'show-inactive',
  'expand-members',
  'signed',
  'row-numbers',
]

export interface LayoutSettingsClient {
  settings(caseId: string, request: LayoutSettingsRequest, signal?: AbortSignal): Promise<Envelope<LayoutSettings>>
}

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
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

function wireValue(value: unknown): value is Value {
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

function property(value: unknown): value is LayoutProperty {
  if (!object(value)) return false
  const boundedInteger = (number: unknown) => number === null || Number.isSafeInteger(number)
  return (
    typeof value.id === 'string' &&
    propertyIds.includes(value.id as LayoutProperty['id']) &&
    typeof value.label === 'string' &&
    value.label.length > 0 &&
    ['report', 'numbers', 'visibility'].includes(String(value.group)) &&
    ['text', 'enum', 'integer', 'boolean'].includes(String(value.type)) &&
    Array.isArray(value.options) &&
    value.options.every((option) => typeof option === 'string') &&
    new Set(value.options).size === value.options.length &&
    boundedInteger(value.minimum) &&
    boundedInteger(value.maximum) &&
    (value.minimum === null || value.maximum === null || (value.minimum as number) <= (value.maximum as number)) &&
    typeof value.declared === 'boolean' &&
    wireValue(value.explicitValue) &&
    wireValue(value.inheritedValue) &&
    wireValue(value.effectiveValue) &&
    typeof value.editable === 'boolean' &&
    (value.reason === null || typeof value.reason === 'string') &&
    typeof value.canUnset === 'boolean'
  )
}

/** Offsets are structural UTF-16 positions; exact source text is never parsed or normalized here. */
function patchesMatch(value: unknown, before: string, after: string): boolean {
  if (!Array.isArray(value)) return false
  let source = before
  for (const patch of value) {
    if (
      !object(patch) ||
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
  }
  return source === after
}

function matches(body: unknown, caseId: string, request: LayoutSettingsRequest): body is Envelope<LayoutSettings> {
  if (
    !object(body) ||
    body.contract !== 'mantra.workbench/4' ||
    !hash(body.revision) ||
    body.revision !== request.baseRevision ||
    !object(body.engine) ||
    typeof body.engine.mantra !== 'string' ||
    typeof body.engine.normein !== 'string' ||
    !object(body.data)
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
    data.scope !== 'layout' ||
    typeof data.layoutId !== 'string' ||
    !data.layoutId ||
    typeof data.preset !== 'string' ||
    !data.preset ||
    !object(data.source) ||
    !hash(data.source.handle) ||
    data.source.handle !== request.handle ||
    typeof data.source.document !== 'string' ||
    !Object.hasOwn(data.baseRevisions, data.source.document) ||
    typeof data.source.text !== 'string' ||
    data.source.text.length > 65_536 ||
    !Array.isArray(data.properties) ||
    data.properties.length !== propertyIds.length ||
    !data.properties.every(property) ||
    new Set(data.properties.map((item) => item.id)).size !== data.properties.length ||
    !patchesMatch(data.patches, request.text, data.source.text)
  )
    return false
  return request.operations.length > 0 || (data.patches as unknown[]).length === 0
}

/** The endpoint returns an in-memory source edit, never a template-file save or a calculated preview. */
export class LiveLayoutSettingsClient implements LayoutSettingsClient {
  async settings(caseId: string, request: LayoutSettingsRequest, signal?: AbortSignal) {
    const token = document.querySelector<HTMLMetaElement>('meta[name="mantra-session-token"]')?.content
    if (!token) throw new Error('Start the live Mantra workbench to edit layout settings.')
    const reply = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/template-layout-settings`, {
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
      throw new Error('The layout settings response does not match this draft and its captured source revisions.')
    return body
  }
}
