import { expect, it } from 'vitest'
import type { SourceHistory, SourceTransaction } from './model'
import { sourceDiffHunks } from './sourceDiff'

const document = 'source.mantra'

function transaction(label: string, before: string, after: string): SourceTransaction {
  return { label, before: { [document]: before }, after: { [document]: after }, patches: [], ownerHandles: [] }
}

function history(...past: SourceTransaction[]): SourceHistory {
  return { past, future: [] }
}

it('separates label and class edits without marking intervening unchanged lines as removed or added', () => {
  const base = '(info value "Original label" 1)\n; untouched comment\n(def sibling 2)\n:class :subtotal\n'
  const label = base.replace('Original label', 'Updated label')
  const classes = label.replace(':subtotal', ':result')
  const hunks = sourceDiffHunks(
    document,
    base,
    classes,
    history(transaction('Label · value', base, label), transaction('Classes · value', label, classes)),
  )
  expect(hunks).toEqual([
    {
      beforeStart: 0,
      afterStart: 0,
      removed: ['(info value "Original label" 1)\n'],
      added: ['(info value "Updated label" 1)\n'],
      operations: ['Label · value'],
    },
    {
      beforeStart: 3,
      afterStart: 3,
      removed: [':class :subtotal\n'],
      added: [':class :result\n'],
      operations: ['Classes · value'],
    },
  ])
})

it('matches repeated unchanged lines around a single inserted line', () => {
  const base = 'header\nrepeated\nrepeated\nfooter\n'
  const draft = 'header\nrepeated\ninserted\nrepeated\nfooter\n'
  expect(sourceDiffHunks(document, base, draft)).toEqual([
    {
      beforeStart: 2,
      afterStart: 2,
      removed: [],
      added: ['inserted\n'],
      operations: ['Source edit · history unavailable'],
    },
  ])
})

it('keeps line locations correct after an earlier deletion', () => {
  const base = 'before\nremove this\nmiddle\nold\nafter\n'
  const draft = 'before\nmiddle\nnew\nafter\n'
  expect(
    sourceDiffHunks(document, base, draft).map(({ beforeStart, afterStart, removed, added }) => ({
      beforeStart,
      afterStart,
      removed,
      added,
    })),
  ).toEqual([
    { beforeStart: 1, afterStart: 1, removed: ['remove this\n'], added: [] },
    { beforeStart: 3, afterStart: 2, removed: ['old\n'], added: ['new\n'] },
  ])
})

it('recognizes CRLF and final-newline changes without discarding source bytes', () => {
  expect(sourceDiffHunks(document, 'one\r\ntwo', 'one\ntwo\n')[0]).toMatchObject({
    removed: ['one\r\n', 'two'],
    added: ['one\n', 'two\n'],
  })
  expect(sourceDiffHunks(document, '', 'new\n')[0]).toMatchObject({ removed: [], added: ['new\n'] })
  expect(sourceDiffHunks(document, 'old\n', '')[0]).toMatchObject({ removed: ['old\n'], added: [] })
  expect(sourceDiffHunks(document, 'same\n', 'same\n')).toEqual([])
})

it('attributes only edits after a saved baseline even though older undo history is retained', () => {
  const original = 'Original label\n:class :subtotal\n'
  const saved = original.replace('Original label', 'Saved label')
  const draft = saved.replace(':subtotal', ':result')
  const hunks = sourceDiffHunks(
    document,
    saved,
    draft,
    history(transaction('Earlier saved label', original, saved), transaction('Current classes', saved, draft)),
  )
  expect(hunks).toHaveLength(1)
  expect(hunks[0].operations).toEqual(['Current classes'])
  expect(hunks[0].removed).toEqual([':class :subtotal\n'])
})

it('attributes an undo across the saved baseline from its inverse history instead of an unrelated redo entry', () => {
  const original = 'original\n'
  const saved = 'saved\n'
  const later = 'later\n'
  const sourceHistory: SourceHistory = {
    past: [],
    future: [transaction('Later edit', saved, later), transaction('Saved label', original, saved)],
  }
  const hunks = sourceDiffHunks(document, saved, original, sourceHistory)
  expect(hunks[0].operations).toEqual(['Undo · Saved label'])
  expect(hunks[0].removed).toEqual(['saved\n'])
  expect(hunks[0].added).toEqual(['original\n'])
})

it('attributes reachable edits after an external rebase without including archived history', () => {
  const previous = 'old external header\nlabel\n'
  const base = 'new external header\nlabel\n'
  const draft = base.replace('label\n', 'draft label\n')
  const archived = {
    ...transaction('Archived source edit', previous, previous.replace('label', 'archived')),
    blockedReason: 'External edit',
  }
  const hunks = sourceDiffHunks(document, base, draft, history(archived, transaction('New label', base, draft)))
  expect(hunks[0].operations).toEqual(['New label'])
  expect(hunks[0].removed).toEqual(['label\n'])
  expect(hunks[0].added).toEqual(['draft label\n'])
})

it('keeps both contributing operation labels when separate properties on one line are edited', () => {
  const base = '(info value "Label" 1 :class :subtotal)\n'
  const label = base.replace('Label', 'Revised')
  const draft = label.replace(':subtotal', ':result')
  const hunks = sourceDiffHunks(
    document,
    base,
    draft,
    history(transaction('Label edit', base, label), transaction('Class edit', label, draft)),
  )
  expect(hunks).toHaveLength(1)
  expect(hunks[0].operations).toEqual(['Label edit', 'Class edit'])
})

it('handles raw source transactions with multiple separated edits and ignores redo-only operations', () => {
  const base = 'old top\nkeep\nold bottom\n'
  const draft = 'new top\nkeep\nnew bottom\n'
  const sourceHistory = history(transaction('Source edit · document', base, draft))
  sourceHistory.future = [transaction('Undone edit', draft, draft.replace('keep', 'changed'))]
  const hunks = sourceDiffHunks(document, base, draft, sourceHistory)
  expect(hunks).toHaveLength(2)
  expect(hunks.map((hunk) => hunk.operations)).toEqual([['Source edit · document'], ['Source edit · document']])
})

it('does not attach unrelated operation labels when a history chain is unavailable', () => {
  const hunks = sourceDiffHunks(
    document,
    'base\n',
    'external\n',
    history(transaction('Unrelated label', 'different base\n', 'different draft\n')),
    'External source edit (simulated)',
  )
  expect(hunks[0].operations).toEqual(['External source edit (simulated)'])
})
