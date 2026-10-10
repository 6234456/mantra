import { describe, expect, it } from 'vitest'
import type { Diagnostic } from '../types'
import type { DocumentTexts, PreviewResult, SourcePatch, SourceRevisions } from './service'
import {
  applySourcePatches,
  authoringReducer,
  canRedo,
  canSave,
  canUndo,
  createAuthoringState,
  currentPreview,
  diagnosticsAreStale,
  invertSourcePatches,
  isDirty,
  previewStatus,
  saveCapsule,
  saveReason,
  type AuthoringState,
} from './model'

const documents = {
  'source.mantra': '; retained comment\r\n(info example "Initial label" (+ input 1))\r\n',
  'layout.mantra': '(layout example {:title "Initial title"})\n',
}
const revisions = { 'source.mantra': 'source-base', 'layout.mantra': 'layout-base' }
const diagnostic: Diagnostic = {
  code: 'MANTRA-FORMULA',
  message: 'Unknown symbol',
  severity: 'error',
  category: 'structural',
  location: { document: 'source.mantra', line: 2, column: 1, startOffset: 20, endOffset: 23 },
  address: null,
  related: [],
  rowIndex: null,
  column: null,
  caseRevision: null,
}

function preview(
  draftSequence = 0,
  kind: PreviewResult['kind'] = 'valid',
  baseRevisions: SourceRevisions = revisions,
): PreviewResult {
  return {
    draftSequence,
    kind,
    baseRevisions,
    digest: `recorded-source-${draftSequence}`,
    responses: {
      paper: {
        contract: 'mantra.workbench/4',
        revision: `engine-revision-${draftSequence}`,
        engine: { mantra: 'test', normein: 'test' },
        data: {
          title: `Recorded Paper ${draftSequence}`,
          tables: [],
          audit: [],
          header: [],
          overview: [],
          auxiliary: [],
          legend: [],
        },
      },
      explains: {},
      errors: [],
    },
    diagnostics: kind === 'invalid' ? [diagnostic] : [],
  }
}

function initial(): AuthoringState {
  return createAuthoringState({ documents, baseRevisions: revisions, preview: preview() })
}

function edit(state: AuthoringState, from = 'Initial label', to = 'Edited label', document = 'source.mantra') {
  const start = state.documents[document].indexOf(from)
  return authoringReducer(state, {
    type: 'transaction',
    label: 'Label edit',
    ownerHandles: ['opaque-owner'],
    operation: { handle: 'opaque-owner', op: 'setText', value: to },
    patches: [{ document, start, end: start + from.length, text: to, inverse: from }],
  })
}

function receive(state: AuthoringState, kind: PreviewResult['kind'] = 'valid') {
  return authoringReducer(state, {
    type: 'previewReceived',
    result: preview(state.draftSequence, kind, state.baseRevisions),
  })
}

const external = {
  documents: { ...documents, 'layout.mantra': '(layout example {:title "Externally edited"})\n' },
  baseRevisions: { ...revisions, 'layout.mantra': 'layout-external' },
  changed: ['layout.mantra'],
}

describe('authoring response identity and recorded evidence', () => {
  it('ignores an older response without changing Paper, diagnostics or the save decision', () => {
    const first = edit(initial())
    const latest = edit(first, 'Edited label', 'Newer label')
    const waiting = authoringReducer(latest, {
      type: 'previewStarted',
      draftSequence: latest.draftSequence,
      baseRevisions: latest.baseRevisions,
    })
    const before = [currentPreview(waiting), waiting.diagnostics, canSave(waiting), saveReason(waiting)]
    const late = authoringReducer(waiting, { type: 'previewReceived', result: preview(first.draftSequence, 'invalid') })
    expect(late).toBe(waiting)
    expect([currentPreview(late), late.diagnostics, canSave(late), saveReason(late)]).toEqual(before)
    expect(previewStatus(late)).toBe(`Calculating draft #${latest.draftSequence}…`)
  })

  it.each([
    { 'source.mantra': 'wrong', 'layout.mantra': 'layout-base' },
    { 'source.mantra': 'source-base' },
    { ...revisions, 'new-dependency.mantra': 'new' },
  ])('requires the complete participating revision set, independently of sequence', (baseRevisions) => {
    const state = receive(edit(initial()))
    expect(canSave(state)).toBe(true)
    const stale = authoringReducer(state, {
      type: 'previewReceived',
      result: preview(state.draftSequence, 'invalid', baseRevisions as SourceRevisions),
    })
    expect(stale).toBe(state)
    expect(canSave(stale)).toBe(true)
    expect(stale.diagnostics).toEqual([])
  })

  it('accepts revision maps in any key order and rejects future sequence responses', () => {
    const state = edit(initial())
    const reordered = { 'layout.mantra': 'layout-base', 'source.mantra': 'source-base' }
    const fresh = authoringReducer(state, { type: 'previewReceived', result: preview(1, 'valid', reordered) })
    expect(canSave(fresh)).toBe(true)
    expect(authoringReducer(fresh, { type: 'previewReceived', result: preview(2, 'invalid') })).toBe(fresh)
  })

  it('keeps the previous successful Paper after technical errors and unknown drafts, then replaces it after repair', () => {
    const baseline = initial()
    const invalid = receive(edit(baseline), 'invalid')
    expect(currentPreview(invalid)).toBe(currentPreview(baseline))
    expect(invalid.validity).toBe('invalid')
    expect(saveCapsule(invalid).label).toBe('Invalid draft')
    expect(canSave(invalid)).toBe(false)
    expect(previewStatus(invalid)).toBe('Previous valid preview (draft #0)')

    const fixing = edit(invalid, 'Edited label', 'Repair')
    expect(fixing.diagnostics).toEqual([diagnostic])
    expect(diagnosticsAreStale(fixing)).toBe(true)
    const unknown = receive(fixing, 'unrecorded')
    expect(currentPreview(unknown)).toBe(currentPreview(baseline))
    expect(previewStatus(unknown)).toContain('No engine preview')
    expect(canSave(unknown)).toBe(false)

    const repaired = receive(edit(unknown, 'Repair', 'Recorded repair'))
    expect(currentPreview(repaired)?.draftSequence).toBe(3)
    expect(repaired.validity).toBe('valid')
    expect(repaired.diagnostics).toEqual([])
    expect(canSave(repaired)).toBe(true)
  })

  it('shows a runtime partial result without claiming validity or replacing the previous successful result', () => {
    const baseline = initial()
    const runtime = receive(edit(baseline), 'runtimeFailure')
    expect(runtime.validity).toBe('runtimeFailure')
    expect(currentPreview(runtime)?.kind).toBe('runtimeFailure')
    expect(runtime.preview.previousValid).toBe(currentPreview(baseline))
    expect(previewStatus(runtime)).toContain('runtime failure')
    expect(canSave(runtime)).toBe(true)
    const unknown = receive(edit(runtime, 'Edited label', 'Unknown'), 'unrecorded')
    expect(currentPreview(unknown)).toBe(currentPreview(baseline))
    expect(currentPreview(unknown)?.kind).toBe('valid')
  })

  it('does not infer technical failure from a business finding reported by a valid engine response', () => {
    const state = edit(initial())
    const result = preview(state.draftSequence)
    result.diagnostics = [{ ...diagnostic, category: 'business', code: 'MANTRA-RECONCILE-FAILED' }]
    const finding = authoringReducer(state, { type: 'previewReceived', result })
    expect(finding.validity).toBe('valid')
    expect(canSave(finding)).toBe(true)
    expect(finding.diagnostics[0].category).toBe('business')
  })
})

describe('exact source transactions and saved history', () => {
  it('restores exact UTF-8 bytes through undo and redo while monotonically increasing draft sequence', () => {
    const state = edit(initial(), 'Initial label', '新しい 😀 label')
    expect(canUndo(state)).toBe(true)
    const undo = authoringReducer(state, { type: 'undo' })
    expect(new TextEncoder().encode(undo.documents['source.mantra'])).toEqual(
      new TextEncoder().encode(documents['source.mantra']),
    )
    expect(undo.documents).toEqual(documents)
    expect(isDirty(undo)).toBe(false)
    expect(undo.draftSequence).toBe(2)
    expect(canRedo(undo)).toBe(true)
    const redo = authoringReducer(undo, { type: 'redo' })
    expect(redo.documents).toEqual(state.documents)
    expect(redo.draftSequence).toBe(3)
    expect(canSave(redo)).toBe(false)
    expect(redo.history.past[0].operation?.value).toBe('新しい 😀 label')
  })

  it('treats a multi-document operation as one undo entry and clears redo after a new edit', () => {
    const patches = Object.entries(documents).map(([document, source]) => ({
      document,
      start: source.indexOf('Initial'),
      end: source.indexOf('Initial') + 7,
      text: 'Changed',
      inverse: 'Initial',
    }))
    const state = authoringReducer(initial(), { type: 'transaction', label: 'Two properties', patches })
    expect(state.history.past).toHaveLength(1)
    const undo = authoringReducer(state, { type: 'undo' })
    expect(undo.documents).toEqual(documents)
    const next = edit(undo)
    expect(canRedo(next)).toBe(false)
    expect(next.draftSequence).toBe(3)
  })

  it('locks source changes while saving, accepts only its response and retains history across save', () => {
    const ready = receive(edit(initial()))
    const saving = authoringReducer(ready, { type: 'saveStarted' })
    expect(saveCapsule(saving).kind).toBe('saving')
    expect(canUndo(saving)).toBe(false)
    expect(edit(saving, 'Edited label', 'Lost race')).toBe(saving)
    expect(authoringReducer(saving, { type: 'undo' })).toBe(saving)
    expect(authoringReducer(saving, { type: 'redo' })).toBe(saving)
    expect(authoringReducer(saving, { type: 'discard' })).toBe(saving)
    expect(authoringReducer(saving, { type: 'restore', documents })).toBe(saving)
    expect(authoringReducer(saving, { type: 'saveStarted' })).toBe(saving)
    expect(authoringReducer(saving, { type: 'saveSucceeded', draftSequence: 0, baseRevisions: revisions })).toBe(saving)
    const saved = authoringReducer(saving, {
      type: 'saveSucceeded',
      draftSequence: saving.draftSequence,
      baseRevisions: { ...revisions, 'source.mantra': 'saved-revision' },
    })
    expect(isDirty(saved)).toBe(false)
    expect(saveCapsule(saved).kind).toBe('saved')
    expect(saved.history).toBe(ready.history)
    expect(saved.draftSequence).toBeGreaterThan(saving.draftSequence)
    expect(canSave(saved)).toBe(false)
    const undone = authoringReducer(saved, { type: 'undo' })
    expect(undone.documents).toEqual(documents)
    expect(isDirty(undone)).toBe(true)
    expect(saveCapsule(undone).kind).toBe('unsaved')
    expect(undone.baseDocuments).toEqual(ready.documents)
  })

  it('rejects stale, overlapping and missing-document patches without altering the draft', () => {
    const state = initial()
    const invalid: SourcePatch[][] = [
      [{ document: 'source.mantra', start: 0, end: 1, text: 'x', inverse: 'wrong' }],
      [{ document: 'missing.mantra', start: 0, end: 0, text: 'x', inverse: '' }],
      [
        { document: 'source.mantra', start: 0, end: 5, text: 'x', inverse: '; ret' },
        { document: 'source.mantra', start: 3, end: 6, text: 'x', inverse: 'eta' },
      ],
    ]
    for (const patches of invalid) {
      expect(authoringReducer(state, { type: 'transaction', label: 'Invalid patch', patches })).toBe(state)
    }
  })

  it('inverts simultaneous patches with independent insertion-length shifts per document', () => {
    const original: DocumentTexts = { first: 'abcdef\r\n', second: 'ABCDEFGHIJ' }
    const patches: SourcePatch[] = [
      { document: 'first', start: 0, end: 1, text: '長😀', inverse: 'a' },
      { document: 'first', start: 3, end: 5, text: '', inverse: 'de' },
      { document: 'second', start: 1, end: 3, text: 'replacement', inverse: 'BC' },
      { document: 'second', start: 7, end: 7, text: ' inserted ', inverse: '' },
    ]
    const changed = applySourcePatches(original, patches)
    expect(changed).toEqual({ first: '長😀bcf\r\n', second: 'AreplacementDEFG inserted HIJ' })
    expect(applySourcePatches(changed, invertSourcePatches(patches))).toEqual(original)
  })
})

describe('external source changes and recovery', () => {
  it('keeps the complete draft and history on conflict and discards to the external baseline', () => {
    const state = receive(edit(initial()))
    const conflict = authoringReducer(state, { type: 'externalChanged', ...external })
    expect(conflict.documents).toBe(state.documents)
    expect(conflict.history).toBe(state.history)
    expect(saveCapsule(conflict).kind).toBe('conflict')
    expect(canSave(conflict)).toBe(false)
    expect(conflict.preview.status).toBe('previous')
    expect(currentPreview(conflict)).toBe(currentPreview(state))
    expect(previewStatus(conflict)).toBe('Preview based on previous source revisions (conflict)')
    const discarded = authoringReducer(conflict, { type: 'discard' })
    expect(discarded.documents).toEqual(external.documents)
    expect(discarded.baseRevisions).toEqual(external.baseRevisions)
    expect(discarded.conflict).toBeUndefined()
    expect(discarded.history.past).toHaveLength(0)
    expect(isDirty(discarded)).toBe(false)
  })

  it('keeps runtime evidence marked with the old source revisions until a new-base preview arrives', () => {
    const runtime = receive(edit(initial()), 'runtimeFailure')
    const evidence = currentPreview(runtime)
    const conflict = authoringReducer(runtime, { type: 'externalChanged', ...external })
    expect(conflict.preview.status).toBe('previous')
    expect(currentPreview(conflict)).toBe(evidence)
    expect(currentPreview(conflict)?.baseRevisions).toEqual(revisions)

    const waiting = authoringReducer(conflict, {
      type: 'previewStarted',
      draftSequence: conflict.draftSequence,
      baseRevisions: revisions,
    })
    expect(waiting.preview.status).toBe('previous')
    expect(currentPreview(waiting)).toBe(evidence)
    const oldBaseResponse = receive(waiting, 'runtimeFailure')
    expect(oldBaseResponse.preview.status).toBe('previous')
    expect(previewStatus(oldBaseResponse)).toBe('Preview based on previous source revisions (conflict)')
    expect(canSave(oldBaseResponse)).toBe(false)

    const rebased = authoringReducer(oldBaseResponse, { type: 'rebase', ...external })
    expect(rebased.conflict).toBeUndefined()
    expect(previewStatus(rebased)).not.toContain('conflict')
    expect(rebased.preview.status).toBe('previous')
    expect(canSave(rebased)).toBe(false)
    const ready = receive(rebased, 'runtimeFailure')
    expect(ready.preview.status).toBe('current')
    expect(currentPreview(ready)?.baseRevisions).toEqual(external.baseRevisions)
    expect(previewStatus(ready)).toContain('runtime failure')
    expect(canSave(ready)).toBe(true)
  })

  it('rebases untouched external documents while retaining source edits and making history safe to undo', () => {
    const edited = edit(initial())
    const conflict = authoringReducer(edited, { type: 'externalChanged', ...external })
    const rebased = authoringReducer(conflict, { type: 'rebase', ...external })
    expect(rebased.documents['source.mantra']).toBe(edited.documents['source.mantra'])
    expect(rebased.documents['layout.mantra']).toBe(external.documents['layout.mantra'])
    expect(rebased.baseRevisions).toEqual(external.baseRevisions)
    expect(rebased.draftSequence).toBeGreaterThan(edited.draftSequence)
    expect(canSave(rebased)).toBe(false)
    expect(rebased.conflict).toBeUndefined()
    const oldResponse = authoringReducer(rebased, {
      type: 'previewReceived',
      result: preview(rebased.draftSequence, 'valid', revisions),
    })
    expect(oldResponse).toBe(rebased)
    const ready = receive(rebased)
    expect(canSave(ready)).toBe(true)
    const undone = authoringReducer(ready, { type: 'undo' })
    expect(undone.documents).toEqual(external.documents)
    expect(isDirty(undone)).toBe(false)
  })

  it('stores conflict data without its event type so a UI can spread it into a rebase event', () => {
    const edited = edit(initial())
    const conflict = authoringReducer(edited, { type: 'externalChanged', ...external })
    expect(conflict.conflict).not.toHaveProperty('type')
    const rebased = authoringReducer(conflict, { type: 'rebase', ...conflict.conflict! })
    expect(rebased.conflict).toBeUndefined()
    expect(rebased.baseRevisions).toEqual(external.baseRevisions)
    expect(rebased.documents['source.mantra']).toBe(edited.documents['source.mantra'])
    expect(rebased.documents['layout.mantra']).toBe(external.documents['layout.mantra'])

    const overlapping = edit(initial(), 'Initial title', 'My title', 'layout.mantra')
    const blocked = authoringReducer(overlapping, { type: 'rebase', ...external })
    expect(blocked.conflict).not.toHaveProperty('type')
    expect(blocked.conflict?.overlapping).toEqual(['layout.mantra'])
  })

  it('preserves overlapping source edits instead of silently replacing them on rebase', () => {
    const edited = edit(initial(), 'Initial title', 'My title', 'layout.mantra')
    const conflict = authoringReducer(edited, { type: 'externalChanged', ...external })
    const blocked = authoringReducer(conflict, { type: 'rebase', ...external })
    expect(blocked.documents).toBe(edited.documents)
    expect(blocked.history).toBe(edited.history)
    expect(blocked.conflict?.overlapping).toEqual(['layout.mantra'])
    expect(blocked.baseRevisions).toEqual(revisions)
    expect(canSave(blocked)).toBe(false)
  })

  it('refreshes a clean draft and blocks undo history that overlaps newly changed source', () => {
    const edited = edit(initial(), 'Initial title', 'My title', 'layout.mantra')
    const clean = authoringReducer(edited, { type: 'undo' })
    const refreshed = authoringReducer(clean, { type: 'externalChanged', ...external })
    expect(refreshed.documents).toEqual(external.documents)
    expect(refreshed.conflict).toBeUndefined()
    expect(canRedo(refreshed)).toBe(false)
    expect(refreshed.history.future[0].blockedReason).toContain('source changed outside')
    expect(authoringReducer(refreshed, { type: 'redo' })).toBe(refreshed)
  })

  it('does not let a save acknowledgement clear an external conflict observed during the request', () => {
    const ready = receive(edit(initial()))
    const saving = authoringReducer(ready, { type: 'saveStarted' })
    const conflict = authoringReducer(saving, { type: 'externalChanged', ...external })
    expect(conflict.documents).toBe(ready.documents)
    const acknowledgement = authoringReducer(conflict, {
      type: 'saveSucceeded',
      draftSequence: saving.draftSequence,
      baseRevisions: { ...revisions, 'source.mantra': 'saved' },
    })
    expect(acknowledgement).toBe(conflict)
    expect(saveCapsule(acknowledgement).kind).toBe('conflict')
  })

  it('retains draft and history after a commit conflict, technical rejection and network failure', () => {
    const ready = receive(edit(initial()))
    const saving = authoringReducer(ready, { type: 'saveStarted' })
    const conflict = authoringReducer(saving, {
      type: 'saveFailed',
      draftSequence: saving.draftSequence,
      kind: 'conflict',
      conflict: external,
    })
    expect(conflict.documents).toBe(ready.documents)
    expect(conflict.history).toBe(ready.history)
    expect(conflict.conflict).toEqual(external)
    expect(conflict.preview.status).toBe('previous')
    expect(previewStatus(conflict)).toBe('Preview based on previous source revisions (conflict)')
    const invalid = authoringReducer(saving, {
      type: 'saveFailed',
      draftSequence: saving.draftSequence,
      kind: 'invalid',
      diagnostics: [diagnostic],
    })
    expect(invalid.documents).toBe(ready.documents)
    expect(invalid.history).toBe(ready.history)
    expect(invalid.validity).toBe('invalid')
    expect(canSave(invalid)).toBe(false)
    const offline = authoringReducer(saving, {
      type: 'saveFailed',
      draftSequence: saving.draftSequence,
      kind: 'offline',
    })
    expect(offline.documents).toBe(ready.documents)
    expect(offline.connection).toBe('offline')
    expect(offline.saved).toBe(false)
    expect(canSave(offline)).toBe(false)
  })

  it.each(['valid', 'runtimeFailure'] as const)(
    'keeps restored source unvalidated until a matching technically valid %s preview',
    (kind) => {
      const recoveredDocuments = edit(initial()).documents
      const recovered = authoringReducer(initial(), { type: 'restore', documents: recoveredDocuments })
      expect(recovered.documents).toEqual(recoveredDocuments)
      expect(saveCapsule(recovered).kind).toBe('restored')
      expect(canSave(recovered)).toBe(false)
      const stale = authoringReducer(recovered, { type: 'previewReceived', result: preview(0, kind) })
      expect(stale).toBe(recovered)
      const wrongBase = authoringReducer(recovered, {
        type: 'previewReceived',
        result: preview(recovered.draftSequence, kind, external.baseRevisions),
      })
      expect(wrongBase).toBe(recovered)
      for (const rejectedKind of ['invalid', 'unrecorded'] as const) {
        const notValidated = receive(recovered, rejectedKind)
        expect(saveCapsule(notValidated).kind).toBe('restored')
        expect(canSave(notValidated)).toBe(false)
      }
      const validated = receive(recovered, kind)
      expect(validated.restored).toBe(false)
      expect(validated.validity).toBe(kind)
      expect(saveCapsule(validated).kind).toBe('unsaved')
      expect(canSave(validated)).toBe(true)
      const validatedPreview = currentPreview(validated)
      const previousValid = validated.preview.previousValid
      const lateInvalid = authoringReducer(validated, { type: 'previewReceived', result: preview(0, 'invalid') })
      expect(lateInvalid).toBe(validated)
      expect(lateInvalid.restored).toBe(false)
      expect(currentPreview(lateInvalid)).toBe(validatedPreview)
      expect(lateInvalid.preview.previousValid).toBe(previousValid)
      expect(canSave(lateInvalid)).toBe(true)
    },
  )

  it('uses the required capsule priority and preserves edits while read-only', () => {
    const recovered = authoringReducer(initial(), { type: 'restore', documents: edit(initial()).documents })
    const invalid = receive(recovered, 'invalid')
    expect(saveCapsule(invalid).kind).toBe('restored')
    const conflict = authoringReducer(invalid, { type: 'externalChanged', ...external })
    expect(saveCapsule(conflict).kind).toBe('conflict')
    const readonly = authoringReducer(conflict, { type: 'accessChanged', readOnlyReason: 'Captured package' })
    expect(saveCapsule(readonly).kind).toBe('readonly')
    expect(saveReason(readonly)).toContain('Captured package')
    expect(edit(readonly, 'Edited label', 'Forbidden')).toBe(readonly)
    expect(authoringReducer(readonly, { type: 'undo' })).toBe(readonly)
  })
})
