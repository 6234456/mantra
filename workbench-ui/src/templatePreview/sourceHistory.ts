export interface SourceTransaction {
  handle: string
  before: string
  after: string
  label: string
}

export interface SourceHistory {
  past: readonly SourceTransaction[]
  future: readonly SourceTransaction[]
}

export const SOURCE_HISTORY_LIMIT = 50

export function emptySourceHistory(): SourceHistory {
  return { past: [], future: [] }
}

/** Source history contains exact accepted text, without interpreting the DSL or calculated values. */
export function recordSourceChange(history: SourceHistory, transaction: SourceTransaction): SourceHistory {
  if (transaction.before === transaction.after) return history
  return { past: [...history.past, { ...transaction }].slice(-SOURCE_HISTORY_LIMIT), future: [] }
}

/** A history entry can only restore the exact source version from which the transaction was recorded. */
export function stepSourceHistory(
  history: SourceHistory,
  direction: 'undo' | 'redo',
  documents: Readonly<Record<string, string>>,
): { history: SourceHistory; documents: Record<string, string>; transaction: SourceTransaction } | undefined {
  const transaction = (direction === 'undo' ? history.past : history.future).at(-1)
  if (!transaction || documents[transaction.handle] !== transaction[direction === 'undo' ? 'after' : 'before']) return
  return {
    transaction,
    documents: { ...documents, [transaction.handle]: transaction[direction === 'undo' ? 'before' : 'after'] },
    history:
      direction === 'undo'
        ? { past: history.past.slice(0, -1), future: [...history.future, transaction] }
        : { past: [...history.past, transaction], future: history.future.slice(0, -1) },
  }
}
