// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from './App'
import { scenariosPath } from './ScenariosPage'
import { casePath } from '../address'
import compare from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/compare-2026.json'
import structure from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/structure.json'
import run from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/run.json'
import paper from '../../../mantra-workbench/src/test/resources/golden/de-est-case-mustermann-552b3ca5/paper.json'

const caseId = 'sample/case.mantra'
const parameterId = compare.data.variant.parameters[0]

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  history.replaceState(null, '', '/')
})

function serve() {
  const documents: Record<string, unknown> = {
    '/fixtures/index.json': {
      cases: [
        {
          id: caseId,
          title: 'Case',
          files: {
            structure: '/structure.json',
            run: '/run.json',
            paper: '/paper.json',
            compares: { [JSON.stringify([parameterId])]: '/compare.json' },
          },
        },
      ],
      parameters: [{ id: parameterId, path: 'parameters.mantra' }],
    },
    '/structure.json': structure,
    '/run.json': run,
    '/paper.json': paper,
    '/compare.json': compare,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => ({ ok: true, json: async () => documents[url] })),
  )
}

it('links selections, restores them through navigation and follows browser history', async () => {
  serve()
  history.replaceState(null, '', scenariosPath(caseId, []))
  render(<App />)
  fireEvent.change(await screen.findByLabelText('Parametersatz hinzufügen'), { target: { value: parameterId } })
  expect(new URLSearchParams(location.search).getAll('scenario')).toEqual([parameterId])
  const table = await screen.findByRole('table')
  expect(within(table).getByText(compare.data.mainline[0].display.variant)).toBeTruthy()
  const navigation = screen.getByRole('navigation', { name: 'Case navigation' })
  fireEvent.click(within(navigation).getByRole('link', { name: 'Übersicht' }))
  expect(location.pathname).toBe(`${casePath(caseId)}/overview`)
  fireEvent.click(within(navigation).getByRole('link', { name: 'Szenarien' }))
  expect(location.pathname + location.search).toBe(scenariosPath(caseId, [parameterId]))
  expect(await screen.findByRole('table')).toBeTruthy()
  history.back()
  await waitFor(() => expect(location.pathname).toBe(`${casePath(caseId)}/overview`))
  await waitFor(() => expect(screen.queryByRole('heading', { name: 'Szenarien' })).toBeNull())
  history.forward()
  await waitFor(() => expect(location.pathname + location.search).toBe(scenariosPath(caseId, [parameterId])))
  expect(await screen.findByRole('table')).toBeTruthy()
  cleanup()
  render(<App />)
  expect(await screen.findByRole('table')).toBeTruthy()
  expect(screen.getByRole('button', { name: `${parameterId} entfernen` })).toBeTruthy()
})
