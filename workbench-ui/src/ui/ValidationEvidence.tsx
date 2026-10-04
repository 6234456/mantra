import type { RunValue, Value } from '../types'
import { language } from '../i18n'
import './ValidationEvidence.css'

const lang = language()
export type Validation = NonNullable<RunValue['validation']>

function decimalText(value: Value): string {
  return value && typeof value === 'object' && !Array.isArray(value) && 'n' in value ? value.n : '—'
}

/** The outcome and reconciliation amounts are supplied by the engine, including its tolerance. */
export function ValidationEvidence({ validation }: { validation: Validation }) {
  const result =
    validation.passed == null
      ? lang === 'de'
        ? 'Nicht geprüft'
        : 'Not checked'
      : validation.passed
        ? lang === 'de'
          ? 'Bestanden'
          : 'Passed'
        : lang === 'de'
          ? 'Nicht bestanden'
          : 'Failed'
  return (
    <section className="validation-evidence" aria-label={lang === 'de' ? 'Fachliche Prüfung' : 'Business check'}>
      <h3>{lang === 'de' ? 'Fachliche Prüfung' : 'Business check'}</h3>
      <p className={`validation-outcome ${validation.passed === false ? 'failed' : ''}`}>{result}</p>
      <p className="muted">
        {lang === 'de' ? 'Schweregrad' : 'Severity'}: {validation.severity}
      </p>
      {validation.reconciliation && (
        <dl>
          <div>
            <dt>{lang === 'de' ? 'Linke Seite' : 'Left side'}</dt>
            <dd>{decimalText(validation.reconciliation.left)}</dd>
          </div>
          <div>
            <dt>{lang === 'de' ? 'Rechte Seite' : 'Right side'}</dt>
            <dd>{decimalText(validation.reconciliation.right)}</dd>
          </div>
          <div>
            <dt>{lang === 'de' ? 'Differenz' : 'Difference'}</dt>
            <dd>{decimalText(validation.reconciliation.difference)}</dd>
          </div>
          <div>
            <dt>{lang === 'de' ? 'Toleranz' : 'Tolerance'}</dt>
            <dd>{decimalText(validation.reconciliation.tolerance)}</dd>
          </div>
        </dl>
      )}
    </section>
  )
}
