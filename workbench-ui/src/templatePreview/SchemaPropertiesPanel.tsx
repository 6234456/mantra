import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Envelope } from '../types'
import { sameTemplateRevisions, TemplatePreviewError, type TemplateSource, type TemplateSources } from './client'
import type { SchemaPropertyDraft } from './recovery'
import './SchemaPropertiesPanel.css'
import {
  LiveSchemaPropertiesClient,
  type SchemaProperties,
  type SchemaPropertiesClient,
  type SchemaPropertiesRequest,
  type SchemaPropertyOwner,
  type SchemaPropertyOperation,
} from './schemaPropertiesClient'

interface Binding {
  caseId: string
  revision: string
  revisions: Record<string, string>
  handle: string
  text: string
}

function sameBinding(left?: Binding, right?: Binding): boolean {
  return (
    !!left &&
    !!right &&
    left.caseId === right.caseId &&
    left.revision === right.revision &&
    left.handle === right.handle &&
    left.text === right.text &&
    sameTemplateRevisions(left.revisions, right.revisions)
  )
}

function identity(owner: SchemaPropertyOwner): string {
  return JSON.stringify([owner.document, owner.path, owner.form, owner.node, owner.property])
}

function draftIdentity(draft: SchemaPropertyDraft): string {
  return JSON.stringify([draft.document, draft.path, draft.form, draft.node, draft.property])
}

function fieldText(owner: SchemaPropertyOwner): string {
  return typeof owner.value === 'string'
    ? owner.value
    : Array.isArray(owner.value)
      ? owner.value.map((value) => (value && typeof value === 'object' && 'kw' in value ? value.kw : '')).join(' ')
      : ''
}

/** Source properties are inspected explicitly. Paper remains the authority for computed presentation. */
export function SchemaPropertiesPanel({
  caseId,
  snapshot,
  source,
  text,
  conflict,
  onApply,
  onConflict,
  onDraftChange,
  restoredDraft,
  client: supplied,
}: {
  caseId: string
  snapshot: Envelope<TemplateSources>
  source?: TemplateSource
  text?: string
  conflict: boolean
  onApply: (caseId: string, request: SchemaPropertiesRequest, nextText: string) => boolean
  onConflict: () => void
  onDraftChange: (draft: SchemaPropertyDraft | null) => void
  restoredDraft?: SchemaPropertyDraft | null
  client?: SchemaPropertiesClient
}) {
  const client = useMemo(() => supplied ?? new LiveSchemaPropertiesClient(), [supplied])
  const [inspection, setInspection] = useState<{ binding: Binding; data: SchemaProperties }>()
  const [selection, setSelection] = useState(restoredDraft ? draftIdentity(restoredDraft) : '')
  const [edit, setEdit] = useState<SchemaPropertyDraft | null>(restoredDraft ?? null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [composing, setComposing] = useState(false)
  const compositionKey = useRef(false)
  const generation = useRef(0)
  const sequence = useRef(0)
  const pending = useRef<AbortController | null>(null)
  const binding: Binding | undefined =
    source && text !== undefined
      ? {
          caseId,
          revision: snapshot.revision,
          revisions: snapshot.data.baseRevisions,
          handle: source.handle,
          text,
        }
      : undefined
  const latest = useRef({ binding, conflict, edit, onApply, onConflict })
  useLayoutEffect(() => {
    latest.current = { binding, conflict, edit, onApply, onConflict }
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
  }, [caseId, snapshot.revision, snapshot.data.baseRevisions, source?.handle, text, conflict, abortPending])
  useLayoutEffect(() => {
    onDraftChange(edit)
  }, [edit, onDraftChange])

  const current = sameBinding(inspection?.binding, binding) && !conflict
  const selected = inspection?.data.owners.find((owner) => identity(owner) === selection)
  const matchingEdit =
    !!edit &&
    !!selected &&
    draftIdentity(edit) === identity(selected) &&
    inspection!.binding.text.slice(selected.declarationRange.startOffset, selected.declarationRange.endOffset) ===
      edit.declaration
  const classes = (edit?.text ?? '').trim() ? edit!.text.trim().split(/\s+/) : []
  const classError =
    edit?.property === 'classes' &&
    !edit.unset &&
    (classes.length > 64 ||
      new Set(classes).size !== classes.length ||
      classes.some((name) => !/^[a-z][a-z0-9-]*$/.test(name)))
  const canApply =
    current &&
    selected?.editable &&
    matchingEdit &&
    !classError &&
    !busy &&
    !composing &&
    (!edit?.unset || selected.canUnset)

  function stage(value: string, unset = false) {
    if (!selected?.editable || !current) return
    const next: SchemaPropertyDraft = {
      document: selected.document,
      path: [...selected.path],
      form: selected.form,
      node: selected.node,
      property: selected.property,
      declaration: inspection!.binding.text.slice(
        selected.declarationRange.startOffset,
        selected.declarationRange.endOffset,
      ),
      text: value,
      unset,
    }
    latest.current = { ...latest.current, edit: next }
    setEdit(next)
    setError('')
    setNotice('')
  }

  function cancel() {
    generation.current++
    pending.current?.abort()
    latest.current = { ...latest.current, edit: null }
    setEdit(null)
    setBusy(false)
    setError('')
    setNotice('Schema property changes cancelled.')
  }

  async function request(apply: boolean) {
    if (!binding || !source?.editable || conflict || busy || composing || (apply && !canApply)) return
    const controller = new AbortController()
    pending.current?.abort()
    pending.current = controller
    const id = ++generation.current
    const submitted = apply ? edit : null
    const operations: SchemaPropertyOperation[] =
      submitted && selected
        ? [
            submitted.unset
              ? { op: 'unset', owner: selected.handle }
              : submitted.property === 'classes'
                ? { op: 'setClasses', owner: selected.handle, classes }
                : { op: 'setText', owner: selected.handle, text: submitted.text },
          ]
        : []
    const sent: SchemaPropertiesRequest = {
      baseRevision: binding.revision,
      baseRevisions: { ...binding.revisions },
      draftSequence: ++sequence.current,
      handle: binding.handle,
      text: binding.text,
      operations,
    }
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const reply = await client.properties(caseId, sent, controller.signal)
      if (
        controller.signal.aborted ||
        generation.current !== id ||
        latest.current.conflict ||
        !sameBinding(binding, latest.current.binding)
      )
        return
      if (reply.data.source.document !== source.document)
        throw new Error('The response does not identify the captured root schema.')
      // A newer field edit must not be cleared or rebound by a late Apply confirmation.
      if (apply && latest.current.edit !== submitted) {
        setNotice('The fields changed while applying. Review and apply the current fields again.')
        return
      }
      if (apply && !latest.current.onApply(caseId, sent, reply.data.source.text)) {
        setError('The source changed. Inspect schema properties and review the retained fields again.')
        return
      }
      setInspection({ binding: { ...binding, text: reply.data.source.text }, data: reply.data })
      if (apply) {
        latest.current = { ...latest.current, edit: null }
        setEdit(null)
        setNotice('Schema property applied to the source draft. Preview the draft to check its results.')
      } else {
        setSelection((previous) =>
          latest.current.edit
            ? draftIdentity(latest.current.edit)
            : reply.data.owners.some((owner) => identity(owner) === previous)
              ? previous
              : reply.data.owners[0]
                ? identity(reply.data.owners[0])
                : '',
        )
        setNotice(latest.current.edit ? 'Properties refreshed. Review the retained fields before applying.' : '')
      }
    } catch (failure) {
      if (controller.signal.aborted || generation.current !== id) return
      setError(failure instanceof Error ? failure.message : String(failure))
      if (failure instanceof TemplatePreviewError && failure.status === 409) latest.current.onConflict()
    } finally {
      if (!controller.signal.aborted && generation.current === id) setBusy(false)
    }
  }

  return (
    <section className="sheet template-schema-properties" aria-label="Schema presentation properties">
      <h2>Schema presentation properties</h2>
      <p>
        Edit declared labels, note text and class assignments. Hidden and inactive declarations are included; final text
        and styles come from the engine preview.
      </p>
      {!source ? (
        <p>No editable root schema was captured.</p>
      ) : (
        <>
          <button
            type="button"
            disabled={busy || conflict || !source.editable || composing}
            onClick={() => void request(false)}
          >
            {inspection ? 'Refresh schema properties' : 'Inspect schema properties'}
          </button>
          {busy && <p role="status">Reading schema properties…</p>}
          {inspection && !current && (
            <p role="status">Source changed. Refresh schema properties before applying retained fields.</p>
          )}
          {inspection && (
            <label>
              Schema property
              <select
                aria-label="Schema property"
                value={selection}
                disabled={!!edit || busy || !current}
                onChange={(event) => setSelection(event.target.value)}
              >
                {!selected && (
                  <option value={selection}>
                    {edit ? 'Retained property unavailable' : 'No presentation declarations'}
                  </option>
                )}
                {inspection.data.owners.map((owner) => (
                  <option key={identity(owner)} value={identity(owner)}>
                    {owner.document}:{owner.declarationRange.line} · {owner.form} {owner.node ?? 'note'} ·{' '}
                    {owner.property}
                    {owner.editable ? '' : ' · read-only'}
                  </option>
                ))}
              </select>
            </label>
          )}
          {selected && (
            <p className="muted">
              {selected.editable ? 'Root schema declaration' : selected.reason} ·{' '}
              {selected.property === 'classes'
                ? selected.declared
                  ? 'Explicit class declaration'
                  : 'No class declaration'
                : 'Declared source text'}
            </p>
          )}
          {(selected || edit) && (
            <>
              <label>
                {(edit?.property ?? selected?.property) === 'classes' ? 'Assigned classes' : 'Declared text'}
                <textarea
                  aria-label={
                    (edit?.property ?? selected?.property) === 'classes' ? 'Assigned classes' : 'Declared text'
                  }
                  value={edit?.text ?? (selected ? fieldText(selected) : '')}
                  readOnly={!selected?.editable || !current || (!!edit && !matchingEdit)}
                  onChange={(event) => stage(event.target.value)}
                  onCompositionStart={() => {
                    compositionKey.current = true
                    setComposing(true)
                  }}
                  onCompositionEnd={() => {
                    setComposing(false)
                  }}
                  onKeyDownCapture={(event) => {
                    compositionKey.current = event.nativeEvent.isComposing || event.keyCode === 229
                  }}
                />
              </label>
              {(edit?.property ?? selected?.property) === 'classes' && (
                <p>
                  Separate class names with spaces, without colons. Empty text sets explicit []; Remove class
                  declaration restores absence. Unknown valid names are retained.
                </p>
              )}
              {selected?.canUnset && (
                <button
                  type="button"
                  disabled={!current || busy || composing}
                  onClick={() => stage(fieldText(selected), true)}
                >
                  Remove class declaration
                </button>
              )}
              {edit?.unset && <p role="status">The class declaration will be removed.</p>}
              {edit && !inspection && (
                <p role="status">
                  Restored schema fields have not been inspected. Inspect the current schema before applying them, or
                  cancel the fields.
                </p>
              )}
              {edit && inspection && !matchingEdit && (
                <p role="status">
                  The retained declaration is unavailable or has changed. Cancel these fields and select the intended
                  declaration again.
                </p>
              )}
              {classError && (
                <p role="alert">
                  Use at most 64 unique lowercase class names containing letters, digits and hyphens, starting with a
                  letter.
                </p>
              )}
              <button
                type="button"
                disabled={!canApply}
                onKeyDownCapture={(event) => {
                  compositionKey.current = event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229
                }}
                onPointerDown={() => {
                  compositionKey.current = false
                }}
                onClick={() => {
                  if (!compositionKey.current) void request(true)
                }}
              >
                Apply schema property
              </button>
              <button type="button" disabled={!edit} onClick={cancel}>
                Cancel schema property changes
              </button>
            </>
          )}
        </>
      )}
      {error && <p role="alert">{error}</p>}
      {notice && <p role="status">{notice}</p>}
    </section>
  )
}
