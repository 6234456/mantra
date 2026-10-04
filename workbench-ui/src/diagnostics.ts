import type { Address, Diagnostic, InputField, Structure } from './types'
import { addressToPath, casePath } from './address'

const fieldId = (field: string | InputField) => (typeof field === 'string' ? field : field.id)
const sameCoord = (left: Address, right: Address) =>
  JSON.stringify(left.coord ?? []) === JSON.stringify(right.coord ?? [])

/** Diagnostic row positions stay separate from the stable keys used to edit table cells. */
export function diagnosticsForInput(diagnostics: Diagnostic[], address: Address, rowIndex?: number): Diagnostic[] {
  return diagnostics.filter((finding) => {
    const target = finding.address
    if (!target || target.node !== address.node || !sameCoord(target, address)) return false
    if (!address.cell) return !target.cell && finding.rowIndex == null
    if (finding.rowIndex != null && finding.column != null)
      return finding.rowIndex === rowIndex && finding.column === address.cell.column
    return (
      target.cell?.column === address.cell.column &&
      (target.cell.row === address.cell.row || (rowIndex !== undefined && target.cell.row === String(rowIndex)))
    )
  })
}

/** Input findings open the editor; calculation findings open their owning paper panel. */
export function diagnosticPath(caseId: string, structure: Structure, finding: Diagnostic): string | undefined {
  const address = finding.address
  if (!address) return undefined
  const query = `?cell=${encodeURIComponent(addressToPath(address))}`
  if (structure.generalInputs.some((field) => fieldId(field) === address.node))
    return `${casePath(caseId)}/inputs/general${query}`
  const inputPanel = structure.panels.find((panel) => panel.fields.some((field) => fieldId(field) === address.node))
  if (inputPanel) return `${casePath(caseId)}/inputs/${encodeURIComponent(inputPanel.id)}${query}`
  const panel = structure.panels.find((item) => item.nodes.includes(address.node) || item.result === address.node)
  return panel ? `${casePath(caseId)}/panels/${encodeURIComponent(panel.id)}${query}` : undefined
}
