import reference from '../../docs/diagnostics.md?raw'
import { describe, expect, it } from 'vitest'
import { diagnosticDetailsLabel, diagnosticMessages, explainDiagnostic } from './diagnosticMessages'

const rows = reference.split('\n').flatMap((line) => {
  const match = /^\| `((?:DSL|MANTRA)-[^`]+)` \|/.exec(line)
  if (!match) return []
  return [[match[1], line.split('|').at(-2)!.trim()] as const]
})

describe('diagnostic explanations', () => {
  it('covers exactly every documented static code with the documented English meaning and a German translation', () => {
    expect(rows.length).toBeGreaterThan(200)
    expect(new Set(rows.map(([code]) => code)).size).toBe(rows.length)
    expect(Object.keys(diagnosticMessages).sort()).toEqual(rows.map(([code]) => code).sort())
    for (const [code, meaning] of rows) {
      expect(diagnosticMessages[code].en, code).toBe(meaning)
      expect(diagnosticMessages[code].de.trim(), code).not.toBe('')
      expect(diagnosticMessages[code].de, code).not.toBe(meaning)
      expect(explainDiagnostic({ code, message: 'Original cause' }, 'en').source, code).toBe('catalogue')
      expect(explainDiagnostic({ code, message: 'Original cause' }, 'de').source, code).toBe('catalogue')
    }
  })

  it('preserves runtime details exactly in both languages without deriving category or replacing dynamic amounts', () => {
    const diagnostic = {
      code: 'MANTRA-INPUT-MIN-ROWS',
      message: 'Input facts has 0 rows; at least 2 required.\nsource: A/P2 <raw>',
    }
    const english = explainDiagnostic(diagnostic, 'en')
    const german = explainDiagnostic(diagnostic, 'de')
    expect(english.summary).toContain('too few rows')
    expect(german.summary).toContain('zu wenige Zeilen')
    expect(english.details).toBe(diagnostic.message)
    expect(german.details).toBe(diagnostic.message)
    expect(diagnosticDetailsLabel('de')).toBe('Originaldetails')
    expect(diagnosticDetailsLabel('en')).toBe('Original details')
  })

  it('explains all import failures in both languages while preserving the original cell and byte-limit details', () => {
    const codes = [
      'MANTRA-DATA-UTF8',
      'MANTRA-DATA-XLSX-CELL',
      'MANTRA-DATA-XLSX-CONTAINER',
      'MANTRA-DATA-XLSX-LIMIT',
      'MANTRA-DATA-XLSX-NAME',
    ]
    const message = 'facts.xlsx/base_value__A__P1: #DIV/0!; expanded bytes 16777217 > 16777216'
    for (const code of codes) {
      for (const language of ['en', 'de'] as const) {
        const explanation = explainDiagnostic({ code, message }, language)
        expect(explanation.source, `${code}/${language}`).toBe('catalogue')
        expect(explanation.summary, `${code}/${language}`).toBe(diagnosticMessages[code][language])
        expect(explanation.details, `${code}/${language}`).toBe(message)
      }
    }
  })

  it('distinguishes an unknown kernel cause from a new host diagnostic and retains each original cause', () => {
    const kernel = { code: 'DSL-NEW-KERNEL-CAUSE', message: 'Real kernel cause, retained exactly' }
    expect(explainDiagnostic(kernel, 'de')).toEqual({
      summary: 'Der Rechenkern hat einen Befund gemeldet. Die Originaldetails enthalten die konkrete Ursache.',
      details: kernel.message,
      source: 'kernel',
    })
    expect(explainDiagnostic(kernel, 'en').summary).toContain('calculation kernel')
    const host = { code: 'MANTRA-FUTURE-CODE', message: 'Future host details' }
    expect(explainDiagnostic(host, 'de').source).toBe('unknown')
    expect(explainDiagnostic(host, 'en').details).toBe(host.message)
    expect(explainDiagnostic({ code: 'constructor', message: 'No inherited property lookup' }, 'en').source).toBe(
      'unknown',
    )
  })

  it('keeps the shared catalogue and each language pair immutable', () => {
    expect(Object.isFrozen(diagnosticMessages)).toBe(true)
    expect(Object.values(diagnosticMessages).every(Object.isFrozen)).toBe(true)
  })
})
