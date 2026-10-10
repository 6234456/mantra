import type { SourceHistory, SourceTransaction } from './model'

export interface SourceDiffHunk {
  beforeStart: number
  afterStart: number
  removed: string[]
  added: string[]
  operations: string[]
}

interface LineChange {
  beforeStart: number
  afterStart: number
  removed: string[]
  added: string[]
}

/** Keep line endings in comparisons: CRLF and a missing final newline are source changes too. */
function lines(source: string): string[] {
  return source.match(/[^\n]*\n|[^\n]+$/g) ?? []
}

function scores(left: string[], right: string[]): Uint32Array {
  let previous = new Uint32Array(right.length + 1)
  for (const line of left) {
    const current = new Uint32Array(right.length + 1)
    for (let column = 0; column < right.length; column++) {
      current[column + 1] =
        line === right[column] ? previous[column] + 1 : Math.max(previous[column + 1], current[column])
    }
    previous = current
  }
  return previous
}

/** Hirschberg matching avoids a document-wide quadratic matrix and matches repeated lines deterministically. */
function matchingLines(left: string[], right: string[], leftOffset = 0, rightOffset = 0): Array<[number, number]> {
  if (!left.length || !right.length) return []
  if (left.length === 1) {
    const index = right.indexOf(left[0])
    return index < 0 ? [] : [[leftOffset, rightOffset + index]]
  }
  const middle = Math.floor(left.length / 2)
  const forward = scores(left.slice(0, middle), right)
  const backward = scores(left.slice(middle).reverse(), [...right].reverse())
  let split = 0
  for (let index = 1; index <= right.length; index++) {
    if (forward[index] + backward[right.length - index] > forward[split] + backward[right.length - split]) {
      split = index
    }
  }
  return [
    ...matchingLines(left.slice(0, middle), right.slice(0, split), leftOffset, rightOffset),
    ...matchingLines(left.slice(middle), right.slice(split), leftOffset + middle, rightOffset + split),
  ]
}

function lineChanges(before: string[], after: string[]): LineChange[] {
  let prefix = 0
  while (prefix < before.length && prefix < after.length && before[prefix] === after[prefix]) prefix++
  let beforeEnd = before.length
  let afterEnd = after.length
  while (beforeEnd > prefix && afterEnd > prefix && before[beforeEnd - 1] === after[afterEnd - 1]) {
    beforeEnd--
    afterEnd--
  }
  const left = before.slice(prefix, beforeEnd)
  const right = after.slice(prefix, afterEnd)
  const shared = new Set(left)
  const matches = right.some((line) => shared.has(line)) ? matchingLines(left, right, prefix, prefix) : []
  const changes: LineChange[] = []
  let beforeStart = prefix
  let afterStart = prefix
  for (const [leftIndex, rightIndex] of [...matches, [beforeEnd, afterEnd]]) {
    if (leftIndex !== beforeStart || rightIndex !== afterStart) {
      changes.push({
        beforeStart,
        afterStart,
        removed: before.slice(beforeStart, leftIndex),
        added: after.slice(afterStart, rightIndex),
      })
    }
    beforeStart = leftIndex + 1
    afterStart = rightIndex + 1
  }
  return changes
}

function transactionsAfterBase(
  document: string,
  before: string,
  after: string,
  history: SourceHistory | undefined,
): SourceTransaction[] | undefined {
  if (!history) return undefined
  const chains = [
    history.past,
    history.future.map((transaction) => ({
      ...transaction,
      label: `Undo · ${transaction.label}`,
      before: transaction.after,
      after: transaction.before,
    })),
  ]
  for (const chain of chains) {
    const transactions = chain.filter((transaction) => transaction.before[document] !== transaction.after[document])
    let first = -1
    for (let index = 0; index < transactions.length; index++) {
      if (transactions[index].before[document] === before) first = index
      if (transactions[index].after[document] === before) first = index + 1
    }
    if (first < 0) continue
    const result: SourceTransaction[] = []
    let source = before
    for (const transaction of transactions.slice(first)) {
      if (source === after || transaction.before[document] !== source) break
      result.push(transaction)
      source = transaction.after[document]
    }
    if (source === after) return result
  }
  return undefined
}

/** Attribute only the reachable history after this saved/rebased baseline, rather than every retained undo entry. */
export function sourceDiffHunks(
  document: string,
  before: string,
  after: string,
  history?: SourceHistory,
  fallbackOperation = 'Source edit · history unavailable',
): SourceDiffHunk[] {
  const beforeLines = lines(before)
  const afterLines = lines(after)
  const changes = lineChanges(beforeLines, afterLines)
  const transactions = transactionsAfterBase(document, before, after, history)
  if (!transactions) return changes.map((change) => ({ ...change, operations: [fallbackOperation] }))

  let tracked = beforeLines.map((_line, index) => ({ base: new Set([index]), labels: new Set<string>() }))
  const deleted = beforeLines.map(() => new Set<string>())
  for (const transaction of transactions) {
    const next = [...tracked]
    const edits = lineChanges(lines(transaction.before[document]), lines(transaction.after[document]))
    for (const edit of [...edits].reverse()) {
      const removed = tracked.slice(edit.beforeStart, edit.beforeStart + edit.removed.length)
      const base = new Set(removed.flatMap((line) => [...line.base]))
      const labels = new Set([...removed.flatMap((line) => [...line.labels]), transaction.label])
      for (const index of base) for (const label of labels) deleted[index].add(label)
      next.splice(
        edit.beforeStart,
        edit.removed.length,
        ...edit.added.map(() => ({ base: new Set(base), labels: new Set(labels) })),
      )
    }
    tracked = next
  }
  const operationOrder = [...new Set(transactions.map((transaction) => transaction.label))]
  return changes.map((change) => {
    const labels = new Set([
      ...deleted.slice(change.beforeStart, change.beforeStart + change.removed.length).flatMap((set) => [...set]),
      ...tracked.slice(change.afterStart, change.afterStart + change.added.length).flatMap((line) => [...line.labels]),
    ])
    const operations = operationOrder.filter((label) => labels.has(label))
    return { ...change, operations: operations.length ? operations : [fallbackOperation] }
  })
}
