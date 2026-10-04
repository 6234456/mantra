import type { CaseGraph } from '../types'
import { casePath, provenancePath } from '../address'
import { language } from '../i18n'
import './LinkedSourceEvidence.css'

const lang = language()

export function CaseChainEvidence({
  graph,
  caseId,
  navigate,
}: {
  graph?: CaseGraph | null
  caseId: string
  navigate: (path: string) => void
}) {
  if (!graph || (!graph.edges.length && graph.cases.length < 2)) return null
  function follow(event: React.MouseEvent<HTMLAnchorElement>, path: string) {
    if (event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
      event.preventDefault()
      navigate(path)
    }
  }
  return (
    <section className="sheet case-chain-evidence" aria-label={lang === 'de' ? 'Fallkette' : 'Case chain'}>
      <div className="section-heading">
        <h2>{lang === 'de' ? 'Fallkette' : 'Case chain'}</h2>
        <span className="muted">
          {graph.cases.length} {lang === 'de' ? 'Fälle' : 'cases'}
        </span>
      </div>
      <ol>
        {graph.cases.map((entry) => {
          const path = casePath(entry.case) + '/overview'
          return (
            <li key={entry.case}>
              <a href={path} onClick={(event) => follow(event, path)}>
                {entry.caseId}
              </a>
              <span>
                {entry.schema.id}
                {entry.schema.version ? ' · v' + entry.schema.version : ''}
              </span>
              <strong
                className={entry.succeeded && entry.validationPassed ? 'case-chain-ready' : 'case-chain-findings'}
              >
                {!entry.succeeded
                  ? lang === 'de'
                    ? 'Berechnung fehlgeschlagen'
                    : 'Calculation failed'
                  : !entry.validationPassed
                    ? lang === 'de'
                      ? 'Fachliche Befunde'
                      : 'Business findings'
                    : lang === 'de'
                      ? 'Berechnet'
                      : 'Calculated'}
              </strong>
              <details>
                <summary>{lang === 'de' ? 'Revision' : 'Revision'}</summary>
                <code>{entry.revision}</code>
              </details>
            </li>
          )
        })}
      </ol>
      <div className="case-chain-mappings">
        {graph.edges.map((edge, index) => {
          const path = provenancePath(caseId, edge.from, edge.revision)
          const from = edge.from.node + (edge.from.coord?.length ? ' @ ' + edge.from.coord.join(' / ') : '')
          const to = edge.to.node + (edge.to.coord?.length ? ' @ ' + edge.to.coord.join(' / ') : '')
          return (
            <p key={index}>
              <a href={path} onClick={(event) => follow(event, path)}>
                {from}
              </a>
              <span> → {to}</span>
            </p>
          )
        })}
      </div>
    </section>
  )
}
