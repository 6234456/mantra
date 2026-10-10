import { useEffect, useRef, useState } from 'react'
import { WorkbenchReadError, type WorkbenchData } from '../data'
import type { Diagnostic, SourceContext } from '../types'
import { language } from '../i18n'
import './DiagnosticSourceContext.css'

const lang = language()
const copy = {
  title: lang === 'de' ? 'Quelltext' : 'Source text',
  show: lang === 'de' ? 'Quelltext anzeigen' : 'Show source text',
  retry: lang === 'de' ? 'Erneut versuchen' : 'Try again',
  loading: lang === 'de' ? 'Quelltext wird geladen …' : 'Loading source text …',
  noLocation:
    lang === 'de' ? 'Dieser Befund hat keine Dokumentposition.' : 'This finding has no document source location.',
  unavailable:
    lang === 'de' ? 'Quelltext ist in dieser Ansicht nicht verfügbar.' : 'Source text is unavailable in this view.',
  stale:
    lang === 'de'
      ? 'Die Dokumente haben sich geändert. Laden Sie die Befunde neu, um den aktuellen Quelltext zu öffnen.'
      : 'The documents have changed. Reload the findings to open the current source text.',
  failed: lang === 'de' ? 'Quelltext konnte nicht geladen werden.' : 'Could not load source text.',
  truncated:
    lang === 'de'
      ? 'Begrenzter Ausschnitt; weitere Zeilen oder Zeichen wurden ausgelassen.'
      : 'Bounded excerpt; further lines or characters have been omitted.',
  revision: lang === 'de' ? 'Quellrevision' : 'Source revision',
  line: lang === 'de' ? 'Zeile' : 'Line',
  column: lang === 'de' ? 'Spalte' : 'Column',
}

type LoadState =
  | { kind: 'idle' | 'loading' }
  | { kind: 'ready'; source: SourceContext }
  | { kind: 'error'; message: string; stale: boolean }

function sameLocation(source: SourceContext, finding: Diagnostic) {
  const expected = finding.location
  return (
    expected &&
    source.location.document === expected.document &&
    source.location.line === expected.line &&
    source.location.column === expected.column &&
    source.location.startOffset === expected.startOffset &&
    source.location.endOffset === expected.endOffset
  )
}

/** Only server-selected diagnostic evidence is rendered; source bytes are never interpreted as markup. */
export function DiagnosticSourceContext({
  caseId,
  diagnosticIndex,
  expectedRevision,
  finding,
  data,
}: {
  caseId: string
  diagnosticIndex: number
  expectedRevision: string
  finding: Diagnostic
  data: WorkbenchData
}) {
  const [state, setState] = useState<LoadState>({ kind: 'idle' })
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  const available = !!finding.location && !!data.sourceContext && !!expectedRevision

  async function load() {
    if (!available || !data.sourceContext) return
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setState({ kind: 'loading' })
    try {
      const result = await data.sourceContext(caseId, diagnosticIndex, expectedRevision, controller.signal)
      if (controller.signal.aborted) return
      if (
        result.revision !== expectedRevision ||
        (finding.caseRevision && result.data.revision !== finding.caseRevision) ||
        !sameLocation(result.data, finding)
      ) {
        throw new WorkbenchReadError(409, copy.stale)
      }
      setState({ kind: 'ready', source: result.data })
    } catch (error) {
      if (controller.signal.aborted) return
      const stale = error instanceof WorkbenchReadError && error.status === 409
      setState({
        kind: 'error',
        stale,
        message: stale ? copy.stale : `${copy.failed} ${error instanceof Error ? error.message : ''}`,
      })
    }
  }

  return (
    <section className="diagnostic-source" aria-label={copy.title}>
      <h3>{copy.title}</h3>
      {!available && <p className="muted">{finding.location ? copy.unavailable : copy.noLocation}</p>}
      {available && state.kind !== 'ready' && (
        <button
          type="button"
          onClick={load}
          disabled={state.kind === 'loading' || (state.kind === 'error' && state.stale)}
        >
          {state.kind === 'error' ? copy.retry : copy.show}
        </button>
      )}
      {state.kind === 'loading' && <p role="status">{copy.loading}</p>}
      {state.kind === 'error' && <p role="alert">{state.message}</p>}
      {state.kind === 'ready' && (
        <>
          <div className="diagnostic-source-scroll" tabIndex={0} aria-label={copy.title}>
            <ol className="diagnostic-source-lines" start={state.source.lines[0]?.number ?? 1}>
              {state.source.lines.map((line) => {
                const highlighted = line.highlightStart !== null && line.highlightEnd !== null
                return (
                  <li
                    key={line.number}
                    className={highlighted ? 'has-highlight' : ''}
                    value={line.number}
                    aria-label={`${copy.line} ${line.number}, ${copy.column} ${line.startColumn}`}
                  >
                    <code>
                      {line.startColumn > 1 && <span className="source-omission">…</span>}
                      {highlighted ? (
                        <>
                          {line.text.slice(0, line.highlightStart!)}
                          <mark>{line.text.slice(line.highlightStart!, line.highlightEnd!) || ' '}</mark>
                          {line.text.slice(line.highlightEnd!)}
                        </>
                      ) : (
                        line.text || ' '
                      )}
                    </code>
                  </li>
                )
              })}
            </ol>
          </div>
          {state.source.truncated && <p className="muted">{copy.truncated}</p>}
          <details className="diagnostic-source-revision">
            <summary>{copy.revision}</summary>
            <span>{state.source.case}</span>
            <code>{state.source.revision}</code>
          </details>
        </>
      )}
    </section>
  )
}
