// @vitest-environment jsdom
import { useState } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { Envelope } from '../types'
import { StyleClassesPanel } from './StyleClassesPanel'
import { TemplatePreviewError } from './client'
import { firstCase, templateSources } from './fixtures.test.helpers'
import type { StyleClass, StyleClasses, StyleClassesClient, StyleClassesRequest, StyleSpec } from './styleClassesClient'

afterEach(cleanup)

const snapshot = templateSources()
const source = snapshot.data.documents.find((item) => item.role === 'layout')!
const emptyStyle: StyleSpec = { weight: null, tone: null, fill: null }
const localStyle: StyleSpec = { weight: 'bold', tone: 'muted', fill: null }
const classes: StyleClass[] = [
  {
    name: 'result',
    editable: true,
    reason: null,
    presets: ['working-paper'],
    local: null,
    inherited: { weight: 'bold', tone: 'accent', fill: 'accent' },
    effective: { weight: 'bold', tone: 'accent', fill: 'accent' },
    references: [],
    canRemove: false,
    removeReason: 'There is no local definition to remove.',
  },
  {
    name: 'custom',
    editable: true,
    reason: null,
    presets: [],
    local: localStyle,
    inherited: emptyStyle,
    effective: localStyle,
    references: [{ kind: 'use', startOffset: 0, endOffset: 1 }],
    canRemove: false,
    removeReason: 'This local definition is referenced by :use without a preset fallback.',
  },
]

function response(request: StyleClassesRequest, nextText = request.text): Envelope<StyleClasses> {
  return {
    contract: 'mantra.workbench/4',
    revision: request.baseRevision,
    engine: snapshot.engine,
    data: {
      document: firstCase,
      draftSequence: request.draftSequence,
      baseRevisions: request.baseRevisions,
      scope: 'layout-style-classes',
      layoutId: 'example/layout',
      stylePresets: ['working-paper'],
      usageScope: 'submitted-layout',
      source: { handle: source.handle, document: source.document, text: nextText },
      classes,
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
  return { classes: vi.fn<StyleClassesClient['classes']>(async (_case, request) => response(request)) }
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
  fireEvent.click(screen.getByRole('button', { name: /Inspect style classes|Refresh style classes/ }))
  await screen.findByLabelText('Style class')
}

function choose(name: string) {
  fireEvent.change(screen.getByLabelText('Style class'), { target: { value: name } })
}

const field = (name: string) => screen.getByLabelText(`Style class ${name}`) as HTMLInputElement
const apply = () => screen.getByRole('button', { name: 'Apply style changes' }) as HTMLButtonElement
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

it('inspects only on request and exposes the submitted-layout usage scope without evaluating styles', async () => {
  const initial = props()
  const { rerender } = render(<StyleClassesPanel {...initial} />)
  expect(initial.client.classes).not.toHaveBeenCalled()
  await inspect()
  expect(initial.client.classes.mock.lastCall![1]).toMatchObject({
    baseRevision: snapshot.revision,
    baseRevisions: snapshot.data.baseRevisions,
    handle: source.handle,
    text: source.text,
    operations: [],
  })
  expect(screen.getByText(/Usage scope: submitted root layout only/)).toBeTruthy()
  expect(screen.getByText(/Schema, includes and unsaved schema edits are outside/)).toBeTruthy()
  expect(screen.getAllByText(/weight: bold · tone: accent · fill: accent/)).toHaveLength(2)
  rerender(<StyleClassesPanel {...initial} source={undefined} text={undefined} />)
  expect(screen.getByText(/No editable root layout was captured/)).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Refresh style classes' })).toBeNull()
})

it('creates a local class with deliberate normal/default/none values distinct from omitted properties', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  fireEvent.click(screen.getByRole('button', { name: 'Create local style class' }))
  expect(apply().disabled).toBe(true)
  fireEvent.change(field('name'), { target: { value: 'summary' } })
  expect(field('weight').value).toBe('')
  fireEvent.change(field('weight'), { target: { value: 'normal' } })
  fireEvent.change(field('tone'), { target: { value: 'default' } })
  fireEvent.change(field('fill'), { target: { value: 'none' } })
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.classes).toHaveBeenCalledTimes(2))
  expect(initial.client.classes.mock.lastCall![1].operations).toEqual([
    { op: 'upsert', name: 'summary', style: { weight: 'normal', tone: 'default', fill: 'none' } },
  ])
  await waitFor(() => expect(initial.onApply).toHaveBeenCalled())
})

it('creates an empty local override for a preset without copying inherited fields into explicit declarations', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  fireEvent.click(screen.getByRole('button', { name: 'Create local override' }))
  expect(field('name').value).toBe('result')
  expect(field('name').readOnly).toBe(true)
  expect(field('weight').value).toBe('')
  expect(field('tone').value).toBe('')
  expect(field('fill').value).toBe('')
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.classes).toHaveBeenCalledTimes(2))
  expect(initial.client.classes.mock.lastCall![1].operations).toEqual([
    { op: 'upsert', name: 'result', style: emptyStyle },
  ])
})

it('does not let creation silently replace an existing local definition', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  fireEvent.click(screen.getByRole('button', { name: 'Create local style class' }))
  fireEvent.change(field('name'), { target: { value: 'custom' } })
  expect(screen.getByText(/A local definition with this name already exists/)).toBeTruthy()
  expect(apply().disabled).toBe(true)
  fireEvent.click(apply())
  expect(initial.client.classes).toHaveBeenCalledOnce()
  expect(initial.onApply).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  choose('custom')
  expect(field('weight').value).toBe('bold')
  expect(field('tone').value).toBe('muted')
})

it('edits only the selected local definition and preserves an omitted style property', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  choose('custom')
  expect(field('name').readOnly).toBe(true)
  fireEvent.change(field('weight'), { target: { value: 'normal' } })
  fireEvent.change(field('tone'), { target: { value: '' } })
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.classes).toHaveBeenCalledTimes(2))
  expect(initial.client.classes.mock.lastCall![1].operations).toEqual([
    { op: 'upsert', name: 'custom', style: { weight: 'normal', tone: null, fill: null } },
  ])
})

it('links the server removal reason and allows a staged removal when the preset fallback remains', async () => {
  const initial = props()
  initial.client.classes.mockImplementation(async (_case, request) => {
    const reply = response(request)
    reply.data.classes = classes.map((item) =>
      item.name === 'result' ? { ...item, local: localStyle, canRemove: true, removeReason: null } : item,
    )
    return reply
  })
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  choose('custom')
  const blocked = screen.getByRole('button', { name: 'Remove local style class' }) as HTMLButtonElement
  expect(blocked.disabled).toBe(true)
  expect(document.getElementById(blocked.getAttribute('aria-describedby')!)?.textContent).toContain(
    'without a preset fallback',
  )
  choose('result')
  fireEvent.click(screen.getByRole('button', { name: 'Remove local style class' }))
  expect(initial.client.classes).toHaveBeenCalledOnce()
  expect(screen.getByText(/Local definition :result will be removed when you apply/)).toBeTruthy()
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.classes).toHaveBeenCalledTimes(2))
  expect(initial.client.classes.mock.lastCall![1].operations).toEqual([{ op: 'remove', name: 'result' }])
})

it('keeps a nonsimple selector-only name visible and read-only with the supplied reason', async () => {
  const initial = props()
  initial.client.classes.mockImplementation(async (_case, request) => {
    const reply = response(request)
    reply.data.classes = [
      {
        ...classes[1],
        name: 'FutureTag',
        editable: false,
        reason: 'Only simple lowercase class names can have local definitions.',
        local: null,
        canRemove: false,
      },
    ]
    return reply
  })
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  expect((screen.getByLabelText('Style class') as HTMLSelectElement).value).toBe('FutureTag')
  expect(screen.getByText('Only simple lowercase class names can have local definitions.')).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Create local override' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByLabelText('Style class name')).toBeNull()
})

it('preserves incomplete names and native IME confirmation without submitting a style edit', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  fireEvent.click(screen.getByRole('button', { name: 'Create local style class' }))
  fireEvent.change(field('name'), { target: { value: '中文' } })
  expect(field('name').value).toBe('中文')
  expect(apply().disabled).toBe(true)
  fireEvent.change(field('name'), { target: { value: 'summary' } })
  fireEvent.compositionStart(field('name'))
  expect(fireEvent.keyDown(field('name'), { key: 'Enter', keyCode: 229, isComposing: true })).toBe(true)
  fireEvent.submit(apply().closest('form')!)
  expect(initial.client.classes).toHaveBeenCalledOnce()
  fireEvent.compositionEnd(field('name'))
  expect(fireEvent.keyDown(field('name'), { key: 'Enter', keyCode: 229 })).toBe(true)
  fireEvent.submit(apply().closest('form')!)
  expect(initial.client.classes).toHaveBeenCalledOnce()
  fireEvent.keyUp(field('name'), { key: 'Enter' })
  fireEvent.click(apply())
  await waitFor(() => expect(initial.client.classes).toHaveBeenCalledTimes(2))
})

it('discards a late inspection after source editing while retaining the style buffer for explicit refreshed apply', async () => {
  const initial = props()
  const { rerender } = render(<StyleClassesPanel {...initial} />)
  await inspect()
  choose('custom')
  fireEvent.change(field('fill'), { target: { value: 'subtle' } })
  const late = deferred<Envelope<StyleClasses>>()
  initial.client.classes.mockReturnValueOnce(late.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Refresh style classes' }))
  const request = initial.client.classes.mock.lastCall![1]
  const newer = source.text + '\n; New manual source'
  rerender(<StyleClassesPanel {...initial} text={newer} />)
  await act(async () => {
    late.resolve(response(request))
    await late.promise
  })
  expect(field('fill').value).toBe('subtle')
  expect(apply().disabled).toBe(true)
  await inspect()
  fireEvent.click(apply())
  await waitFor(() => expect(initial.onApply).toHaveBeenCalled())
  expect(initial.client.classes.mock.lastCall![1]).toMatchObject({
    text: newer,
    operations: [{ op: 'upsert', name: 'custom', style: { ...localStyle, fill: 'subtle' } }],
  })
})

it('keeps newer field edits when an earlier source patch is accepted', async () => {
  const client = fakeClient()
  const late = deferred<Envelope<StyleClasses>>()
  const onApply = vi.fn()
  function Harness() {
    const [text, setText] = useState(source.text)
    return (
      <StyleClassesPanel
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
  choose('custom')
  fireEvent.change(field('weight'), { target: { value: 'normal' } })
  client.classes.mockReturnValueOnce(late.promise)
  fireEvent.click(apply())
  const request = client.classes.mock.lastCall![1]
  fireEvent.change(field('fill'), { target: { value: 'accent' } })
  await act(async () => {
    late.resolve(response(request, request.text + '\n; accepted style'))
    await late.promise
  })
  expect(onApply).toHaveBeenCalledOnce()
  expect(field('fill').value).toBe('accent')
  expect(apply().disabled).toBe(false)
})

it('keeps edits made during creation editable after that class is accepted as a new local definition', async () => {
  const client = fakeClient()
  const late = deferred<Envelope<StyleClasses>>()
  function Harness() {
    const [text, setText] = useState(source.text)
    return (
      <StyleClassesPanel
        {...props(client)}
        text={text}
        onApply={(_case, _request, next) => {
          setText(next)
          return true
        }}
      />
    )
  }
  render(<Harness />)
  await inspect()
  fireEvent.click(screen.getByRole('button', { name: 'Create local style class' }))
  fireEvent.change(field('name'), { target: { value: 'summary' } })
  fireEvent.change(field('weight'), { target: { value: 'bold' } })
  client.classes.mockReturnValueOnce(late.promise)
  fireEvent.click(apply())
  const request = client.classes.mock.lastCall![1]
  fireEvent.change(field('fill'), { target: { value: 'accent' } })
  const accepted = response(request, request.text + '\n; created summary')
  accepted.data.classes = [
    ...classes,
    {
      name: 'summary',
      editable: true,
      reason: null,
      presets: [],
      local: { weight: 'bold', tone: null, fill: null },
      inherited: emptyStyle,
      effective: { weight: 'bold', tone: null, fill: null },
      references: [],
      canRemove: true,
      removeReason: null,
    },
  ]
  await act(async () => {
    late.resolve(accepted)
    await late.promise
  })
  expect(field('name').readOnly).toBe(true)
  expect(field('fill').value).toBe('accent')
  expect(field('fill').disabled).toBe(false)
  expect(apply().disabled).toBe(false)
  expect(screen.queryByText(/A local definition with this name already exists/)).toBeNull()
})

it('retains field buffers after 422 and 409 then cancels a pending operation without accepting its late reply', async () => {
  const initial = props()
  render(<StyleClassesPanel {...initial} />)
  await inspect()
  choose('custom')
  fireEvent.change(field('fill'), { target: { value: 'accent' } })
  initial.client.classes.mockRejectedValueOnce(new TemplatePreviewError(422, 'The style declaration is ambiguous'))
  fireEvent.click(apply())
  await screen.findByText('The style declaration is ambiguous')
  expect(field('fill').value).toBe('accent')
  initial.client.classes.mockRejectedValueOnce(new TemplatePreviewError(409, 'The saved source changed'))
  fireEvent.click(apply())
  await waitFor(() => expect(initial.onConflict).toHaveBeenCalledOnce())
  expect(initial.onApply).not.toHaveBeenCalled()
  const late = deferred<Envelope<StyleClasses>>()
  initial.client.classes.mockReturnValueOnce(late.promise)
  fireEvent.click(apply())
  const request = initial.client.classes.mock.lastCall![1]
  fireEvent.click(screen.getByRole('button', { name: 'Cancel style changes' }))
  await act(async () => {
    late.resolve(response(request, request.text + '; late'))
    await late.promise
  })
  expect(initial.onApply).not.toHaveBeenCalled()
  expect(field('fill').value).toBe('')
  expect(apply().disabled).toBe(true)
})

it('rejects a response that labels another captured document as the root layout', async () => {
  const initial = props()
  initial.client.classes.mockImplementationOnce(async (_case, request) => {
    const reply = response(request)
    reply.data.source.document = snapshot.data.documents.find((item) => item.role === 'schema')!.document
    return reply
  })
  render(<StyleClassesPanel {...initial} />)
  fireEvent.click(screen.getByRole('button', { name: 'Inspect style classes' }))
  await screen.findByRole('alert')
  expect(screen.queryByLabelText('Style class')).toBeNull()
  expect(initial.onApply).not.toHaveBeenCalled()
})
