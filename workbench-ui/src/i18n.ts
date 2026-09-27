const copy = {
  de: {
    overview: 'Übersicht', mainline: 'Hauptlinie', auxiliary: 'Nebeninformation', workspace: 'Arbeitsbereiche',
    inputs: 'Stammdaten & Eingaben', parameters: 'Parameter', sources: 'Datenquellen', extensions: 'Anpassungen',
    diagnostics: 'Prüfungen', export: 'Export', selected: 'Ausgewählt', calculation: 'Rechenweg', provenance: 'Herkunft',
    usedIn: 'Wird verwendet in', flowsInto: 'Fließt ein in', step: 'Schritt', showZero: 'Nullzeilen anzeigen',
    unavailable: 'Für diese Ansicht liegen noch keine Daten vor.', noFixtures: 'Keine Fixture gefunden. WP3 muss die Vertragsdateien bereitstellen.',
    retry: 'Erneut laden', statusReady: 'Berechnung aktuell', statusError: 'Fehler', paper: 'Arbeitspapier',
    inspect: 'Zelle auswählen, um den Rechenweg zu sehen.', sourceTree: 'Herkunftsbaum', intermediate: 'Zwischenwerte',
    chooseCase: 'Fall wählen', result: 'Ergebnis', noResult: 'Kein Ergebnis', noPaper: 'Für diesen Bereich liegt noch kein Arbeitspapier vor.',
    comparison: 'Vergleich', effect: 'Auswirkungen auf die Hauptlinie', bases: 'Grundlagen', continueTree: 'Weitere Quellen laden', traceTruncated: 'Der Rechenweg wurde begrenzt.',
    workbook: 'Arbeitsmappe', worksheets: 'Blätter', preview: 'Vorschau', fidelity: 'Formeltreue',
    formulaCells: 'Formelzellen', inputCells: 'Eingabezellen', fallbackCells: 'Nur Werte', namedRanges: 'Benannte Bereiche',
    download: 'Herunterladen', downloadLive: 'Download im Live-Modus verfügbar', layout: 'Darstellung', currentLayout: 'Fallvorgabe',
    truncatedPreview: 'Vorschau zeigt die ersten 50 Zeilen und 20 Spalten.', evaluationErrors: 'Auswertungsfehler',
  },
  en: {
    overview: 'Overview', mainline: 'Mainline', auxiliary: 'Additional information', workspace: 'Work areas',
    inputs: 'Inputs', parameters: 'Parameters', sources: 'Data sources', extensions: 'Extensions',
    diagnostics: 'Checks', export: 'Export', selected: 'Selected', calculation: 'Calculation', provenance: 'Provenance',
    usedIn: 'Used in', flowsInto: 'Flows into', step: 'Step', showZero: 'Show zero rows',
    unavailable: 'No data is available for this view yet.', noFixtures: 'No fixtures found. WP3 must provide the contract files.',
    retry: 'Reload', statusReady: 'Calculation current', statusError: 'errors', paper: 'Working paper',
    inspect: 'Select a cell to inspect its calculation.', sourceTree: 'Provenance tree', intermediate: 'Intermediate values',
    chooseCase: 'Choose case', result: 'Result', noResult: 'No result', noPaper: 'No working paper is available for this panel yet.',
    comparison: 'Comparison', effect: 'Effect on the mainline', bases: 'Basis', continueTree: 'Load more sources', traceTruncated: 'This calculation trail was truncated.',
    workbook: 'Workbook', worksheets: 'sheets', preview: 'Preview', fidelity: 'Formula fidelity',
    formulaCells: 'Formula cells', inputCells: 'Input cells', fallbackCells: 'Values only', namedRanges: 'Named ranges',
    download: 'Download', downloadLive: 'Download available in live mode', layout: 'Layout', currentLayout: 'Case default',
    truncatedPreview: 'Preview shows the first 50 rows and 20 columns.', evaluationErrors: 'Evaluation errors',
  },
}
export type Language = keyof typeof copy
export function language() : Language {
  const storage = window.localStorage
  const saved = typeof storage?.getItem === 'function' ? storage.getItem('mantra.workbench.language') : null
  return saved === 'de' || saved === 'en' ? saved : document.documentElement.lang.startsWith('en') ? 'en' : 'de'
}
export function chooseLanguage(next: Language) { if (typeof window.localStorage?.setItem === 'function') window.localStorage.setItem('mantra.workbench.language', next); location.reload() }
export function t(key: keyof typeof copy.de, lang: Language) { return copy[lang][key] }
