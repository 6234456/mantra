// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { Envelope } from '../types'
import { LayoutSettingsPanel } from './LayoutSettingsPanel'
import { TemplatePreviewError } from './client'
import { firstCase, templateSources } from './fixtures.test.helpers'
import type {
  LayoutProperty,
  LayoutSettings,
  LayoutSettingsClient,
  LayoutSettingsRequest,
} from './layoutSettingsClient'

afterEach(cleanup)

const snapshot = templateSources()
const source = snapshot.data.documents.find((item) => item.role === 'layout')!
const property = (id: LayoutProperty['id'], overrides: Partial<LayoutProperty> = {}): LayoutProperty => ({
  id,
  label: id,
  group: 'numbers',
  type: 'text',
  options: [],
  minimum: null,
  maximum: null,
  declared: true,
  explicitValue: 'original',
  inheritedValue: null,
  effectiveValue: 'original',
  editable: true,
  reason: null,
  canUnset: true,
  ...overrides,
})
const properties = [
  property('title', { group: 'report' }),
  property('precision', {
    type: 'integer',
    minimum: 0,
    maximum: 18,
    explicitValue: { n: '2' },
    inheritedValue: { n: '2' },
    effectiveValue: { n: '2' },
  }),
  property('grouping', { type: 'boolean', explicitValue: true, inheritedValue: true, effectiveValue: true }),
  property('zero'),
  property('language', {
    type: 'enum',
    options: ['de', 'en'],
    explicitValue: { kw: 'de' },
    effectiveValue: { kw: 'de' },
  }),
]

function response(request: LayoutSettingsRequest, nextText = request.text): Envelope<LayoutSettings> {
  return {
    contract: 'mantra.workbench/4',
    revision: request.baseRevision,
    engine: snapshot.engine,
    data: {
      document: firstCase,
      draftSequence: request.draftSequence,
      baseRevisions: request.baseRevisions,
      scope: 'layout',
      layoutId: 'example/layout',
      preset: 'de-staffel-4',
      source: { handle: source.handle, document: source.document, text: nextText },
      properties,
      patches:
        nextText === request.text
          ? []
          : [
              {
                startOffset: request.text.length,
                endOffset: request.text.length,
                text: nextText.slice(request.text.length),
                removedText: '',
              },
            ],
    },
  }
}

function fakeClient() {
  return { settings: vi.fn<LayoutSettingsClient['settings']>(async (_case, request) => response(request)) }
}

function props(client = fakeClient()) {
  return {
    caseId: firstCase,
    snapshot,
    source,
    text: source.text,
    client,
    onApply: vi.fn(() => true),
    onConflict: vi.fn(),
    onPendingChange: vi.fn(),
  }
}

async function inspect() {
  fireEvent.click(screen.getByRole('button', { name: /Inspect layout settings|Refresh layout settings/ }))
  await screen.findByLabelText('Layout setting precision')
}

const input = (id: string) => screen.getByLabelText(`Layout setting ${id}`) as HTMLInputElement
const apply = () => screen.getByRole('button', { name: 'Apply layout settings' }) as HTMLButtonElement
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

it('reads settings explicitly and keeps a case without a captured root layout read-only', async () => {
  const initial = props()
  const { rerender } = render(<LayoutSettingsPanel {...initial} />)
  expect(initial.client.settings).not.toHaveBeenCalled()
  await inspect()
  expect(initial.client.settings.mock.lastCall![1]).toMatchObject({
    baseRevision: snapshot.revision,
    baseRevisions: snapshot.data.baseRevisions,
    handle: source.handle,
    text: source.text,
    operations: [],
  })
  expect(input('precision').value).toBe('2')
  rerender(<LayoutSettingsPanel {...initial} source={undefined} text={undefined} />)
  expect(screen.getByText(/No editable root layout was captured/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Refresh layout settings' })).toBeNull()
})

it('submits zero, false, an empty text value and an explicit reset without conflating them', async () => {
  const initial = props()
  render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '0' } })
  fireEvent.change(input('grouping'), { target: { value: 'false' } })
  fireEvent.change(input('zero'), { target: { value: '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Reset layout setting title' }))
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.settings).toHaveBeenCalledTimes(2))
  const request = initial.client.settings.mock.lastCall![1]
  expect(request.operations).toEqual([
    { op: 'set', property: 'precision', text: '0' },
    { op: 'set', property: 'grouping', text: 'false' },
    { op: 'set', property: 'zero', text: '' },
    { op: 'unset', property: 'title' },
  ])
  await waitFor(() => expect(initial.onApply).toHaveBeenCalledWith(firstCase, request, source.text))
})

it('keeps incomplete integer input and lets native IME confirmation pass without submitting source edits', async () => {
  const initial = props()
  render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '1.' } })
  expect(apply().disabled).toBe(true)
  fireEvent.change(input('precision'), { target: { value: '0' } })
  fireEvent.compositionStart(input('precision'))
  expect(fireEvent.keyDown(input('precision'), { key: 'Enter', keyCode: 229, isComposing: true })).toBe(true)
  fireEvent.submit(apply().closest('form')!)
  expect(initial.client.settings).toHaveBeenCalledOnce()
  expect(initial.onApply).not.toHaveBeenCalled()
  fireEvent.compositionEnd(input('precision'))
  expect(fireEvent.keyDown(input('precision'), { key: 'Enter', keyCode: 229 })).toBe(true)
  fireEvent.submit(apply().closest('form')!)
  expect(initial.client.settings).toHaveBeenCalledOnce()
  fireEvent.keyUp(input('precision'), { key: 'Enter', keyCode: 229 })
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.settings).toHaveBeenCalledTimes(2))
})

it('ignores an earlier inspection after the source changes and preserves field buffers until an explicit refreshed apply', async () => {
  const initial = props()
  const { rerender } = render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '7' } })
  const pending = deferred<Envelope<LayoutSettings>>()
  initial.client.settings.mockReturnValueOnce(pending.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Refresh layout settings' }))
  const old = initial.client.settings.mock.lastCall![1]
  const newer = source.text + '\n; Manual source edit'
  rerender(<LayoutSettingsPanel {...initial} text={newer} />)
  await act(async () => {
    pending.resolve(response(old))
    await pending.promise
  })
  expect(input('precision').value).toBe('7')
  expect(apply().disabled).toBe(true)
  expect(initial.onApply).not.toHaveBeenCalled()
  await inspect()
  fireEvent.click(apply())
  await waitFor(() => expect(initial.onApply).toHaveBeenCalled())
  expect(initial.client.settings.mock.lastCall![1]).toMatchObject({
    text: newer,
    operations: [{ op: 'set', property: 'precision', text: '7' }],
  })
})

it('retains newer field input when an earlier accepted source operation returns', async () => {
  const client = fakeClient()
  const pending = deferred<Envelope<LayoutSettings>>()
  const onApply = vi.fn()
  function Harness() {
    const [text, setText] = useState(source.text)
    return (
      <LayoutSettingsPanel
        {...props(client)}
        text={text}
        onApply={(_case, request, next) => {
          onApply(request, next)
          setText(next)
          return true
        }}
      />
    )
  }
  render(<Harness />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '4' } })
  client.settings.mockReturnValueOnce(pending.promise)
  fireEvent.click(apply())
  const request = client.settings.mock.lastCall![1]
  fireEvent.change(input('precision'), { target: { value: '6' } })
  await act(async () => {
    pending.resolve(response(request, request.text + '\n; accepted setting'))
    await pending.promise
  })
  expect(onApply).toHaveBeenCalledOnce()
  expect(input('precision').value).toBe('6')
  expect(apply().disabled).toBe(false)
})

it('retains an invalid field buffer after 422 and reports a revision conflict without applying either reply', async () => {
  const initial = props()
  render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '999' } })
  initial.client.settings.mockRejectedValueOnce(new TemplatePreviewError(422, 'Precision must be between 0 and 18'))
  fireEvent.click(apply())
  await screen.findByRole('alert')
  expect(input('precision').value).toBe('999')
  expect(initial.onApply).not.toHaveBeenCalled()
  initial.client.settings.mockRejectedValueOnce(new TemplatePreviewError(409, 'Changed source revision'))
  fireEvent.click(apply())
  await waitFor(() => expect(initial.onConflict).toHaveBeenCalledOnce())
  expect(initial.onApply).not.toHaveBeenCalled()
})

it('cancels a pending source operation while keeping the inspected source unchanged', async () => {
  const initial = props()
  render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  fireEvent.change(input('precision'), { target: { value: '4' } })
  const pending = deferred<Envelope<LayoutSettings>>()
  initial.client.settings.mockReturnValueOnce(pending.promise)
  fireEvent.click(apply())
  const request = initial.client.settings.mock.lastCall![1]
  fireEvent.click(screen.getByRole('button', { name: 'Cancel setting changes' }))
  await act(async () => {
    pending.resolve(response(request, request.text + '; late'))
    await pending.promise
  })
  expect(initial.onApply).not.toHaveBeenCalled()
  expect(input('precision').value).toBe('2')
  expect(apply().disabled).toBe(true)
})

it('keeps an unsupported explicit boolean visibly unmatched and allows only a deliberate reset', async () => {
  const initial = props()
  initial.client.settings.mockImplementationOnce(async (_case, request) => {
    const reply = response(request)
    reply.data.properties = reply.data.properties.map((item) =>
      item.id === 'grouping'
        ? {
            ...item,
            explicitValue: null,
            effectiveValue: false,
            editable: false,
            reason: 'This source value cannot be edited by this form.',
          }
        : item,
    )
    return reply
  })
  render(<LayoutSettingsPanel {...initial} />)
  await inspect()
  const field = input('grouping') as unknown as HTMLSelectElement
  expect(field.disabled).toBe(true)
  expect(field.value).toBe('')
  expect(field.selectedOptions[0].textContent).toBe('Unsupported source value')
  fireEvent.click(screen.getByRole('button', { name: 'Reset layout setting grouping' }))
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.settings).toHaveBeenCalledTimes(2))
  expect(initial.client.settings.mock.lastCall![1].operations).toEqual([{ op: 'unset', property: 'grouping' }])
})

it('rejects a response that labels a different participating source as the root layout', async () => {
  const initial = props()
  initial.client.settings.mockImplementationOnce(async (_case, request) => {
    const reply = response(request)
    reply.data.source.document = snapshot.data.documents.find((item) => item.role === 'schema')!.document
    return reply
  })
  render(<LayoutSettingsPanel {...initial} />)
  fireEvent.click(screen.getByRole('button', { name: 'Inspect layout settings' }))
  await screen.findByRole('alert')
  expect(initial.onApply).not.toHaveBeenCalled()
  expect(screen.queryByLabelText('Layout setting precision')).toBeNull()
})
