import type { Aggregate, Value } from '../types'
import { language, t } from '../i18n'
import { RatioAggregateEvidence } from './RatioAggregateEvidence'

const lang = language()
function raw(value: Value): string {
  if (value == null) return 'nil'
  if (typeof value === 'object' && 'n' in value) return value.n
  return JSON.stringify(value)
}

/** Displays exact evidence supplied by the engine; neither boundary selection nor sums run here. */
export function AggregateEvidence({ aggregate }: { aggregate: Aggregate }) {
  if (aggregate.kind === 'ratio') return <RatioAggregateEvidence aggregate={aggregate} />
  const boundary = aggregate.kind === 'boundary'
  const members = boundary
    ? [
        ...aggregate.selected,
        ...aggregate.members.filter(
          (member) =>
            !aggregate.selected.some((selected) => JSON.stringify(selected.coord) === JSON.stringify(member.coord)),
        ),
      ]
    : aggregate.members
  const title = boundary
    ? lang === 'de'
      ? 'Bestandsaggregation'
      : 'Stock aggregation'
    : lang === 'de'
      ? 'Summenaggregation'
      : 'Sum aggregation'
  return (
    <section className="ratio-evidence reduction-evidence" aria-label={title}>
      <h4>{title}</h4>
      {boundary && (
        <p>
          <code>{aggregate.dimension}</code> ·{' '}
          {aggregate.boundary === 'first'
            ? lang === 'de'
              ? 'Erste Periode'
              : 'First period'
            : lang === 'de'
              ? 'Letzte Periode'
              : 'Last period'}
        </p>
      )}
      <dl className="ratio-totals">
        <div>
          <dt>{lang === 'de' ? 'Ergebnis' : 'Result'}</dt>
          <dd title={aggregate.result?.n}>{aggregate.display.result}</dd>
        </div>
      </dl>
      {aggregate.undefinedReason && <p className="ratio-undefined">{aggregate.undefinedReason}</p>}
      {!!Object.keys(aggregate.fixed).length && (
        <p className="muted">
          {Object.entries(aggregate.fixed)
            .map(([dim, key]) => `${dim}=${key}`)
            .join(', ')}
        </p>
      )}
      <details>
        <summary>
          {lang === 'de' ? 'Beiträge' : 'Contributions'} · {aggregate.activeMemberCount}/{aggregate.memberCount}
        </summary>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>{lang === 'de' ? 'Koordinate' : 'Coordinate'}</th>
                <th>{lang === 'de' ? 'Wert' : 'Value'}</th>
                <th>{lang === 'de' ? 'Auswahl' : 'Selected'}</th>
              </tr>
            </thead>
            <tbody>
              {members.map((member, index) => (
                <tr key={index}>
                  <td>
                    <code>{member.coord.map((key, i) => `${aggregate.dimensions[i]}=${key}`).join(', ')}</code>
                  </td>
                  <td>
                    <code>{raw(member.value)}</code>
                  </td>
                  <td>{member.selected && member.active ? '✓' : '—'}</td>
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
