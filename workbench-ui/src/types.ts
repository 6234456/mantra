/** The v1 JSON contract. Numbers in Value are always decimal strings. */
export type Value = null | boolean | string | { n: string } | { kw: string } | { date: string } | Value[] | { map: [Value, Value][] }
export interface Envelope<T> { contract: 'mantra.workbench/1'; revision: string; engine: { mantra: string; normein: string }; data: T }
export interface Address { node: string; coord?: string[]; cell?: { row: string; column: string } }
export interface Diagnostic { severity: 'error' | 'warning' | 'info'; code: string; message: string; address?: Address | null; location?: { document: string; line: number; column: number; startOffset?: number; endOffset?: number } | null }
export interface MainlineStep { step: number; panel: string; title: string; result: string }
export interface Entry { step: number; panel: string; via: string; viaLabel: string; path: string[] }
export interface Crumb { kind?: 'mainline'; panel?: string; label?: string; node?: string }
export interface Flow { fromPanel: string; fromNode: string; toPanel: string; toNode: string }
export interface Panel { id: string; title: string; role: 'mainline' | 'branch' | 'auxiliary'; step?: number; parent?: string | null; dims: string[]; result?: string | null; breadcrumb: Crumb[]; entries: Entry[]; fields: Array<string | { id: string }>; nodes: string[]; imports: Flow[]; exports: Flow[] }
export interface NodeMeta { id?: string; label: string; kind: string; type?: string; dims?: string[]; reference?: string; note?: string; formula?: string | { text: string }; [key: string]: unknown }
export interface Structure { schema: string; schemaVersion?: string; title: string; period?: string; mainline: MainlineStep[]; panels: Panel[]; generalInputs: Array<string | { id: string }>; params: unknown[]; nodes?: Record<string, NodeMeta>; headline?: string | null; groupTitles?: Record<string, string> }
export interface RunValue { value: Value; display: string; active: boolean; origin?: string; source?: string }
export interface Run { succeeded: boolean; members: Record<string, { key: string; label: string }[]>; values: Record<string, Record<string, RunValue>>; diagnostics: Diagnostic[] }
export interface Cell { text: string; address?: Address | null; editable?: boolean; style?: { weight?: string; tone?: string; fill?: string } }
export interface PaperColumn { id: string; header: string; align?: string; width?: number }
export interface PaperRow { kind: string; depth: number; cells: Cell[]; nodeId?: string | null; flags?: string[]; anchor?: string; optionKey?: string; sectionId?: string }
export interface PaperTable { id: string; ref: string; title: string; breadcrumb?: string; columns: PaperColumn[]; rows: PaperRow[] }
export interface AuditEntry { anchor: string; citation: string; label: string; member?: string | null; formula: string; working: string; result: string; reference?: string | null }
export interface Paper { title: string; subtitle?: string | null; headline?: { node: string; label: string; value: string } | null; inputGroups?: Array<{ key: string; title: string; inputs: string[] }>; header: Array<[string, string]>; overview: unknown[]; auxiliary: unknown[]; tables: PaperTable[]; audit: AuditEntry[]; legend: Array<[string, string]>; diagnostics?: Diagnostic[] }
export interface ExplainReference { address: Address; label: string; display: string; kind?: string; origin?: string }
export interface ExplainOption { label?: string; key?: string; display?: string; selected?: boolean; difference?: Value; differenceDisplay?: string }
export interface Explain { address: Address; label: string; kind: string; formula?: { text: string }; result: { value: Value; display: string }; status: string; steps: { text: string; display: string }[]; branches: unknown[]; references: ExplainReference[]; parts: unknown[]; options: ExplainOption[]; reference?: string; truncated?: boolean }
export interface CaseSummary { id: string; title: string; period?: string; schema?: string; revision?: string }
export interface Workspace { cases: CaseSummary[] }
