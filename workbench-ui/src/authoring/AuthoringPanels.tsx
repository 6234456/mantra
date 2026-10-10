import { useEffect, useId, useRef, useState } from 'react'
import { explainDiagnostic } from '../diagnosticMessages'
import { ExplainDetails } from '../ui/ExplainDetails'
import { ValidationEvidence } from '../ui/ValidationEvidence'
import type { Diagnostic, Explain, ExportPreview, RunValue } from '../types'
import { AuthoringCodeEditor } from './AuthoringCodeEditor'
import type { AuthoringState, SourceHistory } from './model'
import { diagnosticsAreStale } from './model'
import type { DocumentTexts, SourceOwner, SourcePatch } from './service'
import { ownerForDiagnostic } from './simulated/sourceOwners'
import { sourceDiffHunks } from './sourceDiff'
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
  const previousFocus = useRef(document.activeElement as HTMLElement | null)
  const initialFocus = useRef<HTMLElement | null>(null)
  const close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    const previous = previousFocus.current
    // A child editor may already have focused itself during commit; keep that intentional focus.
    if (element.current?.contains(document.activeElement)) {
      initialFocus.current = document.activeElement as HTMLElement
    } else {
      // StrictMode replays setup after cleanup; retain the child target instead of the close button.
      const target = element.current?.contains(initialFocus.current)
        ? initialFocus.current
        : element.current?.querySelector<HTMLButtonElement>('button')
      target?.focus()
      initialFocus.current = target ?? null
    }
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
  const ownerHighlights =
    selectedOwner?.document === selectedDocument && pending === source
      ? [
          { from: selectedOwner.range.start, to: selectedOwner.range.end, primary: true },
          ...owners
            .filter(
              (owner) =>
                owner.document === selectedDocument &&
                owner.handle !== selectedOwner.handle &&
                owner.property !== 'declaration' &&
                owner.declarationRange.start === selectedOwner.declarationRange.start &&
                owner.declarationRange.end === selectedOwner.declarationRange.end,
            )
            .map((owner) => ({ from: owner.range.start, to: owner.range.end, primary: false })),
        ]
      : []
  return (
    <section className="author-card author-source-pane" aria-label="Source view">
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
        ownerHighlights={ownerHighlights}
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

export function SourceDocumentDiff({
  document,
  before,
  after,
  history,
  fallbackOperation,
}: {
  document: string
  before: string
  after: string
  history?: SourceHistory
  fallbackOperation?: string
}) {
  const hunks = sourceDiffHunks(document, before, after, history, fallbackOperation)
  return (
    <section className="author-document-diff" aria-label={`Source diff ${document}`} data-source-diff={document}>
      <h3>{document}</h3>
      {!hunks.length && <p>No source changes.</p>}
      {hunks.map((hunk, index) => (
        <div className="author-diff-hunk" data-source-hunk={index + 1} key={`${hunk.beforeStart}-${hunk.afterStart}`}>
          <p className="author-reference">{hunk.operations.join(' · ')}</p>
          <code>
            @@ -{hunk.beforeStart + 1},{hunk.removed.length} +{hunk.afterStart + 1},{hunk.added.length} @@
          </code>
          {(['removed', 'added'] as const).flatMap((kind) =>
            hunk[kind].map((line, lineIndex) => (
              <code
                className={`author-diff-line author-diff-${kind === 'removed' ? 'del' : 'add'}`}
                key={`${kind}-${lineIndex}`}
                data-source-change={kind}
              >
                {kind === 'removed' ? '−' : '+'} {line.replace(/\r?\n$/, '')}
                {line.endsWith('\r\n') && <span className="author-reference"> · CRLF</span>}
                {!line.endsWith('\n') && <span className="author-reference"> · No newline at end of file</span>}
              </code>
            )),
          )}
        </div>
      ))}
    </section>
  )
}

export function ChangesPanel({ state, onRevert }: { state: AuthoringState; onRevert: () => void }) {
  const changed = Object.keys(state.documents).filter((path) => state.documents[path] !== state.baseDocuments[path])
  return (
    <>
      {!changed.length && <p>No source changes.</p>}
      {!!changed.length && <p className="author-reference">Unrelated bytes unchanged · simulated source patches</p>}
      {changed.map((document) => (
        <SourceDocumentDiff
          key={document}
          document={document}
          before={state.baseDocuments[document]}
          after={state.documents[document]}
          history={state.history}
        />
      ))}
      {!!state.history.past.length && (
        <button disabled={!!state.history.past.at(-1)?.blockedReason || !!state.saving} onClick={onRevert}>
          Revert latest source transaction
        </button>
      )}
      {state.conflict && (
        <section>
          <h3>External changes (simulated)</h3>
          {state.conflict.changed.map((path) => (
            <SourceDocumentDiff
              key={path}
              document={path}
              before={state.baseDocuments[path] ?? ''}
              after={state.conflict!.documents[path] ?? ''}
              fallbackOperation="External source edit (simulated)"
            />
          ))}
        </section>
      )}
    </>
  )
}

export function BuildPanel({ report }: { report?: ExportPreview }) {
  const reasons = useId()
  return (
    <>
      <h3>ExcelExport report for the recorded base state</h3>
      <p>This recording describes the original base state, not the current simulated saved source revision.</p>
      <p>Mantra runtime template: not built. Derived Excel template: not built.</p>
      {report && <pre>{JSON.stringify(report.report, null, 2)}</pre>}
      <p>Template Engine import and recalculation: not verified.</p>
      <p>Microsoft Excel recalculation: not verified.</p>
      <div className="author-toolbar">
        <button disabled aria-describedby={`${reasons}-build`}>
          Build
        </button>
        <button disabled aria-describedby={`${reasons}-publish`}>
          Publish
        </button>
        <button disabled aria-describedby={`${reasons}-open`}>
          Open in Template Engine
        </button>
      </div>
      <p id={`${reasons}-build`}>Build is unavailable: build records require contract G-A10.</p>
      <p id={`${reasons}-publish`}>Publish is unavailable: publishing requires contract G-A11 and a built template.</p>
      <p id={`${reasons}-open`}>
        Open in Template Engine is unavailable: template import and recalculation are not verified.
      </p>
    </>
  )
}

export function originalDocuments(base: Record<string, { text: string }>): DocumentTexts {
  return Object.fromEntries(Object.entries(base).map(([path, document]) => [path, document.text]))
}
