import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Envelope } from '../types'
import type { RecoveryPending } from './recovery'
import { sameTemplateRevisions, TemplatePreviewError, type TemplateSource, type TemplateSources } from './client'
import {
  LiveStyleClassesClient,
  type StyleClass,
  type StyleClassOperation,
  type StyleClasses,
  type StyleClassesClient,
  type StyleClassesRequest,
  type StyleSpec,
} from './styleClassesClient'
import './StyleClassesPanel.css'

interface Binding {
  caseId: string
  revision: string
  baseRevisions: Record<string, string>
  handle: string
  text: string
}

interface Edit {
  operation: StyleClassOperation
  version: number
  creating: boolean
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

const emptyStyle: StyleSpec = { weight: null, tone: null, fill: null }
const styleProperties = ['weight', 'tone', 'fill'] as const
const choices = {
  weight: ['normal', 'bold'],
  tone: ['default', 'muted', 'accent'],
  fill: ['none', 'subtle', 'accent'],
} as const

function describe(style: StyleSpec): string {
  return styleProperties.map((key) => `${key}: ${style[key] ?? 'unspecified'}`).join(' · ')
}

/** Local declarations share the root DSL source; final cell styles still come from the engine Paper. */
export function StyleClassesPanel({
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
  onApply: (caseId: string, request: StyleClassesRequest, nextText: string) => boolean
  onConflict: () => void
  onPendingChange?: (pending: boolean) => void
  restoredDraft?: RecoveryPending['styleClass']
  onDraftChange?: (draft: RecoveryPending['styleClass']) => void
  client?: StyleClassesClient
}) {
  const client = useMemo(() => supplied ?? new LiveStyleClassesClient(), [supplied])
  const [inspection, setInspection] = useState<{ binding: Binding; data: StyleClasses }>()
  const [selection, setSelection] = useState(restoredDraft?.selection ?? '')
  const [edit, setEdit] = useState<Edit | undefined>(() =>
    restoredDraft
      ? {
          operation:
            restoredDraft.operation.op === 'upsert'
              ? { ...restoredDraft.operation, style: { ...restoredDraft.operation.style } }
              : { ...restoredDraft.operation },
          creating: restoredDraft.creating,
          version: 1,
        }
      : undefined,
  )
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [composing, setComposing] = useState(false)
  const generation = useRef(0)
  const sequence = useRef(0)
  const fieldVersion = useRef(restoredDraft ? 1 : 0)
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
  useEffect(() => {
    onPendingChange?.(!!edit)
    return () => onPendingChange?.(false)
  }, [edit, onPendingChange])
  const rawDraft = useMemo<RecoveryPending['styleClass']>(
    () =>
      edit
        ? {
            operation: edit.operation,
            creating: edit.creating,
            selection,
          }
        : null,
    [edit, selection],
  )
  useLayoutEffect(() => {
    onDraftChange?.(rawDraft)
  }, [rawDraft, onDraftChange])

  const current = sameBinding(inspection?.binding, binding) && !conflict
  const selected = inspection?.data.classes.find((item) => item.name === selection)
  const operation = edit?.operation
  const formName = operation?.name ?? selected?.name ?? ''
  const formStyle = operation?.op === 'upsert' ? operation.style : (selected?.local ?? emptyStyle)
  const validName = /^[a-z][a-z0-9-]*$/.test(formName)
  const existingLocalName =
    !!edit?.creating && !!inspection?.data.classes.some((item) => item.name === formName && item.local !== null)
  const canApply =
    current &&
    source?.editable &&
    !!edit &&
    !busy &&
    !composing &&
    (operation?.op === 'remove'
      ? selected?.name === operation.name && selected.canRemove
      : validName &&
        !existingLocalName &&
        (edit.creating || (selected?.name === operation?.name && selected?.editable)))

  function clear() {
    generation.current++
    pending.current?.abort()
    setBusy(false)
    setEdit(undefined)
    setError('')
    setNotice('Unapplied style class changes canceled.')
  }

  function stage(operation: StyleClassOperation, creating = false) {
    setEdit({ operation, creating, version: ++fieldVersion.current })
    setError('')
    setNotice('')
  }

  function changeStyle(property: (typeof styleProperties)[number], raw: string) {
    stage(
      { op: 'upsert', name: formName, style: { ...formStyle, [property]: raw === '' ? null : raw } as StyleSpec },
      edit?.creating ?? false,
    )
  }

  async function request(apply: boolean) {
    if (!binding || !source?.editable || conflict || busy || composing || (apply && !canApply)) return
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    const requestGeneration = ++generation.current
    const submitted = apply ? edit : undefined
    const request: StyleClassesRequest = {
      baseRevision: binding.revision,
      baseRevisions: binding.baseRevisions,
      draftSequence: ++sequence.current,
      handle: binding.handle,
      text: binding.text,
      operations: submitted ? [submitted.operation] : [],
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const reply = await client.classes(caseId, request, controller.signal)
      if (
        controller.signal.aborted ||
        generation.current !== requestGeneration ||
        latest.current.conflict ||
        !sameBinding(binding, latest.current.binding)
      )
        return
      if (reply.data.source.document !== source.document) {
        throw new Error(
          'The style response does not identify the captured root layout. Refresh style classes and retry.',
        )
      }
      if (apply && !latest.current.onApply(caseId, request, reply.data.source.text)) {
        setError('The source changed. Refresh style classes and review retained changes before applying again.')
        return
      }
      setInspection({ binding: { ...binding, text: reply.data.source.text }, data: reply.data })
      if (apply) {
        setEdit((previous) => {
          if (previous?.version === submitted?.version) return undefined
          if (
            previous?.creating &&
            previous.operation.op === 'upsert' &&
            submitted?.operation.op === 'upsert' &&
            previous.operation.name === submitted.operation.name
          )
            return { ...previous, creating: false }
          return previous
        })
        if (submitted?.operation.op === 'upsert') setSelection(submitted.operation.name)
        else if (submitted) setSelection(reply.data.classes.find((item) => item.name === selection)?.name ?? '')
        setNotice('Style changes applied to the source draft. Preview the draft to check its results.')
      } else {
        setSelection((previous) =>
          edit && !edit.creating
            ? edit.operation.name
            : reply.data.classes.some((item) => item.name === previous)
              ? previous
              : (reply.data.classes[0]?.name ?? ''),
        )
        setNotice(edit ? 'Style classes refreshed. Review retained changes before applying.' : '')
      }
    } catch (failure) {
      if (controller.signal.aborted || generation.current !== requestGeneration) return
      setError(failure instanceof Error ? failure.message : String(failure))
      if (failure instanceof TemplatePreviewError && failure.status === 409) latest.current.onConflict()
    } finally {
      if (!controller.signal.aborted && generation.current === requestGeneration) setBusy(false)
    }
  }

  function origin(item: StyleClass): string {
    return [
      item.local ? 'Local definition' : 'No local definition',
      ...item.presets.map((name) => `preset :${name}`),
    ].join(' · ')
  }

  return (
    <section className="sheet template-style-classes" aria-label="Style classes">
      <h2>Style classes</h2>
      {edit && !inspection && (
        <div>
          <p role="status">
            Restored style fields have not been inspected. Inspect the current layout before applying them.
          </p>
          <pre aria-label="Uninspected restored style change">{JSON.stringify(rawDraft, null, 2)}</pre>
          <button type="button" onClick={clear}>
            Cancel style changes
          </button>
        </div>
      )}
      {!source ? (
        <p>No editable root layout was captured. Choose a layout in the workbench and reload the saved source.</p>
      ) : (
        <>
          <p>Edit local weight, tone and fill definitions in the root layout. Preset definitions remain read-only.</p>
          <p>
            Usage scope: submitted root layout only. Schema, includes and unsaved schema edits are outside this list.
            Preview the complete draft to inspect final cell styles.
          </p>
          <button disabled={!source.editable || conflict || busy || composing} onClick={() => void request(false)}>
            {inspection ? 'Refresh style classes' : 'Inspect style classes'}
          </button>
          {!source.editable && <p>{source.reason ?? 'This layout is read-only.'}</p>}
          {conflict && <p>Resolve the source revision conflict before editing style classes.</p>}
          {inspection && !current && !conflict && (
            <p role="status">Source changed. Refresh style classes and review retained changes before applying.</p>
          )}
          {busy && <p role="status">Reading style classes…</p>}
          {error && <p role="alert">{error}</p>}
          {notice && <p role="status">{notice}</p>}
          {inspection && (
            <>
              <p className="muted">
                Root layout <code>{inspection.data.layoutId}</code> · style presets{' '}
                {inspection.data.stylePresets.length ? inspection.data.stylePresets.join(', ') : 'none'}
              </p>
              <label>
                Style class
                <select
                  aria-label="Style class"
                  value={selection}
                  disabled={!!edit || busy || composing}
                  onChange={(event) => setSelection(event.target.value)}
                >
                  <option value="">Select a class</option>
                  {selection && !inspection.data.classes.some((item) => item.name === selection) && (
                    <option value={selection}>:{selection} · pending target unavailable</option>
                  )}
                  {inspection.data.classes.map((item) => (
                    <option key={item.name} value={item.name}>
                      :{item.name} · {item.local ? 'local' : item.presets.length ? 'preset' : 'selector only'}
                    </option>
                  ))}
                </select>
              </label>
              <button
                disabled={!current || !!edit || busy || composing || !source.editable}
                onClick={() => stage({ op: 'upsert', name: '', style: { ...emptyStyle } }, true)}
              >
                Create local style class
              </button>
              <ul className="template-style-catalog" aria-label="Style class catalog">
                {inspection.data.classes.map((item) => (
                  <li key={item.name} data-class-name={item.name}>
                    <code>:{item.name}</code> — {origin(item)}
                  </li>
                ))}
              </ul>
              {selected && !edit?.creating && (
                <div className="template-style-selected">
                  <h3>:{selected.name}</h3>
                  <dl>
                    <div>
                      <dt>Explicit local style</dt>
                      <dd>{selected.local ? describe(selected.local) : 'No local definition'}</dd>
                    </div>
                    <div>
                      <dt>Inherited preset style</dt>
                      <dd>{describe(selected.inherited)}</dd>
                    </div>
                    <div>
                      <dt>Class effective style</dt>
                      <dd>{describe(selected.effective)}</dd>
                    </div>
                  </dl>
                  <p>References in submitted layout: {selected.references.length}</p>
                  {!selected.editable && <p>{selected.reason ?? 'This class cannot be edited through this form.'}</p>}
                  {selected.references.length > 0 && (
                    <ul aria-label={`References to style class ${selected.name}`}>
                      {selected.references.map((reference, index) => (
                        <li key={index}>
                          {reference.kind === 'use' ? ':use reference' : ':class selector'} · UTF-16{' '}
                          {reference.startOffset}–{reference.endOffset}
                        </li>
                      ))}
                    </ul>
                  )}
                  {!selected.local && (
                    <button
                      disabled={!current || !!edit || busy || composing || !selected.editable || !source.editable}
                      onClick={() => stage({ op: 'upsert', name: selected.name, style: { ...emptyStyle } })}
                    >
                      Create local override
                    </button>
                  )}
                  {selected.local && (
                    <>
                      <button
                        disabled={
                          !current ||
                          !!edit ||
                          busy ||
                          composing ||
                          !selected.editable ||
                          !selected.canRemove ||
                          !source.editable
                        }
                        aria-describedby={selected.removeReason ? 'template-style-remove-reason' : undefined}
                        onClick={() => stage({ op: 'remove', name: selected.name })}
                      >
                        Remove local style class
                      </button>
                      {selected.removeReason && <p id="template-style-remove-reason">{selected.removeReason}</p>}
                    </>
                  )}
                </div>
              )}
              {(selected?.local || edit) && (
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
                  {edit && !edit.creating && !selected && (
                    <p role="status">
                      The pending class is not in the inspected catalog. Cancel this edit and create a class explicitly,
                      or repair its source and refresh.
                    </p>
                  )}
                  {operation?.op === 'remove' ? (
                    <p role="status">
                      Local definition :{operation.name} will be removed when you apply. Preset style, if available,
                      remains.
                    </p>
                  ) : (
                    <fieldset
                      disabled={!current || conflict || !source.editable || (!edit?.creating && !selected?.editable)}
                    >
                      <legend>{edit?.creating ? 'New local style class' : 'Local style definition'}</legend>
                      <label>
                        Class name (without colon)
                        <input
                          aria-label="Style class name"
                          value={formName}
                          readOnly={!edit?.creating}
                          onChange={(event) =>
                            stage({ op: 'upsert', name: event.target.value, style: { ...formStyle } }, true)
                          }
                        />
                      </label>
                      {edit?.creating && !validName && <p>Use a lowercase name matching [a-z][a-z0-9-]*.</p>}
                      {existingLocalName && (
                        <p role="status">
                          A local definition with this name already exists. Cancel creation and select that class to
                          edit it.
                        </p>
                      )}
                      {styleProperties.map((property) => (
                        <label key={property}>
                          {property}
                          <select
                            aria-label={`Style class ${property}`}
                            value={formStyle[property] ?? ''}
                            onChange={(event) => changeStyle(property, event.target.value)}
                          >
                            <option value="">Omit (use preset if available)</option>
                            {choices[property].map((choice) => (
                              <option key={choice} value={choice}>
                                {choice}
                              </option>
                            ))}
                          </select>
                        </label>
                      ))}
                    </fieldset>
                  )}
                  <div className="template-style-actions">
                    <button type="submit" disabled={!canApply}>
                      Apply style changes
                    </button>
                    <button type="button" disabled={!edit && !busy} onClick={clear}>
                      Cancel style changes
                    </button>
                  </div>
                </form>
              )}
            </>
          )}
        </>
      )}
    </section>
  )
}
