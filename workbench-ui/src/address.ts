import type { Address } from './types'

/** Encode each address component, including punctuation reserved by the URL grammar. */
const encodePart = (part: string) =>
  encodeURIComponent(part).replace(/[.'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)

export function addressToPath(address: Address): string {
  const members = address.coord?.length ? `@${address.coord.map(encodePart).join('/')}` : ''
  const cell = address.cell ? `#${encodePart(address.cell.row)}.${encodePart(address.cell.column)}` : ''
  return `${encodePart(address.node)}${members}${cell}`
}

export function addressFromPath(path: string, sourceCase: string | null = null): Address | null {
  try {
    const [beforeCell, cellText] = path.split('#', 2)
    const [nodeText, ...coordText] = beforeCell.split('@')
    if (!nodeText) return null
    const result: Address = { case: sourceCase, node: decodeURIComponent(nodeText) }
    if (coordText.length) result.coord = coordText.join('@').split('/').map(decodeURIComponent)
    if (cellText) {
      const separator = cellText.indexOf('.')
      if (separator < 0) return null
      result.cell = {
        row: decodeURIComponent(cellText.slice(0, separator)),
        column: decodeURIComponent(cellText.slice(separator + 1)),
      }
    }
    return result
  } catch {
    return null
  }
}

export const addressKey = (address: Address) => JSON.stringify([address.case ?? null, addressToPath(address)])
export const casePath = (caseId: string) => `/cases/${encodePart(caseId)}`

/** Keep the resource case fixed while identifying the source snapshot explicitly. */
export function provenancePath(rootCase: string, address: Address, expectedRevision?: string): string {
  const query = new URLSearchParams()
  if (address.case) query.set('case', address.case)
  if (expectedRevision) query.set('expectedRevision', expectedRevision)
  const suffix = query.size ? '?' + query.toString() : ''
  return casePath(rootCase) + '/provenance/' + encodeURIComponent(addressToPath(address)) + suffix
}
