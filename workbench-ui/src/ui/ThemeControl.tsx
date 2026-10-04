import { useEffect, useState } from 'react'
import { readThemePreference, resolveTheme, saveThemePreference } from '../theme'
import type { ThemePreference } from '../theme'

export function ThemeControl({ lang }: { lang: 'en' | 'de' }) {
  const [preference, setPreference] = useState<ThemePreference>(readThemePreference)
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)')
    const apply = () => {
      const theme = resolveTheme(preference, media?.matches ?? false)
      document.documentElement.dataset.theme = theme
      document.documentElement.style.colorScheme = theme
    }
    apply()
    media?.addEventListener('change', apply)
    return () => media?.removeEventListener('change', apply)
  }, [preference])
  return (
    <label className="theme-control">
      <span className="sr-only">{lang === 'de' ? 'Farbschema' : 'Appearance'}</span>
      <select
        aria-label={lang === 'de' ? 'Farbschema' : 'Appearance'}
        value={preference}
        onChange={(event) => {
          const next = event.target.value as ThemePreference
          saveThemePreference(next)
          setPreference(next)
        }}
      >
        <option value="system">{lang === 'de' ? 'System' : 'System'}</option>
        <option value="light">{lang === 'de' ? 'Hell' : 'Light'}</option>
        <option value="dark">{lang === 'de' ? 'Dunkel' : 'Dark'}</option>
      </select>
    </label>
  )
}
