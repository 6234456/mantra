import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Envelope, Value } from '../types'
import type { RecoveryPending } from './recovery'
import { sameTemplateRevisions, TemplatePreviewError, type TemplateSource, type TemplateSources } from './client'
import {
  LiveLayoutSettingsClient,
  type LayoutProperty,
  type LayoutSettingOperation,
  type LayoutSettings,
  type LayoutSettingsClient,
  type LayoutSettingsRequest,
} from './layoutSettingsClient'
import './LayoutSettingsPanel.css'

interface Binding {
  caseId: string
  revision: string
  baseRevisions: Record<string, string>
  handle: string
  text: string
}

interface FieldChange {
  operation: LayoutSettingOperation
  version: number
}

function sameBinding(left: Binding | undefined, right: Binding | undefined): boolean {
  return (
    !!left &&
    !!right &&
    left.caseId === right.caseId &&
    left.revision === right.revision &&
    left.handle === right.handle &&
    left.text === right.text &&
    sameTemplateRevisions(left.baseRevisions, right.baseRevisions)
  )
}

/** Display exact root-layout values without turning presentation metadata into calculation values. */
function valueText(value: Value): string {
  if (value === null) return 'None'
  if (typeof value === 'string') return value === '' ? '""' : value
  if (typeof value === 'boolean') return String(value)
  if (!Array.isArray(value)) {
    if ('n' in value) return value.n
    if ('kw' in value) return `:${value.kw}`
  }
  return JSON.stringify(value)
}

function inputText(value: Value): string {
  if (value === null) return ''
  if (typeof value === 'string' || typeof value === 'boolean') return String(value)
  if (!Array.isArray(value)) {
    if ('n' in value) return value.n
    if ('kw' in value) return value.kw
  }
  return ''
}

function complete(property: LayoutProperty, change: FieldChange): boolean {
  if (change.operation.op === 'unset') return property.canUnset
  if (!property.editable) return false
  const raw = change.operation.text
  if (property.type === 'integer') return /^-?\d+$/.test(raw)
  if (property.type === 'boolean') return raw === 'true' || raw === 'false'
  if (property.type === 'enum') return property.options.includes(raw)
  return true
}

export function LayoutSettingsPanel({
  caseId,
  snapshot,
  source,
  text,
  conflict = false,
  onApply,
  onConflict,
  onPendingChange,
  restoredDraft,
  onDraftChange,
  client: supplied,
}: {
  caseId: string
  snapshot: Envelope<TemplateSources>
  source?: TemplateSource
  text?: string
  conflict?: boolean
  onApply: (caseId: string, request: LayoutSettingsRequest, nextText: string) => boolean
  onConflict: () => void
  onPendingChange?: (pending: boolean) => void
  restoredDraft?: RecoveryPending['layoutSettings']
  onDraftChange?: (draft: RecoveryPending['layoutSettings']) => void
  client?: LayoutSettingsClient
}) {
  const client = useMemo(() => supplied ?? new LiveLayoutSettingsClient(), [supplied])
  const [inspection, setInspection] = useState<{ binding: Binding; data: LayoutSettings }>()
  const [changes, setChanges] = useState<Record<string, FieldChange>>(() =>
    Object.fromEntries(
      (restoredDraft ?? []).map((operation, index) => [
        operation.property,
        { operation: { ...operation }, version: index + 1 },
      ]),
    ),
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [composing, setComposing] = useState(false)
  const generation = useRef(0)
  const sequence = useRef(0)
  const fieldVersion = useRef(restoredDraft?.length ?? 0)
  const compositionKey = useRef(false)
  const pending = useRef<AbortController | null>(null)
  const latest = useRef<{
    binding?: Binding
    onApply: typeof onApply
    onConflict: typeof onConflict
    conflict: boolean
  }>({ onApply, onConflict, conflict })
  const binding: Binding | undefined =
    source && text !== undefined
      ? { caseId, revision: snapshot.revision, baseRevisions: snapshot.data.baseRevisions, handle: source.handle, text }
      : undefined

  useLayoutEffect(() => {
    latest.current = { binding, onApply, onConflict, conflict }
  })
  const abortPending = useCallback(() => {
    generation.current++
    pending.current?.abort()
  }, [])
  useLayoutEffect(() => {
    abortPending()
    setBusy(false)
    setError('')
    return abortPending
  }, [caseId, snapshot.revision, source?.handle, source?.editable, text, conflict, abortPending])
  const hasChanges = Object.keys(changes).length > 0
  const rawDraft = useMemo(() => Object.values(changes).map((change) => change.operation), [changes])
  useLayoutEffect(() => {
    onDraftChange?.(rawDraft)
  }, [rawDraft, onDraftChange])
  useEffect(() => {
    onPendingChange?.(hasChanges)
    return () => onPendingChange?.(false)
  }, [hasChanges, onPendingChange])
  const current = sameBinding(inspection?.binding, binding) && !conflict
  const properties = inspection?.data.properties ?? []
  const canApply =
    current &&
    source?.editable &&
    hasChanges &&
    !busy &&
    !composing &&
    Object.entries(changes).every(([id, change]) => {
      const property = properties.find((item) => item.id === id)
      return !!property && complete(property, change)
    })

  function cancel() {
    generation.current++
    pending.current?.abort()
    setBusy(false)
    setChanges({})
    setError('')
    setNotice('Unapplied setting changes canceled.')
  }

  async function request(apply: boolean) {
    if (!binding || !source?.editable || conflict || busy || composing || (apply && !canApply)) return
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    const requestGeneration = ++generation.current
    const submitted = apply ? changes : {}
    const request: LayoutSettingsRequest = {
      baseRevision: binding.revision,
      baseRevisions: binding.baseRevisions,
      draftSequence: ++sequence.current,
      handle: binding.handle,
      text: binding.text,
      operations: Object.values(submitted).map((change) => change.operation),
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const reply = await client.settings(caseId, request, controller.signal)
      if (
        controller.signal.aborted ||
        generation.current !== requestGeneration ||
        latest.current.conflict ||
        !sameBinding(binding, latest.current.binding)
      )
        return
      if (reply.data.source.document !== source.document) {
        throw new Error('The settings response does not identify the captured root layout. Refresh settings and retry.')
      }
      if (apply && !latest.current.onApply(caseId, request, reply.data.source.text)) {
        setError('The source changed. Refresh settings and review your retained changes before applying again.')
        return
      }
      setInspection({ binding: { ...binding, text: reply.data.source.text }, data: reply.data })
      if (apply) {
        setChanges((previous) =>
          Object.fromEntries(
            Object.entries(previous).filter(([id, change]) => change.version !== submitted[id]?.version),
          ),
        )
        setNotice('Settings applied to the source draft. Preview the draft to update its results.')
      } else {
        setNotice(hasChanges ? 'Settings refreshed. Review the retained field changes before applying.' : '')
      }
    } catch (failure) {
      if (controller.signal.aborted || generation.current !== requestGeneration) return
      setError(failure instanceof Error ? failure.message : String(failure))
      if (failure instanceof TemplatePreviewError && failure.status === 409) latest.current.onConflict()
    } finally {
      if (!controller.signal.aborted && generation.current === requestGeneration) setBusy(false)
    }
  }

  function change(property: LayoutProperty, raw: string) {
    const next = {
      operation: { op: 'set' as const, property: property.id, text: raw },
      version: ++fieldVersion.current,
    }
    setChanges((previous) => ({ ...previous, [property.id]: next }))
    setError('')
    setNotice('')
  }

  function reset(property: LayoutProperty) {
    if (!property.declared) {
      setChanges((previous) => {
        const next = { ...previous }
        delete next[property.id]
        return next
      })
    } else {
      const next = {
        operation: { op: 'unset' as const, property: property.id },
        version: ++fieldVersion.current,
      }
      setChanges((previous) => ({ ...previous, [property.id]: next }))
    }
    setError('')
    setNotice('')
  }

  return (
    <section className="sheet template-layout-settings" aria-label="Layout settings">
      <h2>Layout settings</h2>
      {hasChanges && !inspection && (
        <div>
          <p role="status">
            Restored setting fields have not been inspected. Inspect the current layout before applying them.
          </p>
          <ul aria-label="Uninspected restored layout changes">
            {rawDraft.map((operation) => (
              <li key={operation.property}>
                <code>{operation.property}</code>:{' '}
                {operation.op === 'set' ? JSON.stringify(operation.text) : 'Reset to inherited (not applied)'}
              </li>
            ))}
          </ul>
          <button type="button" onClick={cancel}>
            Cancel setting changes
          </button>
        </div>
      )}
      {!source ? (
        <p>No editable root layout was captured. Choose a layout in the workbench and reload the saved source.</p>
      ) : (
        <>
          <p>These settings edit the root layout source. Preview the draft to check its calculated results.</p>
          <button disabled={!source.editable || conflict || busy || composing} onClick={() => void request(false)}>
            {inspection ? 'Refresh layout settings' : 'Inspect layout settings'}
          </button>
          {!source.editable && <p>{source.reason ?? 'This layout is read-only.'}</p>}
          {conflict && <p>Resolve the source revision conflict before editing layout settings.</p>}
          {inspection && !current && !conflict && (
            <p role="status">Source changed. Refresh settings and review retained field changes before applying.</p>
          )}
          {inspection && (
            <p className="muted">
              Root layout <code>{inspection.data.layoutId}</code> · preset <code>{inspection.data.preset}</code>
            </p>
          )}
          {busy && <p role="status">Reading layout settings…</p>}
          {error && <p role="alert">{error}</p>}
          {notice && <p role="status">{notice}</p>}
          {properties.length > 0 && (
            <form
              onSubmit={(event) => {
                event.preventDefault()
                if (!compositionKey.current) void request(true)
              }}
              onCompositionStart={() => setComposing(true)}
              onCompositionEnd={() => setComposing(false)}
              onKeyDown={(event) => {
                compositionKey.current = event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
              }}
              onKeyUp={() => {
                compositionKey.current = false
              }}
            >
              {(['report', 'numbers', 'visibility'] as const).map((group) => (
                <fieldset key={group}>
                  <legend>
                    {group === 'report' ? 'Report' : group === 'numbers' ? 'Number display' : 'Visibility'}
                  </legend>
                  {properties
                    .filter((property) => property.group === group)
                    .map((property) => {
                      const edit = changes[property.id]
                      const displayed =
                        edit?.operation.op === 'set'
                          ? edit.operation.text
                          : inputText(
                              edit?.operation.op === 'unset'
                                ? property.inheritedValue
                                : property.declared
                                  ? property.explicitValue
                                  : property.effectiveValue,
                            )
                      const disabled = !current || !source.editable || !property.editable
                      const options = property.type === 'boolean' ? ['true', 'false'] : property.options
                      return (
                        <div className="template-layout-setting" key={property.id}>
                          <label>
                            {property.label}
                            {property.type === 'boolean' || property.type === 'enum' ? (
                              <select
                                aria-label={`Layout setting ${property.id}`}
                                value={displayed}
                                disabled={disabled}
                                onChange={(event) => change(property, event.target.value)}
                              >
                                {!options.includes(displayed) && (
                                  <option value={displayed}>
                                    {edit?.operation.op === 'set'
                                      ? `${JSON.stringify(displayed)} (unvalidated field value)`
                                      : displayed || (property.declared ? 'Unsupported source value' : 'Inherited')}
                                  </option>
                                )}
                                {options.map((option) => (
                                  <option key={option} value={option}>
                                    {option}
                                  </option>
                                ))}
                              </select>
                            ) : (
                              <input
                                aria-label={`Layout setting ${property.id}`}
                                value={displayed}
                                inputMode={property.type === 'integer' ? 'numeric' : undefined}
                                disabled={disabled}
                                onChange={(event) => change(property, event.target.value)}
                              />
                            )}
                          </label>
                          <button
                            type="button"
                            aria-label={`Set explicit layout setting ${property.id}`}
                            disabled={
                              disabled ||
                              (property.declared && !edit) ||
                              !complete(property, {
                                operation: { op: 'set', property: property.id, text: displayed },
                                version: 0,
                              })
                            }
                            onClick={() => change(property, displayed)}
                          >
                            Use explicit value
                          </button>
                          <button
                            type="button"
                            aria-label={`Reset layout setting ${property.id}`}
                            disabled={!current || !source.editable || (!property.canUnset && !edit)}
                            onClick={() => reset(property)}
                          >
                            Reset to inherited
                          </button>
                          <dl>
                            <div>
                              <dt>{property.declared ? 'Explicit' : 'Inherited'}</dt>
                              <dd>{valueText(property.declared ? property.explicitValue : property.inheritedValue)}</dd>
                            </div>
                            <div>
                              <dt>Root effective value</dt>
                              <dd>{valueText(property.effectiveValue)}</dd>
                            </div>
                            <div>
                              <dt>Inherited value</dt>
                              <dd>{valueText(property.inheritedValue)}</dd>
                            </div>
                          </dl>
                          {edit?.operation.op === 'unset' && <p>Override will be removed when you apply.</p>}
                          {edit?.operation.op === 'set' && <p>Explicit value will be applied.</p>}
                          {property.reason && <p>{property.reason}</p>}
                          {property.minimum !== null && property.maximum !== null && (
                            <p className="muted">
                              Editing range: {property.minimum}–{property.maximum}
                            </p>
                          )}
                        </div>
                      )
                    })}
                </fieldset>
              ))}
              <div className="template-layout-settings-actions">
                <button type="submit" disabled={!canApply}>
                  Apply layout settings
                </button>
                <button type="button" disabled={!hasChanges && !busy} onClick={cancel}>
                  Cancel setting changes
                </button>
                {composing && <span>Finish composition before applying settings.</span>}
              </div>
            </form>
          )}
        </>
      )}
    </section>
  )
}
