// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { ThemeControl } from './ThemeControl'
import { readThemePreference, resolveTheme } from '../theme'

beforeEach(() => {
  // Node 25 exposes an unconfigured storage placeholder; model browser storage explicitly.
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    clear: () => values.clear(),
  })
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  delete document.documentElement.dataset.theme
  document.documentElement.style.colorScheme = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('follows operating-system changes and detaches its listener on unmount', () => {
  let changed: (() => void) | undefined
  const remove = vi.fn()
  const media = {
    matches: false,
    addEventListener: vi.fn((_event, listener) => {
      changed = listener
    }),
    removeEventListener: remove,
  }
  vi.stubGlobal('matchMedia', () => media)
  const view = render(<ThemeControl lang="en" />)
  expect(document.documentElement.dataset.theme).toBe('light')
  media.matches = true
  changed?.()
  expect(document.documentElement.dataset.theme).toBe('dark')
  view.unmount()
  expect(remove).toHaveBeenCalledWith('change', changed)
})

it('persists an explicit preference and keeps it when the system changes', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  render(<ThemeControl lang="de" />)
  fireEvent.change(screen.getByRole('combobox', { name: 'Farbschema' }), { target: { value: 'light' } })
  expect(document.documentElement.dataset.theme).toBe('light')
  expect(readThemePreference()).toBe('light')
  expect(resolveTheme('light', true)).toBe('light')
})

it('changes the current theme when browser storage is unavailable', () => {
  vi.spyOn(window.localStorage, 'getItem').mockImplementation(() => {
    throw new Error('blocked')
  })
  vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
    throw new Error('blocked')
  })
  render(<ThemeControl lang="en" />)
  fireEvent.change(screen.getByRole('combobox', { name: 'Appearance' }), { target: { value: 'dark' } })
  expect(document.documentElement.dataset.theme).toBe('dark')
  expect(readThemePreference()).toBe('system')
})
