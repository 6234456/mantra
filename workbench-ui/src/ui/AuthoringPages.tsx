import { useState } from 'react'
import type { WorkbenchData } from '../data'
import type { ExtensionSlot, FormulaSlot, Structure } from '../types'
import { language, t } from '../i18n'
import { FormulaEditor } from './FormulaEditor'
import './AuthoringPages.css'

const lang = language()
const label = (de: string, en: string) => (lang === 'de' ? de : en)

export function FormulaSlotCard({
  slot,
  caseId,
  revision,
  data,
  onSaved,
}: {
  slot: FormulaSlot
  caseId: string
  revision: string
  data: WorkbenchData
  onSaved: () => void
}) {
  const [error, setError] = useState('')
  async function reset() {
    setError('')
    try {
      await data.formulaEdit(caseId, revision, { op: 'unbindFormula', id: slot.id }, false)
      onSaved()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    }
  }
  return (
    <section className="sheet formula-slot-card">
      <div className="section-heading">
        <div>
          <span className="eyebrow">Formula slot</span>
          <h2>{slot.title}</h2>
          <code>{slot.id}</code>
        </div>
        <span className={`formula-state ${slot.binding ? 'bound' : ''}`}>
          {slot.binding ? label('Überschrieben', 'Bound') : label('Schemaformel', 'Schema formula')}
        </span>
      </div>
      <div className="formula-context">
        <div>
          <span className="eyebrow">{label('Standardformel', 'Default formula')}</span>
          <code>{slot.defaultFormula ?? '—'}</code>
        </div>
        <div>
          <span className="eyebrow">:uses</span>
          <p>{slot.uses?.length ? slot.uses.join(' · ') : label('Keine Beschränkung', 'No restriction')}</p>
        </div>
      </div>
      <FormulaEditor
        key={`${slot.id}-${slot.binding ?? ''}`}
        caseId={caseId}
        revision={revision}
        target={{ kind: 'formulaSlot', id: slot.id }}
        initialFormula={slot.binding ?? slot.defaultFormula ?? ''}
        operation={(formula) => ({ op: 'bindFormula', id: slot.id, formula })}
        data={data}
        onSaved={onSaved}
      />
      {slot.binding && (
        <button type="button" className="formula-secondary" onClick={() => void reset()}>
          {label('Auf Standard zurücksetzen', 'Reset to default')}
        </button>
      )}
      {error && (
        <p role="alert" className="formula-error">
          {error}
        </p>
      )}
    </section>
  )
}

function ExtensionCard({
  slot,
  id,
  structure,
  caseId,
  revision,
  data,
  onSaved,
}: {
  slot: ExtensionSlot
  id: string
  structure: Structure
  caseId: string
  revision: string
  data: WorkbenchData
  onSaved: () => void
}) {
  const node = structure.nodes?.[id]
  const [title, setTitle] = useState(node?.label ?? id)
  const [error, setError] = useState('')
  const initial = typeof node?.formula === 'string' ? node.formula : (node?.formula?.text ?? '')
  async function remove() {
    if (!window.confirm(label(`Zeile ${id} entfernen?`, `Remove line ${id}?`))) return
    setError('')
    try {
      await data.formulaEdit(caseId, revision, { op: 'removeExtension', slot: slot.id, id }, false)
      onSaved()
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    }
  }
  return (
    <article className="extension-line">
      <div className="extension-title">
        <div>
          <span className="eyebrow">{label('Benutzerzeile', 'User line')}</span>
          <code>{id}</code>
        </div>
        <label>
          {label('Titel', 'Title')}
          <input value={title} onChange={(event) => setTitle(event.target.value)} />
        </label>
      </div>
      <FormulaEditor
        caseId={caseId}
        revision={revision}
        target={{ kind: 'extension', slot: slot.id, id, title }}
        initialFormula={initial}
        operation={(formula) => ({ op: 'updateExtension', slot: slot.id, id, title, formula })}
        data={data}
        onSaved={onSaved}
      />
      <button type="button" className="formula-secondary" onClick={() => void remove()}>
        {label('Zeile entfernen', 'Remove line')}
      </button>
      {error && (
        <p role="alert" className="formula-error">
          {error}
        </p>
      )}
    </article>
  )
}

function ReadOnlyExtension({ id, structure }: { id: string; structure: Structure }) {
  const node = structure.nodes?.[id]
  return (
    <article className="extension-readonly">
      <div>
        <span className="eyebrow">{label('Nicht als Formel bearbeitbar', 'Not editable as a formula')}</span>
        <h3>{node?.label ?? id}</h3>
        <code>{id}</code>
      </div>
      <span className="muted">{node?.kind ?? label('Unbekannter Typ', 'Unknown type')}</span>
    </article>
  )
}

function ExtensionSlotCard({
  slot,
  structure,
  caseId,
  revision,
  data,
  onSaved,
}: {
  slot: ExtensionSlot
  structure: Structure
  caseId: string
  revision: string
  data: WorkbenchData
  onSaved: () => void
}) {
  const [id, setId] = useState('')
  const [title, setTitle] = useState('')
  return (
    <section className="sheet extension-slot-card">
      <div className="section-heading">
        <div>
          <span className="eyebrow">Slot</span>
          <h2>{slot.title}</h2>
          <code>{slot.id}</code>
        </div>
        <span className="muted">
          {slot.extensions.length} {label('Zeilen', 'lines')}
        </span>
      </div>
      {slot.extensions.map((line) =>
        structure.nodes?.[line]?.formula != null ? (
          <ExtensionCard
            key={line}
            slot={slot}
            id={line}
            structure={structure}
            caseId={caseId}
            revision={revision}
            data={data}
            onSaved={onSaved}
          />
        ) : (
          <ReadOnlyExtension key={line} id={line} structure={structure} />
        ),
      )}
      <div className="extension-new">
        <span className="eyebrow">{label('Neue Zeile', 'New line')}</span>
        <div className="extension-title">
          <label>
            ID
            <input value={id} onChange={(event) => setId(event.target.value)} placeholder="new-line" />
          </label>
          <label>
            {label('Titel', 'Title')}
            <input value={title} onChange={(event) => setTitle(event.target.value)} />
          </label>
        </div>
        {id.trim() && title.trim() ? (
          <FormulaEditor
            caseId={caseId}
            revision={revision}
            target={{ kind: 'extension', slot: slot.id, id: id.trim(), title: title.trim() }}
            initialFormula=""
            operation={(formula) => ({
              op: 'addExtension',
              slot: slot.id,
              id: id.trim(),
              title: title.trim(),
              formula,
            })}
            data={data}
            onSaved={onSaved}
          />
        ) : (
          <p className="muted">
            {label('ID und Titel eingeben, um eine Formel zu schreiben.', 'Enter an ID and title to write a formula.')}
          </p>
        )}
      </div>
    </section>
  )
}

export function ExtensionsPage({
  caseId,
  structure,
  revision,
  data,
  onSaved,
}: {
  caseId: string
  structure: Structure
  revision: string
  data: WorkbenchData
  onSaved: () => void
}) {
  const slots = structure.slots ?? []
  const formulas = structure.formulaSlots ?? []
  return (
    <>
      <div className="page-heading">
        <span className="eyebrow">{t('workspace', lang)}</span>
        <h1>{t('extensions', lang)}</h1>
        <p>{structure.title}</p>
      </div>
      {!slots.length && !formulas.length && (
        <section className="sheet authoring-empty">
          {label(
            'Dieses Schema öffnet keine Formeln zur Bearbeitung.',
            'This schema does not expose editable formulas.',
          )}
        </section>
      )}
      <div className="authoring-list">
        {slots.map((slot) => (
          <ExtensionSlotCard
            key={slot.id}
            slot={slot}
            structure={structure}
            caseId={caseId}
            revision={revision}
            data={data}
            onSaved={onSaved}
          />
        ))}
        {formulas.map((slot) => (
          <FormulaSlotCard
            key={slot.id}
            slot={slot}
            caseId={caseId}
            revision={revision}
            data={data}
            onSaved={onSaved}
          />
        ))}
      </div>
    </>
  )
}
