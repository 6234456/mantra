import { expect, it } from 'vitest'
import { emptySourceHistory, recordSourceChange, SOURCE_HISTORY_LIMIT, stepSourceHistory } from './sourceHistory'

it('restores exact Unicode, CRLF, comments and empty source text across multiple document handles', () => {
  const original = { schema: '; 中文😀\r\n(schema original)', layout: '(layout original)\r\n', included: 'untouched' }
  const first = {
    handle: 'schema',
    before: original.schema,
    after: '; 编辑😀\r\n(schema edited)',
    label: 'Edit schema',
  }
  const second = { handle: 'layout', before: original.layout, after: '', label: 'Edit layout' }
  const history = recordSourceChange(recordSourceChange(emptySourceHistory(), first), second)
  const documents = { ...original, schema: first.after, layout: second.after }
  const undoLayout = stepSourceHistory(history, 'undo', documents)!
  expect(undoLayout.documents).toEqual({ ...original, schema: first.after })
  const undoSchema = stepSourceHistory(undoLayout.history, 'undo', undoLayout.documents)!
  expect(undoSchema.documents).toEqual(original)
  const redoSchema = stepSourceHistory(undoSchema.history, 'redo', undoSchema.documents)!
  const redoLayout = stepSourceHistory(redoSchema.history, 'redo', redoSchema.documents)!
  expect(redoLayout.documents).toEqual(documents)
  expect(history.past).toEqual([first, second])
  expect(history.future).toEqual([])
})

it('rejects undo and redo when the current handle text is missing or no longer matches the transaction', () => {
  const history = recordSourceChange(emptySourceHistory(), {
    handle: 'layout',
    before: 'before',
    after: 'after',
    label: 'Apply settings',
  })
  expect(stepSourceHistory(history, 'undo', {})).toBeUndefined()
  expect(stepSourceHistory(history, 'undo', { layout: 'new source' })).toBeUndefined()
  const undone = stepSourceHistory(history, 'undo', { layout: 'after' })!
  expect(stepSourceHistory(undone.history, 'redo', { layout: 'new source' })).toBeUndefined()
  expect(stepSourceHistory(emptySourceHistory(), 'undo', {})).toBeUndefined()
  expect(stepSourceHistory(emptySourceHistory(), 'redo', {})).toBeUndefined()
})

it('retains a redo branch after no-op source acceptance and clears it only when a different edit is accepted', () => {
  const initial = recordSourceChange(emptySourceHistory(), {
    handle: 'layout',
    before: 'original',
    after: 'changed',
    label: 'Apply style',
  })
  const undone = stepSourceHistory(initial, 'undo', { layout: 'changed' })!
  expect(
    recordSourceChange(undone.history, { handle: 'layout', before: 'original', after: 'original', label: 'No change' }),
  ).toBe(undone.history)
  const branch = recordSourceChange(undone.history, {
    handle: 'layout',
    before: 'original',
    after: 'different',
    label: 'New edit',
  })
  expect(branch.future).toEqual([])
  expect(stepSourceHistory(branch, 'redo', { layout: 'different' })).toBeUndefined()
})

it('keeps the latest fifty source transactions without retaining caller-owned transaction objects', () => {
  let history = emptySourceHistory()
  const transaction = { handle: 'layout', before: '0', after: '1', label: 'Edit 1' }
  history = recordSourceChange(history, transaction)
  transaction.after = 'caller changed this object'
  expect(history.past[0].after).toBe('1')
  for (let index = 2; index <= SOURCE_HISTORY_LIMIT + 5; index++) {
    history = recordSourceChange(history, {
      handle: 'layout',
      before: String(index - 1),
      after: String(index),
      label: `Edit ${index}`,
    })
  }
  expect(history.past).toHaveLength(50)
  expect(history.past[0].before).toBe('5')
  let documents: Record<string, string> = { layout: '55' }
  for (let index = 0; index < SOURCE_HISTORY_LIMIT; index++) {
    const next = stepSourceHistory(history, 'undo', documents)!
    history = next.history
    documents = next.documents
  }
  expect(documents.layout).toBe('5')
  expect(stepSourceHistory(history, 'undo', documents)).toBeUndefined()
  expect(history.future).toHaveLength(50)
})
