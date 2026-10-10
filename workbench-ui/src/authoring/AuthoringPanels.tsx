import { useEffect, useRef, useState } from 'react'
import { explainDiagnostic } from '../diagnosticMessages'
import { ExplainDetails } from '../ui/ExplainDetails'
import { ValidationEvidence } from '../ui/ValidationEvidence'
import type { Diagnostic, Explain, ExportPreview, RunValue } from '../types'
import { AuthoringCodeEditor } from './AuthoringCodeEditor'
import type { AuthoringState } from './model'
import { diagnosticsAreStale } from './model'
import type { DocumentTexts, SourceOwner, SourcePatch } from './service'
import { ownerForDiagnostic } from './simulated/sourceOwners'
import config from './config.json'

export function ownerText(owner: SourceOwner | undefined) {
  return owner ? (Array.isArray(owner.value) ? owner.value.join(' ') : String(owner.value)) : ''
}

export function sourcePatch(document: string, before: string, after: string): SourcePatch {
  let start = 0
  while (start < before.length && start < after.length && before[start] === after[start]) start++
  let end = before.length
  let replacementEnd = after.length
  while (end > start && replacementEnd > start && before[end - 1] === after[replacementEnd - 1]) {
    end--
    replacementEnd--
  }
  return { document, start, end, text: after.slice(start, replacementEnd), inverse: before.slice(start, end) }
}

export function PrototypeDialog({
  title,
  children,
  onClose,
}: {
  title: string
  children: React.ReactNode
  onClose: () => void
}) {
  const element = useRef<HTMLDivElement>(null)
  const close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    element.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="author-modal-backdrop">
      <div
        ref={element}
        className="author-modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onKeyDown={(event) => {
          if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
          if (event.key === 'Escape') {
            event.preventDefault()
            close.current()
          }
          if (event.key !== 'Tab') return
          const controls = element.current?.querySelectorAll<HTMLElement>(
            'button:not(:disabled), input, [tabindex="0"]',
          )
          const first = controls?.[0]
          const last = controls?.[controls.length - 1]
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault()
            last?.focus()
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault()
            first?.focus()
          }
        }}
      >
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  )
}

export function SourcePane({
  state,
  owners,
  selectedOwner,
  inputs,
  onInput,
  onPatch,
  onSelect,
  onComposition,
}: {
  state: AuthoringState
  owners: SourceOwner[]
  selectedOwner?: SourceOwner
  inputs: Record<string, string>
  onInput: (key: string, value: string) => void
  onPatch: (patch: SourcePatch) => void
  onSelect: (owner: SourceOwner) => void
  onComposition: (value: boolean) => void
}) {
  const [selectedDocument, setDocument] = useState(selectedOwner?.document ?? Object.keys(state.documents)[0])
  const [composing, setComposing] = useState(false)
  const roles = config.documentRoles as Record<string, string>
  const source = state.documents[selectedDocument] ?? ''
  const key = `source:${selectedDocument}`
  const pending = inputs[key] ?? source
  const readOnly = !['schema', 'layout'].includes(roles[selectedDocument]) || !!state.saving || !!state.readOnlyReason
  const latest = useRef({ source, pending, readOnly, selectedDocument, onPatch })
  latest.current = { source, pending, readOnly, selectedDocument, onPatch }
  useEffect(() => {
    if (selectedOwner?.document) setDocument(selectedOwner.document)
  }, [selectedOwner?.document])
  useEffect(() => {
    if (composing || readOnly || source === pending) return
    const timer = setTimeout(() => {
      const current = latest.current
      if (current.source !== current.pending && !current.readOnly)
        current.onPatch(sourcePatch(current.selectedDocument, current.source, current.pending))
    }, 500)
    return () => clearTimeout(timer)
  }, [pending, source, composing, readOnly, selectedDocument])
  const selection =
    selectedOwner?.document === selectedDocument && pending === source
      ? { from: selectedOwner.range.start, to: selectedOwner.range.end }
      : undefined
  return (
    <section className="author-card" aria-label="Source view">
      <div className="author-tabs" role="tablist" aria-label="Participating documents">
        {Object.keys(state.documents).map((path) => (
          <button
            key={path}
            role="tab"
            aria-selected={selectedDocument === path}
            onClick={() => setDocument(path)}
            title={path}
          >
            {roles[path]} {['fragment', 'case'].includes(roles[path]) ? '🔒' : ''}
          </button>
        ))}
      </div>
      <p className="author-reference">{selectedDocument}</p>
      <AuthoringCodeEditor
        value={pending}
        ariaLabel={`Source ${roles[selectedDocument]}`}
        editorKey={selectedDocument}
        readOnly={readOnly}
        selection={selection}
        onChange={(value) => onInput(key, value)}
        onCompositionChange={(value) => {
          setComposing(value)
          onComposition(value)
        }}
        onSelection={(from) => {
          if (pending !== source) return
          const owner = owners
            .filter((item) => item.document === selectedDocument && item.range.start <= from && item.range.end >= from)
            .sort((left, right) => left.range.end - left.range.start - (right.range.end - right.range.start))[0]
          if (owner && owner.handle !== selectedOwner?.handle) onSelect(owner)
        }}
      />
      <p className="author-reference">
        {readOnly ? 'Read-only in this prototype.' : 'Source edits become one source transaction after 500 ms idle.'}
      </p>
    </section>
  )
}

export function ExplainPanel({
  explain,
  value,
  previewSequence,
}: {
  explain?: Explain
  value?: RunValue
  previewSequence?: number
}) {
  return (
    <>
      <p className="author-reference">Recorded engine Explain · preview draft #{previewSequence}</p>
      {explain ? (
        <>
          <code>{explain.formula?.text}</code>
          <p>{explain.result.display}</p>
          <ExplainDetails explain={explain} />
          {value?.validation && <ValidationEvidence validation={value.validation} />}
        </>
      ) : (
        <p>No recorded Explain for this owner.</p>
      )}
    </>
  )
}

export function ProblemsPanel({
  state,
  owners,
  onSelect,
}: {
  state: AuthoringState
  owners: SourceOwner[]
  onSelect: (owner: SourceOwner) => void
}) {
  const groups = new Map<string, { owner?: SourceOwner; diagnostics: Diagnostic[] }>()
  for (const diagnostic of state.diagnostics) {
    const owner = diagnosticsAreStale(state) ? undefined : ownerForDiagnostic(owners, diagnostic)
    const key = owner?.handle ?? 'unlocated'
    const group = groups.get(key) ?? { owner, diagnostics: [] }
    group.diagnostics.push(diagnostic)
    groups.set(key, group)
  }
  return (
    <>
      {diagnosticsAreStale(state) && <p className="author-stale">Previous diagnostic locations are stale.</p>}
      {!state.diagnostics.length && <p>No problems.</p>}
      {[...groups.entries()].map(([key, group]) => (
        <section key={key} className="author-problem">
          {group.owner ? (
            <button onClick={() => onSelect(group.owner!)}>{group.owner.label ?? group.owner.declaration}</button>
          ) : (
            <p>No owner location</p>
          )}
          {group.diagnostics
            .slice()
            .sort(
              (left, right) =>
                (left.location?.endOffset ?? 0) -
                (left.location?.startOffset ?? 0) -
                ((right.location?.endOffset ?? 0) - (right.location?.startOffset ?? 0)),
            )
            .map((diagnostic, index) => {
              const explanation = explainDiagnostic(diagnostic, 'en')
              const location = diagnostic.location
              const path = Object.keys(state.documents).find(
                (document) => location?.document === document || location?.document.endsWith(`/${document}`),
              )
              const slice =
                !diagnosticsAreStale(state) && path && location?.startOffset !== undefined
                  ? state.documents[path].slice(location.startOffset, location.endOffset)
                  : undefined
              return (
                <div key={`${diagnostic.code}-${index}`}>
                  <strong>
                    {diagnostic.category === 'business'
                      ? 'Finding ✗'
                      : diagnostic.category === 'evaluation'
                        ? 'Runtime failure'
                        : 'Error !'}
                  </strong>{' '}
                  <code>{diagnostic.code}</code>
                  <p>{explanation.summary}</p>
                  <p>{explanation.details}</p>
                  {slice && <code>Source range: {slice}</code>}
                </div>
              )
            })}
        </section>
      ))}
    </>
  )
}

function changedLines(before: string, after: string) {
  const left = before.split('\n')
  const right = after.split('\n')
  let start = 0
  while (start < left.length && start < right.length && left[start] === right[start]) start++
  let endLeft = left.length
  let endRight = right.length
  while (endLeft > start && endRight > start && left[endLeft - 1] === right[endRight - 1]) {
    endLeft--
    endRight--
  }
  return { start, removed: left.slice(start, endLeft), added: right.slice(start, endRight) }
}

export function ChangesPanel({ state, onRevert }: { state: AuthoringState; onRevert: () => void }) {
  const changed = Object.keys(state.documents).filter((path) => state.documents[path] !== state.baseDocuments[path])
  return (
    <>
      {!changed.length && <p>No source changes.</p>}
      {changed.map((document) => {
        const diff = changedLines(state.baseDocuments[document], state.documents[document])
        return (
          <section key={document}>
            <h3>{document}</h3>
            <p className="author-reference">Unrelated bytes unchanged · simulated source patches</p>
            <p>
              {state.history.past
                .filter((transaction) => transaction.patches.some((patch) => patch.document === document))
                .map((transaction) => transaction.label)
                .join(' · ')}
            </p>
            <code>@@ line {diff.start + 1} @@</code>
            {diff.removed.map((line, index) => (
              <code className="author-diff-line author-diff-del" key={`removed-${index}`}>
                − {line}
              </code>
            ))}
            {diff.added.map((line, index) => (
              <code className="author-diff-line author-diff-add" key={`added-${index}`}>
                + {line}
              </code>
            ))}
          </section>
        )
      })}
      {!!state.history.past.length && (
        <button disabled={!!state.history.past.at(-1)?.blockedReason || !!state.saving} onClick={onRevert}>
          Revert latest source transaction
        </button>
      )}
      {state.conflict && (
        <section>
          <h3>External changes (simulated)</h3>
          {state.conflict.changed.map((path) => (
            <div key={path}>
              <p>{path}</p>
              <pre>{state.conflict!.documents[path]}</pre>
            </div>
          ))}
        </section>
      )}
    </>
  )
}

export function BuildPanel({ report }: { report?: ExportPreview }) {
  return (
    <>
      <h3>Report of the existing ExcelExport path for the saved example case (recorded)</h3>
      <p>Mantra runtime template: not built. Derived Excel template: not built.</p>
      {report && <pre>{JSON.stringify(report.report, null, 2)}</pre>}
      <p>Template Engine import and recalculation: not verified.</p>
      <p>Microsoft Excel recalculation: not verified.</p>
      <div className="author-toolbar">
        <button disabled>Build</button>
        <button disabled>Publish</button>
        <button disabled>Open in Template Engine</button>
      </div>
      <p>Build records and publishing require contracts G-A10 and G-A11.</p>
    </>
  )
}

export function originalDocuments(base: Record<string, { text: string }>): DocumentTexts {
  return Object.fromEntries(Object.entries(base).map(([path, document]) => [path, document.text]))
}
