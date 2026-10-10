import type { Envelope, PreviewPaper, Diagnostic } from '../types'
import type { AuthoringRecording } from '../authoring/service'
import { recordedResponses } from '../authoring/simulated/recordedService'
import rawRecording from '../authoring/recording/recording.json'
import type { TemplatePreview, TemplatePreviewRequest, TemplateSources } from './client'

const recording: AuthoringRecording = rawRecording
const base = recording.states.find((state) => state.id === 'base')!
const responses = recordedResponses(recording, base)
const candidateExchange = base.exchanges.find(
  (exchange) => exchange.request.method === 'POST' && exchange.response.status === 200,
)!
const candidate = recording.blobs[candidateExchange.response.body.$blob] as Envelope<PreviewPaper>
const invalid = recording.states.find((state) => state.id === 'formula:typo')!
const rejected = invalid.exchanges.find((exchange) => exchange.response.status === 422)!
const rejection = recording.blobs[rejected.response.body.$blob] as { error: { diagnostics: Diagnostic[] } }

export const firstCase = recording.case
export const secondCase = 'another-example.mantra'
export const formulaDiagnostic = rejection.error.diagnostics[0]
export const diagnosticSource = (
  invalid.documents[formulaDiagnostic.location!.document!] ?? recording.base[formulaDiagnostic.location!.document!]
).text

export function templateSources(caseId = firstCase): Envelope<TemplateSources> {
  return {
    contract: candidate.contract,
    engine: candidate.engine,
    revision: candidate.revision,
    data: {
      document: caseId,
      baseRevisions: {
        ...Object.fromEntries(Object.entries(recording.base).map(([path, source]) => [path, source.sha256])),
        'data/example.csv': 'a'.repeat(64),
      },
      documents: Object.entries(recording.base).map(([path, source], index) => {
        const role = path.endsWith('/schema.mantra')
          ? 'schema'
          : path.endsWith('/layout.mantra')
            ? 'layout'
            : path.endsWith('/case-demo.mantra')
              ? 'case'
              : 'included'
        return {
          handle: 'snapshot-handle-' + index,
          document: path,
          role,
          text: source.text,
          sha256: source.sha256,
          editable: role === 'schema' || role === 'layout',
          reason: role === 'schema' || role === 'layout' ? null : 'This captured dependency is read-only.',
        }
      }),
    },
  }
}

export function templateRequest(): TemplatePreviewRequest {
  const source = templateSources()
  return {
    baseRevision: source.revision,
    baseRevisions: source.data.baseRevisions,
    draftSequence: 3,
    documents: source.data.documents
      .filter((document) => document.editable)
      .map(({ handle, text }) => ({ handle, text })),
    inputs: [{ node: 'requested-units', text: ' 1.234,56 ' }],
  }
}

/** Calculation payloads below are copied from the engine recording, never computed by the test. */
export function templatePreview(caseId: string, request: TemplatePreviewRequest): Envelope<TemplatePreview> {
  return {
    ...candidate,
    revision: request.baseRevision,
    data: {
      ...candidate.data,
      document: caseId,
      draftSequence: request.draftSequence,
      baseRevisions: request.baseRevisions,
      structure: responses.structure!.data,
      explain: request.explain ? (responses.explains[request.explain.node]?.data ?? null) : null,
    },
  }
}
