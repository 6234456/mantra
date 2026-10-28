export interface SourceRange {
  startOffset: number
  endOffset: number
}

/** API positions use raw UTF-16 source. HTML textarea values normalize CRLF and bare CR to LF. */
export function textareaSourceRange(text: string, range: SourceRange): SourceRange | undefined {
  if (
    !Number.isSafeInteger(range.startOffset) ||
    !Number.isSafeInteger(range.endOffset) ||
    range.startOffset < 0 ||
    range.endOffset <= range.startOffset ||
    range.endOffset > text.length
  )
    return
  const offset = (index: number) => text.slice(0, index).replace(/\r\n?/g, '\n').length
  return { startOffset: offset(range.startOffset), endOffset: offset(range.endOffset) }
}
