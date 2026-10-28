import presetCatalog from './stylePresets.json'
import type { DocumentTexts } from './service'
import { parseSource } from './simulated/sourceOwners'

type Expression = ReturnType<typeof parseSource>[number]

export interface StyleClassChoice {
  name: string
  declaration: string
  source: string
  kind: 'preset' | 'local' | 'used'
  hasRule: boolean
}
export interface StyleClassGroup {
  id: string
  title: string
  source: string
  choices: StyleClassChoice[]
}
export interface StyleVocabulary {
  groups: StyleClassGroup[]
  unmatched: string[]
  notices: string[]
}

function option(map: Expression | undefined, key: string) {
  if (map?.kind !== '{') return undefined
  for (let index = 0; index + 1 < map.children.length; index += 2) {
    if (map.children[index].text === key) return map.children[index + 1]
  }
  return undefined
}

function keywords(expression: Expression | undefined): string[] {
  if (!expression) return []
  const expressions = expression.kind === '[' ? expression.children : [expression]
  return expressions
    .filter((item) => item.kind === 'atom' && /^:[a-z][a-z0-9-]*$/.test(item.text))
    .map((item) => item.text.slice(1))
}

function location(document: string, source: string, offset: number) {
  return `${document}:${source.slice(0, offset).split('\n').length}`
}

function declaration(map: Expression | undefined) {
  return (
    ['weight', 'tone', 'fill']
      .flatMap((property) => {
        const value = option(map, `:${property}`)
        return value ? [`${property} ${value.text.replace(/^:/, '')}`] : []
      })
      .join(' · ') || 'No authored weight, tone or fill'
  )
}

/** Read source declarations for the picker; never evaluate selectors or resolved cell styles. */
export function readStyleVocabulary(documents: DocumentTexts, selected: string[] = []): StyleVocabulary {
  const groups: StyleClassGroup[] = []
  const notices: string[] = []
  const knownRules = new Set<string>()
  const used = new Set(selected)
  const usedLocations = new Map<string, string>()
  for (const [document, source] of Object.entries(documents)) {
    let expressions: Expression[]
    try {
      expressions = parseSource(source)
    } catch {
      notices.push(`Class declarations unavailable in invalid source: ${document}`)
      continue
    }
    const walk = (expression: Expression) => {
      if (expression.kind === '{') {
        for (const name of keywords(option(expression, ':class'))) {
          used.add(name)
          if (!usedLocations.has(name)) usedLocations.set(name, location(document, source, expression.start))
        }
      }
      for (const child of expression.children) walk(child)
    }
    for (const expression of expressions) walk(expression)
    for (const layout of expressions.filter((item) => item.kind === '(' && item.children[0]?.text === 'layout')) {
      const options = layout.children.find((item) => item.kind === '{')
      const selection = option(options, ':style-preset')
      const selectionLocation = location(document, source, selection?.start ?? layout.start)
      for (const name of keywords(selection)) {
        const preset = presetCatalog.presets.find((item) => item.name === name)
        if (!preset) {
          notices.push(`No recorded vocabulary for :style-preset :${name} at ${selectionLocation}`)
          continue
        }
        groups.push({
          id: `${document}:${layout.start}:preset:${name}`,
          title: `:style-preset :${name}`,
          source: selectionLocation,
          choices: preset.classes.map((item) => {
            knownRules.add(item.name)
            return {
              ...item,
              source: `${selectionLocation} · ${presetCatalog.source.label}`,
              kind: 'preset',
              hasRule: true,
            }
          }),
        })
      }
      const definitions = layout.children.filter(
        (item) => item.kind === '(' && item.children[0]?.text === 'style-class',
      )
      const localChoices: StyleClassChoice[] = []
      for (const definition of definitions) {
        const name = keywords(definition.children[1])[0]
        if (!name) continue
        knownRules.add(name)
        localChoices.push({
          name,
          declaration: declaration(definition.children[2]),
          source: location(document, source, definition.start),
          kind: 'local',
          hasRule: true,
        })
      }
      if (localChoices.length) {
        groups.push({
          id: `${document}:${layout.start}:local`,
          title: 'Local style-class definitions',
          source: document,
          choices: localChoices,
        })
      }
      for (const rule of layout.children.filter((item) => item.kind === '(' && item.children[0]?.text === 'style')) {
        for (const name of keywords(option(rule.children[1], ':class'))) knownRules.add(name)
      }
    }
  }
  const unmatched = [...used].filter((name) => !knownRules.has(name))
  if (unmatched.length) {
    groups.push({
      id: 'used-unmatched',
      title: 'Used tags without a class rule',
      source: 'Current document source',
      choices: unmatched.map((name) => ({
        name,
        declaration: 'No matching rule · no visual effect',
        source: usedLocations.get(name) ?? 'Current item',
        kind: 'used',
        hasRule: false,
      })),
    })
  }
  return { groups, unmatched, notices }
}

export const stylePresetReference = presetCatalog.source
