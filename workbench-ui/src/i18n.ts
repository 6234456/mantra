const copy = {
  de: {
    overview: 'Übersicht', mainline: 'Hauptlinie', auxiliary: 'Nebeninformation', workspace: 'Arbeitsbereiche',
    inputs: 'Stammdaten & Eingaben', parameters: 'Parameter', sources: 'Datenquellen', extensions: 'Anpassungen',
    diagnostics: 'Prüfungen', export: 'Export', selected: 'Ausgewählt', calculation: 'Rechenweg', provenance: 'Herkunft',
    usedIn: 'Wird verwendet in', flowsInto: 'Fließt ein in', step: 'Schritt', showZero: 'Nullzeilen anzeigen',
    unavailable: 'Für diese Ansicht liegen noch keine Daten vor.', noFixtures: 'Keine Fixture gefunden. WP3 muss die Vertragsdateien bereitstellen.',
    retry: 'Erneut laden', statusReady: 'Berechnung aktuell', statusError: 'Fehler', paper: 'Arbeitspapier',
    inspect: 'Zelle auswählen, um den Rechenweg zu sehen.', sourceTree: 'Herkunftsbaum', intermediate: 'Zwischenwerte',
    chooseCase: 'Fall wählen', noResult: 'Kein Ergebnis', noPaper: 'Für diesen Bereich liegt noch kein Arbeitspapier vor.',
    comparison: 'Vergleich', effect: 'Auswirkungen auf die Hauptlinie', bases: 'Grundlagen',
  },
  en: {
    overview: 'Overview', mainline: 'Mainline', auxiliary: 'Additional information', workspace: 'Work areas',
    inputs: 'Inputs', parameters: 'Parameters', sources: 'Data sources', extensions: 'Extensions',
    diagnostics: 'Checks', export: 'Export', selected: 'Selected', calculation: 'Calculation', provenance: 'Provenance',
    usedIn: 'Used in', flowsInto: 'Flows into', step: 'Step', showZero: 'Show zero rows',
    unavailable: 'No data is available for this view yet.', noFixtures: 'No fixtures found. WP3 must provide the contract files.',
    retry: 'Reload', statusReady: 'Calculation current', statusError: 'errors', paper: 'Working paper',
    inspect: 'Select a cell to inspect its calculation.', sourceTree: 'Provenance tree', intermediate: 'Intermediate values',
    chooseCase: 'Choose case', noResult: 'No result', noPaper: 'No working paper is available for this panel yet.',
    comparison: 'Comparison', effect: 'Effect on the mainline', bases: 'Basis',
  },
}
export type Language = keyof typeof copy
export function language() : Language {
  const saved = localStorage.getItem('mantra.workbench.language')
  return saved === 'de' || saved === 'en' ? saved : document.documentElement.lang.startsWith('en') ? 'en' : 'de'
}
export function chooseLanguage(next: Language) { localStorage.setItem('mantra.workbench.language', next); location.reload() }
export function t(key: keyof typeof copy.de, lang: Language) { return copy[lang][key] }
