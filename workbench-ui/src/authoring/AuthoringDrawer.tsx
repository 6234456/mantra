import type { ExportPreview } from '../types'
import { BuildPanel, ChangesPanel, ProblemsPanel } from './AuthoringPanels'
import type { AuthoringState } from './model'
import type { SourceOwner } from './service'

export function AuthoringDrawer({
  tab,
  onTab,
  state,
  owners,
  onSelect,
  onRevert,
  report,
  problemCount,
}: {
  tab: string
  onTab: (tab: string) => void
  state: AuthoringState
  owners: SourceOwner[]
  onSelect: (owner: SourceOwner) => void
  onRevert: () => void
  report?: ExportPreview
  problemCount: number
}) {
  return (
    <section className="author-card author-drawer" data-author-region="drawer" aria-label="Authoring drawer">
      <div className="author-tabs" role="tablist" aria-label="Drawer tabs">
        {['Problems', 'Source changes', 'Build'].map((name) => (
          <button key={name} role="tab" aria-selected={tab === name} onClick={() => onTab(name)}>
            {name}
            {name === 'Problems' && problemCount ? ` (${problemCount})` : ''}
          </button>
        ))}
      </div>
      {tab === 'Problems' && <ProblemsPanel state={state} owners={owners} onSelect={onSelect} />}
      {tab === 'Source changes' && <ChangesPanel state={state} onRevert={onRevert} />}
      {tab === 'Build' && <BuildPanel report={report} />}
    </section>
  )
}
