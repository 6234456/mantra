/** Wire primitives come from JSON Schema. UI projections below refine the schema's open objects. */
import type { Envelope as WireEnvelope, EnvelopeAddress, EnvelopeDiagnostic, Paper as WirePaper, Run as WireRun, Structure as WireStructure, Value as WireValue, Workspace as WireWorkspace } from './generated/contract'

export type Value = WireValue
export type Envelope<T> = Omit<WireEnvelope, 'data'> & { data: T }
export type Address = EnvelopeAddress
export type Diagnostic = EnvelopeDiagnostic
export interface MainlineStep { step: number; panel: string; title: string; result: string }
export interface Entry { step: number; panel: string; via: string; viaLabel: string; path: string[] }
export interface Crumb { kind?: 'mainline'; panel?: string; label?: string; node?: string }
export interface Flow { fromPanel: string; fromNode: string; toPanel: string; toNode: string }
export interface Panel { id: string; title: string; role: 'mainline' | 'branch' | 'auxiliary'; step?: number; parent?: string | null; dims: string[]; result?: string | null; breadcrumb: Crumb[]; entries: Entry[]; fields: Array<string | { id: string }>; nodes: string[]; imports: Flow[]; exports: Flow[] }
export interface NodeMeta { id?: string; label: string; kind: string; type?: string; dims?: string[]; reference?: string; note?: string; formula?: string | { text: string }; [key: string]: unknown }
export type Structure = Pick<WireStructure['data'], 'schema' | 'title'> & { schemaVersion?: string | null; period?: string; mainline: MainlineStep[]; panels: Panel[]; generalInputs: Array<string | { id: string }>; params: unknown[]; nodes?: Record<string, NodeMeta>; headline?: string | null; groupTitles?: Record<string, string> }
export type RunValue = WireRun['data']['values'][string][string]
export type Run = WireRun['data']
export interface Cell { text: string; address?: Address | null; editable?: boolean; style?: { weight?: string; tone?: string; fill?: string } }
export interface PaperColumn { id: string; header: string; content?: string; align?: string; width?: number }
export interface PaperRow { kind: string; depth: number; cells: Cell[]; node?: string | null; flags?: string[]; anchor?: string | null; optionKey?: string | null; section?: string | null }
export interface PaperTable { id: string; ref: string; title: string; breadcrumb?: string; columns: PaperColumn[]; rows: PaperRow[] }
export interface AuditEntry { anchor: string; citation: string; label: string; member?: string | null; formula: string; working: string; result: string; reference?: string | null }
export type Paper = Pick<WirePaper['data'], 'title' | 'header' | 'overview' | 'auxiliary' | 'legend'> & { subtitle?: string | null; headline?: { node: string; label: string; value: string } | null; inputGroups?: Array<{ key: string; title: string; inputs: string[] }>; tables: PaperTable[]; audit: AuditEntry[]; diagnostics?: Diagnostic[] }
export interface ExplainReference { address: Address; label: string; display: string; kind?: string; origin?: string }
export interface ExplainOption { label?: string; key?: string; display?: string; selected?: boolean; difference?: Value; differenceDisplay?: string }
export interface Explain { address: Address; label: string; kind: string; formula?: { text: string }; result: { value: Value; display: string }; status: string; steps: { text: string; display: string }[]; branches: unknown[]; references: ExplainReference[]; parts: unknown[]; options: ExplainOption[]; reference?: string; truncated?: boolean }
export type CaseSummary = Pick<WireWorkspace['data']['cases'][number], 'id' | 'title'> & { period?: string | null; schema?: string | null; revision?: string | null }
export interface Workspace { cases: CaseSummary[] }
