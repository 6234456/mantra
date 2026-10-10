import type {
  ApplyResult,
  DocumentTexts,
  OwnerProperty,
  SemanticOperation,
  SourceOutline,
  SourceOwner,
} from '../service'
import type { Diagnostic } from '../../types'

interface Expression {
  start: number
  end: number
  text: string
  kind: 'atom' | 'string' | '(' | '[' | '{'
  children: Expression[]
}

/** Limited, simulated source scanner; quoted strings and comments never become declarations. */
export function parseSource(text: string): Expression[] {
  let cursor = 0
  const skip = () => {
    while (cursor < text.length) {
      if (/\s/.test(text[cursor])) cursor++
      else if (text[cursor] === ';') {
        while (cursor < text.length && text[cursor] !== '\n') cursor++
      } else break
    }
  }
  const read = (): Expression => {
    skip()
    const start = cursor
    const character = text[cursor++]
    if (character === '"') {
      let closed = false
      while (cursor < text.length) {
        const next = text[cursor++]
        if (next === '\\') cursor++
        else if (next === '"') {
          closed = true
          break
        }
      }
      if (!closed) throw new Error('Unclosed source string')
      return {
        start,
        end: Math.min(cursor, text.length),
        text: text.slice(start, cursor),
        kind: 'string',
        children: [],
      }
    }
    if (character === '(' || character === '[' || character === '{') {
      const closing = character === '(' ? ')' : character === '[' ? ']' : '}'
      const children: Expression[] = []
      skip()
      while (cursor < text.length && text[cursor] !== closing) {
        if (/[)\]}]/.test(text[cursor])) throw new Error('Mismatched source delimiter')
        children.push(read())
        skip()
      }
      if (text[cursor] !== closing) throw new Error('Unclosed source delimiter')
      cursor++
      return { start, end: cursor, text: text.slice(start, cursor), kind: character, children }
    }
    if (/[)\]}]/.test(character)) throw new Error('Unexpected source delimiter')
    while (cursor < text.length && !/[\s()[\]{}";]/.test(text[cursor])) cursor++
    return { start, end: cursor, text: text.slice(start, cursor), kind: 'atom', children: [] }
  }
  const result: Expression[] = []
  skip()
  while (cursor < text.length) {
    result.push(read())
    skip()
  }
  return result
}

function stringValue(expression: Expression) {
  return expression.text.slice(1, -1).replace(/\\([\\"nrt])/g, (_match, escaped: string) => {
    return escaped === 'n' ? '\n' : escaped === 'r' ? '\r' : escaped === 't' ? '\t' : escaped
  })
}

function option(map: Expression | undefined, key: string) {
  if (map?.kind !== '{') return undefined
  const position = map.children.findIndex((child) => child.text === key)
  return position >= 0 ? map.children[position + 1] : undefined
}

function ownerValue(expression: Expression, property: OwnerProperty) {
  if (expression.kind === 'string') return stringValue(expression)
  if (property === 'classes') {
    return (expression.kind === '[' ? expression.children : [expression]).map((child) => child.text.replace(/^:/, ''))
  }
  if (property === 'precision') return Number(expression.text)
  if (property === 'hide-zero') return expression.text === 'true'
  return expression.text
}

export function createSourceOwnerScanner() {
  const handles = new Map<string, string>()
  let nextHandle = 0
  const scan = (documents: DocumentTexts): SourceOwner[] => {
    const owners: SourceOwner[] = []
    for (const [document, text] of Object.entries(documents)) {
      let expressions: Expression[]
      try {
        expressions = parseSource(text)
      } catch {
        // An invalid raw draft has no trustworthy write handles until its syntax is restored.
        continue
      }
      const rootKind = expressions[0]?.children[0]?.text
      const declarations = new Map<string, number>()
      const visit = (expression: Expression, panelId?: string) => {
        if (expression.kind !== '(') return
        const [head, identity, label] = expression.children
        const kind = head?.text ?? ''
        const nodeId = identity?.kind === 'atom' ? identity.text : undefined
        const declarationKey = JSON.stringify([document, panelId ?? null, kind, nodeId ?? null])
        const ordinal = declarations.get(declarationKey) ?? 0
        declarations.set(declarationKey, ordinal + 1)
        const displayLabel = label?.kind === 'string' ? stringValue(label) : undefined
        const declaration = `(${kind}${nodeId ? ` ${nodeId}` : ''} …)`
        const add = (property: OwnerProperty, value: Expression | undefined, editable: boolean, reason?: string) => {
          if (!value) return
          const descriptor = JSON.stringify([declarationKey, ordinal, property])
          let handle = handles.get(descriptor)
          if (!handle) {
            handle = `sim-owner-${++nextHandle}`
            handles.set(descriptor, handle)
          }
          owners.push({
            handle,
            document,
            declaration,
            declarationRange: { start: expression.start, end: expression.end },
            property,
            kind,
            nodeId,
            panelId,
            label: displayLabel,
            range: { start: value.start, end: value.end },
            editable,
            reason,
            value: ownerValue(value, property),
            channel: rootKind === 'case' ? 'Example input' : 'Template definition',
          })
        }
        if (kind === 'section') add('section-title', label?.kind === 'string' ? label : undefined, true)
        if (['info', 'line', 'total', 'check', 'reconcile'].includes(kind)) {
          add('label', label?.kind === 'string' ? label : undefined, true)
          const expressionValue = expression.children[3]
          if (['info', 'line', 'total'].includes(kind)) add('formula', expressionValue, true)
          if (kind === 'check')
            add('condition', expressionValue, false, 'Not editable in this version (check condition)')
          if (kind === 'reconcile') {
            add('left-operand', expressionValue, false, 'Not editable in this version (reconcile operand)')
            add('right-operand', expression.children[4], false, 'Not editable in this version (reconcile operand)')
          }
          const map = expression.children.findLast((child) => child.kind === '{')
          add('classes', option(map, ':class'), true)
        }
        if (kind === 'note') {
          add('note', identity?.kind === 'string' ? identity : undefined, true)
          add('classes', option(expression.children[2], ':class'), true)
        }
        if (kind === 'layout' || kind === 'table') {
          const map = expression.children.find((child) => child.kind === '{')
          add('title', option(map, ':title'), true)
          add('precision', option(map, ':precision'), true)
          add('hide-zero', option(map, ':hide-zero'), true)
          if (kind === 'table') {
            add('table-style', option(map, ':style'), false, 'Not editable in this version (table style)')
            for (const child of expression.children.slice(map ? 3 : 2)) {
              if (child.kind === 'atom' && child.text.startsWith(':')) {
                add('columns', child, false, 'Not editable in this version (table columns)')
                break
              }
            }
          }
        }
        if (kind === 'defn') add('declaration', expression, false, 'Not editable in this version (shared declaration)')
        if (kind === 'case')
          add('declaration', expression, false, 'Example inputs use the separate case editing channel')
        const childPanel = kind === 'section' ? nodeId : panelId
        if (['schema', 'section', 'layout', 'fragment'].includes(kind)) {
          for (const child of expression.children) if (child.kind === '(') visit(child, childPanel)
        }
      }
      for (const expression of expressions) visit(expression)
    }
    return owners
  }
  return { scan }
}

/** Source ranges, rather than displayed labels or amounts, decide diagnostic ownership. */
export function ownerForDiagnostic(owners: SourceOwner[], diagnostic: Diagnostic): SourceOwner | undefined {
  const location = diagnostic.location
  if (!location || location.startOffset === undefined) return undefined
  const start = location.startOffset
  const end = location.endOffset ?? start
  const candidates = owners.filter(
    (owner) => location.document === owner.document || location.document.endsWith(`/${owner.document}`),
  )
  const containing = candidates.filter((owner) => owner.range.start <= start && owner.range.end >= end)
  if (containing.length)
    return containing.sort(
      (left, right) => left.range.end - left.range.start - (right.range.end - right.range.start),
    )[0]
  return candidates.find((owner) => owner.declarationRange.start <= start && owner.declarationRange.end >= end)
}

export function sourceOutline(owners: SourceOwner[]): SourceOutline[] {
  const items = owners.filter((owner) =>
    ['section-title', 'label', 'note', 'declaration', 'title'].includes(owner.property),
  )
  const result: SourceOutline[] = []
  for (const owner of items) {
    const entry: SourceOutline = {
      handle: owner.handle,
      document: owner.document,
      label: typeof owner.value === 'string' ? owner.value : owner.declaration,
      kind: owner.kind,
      nodeId: owner.nodeId,
      panelId: owner.panelId,
      children: [],
    }
    const parent = result.find(
      (candidate) => candidate.document === owner.document && candidate.nodeId === owner.panelId,
    )
    if (parent) parent.children.push(entry)
    else result.push(entry)
  }
  return result
}

function quoted(value: string) {
  return `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
}

/** Semantic operations resolve current ranges from handles; supplied offsets are always rejected. */
export function applySemanticOperation(
  operation: SemanticOperation,
  documents: DocumentTexts,
  scan: (documents: DocumentTexts) => SourceOwner[],
): ApplyResult {
  if (Object.keys(operation).some((key) => !['handle', 'op', 'value'].includes(key))) {
    return { ok: false, reason: 'A semantic operation accepts an owner handle, never source offsets' }
  }
  const owner = scan(documents).find((candidate) => candidate.handle === operation.handle)
  if (!owner) return { ok: false, reason: 'Owner unavailable: the declaration moved' }
  if (!owner.editable) return { ok: false, reason: owner.reason ?? 'Owner is read-only' }
  const value = operation.value
  let text: string
  if (operation.op === 'setText' && ['label', 'section-title', 'note', 'title'].includes(owner.property)) {
    if (typeof value !== 'string' || /[\r\n]/.test(value) || !value.trim()) {
      return { ok: false, reason: 'Text must be a nonempty single line' }
    }
    text = quoted(value)
  } else if (operation.op === 'setFormula' && owner.property === 'formula') {
    if (typeof value !== 'string' || !value.trim()) return { ok: false, reason: 'Formula must not be empty' }
    text = value
  } else if (operation.op === 'setClasses' && owner.property === 'classes') {
    if (!Array.isArray(value) || !value.length || value.some((name) => !/^[a-z][a-z0-9-]*$/.test(name))) {
      return { ok: false, reason: 'Class names must match [a-z][a-z0-9-]*' }
    }
    text = value.length === 1 ? `:${value[0]}` : `[${value.map((name) => `:${name}`).join(' ')}]`
  } else if (operation.op === 'setLayoutOption' && ['title', 'precision', 'hide-zero'].includes(owner.property)) {
    if (owner.property === 'title' && typeof value === 'string' && value.trim() && !/[\r\n]/.test(value))
      text = quoted(value)
    else if (owner.property === 'precision' && typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) {
      text = String(value)
    } else if (owner.property === 'hide-zero' && typeof value === 'boolean') text = String(value)
    else return { ok: false, reason: 'Layout option value has the wrong type' }
  } else return { ok: false, reason: 'Operation does not match this owner property' }
  const source = documents[owner.document]
  const patch = {
    document: owner.document,
    ...owner.range,
    text,
    inverse: source.slice(owner.range.start, owner.range.end),
  }
  const nextDocuments = {
    ...documents,
    [owner.document]: source.slice(0, owner.range.start) + text + source.slice(owner.range.end),
  }
  return { ok: true, patches: [patch], owners: scan(nextDocuments) }
}
