import type { Address, AuditEntry, Paper, PaperRow, Run } from './types'
import { addressKey } from './address'

/** A missing scalar or member stays missing; no other member may stand in for it. */
export function nodeValue(run: Run, node?: string | null, coord = ''): string | undefined {
  if (node?.startsWith('aggregate.'))
    return coord === '' ? run.aggregates?.[node.slice('aggregate.'.length)]?.display.result : undefined
  return node ? run.values[node]?.[coord]?.display : undefined
}

/** Panel summaries explicitly show the engine's whole-domain reduction when the result has dimensions. */
export function panelValue(run: Run, node?: string | null): string | undefined {
  return nodeValue(run, node) ?? (node ? nodeValue(run, `aggregate.${node}`) : undefined)
}

/** WorkingPaperBuilder appends the member coordinates to each row audit anchor. */
export function auditForCell(
  paper: Paper | undefined,
  rows: PaperRow[] | undefined,
  address: Address | undefined,
): AuditEntry | undefined {
  if (!paper || !address) return undefined
  const addressed = paper.audit.find((entry) => entry.address && addressKey(entry.address) === addressKey(address))
  if (addressed) return addressed
  if (!rows) return undefined
  const row = rows.find((item) =>
    item.cells.some((cell) => cell.address && addressKey(cell.address) === addressKey(address)),
  )
  if (!row?.anchor) return undefined
  const anchor = address.coord?.length ? `${row.anchor}-${address.coord.join('-')}` : row.anchor
  return paper.audit.find((entry) => entry.anchor === anchor)
}
