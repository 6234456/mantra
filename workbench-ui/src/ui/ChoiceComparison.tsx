import type { Explain, Paper, Panel, Run, Structure } from '../types'
import { language, t } from '../i18n'
import { nodeValue } from '../viewModel'

const lang = language()

export function ChoiceComparison({
  explain,
  table,
  choiceNode,
  panel,
  structure,
  run,
}: {
  explain?: Explain
  table?: Paper['tables'][number]
  choiceNode?: string
  panel: Panel
  structure: Structure
  run: Run
}) {
  const options = explain?.options.length
    ? explain.options
    : (table?.rows
        .filter((row) => row.kind.toLowerCase() === 'option' && row.node === choiceNode)
        .map((row) => {
          const cells = row.cells.map((cell) => cell.text.trim()).filter(Boolean)
          const labelIndex = table.columns.findIndex((column) => column.id === 'label')
          return {
            key: row.optionKey,
            label: labelIndex >= 0 ? row.cells[labelIndex]?.text : cells[0],
            display: row.cells.find((cell) => cell.address && cell.text.trim())?.text ?? cells.at(-1),
            selected: row.flags?.some((flag) => flag.toLowerCase() === 'selected'),
          }
        }) ?? [])
  return (
    <section className="sheet choice">
      <h2>{t('comparison', lang)}</h2>
      <div className="choice-options">
        {options.map((option, i) => (
          <div className={`choice-option ${option.selected ? 'chosen' : ''}`} key={option.key ?? i}>
            <span>
              {option.selected ? '✓ ' : ''}
              {option.label ?? option.key}
            </span>
            <strong>{option.display}</strong>
            {'differenceDisplay' in option && option.differenceDisplay && <small>{option.differenceDisplay}</small>}
          </div>
        ))}
      </div>
      <h3>{t('effect', lang)}</h3>
      <div className="choice-effects">
        {panel.entries.map((entry) => (
          <span key={`${entry.step}-${entry.via}`}>
            {t('step', lang)} {entry.step} · {entry.viaLabel}{' '}
            <b>{nodeValue(run, entry.via) ?? structure.mainline.find((step) => step.step === entry.step)?.title}</b>
          </span>
        ))}
      </div>
    </section>
  )
}
