import { describe, expect, it, vi } from 'vitest'
import rawRecording from '../recording/recording.json'
import type { AuthoringRecording, DocumentTexts, OwnerProperty, SourceOwner } from '../service'
import { applySourcePatches, invertSourcePatches } from '../model'
import {
  createRecordedService,
  documentsForState,
  rebaseSourceTransactions,
  sourceDigest,
  sourceRevisions,
} from './recordedService'
import { createSourceOwnerScanner, ownerForDiagnostic, parseSource } from './sourceOwners'

const recording = rawRecording as AuthoringRecording
const state = (id: string) => {
  const found = recording.states.find((candidate) => candidate.id === id)
  if (!found) throw new Error(`Missing test state ${id}`)
  return found
}
const baseline = () => documentsForState(recording, state('base'))
const owner = (owners: SourceOwner[], nodeId: string, property: OwnerProperty) => {
  const found = owners.find((candidate) => candidate.nodeId === nodeId && candidate.property === property)
  if (!found) throw new Error(`Missing test owner ${nodeId} ${property}`)
  return found
}
const serviceForTests = () => createRecordedService(recording, { delays: [0] })

describe('simulated source owners', () => {
  it('matches every real formula location and label in all valid engine recordings', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    for (const recordedState of recording.states.filter((candidate) => !candidate.edits.includes('formula:typo'))) {
      const documents = documentsForState(recording, recordedState)
      const owners = service.owners(documents)
      const preview = await service.preview({ draftSequence: 1, baseRevisions: opened.baseRevisions, documents })
      expect(preview.responses.structure).toBeDefined()
      for (const [id, node] of Object.entries(preview.responses.structure!.data.nodes ?? {})) {
        const formula = node.formula
        if (!formula || typeof formula === 'string') continue
        const location = (
          formula as { text: string; location: { document: string; startOffset: number; endOffset: number } }
        ).location
        const formulaOwner = owners.find(
          (candidate) =>
            candidate.nodeId === id &&
            ['formula', 'condition', 'left-operand'].includes(candidate.property) &&
            candidate.document === location.document,
        )
        expect(formulaOwner?.range).toEqual({ start: location.startOffset, end: location.endOffset })
        expect(documents[location.document].slice(location.startOffset, location.endOffset)).toBe(formula.text)
        const labelOwner = owners.find((candidate) => candidate.nodeId === id && candidate.property === 'label')
        if (labelOwner) expect(labelOwner.value).toBe(node.label)
      }
    }
  })

  it('ignores comments, escaped strings and declaration-like expressions inside formulas', () => {
    const text = String.raw`; (info hidden "Comment" (+ 1 2))
(schema example {:title "(info fake \"quoted\" x)"}
 (section panel "Section ; (not a comment)"
  (info visible "A \"quoted\" \\ name 😀" (info nested "Ignored" 1) {:class :result})))`
    const scanner = createSourceOwnerScanner()
    const owners = scanner.scan({ 'schema.mantra': text })
    expect(owners.some((candidate) => ['hidden', 'fake', 'nested'].includes(candidate.nodeId ?? ''))).toBe(false)
    const label = owner(owners, 'visible', 'label')
    expect(label.value).toBe('A "quoted" \\ name 😀')
    expect(text.slice(label.range.start, label.range.end)).toBe(String.raw`"A \"quoted\" \\ name 😀"`)
    expect(() => parseSource('(schema example "unclosed)')).toThrow('Unclosed source string')
    expect(scanner.scan({ 'schema.mantra': '(schema example [)' })).toEqual([])
  })

  it('keeps opaque handles stable while every following source range moves', () => {
    const service = serviceForTests()
    const documents = baseline()
    const before = service.owners(documents)
    const label = owner(before, 'unallocated', 'label')
    const following = owner(before, 'unused-total-capacity', 'formula')
    const applied = service.apply(
      { handle: label.handle, op: 'setText', value: 'A considerably longer display label' },
      documents,
    )
    expect(applied.ok).toBe(true)
    if (!applied.ok) return
    const after = owner(applied.owners, 'unused-total-capacity', 'formula')
    expect(after.handle).toBe(following.handle)
    expect(after.range.start).toBeGreaterThan(following.range.start)
    expect(after.handle).not.toContain(following.nodeId)
  })

  it('keeps reconcile/check operands, table style and fragment declarations read-only', () => {
    const service = serviceForTests()
    const documents = baseline()
    const owners = service.owners(documents)
    for (const property of ['left-operand', 'right-operand', 'condition', 'table-style', 'columns', 'declaration']) {
      const candidate = owners.find((item) => item.property === property)
      expect(candidate?.editable).toBe(false)
      expect(candidate?.reason).toBeTruthy()
      const result = service.apply({ handle: candidate!.handle, op: 'setFormula', value: '0' }, documents)
      expect(result).toEqual({ ok: false, reason: candidate!.reason })
    }
    expect(owner(owners, 'conserved', 'label').editable).toBe(true)
    expect(owner(owners, 'conserved', 'classes').editable).toBe(true)
  })

  it('produces byte-exact recorded edits and byte-exact inverse patches', () => {
    const operations = [
      { edit: 'label', nodeId: 'unallocated', property: 'label', op: 'setText', value: 'Unallocated request' },
      { edit: 'class', nodeId: 'unused-total-capacity', property: 'classes', op: 'setClasses', value: ['result'] },
      {
        edit: 'formula:typo',
        nodeId: 'unallocated',
        property: 'formula',
        op: 'setFormula',
        value: '(- request allocated-totl)',
      },
      {
        edit: 'formula:finding',
        nodeId: 'unallocated',
        property: 'formula',
        op: 'setFormula',
        value: '(- request total-capacity)',
      },
      { edit: 'formula:runtime', nodeId: 'unallocated', property: 'formula', op: 'setFormula', value: '(/ request 0)' },
    ] as const
    for (const specification of operations) {
      const service = serviceForTests()
      const documents = baseline()
      const target = owner(service.owners(documents), specification.nodeId, specification.property)
      const value = typeof specification.value === 'string' ? specification.value : [...specification.value]
      const applied = service.apply({ handle: target.handle, op: specification.op, value }, documents)
      expect(applied.ok).toBe(true)
      if (!applied.ok) continue
      const edited = applySourcePatches(documents, applied.patches)
      expect(edited).toEqual(documentsForState(recording, state(specification.edit)))
      expect(applySourcePatches(edited, invertSourcePatches(applied.patches))).toEqual(documents)
    }
  })

  it('escapes text, validates classes and layout types, and never accepts client offsets', () => {
    const service = serviceForTests()
    const documents = baseline()
    const owners = service.owners(documents)
    const label = owner(owners, 'unallocated', 'label')
    const result = service.apply(
      { handle: label.handle, op: 'setText', value: 'Quoted "text" with \\ slash' },
      documents,
    )
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.patches[0].text).toBe(String.raw`"Quoted \"text\" with \\ slash"`)
    for (const value of ['', '   ', 'new\nline', 'new\rline']) {
      expect(service.apply({ handle: label.handle, op: 'setText', value }, documents).ok).toBe(false)
    }
    expect(service.apply({ handle: label.handle, op: 'setText', value: 'Text', start: 0 } as never, documents).ok).toBe(
      false,
    )
    const classes = owner(owners, 'unallocated', 'classes')
    expect(
      service.apply({ handle: classes.handle, op: 'setClasses', value: ['result', 'strong'] }, documents),
    ).toMatchObject({
      ok: true,
      patches: [{ text: '[:result :strong]' }],
    })
    for (const value of [['Result'], ['bad_class'], [], ['result', '']]) {
      expect(service.apply({ handle: classes.handle, op: 'setClasses', value }, documents).ok).toBe(false)
    }
    const precision = owners.find((candidate) => candidate.property === 'precision')!
    expect(service.apply({ handle: precision.handle, op: 'setLayoutOption', value: 0 }, documents).ok).toBe(true)
    for (const value of [-1, 1.1, '2', false]) {
      expect(service.apply({ handle: precision.handle, op: 'setLayoutOption', value }, documents).ok).toBe(false)
    }
    const hidden = owners.find((candidate) => candidate.property === 'hide-zero')!
    expect(service.apply({ handle: hidden.handle, op: 'setLayoutOption', value: true }, documents).ok).toBe(true)
    expect(service.apply({ handle: hidden.handle, op: 'setLayoutOption', value: 'true' }, documents).ok).toBe(false)
    expect(service.apply({ handle: 'made-up-owner', op: 'setText', value: 'Text' }, documents).ok).toBe(false)
  })
})

describe('recorded authoring service', () => {
  it('hits all 32 digests and returns the exact engine response objects', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    for (const recordedState of recording.states) {
      const documents = documentsForState(recording, recordedState)
      expect(await sourceDigest(documents)).toBe(recordedState.digest)
      const preview = await service.preview({ draftSequence: 7, baseRevisions: opened.baseRevisions, documents })
      expect(preview.stateId).toBe(recordedState.id)
      expect(preview.kind).toBe(
        recordedState.edits.includes('formula:typo')
          ? 'invalid'
          : recordedState.edits.includes('formula:runtime')
            ? 'runtimeFailure'
            : 'valid',
      )
      expect(preview.draftSequence).toBe(7)
      expect(preview.baseRevisions).toEqual(opened.baseRevisions)
      const exchange = recordedState.exchanges.find((candidate) =>
        candidate.request.path.split('?')[0].endsWith('/paper'),
      )!
      const body = recording.blobs[exchange.response.body.$blob]
      if (exchange.response.status === 200) expect(preview.responses.paper).toBe(body)
      else expect(preview.responses.errors.find((error) => error.path === exchange.request.path)?.body).toBe(body)
    }
    const unknown = { ...opened.documents, [recording.documents[0]]: `${opened.documents[recording.documents[0]]}\n` }
    const preview = await service.preview({ draftSequence: 8, baseRevisions: opened.baseRevisions, documents: unknown })
    expect(preview.kind).toBe('unrecorded')
    expect(preview.responses.paper).toBeUndefined()
    expect(preview.responses.run).toBeUndefined()
  })

  it('maps precise recorded diagnostics to their source owner, including multiple same-owner errors', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    const documents = documentsForState(recording, state('formula:typo'))
    const preview = await service.preview({ draftSequence: 1, baseRevisions: opened.baseRevisions, documents })
    expect(preview.diagnostics).toHaveLength(2)
    const owners = service.owners(documents)
    for (const diagnostic of preview.diagnostics) {
      expect(ownerForDiagnostic(owners, diagnostic)?.handle).toBe(owner(owners, 'unallocated', 'formula').handle)
    }
    const location = preview.diagnostics[0].location!
    expect(documents[location.document].slice(location.startOffset!, location.endOffset!)).toBe('allocated-totl')
  })

  it('rejects invalid and unrecorded saves while business findings and runtime failures remain savable', async () => {
    for (const id of ['formula:typo', 'formula:finding', 'formula:runtime']) {
      const service = serviceForTests()
      const opened = await service.open()
      const documents = documentsForState(recording, state(id))
      const committed = await service.commit({ baseRevisions: opened.baseRevisions, documents })
      expect(committed.ok).toBe(id !== 'formula:typo')
      if (committed.ok) {
        expect(committed.message).toBe('Saved in this prototype session — no file was written')
        expect((await service.open()).documents).toEqual(documents)
        expect(committed.baseRevisions).toEqual(await sourceRevisions(documents))
      } else expect(committed.kind).toBe('invalid')
    }
    const service = serviceForTests()
    const opened = await service.open()
    const documents = { ...opened.documents, [recording.documents[0]]: `${opened.documents[recording.documents[0]]}\n` }
    expect(await service.commit({ baseRevisions: opened.baseRevisions, documents })).toMatchObject({
      ok: false,
      kind: 'invalid',
    })
  })

  it('returns a simulated CAS conflict and replays unchanged-document owner operations on the new base', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    const target = owner(opened.owners, 'unallocated', 'label')
    const operation = { handle: target.handle, op: 'setText' as const, value: 'Unallocated request' }
    const applied = service.apply(operation, opened.documents)
    expect(applied.ok).toBe(true)
    if (!applied.ok) return
    const documents = applySourcePatches(opened.documents, applied.patches)
    const external = await service.simulateExternalChange()
    expect(external.changed).toEqual([recording.edits.external.document])
    const conflict = await service.commit({ baseRevisions: opened.baseRevisions, documents })
    expect(conflict).toMatchObject({ ok: false, kind: 'conflict', changed: external.changed })
    expect(documents).toEqual(documentsForState(recording, state('label')))
    const rebased = rebaseSourceTransactions(service, external.documents, external.changed, [
      { operation, patches: applied.patches },
    ])
    expect(rebased.conflicts).toHaveLength(0)
    expect(rebased.documents).toEqual(documentsForState(recording, state('label+external')))
    expect(await service.commit({ baseRevisions: external.baseRevisions, documents: rebased.documents })).toMatchObject(
      { ok: true },
    )
    expect((await service.simulateExternalChange()).changed).toEqual([])
  })

  it('flags overlap instead of replaying a changed-document operation or an opaque raw-source transaction', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    const title = opened.owners.find((candidate) => candidate.property === 'title')!
    const operation = { handle: title.handle, op: 'setLayoutOption' as const, value: 'My title' }
    const applied = service.apply(operation, opened.documents)
    if (!applied.ok) throw new Error('Expected valid test title operation')
    const external = await service.simulateExternalChange()
    const transactions = [{ operation, patches: applied.patches }, { patches: applied.patches }]
    const result = rebaseSourceTransactions(service, external.documents, external.changed, transactions)
    expect(result.conflicts).toEqual(transactions)
    expect(result.documents).toEqual(external.documents)
  })

  it('preserves a simultaneous successful save when an external edit is still hashing its old base', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    const originalDigest = crypto.subtle.digest.bind(crypto.subtle)
    let release = () => {}
    const waiting = new Promise<void>((resolve) => {
      release = resolve
    })
    const hashing = vi.spyOn(crypto.subtle, 'digest').mockImplementationOnce(async (...arguments_) => {
      await waiting
      return originalDigest(...arguments_)
    })
    try {
      const external = service.simulateExternalChange()
      await Promise.resolve()
      const committed = await service.commit({
        baseRevisions: opened.baseRevisions,
        documents: documentsForState(recording, state('label')),
      })
      expect(committed.ok).toBe(true)
      release()
      const changed = await external
      expect(changed.documents).toEqual(documentsForState(recording, state('label+external')))
      expect((await service.open()).documents).toEqual(changed.documents)
    } finally {
      release()
      hashing.mockRestore()
    }
  })

  it('uses untouched recorded candidate input and export reports, with no combined source/input preview', async () => {
    const service = serviceForTests()
    await service.open()
    for (const [text, status] of [
      ['9', 200],
      ['nine', 422],
    ] as const) {
      const result = await service.previewExampleInput(text)
      expect(result.status).toBe(status)
      const exchange = state('base').exchanges.find((candidate) => {
        const body = candidate.request.body as { operations?: Array<{ text: string }> } | undefined
        return candidate.request.method === 'POST' && body?.operations?.[0]?.text === text
      })!
      expect(result.response).toBe(recording.blobs[exchange.response.body.$blob])
    }
    expect((await service.previewExampleInput('10')).kind).toBe('unrecorded')
    const reportExchange = state('base').exchanges.find((candidate) =>
      candidate.request.path.endsWith('/export-preview'),
    )!
    expect(service.exportReport()).toBe(recording.blobs[reportExchange.response.body.$blob])
    await service.simulateExternalChange()
    expect(await service.previewExampleInput('9')).toMatchObject({
      kind: 'unrecorded',
      reason: 'Combined template and example-input preview needs contract G-A4',
    })
  })

  it('reopens a persisted simulated saved baseline and rejects unrelated or unsavable recovery sources', async () => {
    const documents = documentsForState(recording, state('label+class+external'))
    const supplied = { ...documents }
    const service = createRecordedService(recording, { delays: [0], savedDocuments: supplied })
    supplied[recording.documents[0]] += '\n'
    const opened = await service.open()
    expect(opened.documents).toEqual(documents)
    expect(opened.baseRevisions).toEqual(await sourceRevisions(documents))
    expect(opened.preview.stateId).toBe('label+class+external')
    const broken: DocumentTexts[] = [
      documentsForState(recording, state('formula:typo')),
      { ...documents, 'unexpected.mantra': '' },
      { ...documents, [recording.documents[0]]: `${documents[recording.documents[0]]}\n` },
    ]
    for (const savedDocuments of broken) {
      await expect(createRecordedService(recording, { savedDocuments }).open()).rejects.toThrow()
    }
  })

  it('captures preview requests before async work, permitting intentionally late response demonstration', async () => {
    const service = serviceForTests()
    const opened = await service.open()
    service.delayNextPreview(40)
    const documents = documentsForState(recording, state('label'))
    const revisions = { ...opened.baseRevisions }
    const first = service.preview({ draftSequence: 1, baseRevisions: revisions, documents })
    documents[recording.documents[0]] += '\n'
    revisions[recording.documents[0]] = 'mutated'
    const second = await service.preview({
      draftSequence: 2,
      baseRevisions: opened.baseRevisions,
      documents: opened.documents,
    })
    expect(second.stateId).toBe('base')
    const late = await first
    expect(late.stateId).toBe('label')
    expect(late.baseRevisions).toEqual(opened.baseRevisions)
    expect(late.draftSequence).toBe(1)
  })
})
