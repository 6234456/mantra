import type { Explain } from '../types'
import { language, t } from '../i18n'
import { AggregateEvidence } from './AggregateEvidence'

const lang = language()

export function ExplainDetails({ explain }: { explain: Explain }) {
  return (
    <>
      {explain.aggregate && <AggregateEvidence aggregate={explain.aggregate} />}
      {explain.steps.map((step, index) => (
        <div className="calculation-step" key={index}>
          <code>{step.text}</code>
          <b>{step.display}</b>
        </div>
      ))}
      {!!explain.branches.length && (
        <details className="explain-branches">
          <summary>{lang === 'de' ? 'Zweige' : 'Branches'}</summary>
          {explain.branches.map((branch, index) => (
            <div className="calculation-step" key={index}>
              <code>{branch.text}</code>
              <b>{branch.selected ? '✓' : '—'}</b>
            </div>
          ))}
        </details>
      )}
      {explain.parts.map((part, index) => (
        <div className="explain-part" key={index}>
          <div className="reference-item">
            <span>
              {part.sign < 0 ? '− ' : part.sign > 0 ? '+ ' : ''}
              {part.label}
            </span>
            <b>{part.display}</b>
          </div>
          {part.aggregate && <AggregateEvidence aggregate={part.aggregate} />}
        </div>
      ))}
      {explain.truncated && <p className="muted">{t('traceTruncated', lang)}</p>}
    </>
  )
}
