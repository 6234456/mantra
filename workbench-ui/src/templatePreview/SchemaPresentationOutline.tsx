import { useMemo, useState } from 'react'
import type { SchemaPropertyOwner } from './schemaPropertiesClient'
import './SchemaPresentationOutline.css'

export function schemaPropertyIdentity(owner: SchemaPropertyOwner): string {
  return JSON.stringify([owner.document, owner.path, owner.form, owner.node, owner.property])
}

interface Declaration {
  key: string
  path: number[]
  owners: SchemaPropertyOwner[]
  children: Declaration[]
}

interface DocumentOutline {
  document: string
  declarations: Declaration[]
}

function comparePaths(left: number[], right: number[]): number {
  for (let index = 0; index < Math.min(left.length, right.length); index++) {
    if (left[index] !== right[index]) return left[index] - right[index]
  }
  return left.length - right.length
}

/** Paths come from the schema reader. Never infer declarations from source text or Paper rows. */
export function schemaPresentationOutline(owners: SchemaPropertyOwner[]): DocumentOutline[] {
  const documents = new Map<string, Map<string, Declaration>>()
  for (const owner of owners) {
    let declarations = documents.get(owner.document)
    if (!declarations) {
      declarations = new Map()
      documents.set(owner.document, declarations)
    }
    const key = JSON.stringify(owner.path)
    let declaration = declarations.get(key)
    if (!declaration) {
      declaration = { key, path: owner.path, owners: [], children: [] }
      declarations.set(key, declaration)
    }
    declaration.owners.push(owner)
  }
  return [...documents].map(([document, declarations]) => {
    const roots: Declaration[] = []
    for (const declaration of [...declarations.values()].sort((a, b) => comparePaths(a.path, b.path))) {
      let parent: Declaration | undefined
      for (let length = declaration.path.length - 1; length > 0; length--) {
        parent = declarations.get(JSON.stringify(declaration.path.slice(0, length)))
        if (parent) break
      }
      ;(parent?.children ?? roots).push(declaration)
    }
    return { document, declarations: roots }
  })
}

function searchable(declaration: Declaration): string {
  const first = declaration.owners[0]
  return [
    first.document,
    first.form,
    first.node ?? 'note',
    ...declaration.owners.map(
      (owner) => `${owner.property} ${typeof owner.value === 'string' ? owner.value : JSON.stringify(owner.value)}`,
    ),
  ]
    .join(' ')
    .toLocaleLowerCase()
}

function filterDeclarations(declarations: Declaration[], query: string): Declaration[] {
  if (!query) return declarations
  return declarations.flatMap((declaration) => {
    // Keep all properties of a matching declaration, and ancestor context for matching descendants.
    const matches = searchable(declaration).includes(query)
    const children = filterDeclarations(declaration.children, query)
    return matches || children.length ? [{ ...declaration, children }] : []
  })
}

function DeclarationList({
  declarations,
  selected,
  disabled,
  onSelect,
}: {
  declarations: Declaration[]
  selected: string
  disabled: boolean
  onSelect: (owner: SchemaPropertyOwner) => void
}) {
  return (
    <ol>
      {declarations.map((declaration) => {
        const first = declaration.owners[0]
        const text = declaration.owners.find((owner) => owner.type === 'text')?.value
        return (
          <li key={declaration.key}>
            <div className="schema-declaration-heading">
              <strong>
                {first.form}
                {first.node ? ` ${first.node}` : ''}
              </strong>
              <span>
                line {first.declarationRange.line} · path {first.path.join('.')}
              </span>
              {!first.editable && <span>Read-only</span>}
            </div>
            {typeof text === 'string' && (
              <p className="schema-declaration-text">
                {text.length > 160 ? `${text.slice(0, 160)}…` : text || '(empty text)'}
              </p>
            )}
            <div className="schema-declaration-properties">
              {declaration.owners.map((owner) => (
                <button
                  type="button"
                  key={owner.property}
                  disabled={disabled}
                  aria-pressed={selected === schemaPropertyIdentity(owner)}
                  aria-label={`Select ${owner.property} of ${owner.form}${owner.node ? ` ${owner.node}` : ''} in ${owner.document} at line ${owner.declarationRange.line}, path ${owner.path.join('.')}`}
                  onClick={() => onSelect(owner)}
                >
                  {owner.property}
                  {owner.property === 'classes' && !owner.declared ? ' (absent)' : ''}
                </button>
              ))}
            </div>
            {declaration.children.length > 0 && (
              <DeclarationList
                declarations={declaration.children}
                selected={selected}
                disabled={disabled}
                onSelect={onSelect}
              />
            )}
          </li>
        )
      })}
    </ol>
  )
}

export function SchemaPresentationOutline({
  owners,
  selection,
  current,
  busy,
  editing,
  onSelect,
}: {
  owners: SchemaPropertyOwner[]
  selection: string
  current: boolean
  busy: boolean
  editing: boolean
  onSelect: (owner: SchemaPropertyOwner) => void
}) {
  const [filter, setFilter] = useState('')
  const documents = useMemo(() => schemaPresentationOutline(owners), [owners])
  const query = filter.trim().toLocaleLowerCase()
  const visible = documents
    .map((document) => ({ ...document, declarations: filterDeclarations(document.declarations, query) }))
    .filter((document) => document.declarations.length > 0)
  return (
    <nav className="schema-presentation-outline" aria-label="Schema presentation outline">
      <h3>Presentation outline</h3>
      <p>
        Source declarations grouped by document and declaration path. Visibility, values and final styles require an
        engine preview.
      </p>
      <label>
        Find a declaration
        <input
          aria-label="Find a declaration"
          type="search"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
      </label>
      {editing && <p>Apply or cancel the current fields before selecting another property.</p>}
      {!current && <p>The outline is from an earlier source draft. Refresh schema properties to navigate.</p>}
      {!owners.length ? (
        <p>No presentation declarations were returned.</p>
      ) : !visible.length ? (
        <p role="status">No declarations match this search.</p>
      ) : (
        visible.map((document) => (
          <section key={document.document} aria-label={`Declarations in ${document.document}`}>
            <h4>{document.document}</h4>
            <DeclarationList
              declarations={document.declarations}
              selected={selection}
              disabled={!current || busy || editing}
              onSelect={onSelect}
            />
          </section>
        ))
      )}
    </nav>
  )
}
