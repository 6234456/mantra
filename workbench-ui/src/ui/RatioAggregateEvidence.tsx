import type { RatioAggregate } from '../types'
import { language, t } from '../i18n'
import './RatioAggregateEvidence.css'

const lang = language()
const reasons: Record<string, string> =
  lang === 'de'
    ? {
        'no-active-members': 'Keine aktiven Mitglieder.',
        'zero-denominator': 'Die Summe des Nenners ist null; die Quote ist nicht definiert.',
        'rounding-required': 'Der Quotient benötigt eine ausdrücklich festgelegte Rundung.',
      }
    : {
        'no-active-members': 'There are no active members.',
        'zero-denominator': 'The denominator total is zero; the ratio is undefined.',
        'rounding-required': 'The quotient requires an explicit rounding rule.',
      }

/** Display the engine's exact aggregation evidence without recalculating in the browser. */
export function RatioAggregateEvidence({ aggregate }: { aggregate: RatioAggregate }) {
  return (
    <section className="ratio-evidence" aria-label={lang === 'de' ? 'Gewichtete Quote' : 'Weighted ratio'}>
      <h3>{lang === 'de' ? 'Gewichtete Quote' : 'Weighted ratio'}</h3>
      <p className="ratio-expression">
        <code>{aggregate.numeratorId}</code> / <code>{aggregate.denominatorId}</code>
      </p>
      <dl className="ratio-totals">
        <div>
          <dt>{lang === 'de' ? 'Summe Zähler' : 'Numerator total'}</dt>
          <dd title={aggregate.numeratorTotal.n}>{aggregate.display.numeratorTotal}</dd>
        </div>
        <div>
          <dt>{lang === 'de' ? 'Summe Nenner' : 'Denominator total'}</dt>
          <dd title={aggregate.denominatorTotal.n}>{aggregate.display.denominatorTotal}</dd>
        </div>
        <div>
          <dt>{lang === 'de' ? 'Ergebnis' : 'Result'}</dt>
          <dd title={aggregate.result?.n}>{aggregate.display.result}</dd>
        </div>
      </dl>
      <p className="ratio-rounding">
        {lang === 'de' ? 'Rundung' : 'Rounding'}:{' '}
        {aggregate.rounding
          ? `${aggregate.rounding.scale} · ${aggregate.rounding.mode}`
          : lang === 'de'
            ? 'Exakte Division'
            : 'Exact division'}
      </p>
      {aggregate.undefinedReason && (
        <p className="ratio-undefined">{reasons[aggregate.undefinedReason] ?? aggregate.undefinedReason}</p>
      )}
      {!!Object.keys(aggregate.fixed).length && (
        <p className="ratio-fixed">
          {Object.entries(aggregate.fixed)
            .map(([dimension, member]) => `${dimension}=${member}`)
            .join(' · ')}
        </p>
      )}
      <details>
        <summary>
          {lang === 'de' ? 'Mitglieder' : 'Members'} · {aggregate.activeMemberCount}/{aggregate.memberCount}
        </summary>
        <div className="ratio-members-scroll">
          <table className="ratio-members">
            <thead>
              <tr>
                <th>{lang === 'de' ? 'Koordinate' : 'Coordinate'}</th>
                <th>{lang === 'de' ? 'Zähler' : 'Numerator'}</th>
                <th>{lang === 'de' ? 'Nenner' : 'Denominator'}</th>
                <th>{lang === 'de' ? 'Einbezogen' : 'Included'}</th>
              </tr>
            </thead>
            <tbody>
              {aggregate.members.map((member, index) => (
                <tr key={index} className={member.active ? 'active' : 'inactive'}>
                  <th scope="row">
                    {member.coord
                      .map((value, dimension) => `${aggregate.dimensions[dimension] ?? dimension}=${value}`)
                      .join(' · ') || '—'}
                  </th>
                  <td>
                    <code>{member.numerator.n}</code>
                  </td>
                  <td>
                    <code>{member.denominator.n}</code>
                  </td>
                  <td
                    aria-label={
                      member.active
                        ? lang === 'de'
                          ? 'Einbezogen'
                          : 'Included'
                        : lang === 'de'
                          ? 'Ausgeschlossen'
                          : 'Excluded'
                    }
                  >
                    {member.active ? '✓' : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {aggregate.truncated && <p className="muted">{t('traceTruncated', lang)}</p>}
      </details>
    </section>
  )
}
