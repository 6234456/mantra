import type { LinkedValueSource } from '../types'
import { casePath, provenancePath } from '../address'
import { language } from '../i18n'
import './LinkedSourceEvidence.css'

const lang = language()

function plainClick(event: React.MouseEvent<HTMLAnchorElement>, path: string, navigate?: (path: string) => void) {
  if (navigate && event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
    event.preventDefault()
    navigate(path)
  }
}

/** The case address and revision are evidence supplied by the host, not inferred from labels. */
export function LinkedSourceEvidence({
  source,
  rootCaseId,
  navigate,
}: {
  source: LinkedValueSource
  rootCaseId: string
  navigate?: (path: string) => void
}) {
  const address = { ...source.address, case: source.case }
  const explanation = provenancePath(rootCaseId, address, source.revision)
  const overview = casePath(source.case) + '/overview'
  return (
    <section className="linked-source-evidence" aria-label={lang === 'de' ? 'Verknüpfte Quelle' : 'Linked source'}>
      <span className="eyebrow">
        {lang === 'de' ? 'Verknüpfter Wert · schreibgeschützt' : 'Linked value · read only'}
      </span>
      <strong>{source.caseId}</strong>
      <span>
        {source.schema.id}
        {source.schema.version ? ' · v' + source.schema.version : ''}
      </span>
      <small>{source.path}</small>
      <details>
        <summary>{lang === 'de' ? 'Quellrevision' : 'Source revision'}</summary>
        <code>{source.revision}</code>
      </details>
      <div className="linked-source-actions">
        <a href={explanation} onClick={(event) => plainClick(event, explanation, navigate)}>
          {lang === 'de' ? 'Quellwert erklären' : 'Explain source value'} ↗
        </a>
        <a href={overview} onClick={(event) => plainClick(event, overview, navigate)}>
          {lang === 'de' ? 'Quellfall öffnen' : 'Open source case'} ↗
        </a>
      </div>
    </section>
  )
}
