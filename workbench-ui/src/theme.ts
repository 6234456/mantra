export type ThemePreference = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const storageKey = 'mantra.theme'

export function readThemePreference(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(storageKey)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // Private or restricted browsing still supports a theme for this session.
  }
  return 'system'
}

export function saveThemePreference(preference: ThemePreference): void {
  try {
    window.localStorage.setItem(storageKey, preference)
  } catch {
    // Persistence is optional; it does not prevent changing the current appearance.
  }
}

export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : preference
}
