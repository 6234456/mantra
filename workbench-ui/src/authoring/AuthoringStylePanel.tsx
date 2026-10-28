import { useState } from 'react'
import type { Cell } from '../types'
import type { DocumentTexts, SourceOwner } from './service'
import { readStyleVocabulary, stylePresetReference } from './styleVocabulary'
import './AuthoringStylePanel.css'

export interface AuthoringStylePanelProps {
  owner?: SourceOwner
  documents: DocumentTexts
  value: string[]
  onChange: (classes: string[]) => void
  onApply: () => void
  disabled?: boolean
  cell?: Cell
  multiple?: boolean
}

export function AuthoringStylePanel({
  owner,
  documents,
  value,
  onChange,
  onApply,
  disabled = false,
  cell,
  multiple = false,
}: AuthoringStylePanelProps) {
  const [pickerOpen, setPickerOpen] = useState(false)
  const vocabulary = readStyleVocabulary(documents, value)
  const readOnly = disabled || !owner?.editable || multiple
  return (
    <section className="author-style-panel" aria-label="Style classes">
      <h3>Assigned classes</h3>
      {value.length ? (
        <ul className="author-class-chips" aria-label="Assigned classes in source order">
          {value.map((name, index) => (
            <li key={`${name}:${index}`}>
              <span className="author-class-chip">
                <code>:{name}</code>
                <button
                  type="button"
                  aria-label={`Remove class ${name}`}
                  disabled={readOnly}
                  onClick={() => onChange(value.filter((_item, position) => position !== index))}
                >
                  ×
                </button>
              </span>
              {vocabulary.unmatched.includes(name) && <small>No matching rule · no visual effect</small>}
            </li>
          ))}
        </ul>
      ) : (
        <p>No classes assigned.</p>
      )}
      <p className="author-style-hint">Order does not set precedence.</p>
      <button
        type="button"
        disabled={readOnly}
        aria-expanded={pickerOpen}
        aria-controls="author-class-picker"
        onClick={() => setPickerOpen(!pickerOpen)}
      >
        Add class
      </button>
      {pickerOpen && (
        <div className="author-class-picker" id="author-class-picker">
          <p>
            Vocabulary from <a href={stylePresetReference.url}>{stylePresetReference.label}</a>, selected by this
            layout.
          </p>
          {vocabulary.groups.map((group) => (
            <fieldset key={group.id}>
              <legend>{group.title}</legend>
              <small>{group.source}</small>
              <ul>
                {group.choices.map((choice) => (
                  <li key={`${choice.kind}:${choice.name}`}>
                    <button
                      type="button"
                      aria-label={`Add class ${choice.name}`}
                      disabled={readOnly || value.includes(choice.name)}
                      onClick={() => {
                        onChange([...value, choice.name])
                        setPickerOpen(false)
                      }}
                    >
                      <code>:{choice.name}</code>
                      <span>{choice.declaration}</span>
                      <small>{choice.source}</small>
                    </button>
                  </li>
                ))}
              </ul>
            </fieldset>
          ))}
          {!vocabulary.groups.length && <p>No class vocabulary declared by this layout.</p>}
          {vocabulary.notices.map((notice) => (
            <p key={notice}>{notice}</p>
          ))}
        </div>
      )}
      <button type="button" disabled={readOnly} onClick={onApply}>
        Apply classes
      </button>
      {multiple && <p>Multi-row class editing is planned; apply to one declaration at a time.</p>}
      {!owner?.editable && <p>{owner?.reason ?? 'This selection has no editable class declaration.'}</p>}
      <h3>Final cell style · Paper</h3>
      {cell ? (
        <dl className="author-resolved-style">
          <dt>Weight</dt>
          <dd>{cell.style?.weight ?? 'Not supplied by Paper'}</dd>
          <dt>Tone</dt>
          <dd>{cell.style?.tone ?? 'Not supplied by Paper'}</dd>
          <dt>Fill</dt>
          <dd>{cell.style?.fill ?? 'Not supplied by Paper'}</dd>
        </dl>
      ) : (
        <p>No Paper cell selected.</p>
      )}
      <p>Rule provenance unavailable.</p>
      <p>Styles never change values, rounding, aggregation, applicability or validation.</p>
      <p className="author-style-hint">A control class does not mean that validation passed.</p>
    </section>
  )
}
