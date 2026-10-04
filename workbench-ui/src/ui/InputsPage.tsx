import { useEffect, useMemo, useRef, useState } from 'react'
import type { WorkbenchData } from '../data'
import type { Address, Diagnostic, EditOperation, EditResult, InputField, Run, Structure, Value } from '../types'
import { casePath } from '../address'
import { diagnosticsForInput } from '../diagnostics'
import { language, t } from '../i18n'
import { diagnosticDetailsLabel, explainDiagnostic } from '../diagnosticMessages'
import './InputsPage.css'
import { LinkedSourceEvidence } from './LinkedSourceEvidence'

const lang = language()
type Field = InputField
type Group = { id: string; title: string; fields: Field[]; entries?: Structure['panels'][number]['entries'] }

function field(item: string | { id: string }, structure: Structure): Field {
  const declared = typeof item === 'string' ? { id: item } : item
  return { ...structure.nodes?.[declared.id], ...declared, id: declared.id } as Field
}
function groups(structure: Structure): Group[] {
  const all = [
    {
      id: 'general',
      title: lang === 'de' ? 'Allgemeine Eingaben' : 'General inputs',
      fields: structure.generalInputs.map((item) => field(item, structure)),
    },
    ...structure.panels
      .filter((panel) => panel.fields.length)
      .map((panel) => ({
        id: panel.id,
        title: panel.title,
        fields: panel.fields.map((item) => field(item, structure)),
        entries: panel.entries,
      })),
  ]
  return all.filter((group) => group.fields.length)
}
function valueText(value: Value | undefined): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (Array.isArray(value)) return ''
  if ('n' in value) return value.n
  if ('kw' in value) return value.kw
  if ('date' in value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.date)
    return match ? `${match[3]}.${match[2]}.${match[1]}` : value.date
  }
  return ''
}
function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}

export function InputsPage({
  caseId,
  groupId,
  structure,
  run,
  revision,
  data,
  effect,
  onSaved,
  navigate,
  selectedAddress,
}: {
  caseId: string
  groupId?: string
  structure: Structure
  run: Run
  revision: string
  data: WorkbenchData
  effect?: EditResult['difference']
  onSaved: (difference: EditResult['difference']) => void
  navigate: (path: string) => void
  selectedAddress?: Address
}) {
  const editable = data.canEditCase?.(caseId) !== false
  const sections = useMemo(() => groups(structure), [structure])
  const active = sections.find((item) => item.id === groupId) ?? sections[0]
  if (!active)
    return (
      <section className="sheet input-empty">
        {lang === 'de' ? 'Keine Eingaben deklariert.' : 'No inputs declared.'}
      </section>
    )
  async function save(operation: EditOperation) {
    const result = await data.edit(caseId, revision, [operation])
    onSaved(result.data.difference)
  }
  return (
    <>
      <div className="page-heading">
        <span className="eyebrow">{t('workspace', lang)}</span>
        <h1>{t('inputs', lang)}</h1>
        <p>{structure.title}</p>
      </div>
      {!editable && (
        <p role="status">{lang === 'de' ? 'Dieser Fall ist schreibgeschützt.' : 'This case is read-only.'}</p>
      )}
      {!run.validationPassed && editable && (
        <p className="input-validation-note" role="status">
          {lang === 'de'
            ? 'Fachliche Prüfungen sind offen. Eingaben können weiter gespeichert werden.'
            : 'Business checks need attention. Inputs can still be saved.'}
        </p>
      )}
      <div className="input-layout">
        <nav className="sheet input-groups" aria-label={lang === 'de' ? 'Eingabegruppen' : 'Input groups'}>
          <span className="eyebrow">{lang === 'de' ? 'Bereiche' : 'Groups'}</span>
          {sections.map((section) => (
            <a
              key={section.id}
              href={`${casePath(caseId)}/inputs/${encodeURIComponent(section.id)}`}
              aria-current={active.id === section.id ? 'page' : undefined}
              onClick={(event) => {
                event.preventDefault()
                navigate(event.currentTarget.pathname)
              }}
            >
              <span>{section.title}</span>
              <small>{section.fields.length}</small>
            </a>
          ))}
        </nav>
        <div className="input-main">
          <fieldset className="sheet input-sheet" disabled={!editable}>
            <div className="section-heading">
              <div>
                <span className="eyebrow">{t('inputs', lang)}</span>
                <h2>{active.title}</h2>
              </div>
              <span className="muted">{active.fields.length}</span>
            </div>
            {active.fields.map((item) =>
              item.type === 'table' ? (
                <TableField key={item.id} field={item} run={run} save={save} selectedAddress={selectedAddress} />
              ) : (
                <div key={item.id} className="input-field">
                  <div className="input-field-heading">
                    <div>
                      <h3>{item.label ?? item.id}</h3>
                      <span className="input-ref">{String(item.attributes?.kz ?? item.reference ?? item.id)}</span>
                    </div>
                    {item.unit && <span className="input-unit">{item.unit}</span>}
                  </div>
                  {item.help && <p className="muted">{item.help}</p>}
                  <div className="input-members">
                    {coordinates(item, run).map(({ coord, label }) => (
                      <InputControl
                        key={coord.join('/')}
                        field={item}
                        address={{ case: null, node: item.id, ...(coord.length ? { coord } : {}) }}
                        label={label}
                        run={run}
                        rootCaseId={caseId}
                        navigate={navigate}
                        revision={revision}
                        save={save}
                        selected={
                          selectedAddress?.node === item.id &&
                          !selectedAddress.cell &&
                          JSON.stringify(selectedAddress.coord ?? []) === JSON.stringify(coord)
                        }
                      />
                    ))}
                  </div>
                </div>
              ),
            )}
          </fieldset>
          <aside className="sheet input-effect">
            <span className="eyebrow">{lang === 'de' ? 'Wirkung der Eingabe' : 'Input effect'}</span>
            <h2>{active.title}</h2>
            {active.entries?.length ? (
              active.entries.map((entry) => (
                <div className="effect-path" key={`${entry.step}-${entry.via}`}>
                  <span>
                    {t('step', lang)} {entry.step}
                  </span>
                  <strong>{entry.viaLabel}</strong>
                </div>
              ))
            ) : (
              <p className="muted">
                {lang === 'de'
                  ? 'Allgemeine Eingaben wirken auf die Berechnung.'
                  : 'General inputs feed the calculation.'}
              </p>
            )}
            {effect && (
              <div className="effect-changes">
                <span className="eyebrow">{lang === 'de' ? 'Nach dem Speichern' : 'After saving'}</span>
                {effect.mainline.length ? (
                  effect.mainline.map((change, index) => (
                    <div className="effect-change" key={`${change.node}-${index}`}>
                      <span>{structure.nodes?.[change.node]?.label ?? change.node}</span>
                      <strong>
                        {change.display.base ?? '—'} → {change.display.variant ?? '—'}
                      </strong>
                    </div>
                  ))
                ) : (
                  <p className="muted">{lang === 'de' ? 'Keine Änderung der Hauptlinie.' : 'No mainline change.'}</p>
                )}
              </div>
            )}
          </aside>
        </div>
      </div>
    </>
  )
}

function coordinates(field: Field, run: Run): Array<{ coord: string[]; label: string }> {
  if (!field.dims?.length) return [{ coord: [], label: '' }]
  let result = [{ coord: [] as string[], label: '' }]
  for (const dim of field.dims)
    result = result.flatMap((previous) =>
      (run.members[dim] ?? []).map((member) => ({
        coord: [...previous.coord, member.key],
        label: [previous.label, member.label].filter(Boolean).join(' / '),
      })),
    )
  return result
}

function InputControl({
  field,
  address,
  label,
  run,
  rootCaseId,
  navigate,
  revision,
  save,
  selected = false,
}: {
  field: Field
  address: Address
  label: string
  run: Run
  rootCaseId: string
  navigate: (path: string) => void
  revision: string
  save: (operation: EditOperation) => Promise<void>
  selected?: boolean
}) {
  const coordinateKey = address.coord?.join('/') ?? ''
  const item = run.values[field.id]?.[coordinateKey]
  const inputText = valueText(item?.value)
  const [draft, setDraft] = useState(inputText)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const control = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (selected) control.current?.querySelector<HTMLInputElement | HTMLSelectElement>('input,select')?.focus()
  }, [selected])
  useEffect(() => {
    setDraft(inputText)
    setError('')
  }, [revision, field.id, coordinateKey, inputText])
  const diagnostics = diagnosticsForInput(run.diagnostics, address)
  const invalid = !!error || diagnostics.some((finding) => finding.severity === 'error')
  const needsExplicitValue = diagnostics.some(
    (finding) => finding.category === 'business' && finding.code === 'MANTRA-INPUT-REQUIRED',
  )
  const title = label || field.label || field.id
  async function submit(next: string) {
    setBusy(true)
    setError('')
    try {
      await save(next === '' ? { op: 'clearInput', address } : { op: 'setInput', address, text: next })
    } catch (failure) {
      setError(errorText(failure))
    } finally {
      setBusy(false)
    }
  }
  const options = Object.entries(field.options ?? {})
  if (item?.link || item?.origin?.toUpperCase() === 'LINK') {
    return (
      <div className="input-control linked-input-control">
        <span>{title}</span>
        <output className="linked-input-output" aria-label={title}>
          {item.display}
        </output>
        {item.link ? (
          <LinkedSourceEvidence source={item.link} rootCaseId={rootCaseId} navigate={navigate} />
        ) : (
          <p className="muted">{lang === 'de' ? 'Verknüpfter Wert · schreibgeschützt' : 'Linked value · read only'}</p>
        )}
      </div>
    )
  }
  return (
    <div ref={control} className={`input-control${selected ? ' is-selected' : ''}`}>
      <label htmlFor={`input-${field.id}-${address.coord?.join('-') ?? 'single'}`}>
        {label || (lang === 'de' ? 'Wert' : 'Value')}
      </label>
      <div className="input-control-row">
        {field.type === 'boolean' ? (
          <input
            id={`input-${field.id}-${address.coord?.join('-') ?? 'single'}`}
            type="checkbox"
            checked={draft === 'true'}
            disabled={busy}
            aria-invalid={invalid}
            onChange={(event) => {
              const next = String(event.target.checked)
              setDraft(next)
              void submit(next)
            }}
          />
        ) : options.length ? (
          <select
            id={`input-${field.id}-${address.coord?.join('-') ?? 'single'}`}
            value={draft}
            disabled={busy}
            aria-invalid={invalid}
            onChange={(event) => {
              setDraft(event.target.value)
              void submit(event.target.value)
            }}
          >
            <option value="">—</option>
            {options.map(([key, text]) => (
              <option key={key} value={key}>
                {text || key}
              </option>
            ))}
          </select>
        ) : (
          <input
            id={`input-${field.id}-${address.coord?.join('-') ?? 'single'}`}
            type="text"
            inputMode={field.type === 'decimal' || field.type === 'integer' ? 'decimal' : 'text'}
            value={draft}
            disabled={busy}
            aria-invalid={invalid}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void submit(draft)
            }}
          />
        )}
        {((field.type !== 'boolean' && !options.length) || needsExplicitValue) && (
          <button
            type="button"
            disabled={busy || (draft === valueText(item?.value) && !needsExplicitValue)}
            onClick={() => void submit(field.type === 'boolean' ? String(draft === 'true') : draft)}
          >
            {busy ? '…' : lang === 'de' ? 'Speichern' : 'Save'}
          </button>
        )}
        {item?.origin && <small className="origin-tag">{item.origin}</small>}
      </div>
      {error && (
        <p className="input-error" role="alert">
          {error}
        </p>
      )}
      {diagnostics.map((diagnostic, index) => (
        <p className={`input-diagnostic ${diagnostic.category} ${diagnostic.severity}`} key={index}>
          {explainDiagnostic(diagnostic, lang).summary}
          <br />
          <span className="diagnostic-original-detail">
            {diagnosticDetailsLabel(lang)}: <span lang="en">{diagnostic.message}</span>
          </span>
        </p>
      ))}
      <span className="sr-only">{title}</span>
    </div>
  )
}

function tableRows(value: Value | undefined): Array<Record<string, Value>> {
  if (!Array.isArray(value)) return []
  return value.map((row) =>
    row && typeof row === 'object' && !Array.isArray(row) && 'map' in row
      ? Object.fromEntries(row.map.map(([key, item]) => [valueText(key), item]))
      : {},
  )
}
function TableField({
  field,
  run,
  save,
  selectedAddress,
}: {
  field: Field
  run: Run
  save: (operation: EditOperation) => Promise<void>
  selectedAddress?: Address
}) {
  const rows = tableRows(run.values[field.id]?.['']?.value)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [newRow, setNewRow] = useState<Record<string, string>>({})
  const columns = field.columns ?? []
  const tableDiagnostics = diagnosticsForInput(run.diagnostics, { case: null, node: field.id })
  const indexedSelection =
    selectedAddress?.cell?.row !== undefined && rows.some((_, index) => String(index) === selectedAddress.cell?.row)
  async function mutate(operation: EditOperation) {
    setBusy(true)
    setError('')
    try {
      await save(operation)
      return true
    } catch (failure) {
      setError(errorText(failure))
      return false
    } finally {
      setBusy(false)
    }
  }
  const rowKey = (row: Record<string, Value>, index: number) =>
    field.keyColumn ? valueText(row[field.keyColumn]) : String(index)
  function addRow() {
    const rowText = Object.fromEntries(Object.entries(newRow).filter(([, text]) => text.trim() !== ''))
    if (!Object.keys(rowText).length) {
      setError(lang === 'de' ? 'Mindestens einen Spaltenwert eingeben.' : 'Enter at least one column value.')
      return
    }
    void mutate({ op: 'insertRow', table: field.id, rowText }).then((success) => {
      if (success) setNewRow({})
    })
  }
  return (
    <div className="input-field">
      <div className="input-field-heading">
        <div>
          <h3>{field.label ?? field.id}</h3>
          <span className="input-ref">{field.id}</span>
        </div>
      </div>
      <div className="input-table-scroll">
        <table>
          <thead>
            <tr>
              {columns.map((column) => (
                <th key={column.name}>{column.name}</th>
              ))}
              <th>
                <span className="sr-only">{lang === 'de' ? 'Aktionen' : 'Actions'}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={index}>
                {columns.map((column) => (
                  <td key={column.name}>
                    <TableCell
                      table={field.id}
                      row={rowKey(row, index)}
                      column={column.name}
                      value={row[column.name]}
                      save={save}
                      diagnostics={diagnosticsForInput(
                        run.diagnostics,
                        { case: null, node: field.id, cell: { row: rowKey(row, index), column: column.name } },
                        index,
                      )}
                      selected={
                        selectedAddress?.node === field.id &&
                        selectedAddress.cell?.column === column.name &&
                        selectedAddress.cell?.row === (indexedSelection ? String(index) : rowKey(row, index))
                      }
                    />
                  </td>
                ))}
                <td>
                  <button
                    type="button"
                    disabled={busy || index === 0}
                    onClick={() => void mutate({ op: 'moveRow', table: field.id, from: index, to: index - 1 })}
                    aria-label={`Move row ${index + 1} up`}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    disabled={busy || index === rows.length - 1}
                    onClick={() => void mutate({ op: 'moveRow', table: field.id, from: index, to: index + 1 })}
                    aria-label={`Move row ${index + 1} down`}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => void mutate({ op: 'deleteRow', table: field.id, index })}
                    aria-label={`Delete row ${index + 1}`}
                  >
                    ×
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {tableDiagnostics.map((diagnostic, index) => (
        <p key={index} className={`input-diagnostic ${diagnostic.category} ${diagnostic.severity}`}>
          {explainDiagnostic(diagnostic, lang).summary}
          <br />
          <span className="diagnostic-original-detail">
            {diagnosticDetailsLabel(lang)}: <span lang="en">{diagnostic.message}</span>
          </span>
        </p>
      ))}
      <div className="input-add-row">
        <span>{lang === 'de' ? 'Neue Zeile' : 'New row'}</span>
        {columns.map((column) => (
          <label key={column.name}>
            {column.name}
            <input
              value={newRow[column.name] ?? ''}
              onChange={(event) => setNewRow((previous) => ({ ...previous, [column.name]: event.target.value }))}
            />
          </label>
        ))}
        <button type="button" disabled={busy} onClick={addRow}>
          {lang === 'de' ? 'Zeile hinzufügen' : 'Add row'}
        </button>
      </div>
      {error && (
        <p className="input-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

function TableCell({
  table,
  row,
  column,
  value,
  save,
  diagnostics = [],
  selected = false,
}: {
  table: string
  row: string
  column: string
  value?: Value
  save: (operation: EditOperation) => Promise<void>
  diagnostics?: Diagnostic[]
  selected?: boolean
}) {
  const [draft, setDraft] = useState(valueText(value))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const control = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (selected) control.current?.querySelector<HTMLInputElement>('input')?.focus()
  }, [selected])
  useEffect(() => {
    setDraft(valueText(value))
    setError('')
  }, [value])
  async function submit() {
    setBusy(true)
    setError('')
    const address: Address = { case: null, node: table, cell: { row, column } }
    try {
      await save(draft === '' ? { op: 'clearInput', address } : { op: 'setInput', address, text: draft })
    } catch (failure) {
      setError(errorText(failure))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div ref={control} className={`table-cell-edit${selected ? ' is-selected' : ''}`}>
      <input
        aria-label={`${column} · ${row}`}
        value={draft}
        disabled={busy}
        aria-invalid={!!error || diagnostics.some((finding) => finding.severity === 'error')}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') void submit()
        }}
      />
      <button
        type="button"
        disabled={busy || draft === valueText(value)}
        onClick={() => void submit()}
        aria-label={`Save ${column} · ${row}`}
      >
        ✓
      </button>
      {error && (
        <small role="alert" className="input-error">
          {error}
        </small>
      )}
      {diagnostics.map((diagnostic, index) => (
        <small className={`input-diagnostic ${diagnostic.category} ${diagnostic.severity}`} key={index}>
          {explainDiagnostic(diagnostic, lang).summary}
          <br />
          <span className="diagnostic-original-detail">
            {diagnosticDetailsLabel(lang)}: <span lang="en">{diagnostic.message}</span>
          </span>
        </small>
      ))}
    </div>
  )
}
