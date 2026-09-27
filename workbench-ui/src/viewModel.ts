import type { Address, AuditEntry, Paper, PaperRow, Run } from './types'
import { addressKey } from './address'

/** A missing scalar or member stays missing; no other member may stand in for it. */
export function nodeValue(run: Run, node?: string | null, coord = ''): string | undefined {
  return node ? run.values[node]?.[coord]?.display : undefined
}

/** WorkingPaperBuilder appends the member coordinates to each row audit anchor. */
export function auditForCell(paper: Paper | undefined, rows: PaperRow[] | undefined, address: Address | undefined): AuditEntry | undefined {
  if (!paper || !rows || !address) return undefined
  const row = rows.find(item => item.cells.some(cell => cell.address && addressKey(cell.address) === addressKey(address)))
  if (!row?.anchor) return undefined
  const anchor = address.coord?.length ? `${row.anchor}-${address.coord.join('-')}` : row.anchor
  return paper.audit.find(entry => entry.anchor === anchor)
}
