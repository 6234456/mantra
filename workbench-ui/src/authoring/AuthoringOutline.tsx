import type { Paper, Run, Structure } from '../types'
import type { SourceOwner } from './service'

export function AuthoringOutline({
  owners,
  selectedOwner,
  structure,
  paper,
  run,
  panelId,
  invalidHandles,
  onSelect,
}: {
  owners: SourceOwner[]
  selectedOwner?: SourceOwner
  structure?: Structure
  paper?: Paper
  run?: Run
  panelId: string
  invalidHandles: Set<string>
  onSelect: (owner: SourceOwner) => void
}) {
  const visible = owners.filter((owner) =>
    ['section-title', 'label', 'note', 'title', 'declaration'].includes(owner.property),
  )
  const grouped = new Map<string, SourceOwner[]>()
  for (const owner of visible) {
    const sectionPanel =
      owner.property === 'section-title' ? structure?.panels.find((panel) => panel.id === owner.nodeId)?.id : undefined
    const key = owner.panelId ?? sectionPanel ?? (owner.kind === 'defn' ? 'Shared declarations' : 'Template resources')
    grouped.set(key, [...(grouped.get(key) ?? []), owner])
  }
  function details(owner: SourceOwner) {
    const table = paper?.tables.find((item) => item.id === owner.panelId)
    const row = table?.rows.find((item) => item.node === owner.nodeId)
    const value = owner.nodeId ? run?.values[owner.nodeId]?.[''] : undefined
    if (value?.active === false) return ' · inactive by condition'
    if (row?.flags?.includes('explains-zero')) return ' · explains zero'
    if (owner.nodeId && table && !row) return ' · hidden by layout'
    return ''
  }
  return (
    <nav className="author-card author-outline" data-author-region="outline" aria-label="Outline">
      <h2>Outline</h2>
      <p className="author-reference">Owner handles and outline are simulated.</p>
      {[...grouped].map(([key, group]) => (
        <section key={key} aria-label={structure?.panels.find((panel) => panel.id === key)?.title ?? key}>
          <h3>{structure?.panels.find((panel) => panel.id === key)?.title ?? key}</h3>
          {group.map((owner) => (
            <button
              key={owner.handle}
              aria-current={selectedOwner?.handle === owner.handle}
              onClick={() => onSelect(owner)}
            >
              {owner.label ?? owner.declaration} {owner.editable ? 'ƒ' : '🔒'}
              {details(owner)}
              {owner.panelId && owner.panelId !== panelId ? ' · other panel' : ''}
              {owners.some(
                (item) =>
                  item.document === owner.document &&
                  item.declaration === owner.declaration &&
                  invalidHandles.has(item.handle),
              )
                ? ' !'
                : ''}
            </button>
          ))}
        </section>
      ))}
      <button
        onClick={() => {
          const owner = owners.find((item) => item.kind === 'layout' && item.property === 'title')
          if (owner) onSelect(owner)
        }}
      >
        Whole Paper title
      </button>
    </nav>
  )
}
