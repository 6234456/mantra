/** Wire primitives come from JSON Schema. UI projections below refine the schema's open objects. */
import type { Envelope as WireEnvelope, EnvelopeAddress, EnvelopeDiagnostic, ExportPreview as WireExportPreview, Paper as WirePaper, Run as WireRun, Structure as WireStructure, Value as WireValue, Workspace as WireWorkspace, Parameters as WireParameters, Compare as WireCompare } from './generated/contract'

export type Value = WireValue
export type Envelope<T> = Omit<WireEnvelope, 'data'> & { data: T }
export type Address = EnvelopeAddress
export type Diagnostic = EnvelopeDiagnostic
export interface MainlineStep { step: number; panel: string; title: string; result: string }
export interface Entry { step: number; panel: string; via: string; viaLabel: string; path: string[] }
export interface Crumb { kind?: 'mainline'; panel?: string; label?: string; node?: string }
export interface Flow { fromPanel: string; fromNode: string; toPanel: string; toNode: string }
export interface InputField { id: string; label?: string; type?: string; dims?: string[]; options?: Record<string, string>; columns?: Array<{ name: string; type: string; optional?: boolean }>; keyColumn?: string | null; help?: string | null; unit?: string | null; reference?: string | null; group?: string | null; groupTitle?: string | null; attributes?: Record<string, unknown> }
export interface Panel { id: string; title: string; role: 'mainline' | 'branch' | 'auxiliary'; step?: number; parent?: string | null; dims: string[]; result?: string | null; breadcrumb: Crumb[]; entries: Entry[]; fields: Array<string | InputField>; nodes: string[]; imports: Flow[]; exports: Flow[] }
export interface NodeMeta { id?: string; label: string; kind: string; type?: string; dims?: string[]; reference?: string; note?: string; formula?: string | { text: string }; [key: string]: unknown }
export type Structure = Pick<WireStructure['data'], 'schema' | 'title'> & { schemaVersion?: string | null; period?: string; mainline: MainlineStep[]; panels: Panel[]; generalInputs: Array<string | InputField>; params: unknown[]; nodes?: Record<string, NodeMeta>; headline?: string | null; groupTitles?: Record<string, string> }
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
export type Parameters = WireParameters['data']
export type Compare = WireCompare['data']
export type ExportPreview = WireExportPreview['data']
export type EditOperation =
  | { op: 'setInput'; address: Address; text: string }
  | { op: 'clearInput'; address: Address }
  | { op: 'setParam'; id: string; text: string }
  | { op: 'resetParam'; id: string }
  | { op: 'insertRow'; table: string; rowText: Record<string, string>; index?: number }
  | { op: 'updateRow'; table: string; row: Value; index: number }
  | { op: 'deleteRow'; table: string; index: number }
  | { op: 'moveRow'; table: string; from: number; to: number }
  | { op: 'removeSource'; index: number }
export interface SourceBinding { index: number; kind: 'csv' | 'json' | 'xlsx'; path: string; options: Record<string, unknown>; overridden?: string[] }
export interface Sources { sources: SourceBinding[] }
export interface ImportInspection { name: string; format: 'csv' | 'json' | 'xlsx'; columns: Array<{ name: string; sample: string[] }>; rowCount: number; delimiter?: string; decimal?: string; grouping?: string; numericAmbiguous?: boolean }
export interface ImportTemplate { name: string; format: 'csv' | 'json' | 'xlsx'; options: Record<string, unknown> }
export interface EditResult { document: string; preview: boolean; proposedRevision: string; diagnostics: Diagnostic[]; run: Run; difference: Compare }
export interface Diagnostics { diagnostics: Diagnostic[] }
export interface Workspace { cases: CaseSummary[]; parameters?: Array<{ id: string; path: string }>; layouts?: Array<{ id: string; path: string }> }
