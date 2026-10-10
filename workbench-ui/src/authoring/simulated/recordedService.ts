import type {
  ApplyResult,
  AuthoringRecording,
  AuthoringService,
  CommitRequest,
  CommitResult,
  DocumentTexts,
  ExampleInputResult,
  ExternalChange,
  OpenResult,
  PreviewRequest,
  PreviewResult,
  RecordedResponses,
  RecordingExchange,
  RecordingState,
  SemanticOperation,
  SourcePatch,
  SourceRevisions,
} from '../service'
import type { Diagnostic, Envelope, ExportPreview, PreviewPaper } from '../../types'
import { applySemanticOperation, createSourceOwnerScanner, sourceOutline } from './sourceOwners'

export interface RecordedServiceOptions {
  delays?: number[]
  savedDocuments?: DocumentTexts
}

export async function sourceRevisions(documents: DocumentTexts): Promise<SourceRevisions> {
  return Object.fromEntries(
    await Promise.all(
      Object.entries(documents).map(async ([document, text]) => {
        const bytes = new TextEncoder().encode(text)
        const digest = await crypto.subtle.digest('SHA-256', bytes)
        const sha256 = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
        return [document, sha256]
      }),
    ),
  )
}

export async function sourceDigest(documents: DocumentTexts): Promise<string> {
  const revisions = await sourceRevisions(documents)
  const entries = Object.keys(revisions)
    .sort()
    .map((path) => [path, revisions[path]])
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(entries)))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function documentsForState(recording: AuthoringRecording, state: RecordingState): DocumentTexts {
  return Object.fromEntries(
    recording.documents.map((document) => [document, (state.documents[document] ?? recording.base[document]).text]),
  )
}

function sameValues(left: Record<string, string>, right: Record<string, string>) {
  const keys = Object.keys(left)
  return keys.length === Object.keys(right).length && keys.every((key) => left[key] === right[key])
}

function responseBody(recording: AuthoringRecording, exchange: RecordingExchange): unknown {
  const body = recording.blobs[exchange.response.body.$blob]
  if (body === undefined) throw new Error('Recorded engine response is missing')
  return body
}

export function recordedResponses(recording: AuthoringRecording, state: RecordingState): RecordedResponses {
  const responses: RecordedResponses = { explains: {}, errors: [] }
  for (const exchange of state.exchanges) {
    const body = responseBody(recording, exchange)
    const { method, path } = exchange.request
    if (method !== 'GET') continue
    if (exchange.response.status !== 200) {
      responses.errors.push({ path, status: exchange.response.status, body })
      continue
    }
    const pathname = path.split('?')[0]
    if (pathname.endsWith('/workspace')) responses.workspace = body as RecordedResponses['workspace']
    else if (pathname.endsWith('/structure')) responses.structure = body as RecordedResponses['structure']
    else if (pathname.endsWith('/run')) responses.run = body as RecordedResponses['run']
    else if (pathname.endsWith('/paper')) responses.paper = body as RecordedResponses['paper']
    else if (pathname.endsWith('/diagnostics')) responses.diagnostics = body as RecordedResponses['diagnostics']
    else if (pathname.endsWith('/explain')) {
      const address = new URL(path, 'http://prototype.local').searchParams.get('address')
      if (address) responses.explains[address] = body as RecordedResponses['explains'][string]
    }
  }
  return responses
}

function extractDiagnostics(value: unknown): Diagnostic[] {
  if (!value || typeof value !== 'object') return []
  const body = value as { diagnostics?: Diagnostic[]; data?: { diagnostics?: Diagnostic[] }; error?: unknown }
  if (Array.isArray(body.data?.diagnostics)) return body.data.diagnostics
  if (Array.isArray(body.diagnostics)) return body.diagnostics
  return body.error ? extractDiagnostics(body.error) : []
}

export function previewForState(
  recording: AuthoringRecording,
  state: RecordingState,
  request: PreviewRequest,
): PreviewResult {
  const responses = recordedResponses(recording, state)
  const diagnostics = responses.diagnostics
    ? responses.diagnostics.data.diagnostics
    : responses.errors.flatMap((error) => extractDiagnostics(error.body))
  // Repeated rejected GETs can carry the same diagnostic. Keep one untouched diagnostic object.
  const unique = diagnostics.filter(
    (diagnostic, index) =>
      diagnostics.findIndex((candidate) => JSON.stringify(candidate) === JSON.stringify(diagnostic)) === index,
  )
  const kind = responses.errors.some((error) => error.status === 422)
    ? 'invalid'
    : responses.run?.data.succeeded === false
      ? 'runtimeFailure'
      : 'valid'
  return {
    draftSequence: request.draftSequence,
    baseRevisions: { ...request.baseRevisions },
    kind,
    responses,
    diagnostics: unique,
    stateId: state.id,
    digest: state.digest,
  }
}

/** In-memory simulation only. Engine payloads are looked up by exact source digest and never recomputed. */
export function createRecordedService(
  recording: AuthoringRecording,
  options: RecordedServiceOptions = {},
): AuthoringService {
  const scanner = createSourceOwnerScanner()
  const base = recording.states.find((state) => state.id === 'base')
  if (!base) throw new Error('A base recording is required')
  let savedDocuments = documentsForState(recording, base)
  let savedRevisions: SourceRevisions = Object.fromEntries(
    recording.documents.map((document) => [document, recording.base[document].sha256]),
  )
  const recoveredDocuments = options.savedDocuments ? { ...options.savedDocuments } : undefined
  const initialized = (async () => {
    if (!recoveredDocuments) return
    if (
      !sameValues(
        Object.fromEntries(recording.documents.map((document) => [document, ''])),
        Object.fromEntries(Object.keys(recoveredDocuments).map((document) => [document, ''])),
      )
    ) {
      throw new Error('Recovered saved source must contain exactly the recorded dependency closure')
    }
    const digest = await sourceDigest(recoveredDocuments)
    const state = recording.states.find((candidate) => candidate.digest === digest)
    if (!state) throw new Error('Recovered saved source does not match a recorded engine state')
    const preview = previewForState(recording, state, {
      draftSequence: 0,
      baseRevisions: {},
      documents: recoveredDocuments,
    })
    if (!['valid', 'runtimeFailure'].includes(preview.kind))
      throw new Error('Recovered saved source is not a savable state')
    savedDocuments = recoveredDocuments
    savedRevisions = await sourceRevisions(savedDocuments)
  })()
  let delayIndex = 0
  let nextDelay: number | undefined
  const delays = options.delays ?? [300, 600]

  const findPreview = async (request: PreviewRequest): Promise<PreviewResult> => {
    const digest = await sourceDigest(request.documents)
    const state = recording.states.find((candidate) => candidate.digest === digest)
    if (state) return previewForState(recording, state, request)
    return {
      draftSequence: request.draftSequence,
      baseRevisions: { ...request.baseRevisions },
      kind: 'unrecorded',
      responses: { explains: {}, errors: [] },
      diagnostics: [],
      digest,
      reason: 'No engine preview for this draft (not recorded in this prototype)',
    }
  }
  const conflict = (revisions: SourceRevisions): CommitResult | undefined => {
    const changed = Object.keys(savedRevisions).filter((document) => revisions[document] !== savedRevisions[document])
    if (changed.length || Object.keys(revisions).some((document) => !(document in savedRevisions))) {
      return {
        ok: false,
        kind: 'conflict',
        changed,
        currentRevisions: { ...savedRevisions },
        documents: { ...savedDocuments },
      }
    }
    return undefined
  }
  const apply = (operation: SemanticOperation, documents: DocumentTexts): ApplyResult =>
    applySemanticOperation(operation, documents, scanner.scan)

  return {
    async open(): Promise<OpenResult> {
      await initialized
      const documents = { ...savedDocuments }
      const baseRevisions = { ...savedRevisions }
      const preview = await findPreview({ draftSequence: 0, baseRevisions, documents })
      const owners = scanner.scan(documents)
      return {
        simulated: true,
        identity: {
          case: recording.case,
          panel: recording.panel,
          workspace: recording.workspace,
          title:
            preview.responses.structure?.data.panels.find((panel) => panel.id === recording.panel)?.title ??
            recording.panel,
        },
        documents,
        baseRevisions,
        owners,
        outline: sourceOutline(owners),
        preview,
        dependencies: recording.documents.map((document) => ({
          document,
          sha256: baseRevisions[document],
          editable: owners.some((owner) => owner.document === document && owner.editable),
        })),
      }
    },
    owners: scanner.scan,
    apply,
    async preview(request) {
      const snapshot = {
        draftSequence: request.draftSequence,
        baseRevisions: { ...request.baseRevisions },
        documents: { ...request.documents },
      }
      const delay = nextDelay ?? delays[delayIndex++ % Math.max(delays.length, 1)] ?? 0
      nextDelay = undefined
      const result = await findPreview(snapshot)
      if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay))
      return result
    },
    async commit(request: CommitRequest): Promise<CommitResult> {
      await initialized
      const snapshot = { baseRevisions: { ...request.baseRevisions }, documents: { ...request.documents } }
      const before = conflict(snapshot.baseRevisions)
      if (before) return before
      const preview = await findPreview({ ...snapshot, draftSequence: 0 })
      if (!['valid', 'runtimeFailure'].includes(preview.kind)) {
        return { ok: false, kind: 'invalid', reason: preview.reason ?? 'Fix the technical errors before saving' }
      }
      const revisions = await sourceRevisions(snapshot.documents)
      // A simulated external edit can arrive while WebCrypto is hashing the candidate.
      const after = conflict(snapshot.baseRevisions)
      if (after) return after
      savedDocuments = snapshot.documents
      savedRevisions = revisions
      return {
        ok: true,
        simulated: true,
        documents: { ...savedDocuments },
        baseRevisions: { ...savedRevisions },
        message: 'Saved in this prototype session — no file was written',
      }
    },
    async previewExampleInput(text: string): Promise<ExampleInputResult> {
      await initialized
      if ((await sourceDigest(savedDocuments)) !== base.digest) {
        return { kind: 'unrecorded', reason: 'Combined template and example-input preview needs contract G-A4' }
      }
      const exchange = base.exchanges.find((candidate) => {
        const body = candidate.request.body as { operations?: Array<{ text?: string }> } | undefined
        return candidate.request.method === 'POST' && body?.operations?.[0]?.text === text
      })
      if (!exchange)
        return { kind: 'unrecorded', reason: 'No engine preview for this input (not recorded in this prototype)' }
      return {
        kind: exchange.response.status === 200 ? 'valid' : 'invalid',
        status: exchange.response.status,
        response: responseBody(recording, exchange) as Envelope<PreviewPaper>,
      }
    },
    async simulateExternalChange(): Promise<ExternalChange> {
      await initialized
      const edit = recording.edits.external
      if (!edit) throw new Error('External-edit recording is unavailable')
      for (;;) {
        const snapshot = savedDocuments
        const current = snapshot[edit.document]
        if (current.includes(edit.replace)) {
          return { documents: { ...savedDocuments }, baseRevisions: { ...savedRevisions }, changed: [] }
        }
        if (current.split(edit.find).length !== 2)
          throw new Error('Saved source does not match the external-edit fixture')
        const documents = { ...snapshot, [edit.document]: current.replace(edit.find, edit.replace) }
        const revisions = await sourceRevisions(documents)
        // Preserve a simultaneous successful simulated save, then retry this operation on its new base.
        if (snapshot !== savedDocuments) continue
        savedDocuments = documents
        savedRevisions = revisions
        return { documents: { ...savedDocuments }, baseRevisions: { ...savedRevisions }, changed: [edit.document] }
      }
    },
    delayNextPreview(milliseconds) {
      nextDelay = Math.max(0, Number.isFinite(milliseconds) ? milliseconds : 0)
    },
    exportReport() {
      const exchange = base.exchanges.find(
        (candidate) =>
          candidate.request.method === 'GET' && candidate.request.path.split('?')[0].endsWith('/export-preview'),
      )
      return exchange ? (responseBody(recording, exchange) as Envelope<ExportPreview>) : undefined
    },
  }
}

export interface RebaseTransaction {
  operation?: SemanticOperation
  patches: SourcePatch[]
}

/** Replays semantic handles on the new base; changed-document transactions stay unresolved. */
export function rebaseSourceTransactions(
  service: AuthoringService,
  baseDocuments: DocumentTexts,
  changed: string[],
  transactions: RebaseTransaction[],
): { documents: DocumentTexts; replayed: SourcePatch[][]; conflicts: RebaseTransaction[] } {
  let documents = { ...baseDocuments }
  const replayed: SourcePatch[][] = []
  const conflicts: RebaseTransaction[] = []
  for (const transaction of transactions) {
    if (transaction.patches.some((patch) => changed.includes(patch.document)) || !transaction.operation) {
      conflicts.push(transaction)
      continue
    }
    const result = service.apply(transaction.operation, documents)
    if (!result.ok) {
      conflicts.push(transaction)
      continue
    }
    for (const patch of result.patches) {
      const text = documents[patch.document]
      documents = { ...documents, [patch.document]: text.slice(0, patch.start) + patch.text + text.slice(patch.end) }
    }
    replayed.push(result.patches)
  }
  return { documents, replayed, conflicts }
}
