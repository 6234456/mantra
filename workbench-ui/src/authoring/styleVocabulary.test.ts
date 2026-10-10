import { expect, it } from 'vitest'
import { readStyleVocabulary, stylePresetReference } from './styleVocabulary'

it('includes the full selected preset vocabularies in layout order with traceable declarations', () => {
  const result = readStyleVocabulary({
    'layout.mantra': '(layout sample {:style-preset [:working-paper :utilities]} (table rows :label :value))',
  })
  expect(result.groups.map((group) => group.title)).toEqual([
    ':style-preset :working-paper',
    ':style-preset :utilities',
  ])
  expect(result.groups[0].choices.map((choice) => choice.name)).toEqual([
    'source',
    'assumption',
    'detail',
    'subtotal',
    'result',
    'note',
    'variance',
    'control',
  ])
  expect(result.groups[1].choices.map((choice) => choice.name)).toEqual([
    'normal',
    'strong',
    'muted',
    'accent',
    'subtle',
    'highlight',
  ])
  expect(result.groups[0].choices.find((choice) => choice.name === 'variance')?.declaration).toBe(
    'Weight bold · tone accent · fill subtle',
  )
  expect(result.groups[1].choices[0].source).toBe('layout.mantra:1 · dsl-reference §3.4')
  expect(stylePresetReference.document).toBe('docs/dsl-reference.md#34-reusable-style-classes')
})

it('offers no unselected preset and treats omission or an empty vector as opting out', () => {
  const selected = readStyleVocabulary({ 'layout.mantra': '(layout sample {:style-preset :utilities})' })
  expect(selected.groups.flatMap((group) => group.choices).some((choice) => choice.name === 'result')).toBe(false)
  expect(readStyleVocabulary({ 'layout.mantra': '(layout sample {})' }).groups).toEqual([])
  expect(readStyleVocabulary({ 'layout.mantra': '(layout sample {:style-preset []})' }).groups).toEqual([])
})

it('reads local style-class definitions with exact source lines while ignoring comments and quoted forms', () => {
  const result = readStyleVocabulary({
    'layout.mantra': `; (layout fake {:style-preset :working-paper})
(layout sample {:title "(style-class :fake {})"}
  ; (style-class :commented {:weight :bold})
  (style-class :key-result {:weight :bold :tone :accent :fill :subtle})
  (table rows :label :value))`,
  })
  expect(result.groups).toHaveLength(1)
  expect(result.groups[0].title).toBe('Local style-class definitions')
  expect(result.groups[0].choices).toEqual([
    {
      name: 'key-result',
      declaration: 'weight bold · tone accent · fill subtle',
      source: 'layout.mantra:4',
      kind: 'local',
      hasRule: true,
    },
  ])
})

it('retains unknown used tags without claiming that class order calculates a style', () => {
  const result = readStyleVocabulary(
    {
      'schema.mantra': '(schema sample (info value "Value" 1 {:class [:unknown :normal]}))',
      'layout.mantra': '(layout sample {:style-preset :utilities})',
    },
    ['unknown', 'normal'],
  )
  expect(result.unmatched).toEqual(['unknown'])
  expect(result.groups.at(-1)?.choices).toEqual([
    {
      name: 'unknown',
      declaration: 'No matching rule · no visual effect',
      source: 'schema.mantra:1',
      kind: 'used',
      hasRule: false,
    },
  ])
})

it('recognizes authored class selectors as declared rules without evaluating their conditions', () => {
  const result = readStyleVocabulary(
    {
      'layout.mantra': '(layout sample {} (style {:class :tag :column :value} {:tone :accent}))',
    },
    ['tag'],
  )
  expect(result.unmatched).toEqual([])
  expect(result.groups).toEqual([])
})

it('reports unknown presets and invalid source without using a hardcoded fallback vocabulary', () => {
  const result = readStyleVocabulary({
    'layout.mantra': '(layout sample {:style-preset :missing})',
    'invalid.mantra': '(schema sample',
  })
  expect(result.groups).toEqual([])
  expect(result.notices).toEqual([
    'No recorded vocabulary for :style-preset :missing at layout.mantra:1',
    'Class declarations unavailable in invalid source: invalid.mantra',
  ])
})
