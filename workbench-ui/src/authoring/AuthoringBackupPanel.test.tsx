// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AuthoringBackupPanel, recoveryStatusMessage } from './AuthoringBackupPanel'
import { authoringReducer, createAuthoringState } from './model'
import type { AuthoringState } from './model'
import { createRecoveryBackup, recoveryByteLimit } from './recovery'
import * as recovery from './recovery'
import { createRecordedService } from './simulated/recordedService'
import type { AuthoringRecording } from './service'
import recordingJson from './recording/recording.json'

const cryptoModule = 'node:crypto'
const { webcrypto } = await import(cryptoModule)
const recording: AuthoringRecording = recordingJson
let state: AuthoringState
let inputs: Record<string, string>

beforeEach(() => {
  vi.stubGlobal('crypto', webcrypto)
  const documents = Object.fromEntries(Object.entries(recording.base).map(([path, source]) => [path, source.text]))
  const initial = createAuthoringState({
    documents,
    baseRevisions: Object.fromEntries(Object.entries(recording.base).map(([path, source]) => [path, source.sha256])),
  })
  const service = createRecordedService(recording)
  const owner = service.owners(documents).find((item) => item.nodeId === 'unallocated' && item.property === 'label')!
  const operation = { handle: owner.handle, op: 'setText' as const, value: 'Unallocated request' }
  const result = service.apply(operation, documents)
  if (!result.ok) throw new Error(result.reason)
  state = authoringReducer(initial, {
    type: 'transaction',
    label: 'label edit',
    patches: result.patches,
    ownerHandles: [owner.handle],
    operation,
  })
  inputs = {
    [owner.handle]: '未提交文本',
    ['source:' + owner.document]: state.documents[owner.document] + '; Pending source\n',
  }
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function mount() {
  const onImport = vi.fn()
  const rendered = render(
    <AuthoringBackupPanel
      recording={recording}
      state={state}
      inputs={inputs}
      recoveryStatus={{
        status: 'unavailable',
        draftSequence: 1,
        reason: 'Browser storage is full; this draft was not backed up in this browser.',
      }}
      onImport={onImport}
    />,
  )
  fireEvent.click(screen.getByText('Draft recovery and JSON backup'))
  return { ...rendered, onImport }
}

async function backupJson(): Promise<string> {
  const result = await createRecoveryBackup(recording, state, inputs)
  if (result.status !== 'ready') throw new Error(result.reason)
  return result.json
}

it('reports failed recovery accurately and downloads a complete source backup independent of browser storage', async () => {
  vi.stubGlobal('localStorage', undefined)
  const createObjectURL = vi.fn(() => 'blob:task-backup')
  const revokeObjectURL = vi.fn()
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = createObjectURL
      static revokeObjectURL = revokeObjectURL
    },
  )
  let download = ''
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    download = this.download
    expect(this.href).toBe('blob:task-backup')
  })
  mount()
  expect(screen.getByTestId('draft-recovery-status').textContent).toContain('Browser storage is full')
  fireEvent.click(screen.getByRole('button', { name: 'Download draft backup JSON' }))
  await screen.findByText('Backup JSON prepared from draft #1; engine revalidation is required after import.')
  expect(click).toHaveBeenCalledOnce()
  expect(download).toBe('capped-allocation-case-demo.mantra.draft-backup.json')
  expect(createObjectURL).toHaveBeenCalledOnce()
  const payload = JSON.parse((screen.getByRole('textbox', { name: 'Backup JSON' }) as HTMLTextAreaElement).value)
  expect(payload.documents).toEqual(state.documents)
  expect(payload.history).toEqual(state.history)
  expect(payload.inputs).toEqual(inputs)
  expect(payload).not.toHaveProperty('preview')
  expect(payload).not.toHaveProperty('validity')
  await waitFor(() => expect(revokeObjectURL).toHaveBeenCalledWith('blob:task-backup'))
  expect(document.querySelector('a[download]')).toBeNull()
})

it('validates an imported JSON draft for review and requires an explicit restore action', async () => {
  const json = await backupJson()
  const { onImport } = mount()
  fireEvent.change(screen.getByRole('textbox', { name: 'Backup JSON' }), { target: { value: json } })
  fireEvent.click(screen.getByRole('button', { name: 'Validate backup JSON' }))
  await screen.findByRole('region', { name: 'Imported draft review' })
  expect(onImport).not.toHaveBeenCalled()
  expect(screen.getByText(/4 source documents/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Restore imported draft' }))
  expect(onImport).toHaveBeenCalledExactlyOnceWith(
    expect.objectContaining({ documents: state.documents, history: state.history, inputs }),
  )
  const draft = onImport.mock.lastCall![0]
  expect(draft).not.toHaveProperty('preview')
  expect(draft).not.toHaveProperty('validity')
})

it('supports a JSON file import through the same validation and review path', async () => {
  const json = await backupJson()
  const { onImport } = mount()
  const file = new File([json], 'saved-draft.json', { type: 'application/json' })
  const text = vi.fn(async () => json)
  Object.defineProperty(file, 'text', { value: text })
  fireEvent.change(screen.getByLabelText('Import draft backup JSON file'), { target: { files: [file] } })
  await screen.findByRole('region', { name: 'Imported draft review' })
  expect(text).toHaveBeenCalledOnce()
  expect((screen.getByRole('textbox', { name: 'Backup JSON' }) as HTMLTextAreaElement).value).toBe(json)
  expect(onImport).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Restore imported draft' }))
  expect(onImport.mock.lastCall![0].inputs).toEqual(inputs)
})

it('rejects oversized files before reading them and never replaces the current draft', async () => {
  const { onImport } = mount()
  const file = new File([new Uint8Array(recoveryByteLimit + 1)], 'too-large.json')
  const text = vi.fn(async () => '')
  Object.defineProperty(file, 'text', { value: text })
  fireEvent.change(screen.getByLabelText('Import draft backup JSON file'), { target: { files: [file] } })
  expect(screen.getByRole('alert').textContent).toContain('the file was not read or restored')
  expect(text).not.toHaveBeenCalled()
  expect(onImport).not.toHaveBeenCalled()
  expect(screen.queryByRole('region', { name: 'Imported draft review' })).toBeNull()
})

it('refuses injected trusted preview fields and keeps the complete current source draft', async () => {
  const value = JSON.parse(await backupJson())
  const { onImport } = mount()
  fireEvent.change(screen.getByRole('textbox', { name: 'Backup JSON' }), {
    target: { value: JSON.stringify({ ...value, preview: { status: 'current', kind: 'valid' } }) },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Validate backup JSON' }))
  expect((await screen.findByRole('alert')).textContent).toMatch(/invalid source, revisions or history/)
  expect(onImport).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: 'Restore imported draft' })).toBeNull()
  expect(state.documents).toEqual(value.documents)
  expect(inputs).toEqual(value.inputs)
})

it('discards a late validation result after the pasted backup text changes', async () => {
  const json = await backupJson()
  const imported = await recovery.importRecoveryBackup(recording, json)
  let complete!: (result: typeof imported) => void
  const pending = new Promise<typeof imported>((resolve) => (complete = resolve))
  vi.spyOn(recovery, 'importRecoveryBackup').mockReturnValueOnce(pending)
  const { onImport } = mount()
  const text = screen.getByRole('textbox', { name: 'Backup JSON' })
  fireEvent.change(text, { target: { value: json } })
  fireEvent.click(screen.getByRole('button', { name: 'Validate backup JSON' }))
  fireEvent.change(text, { target: { value: '{"different":"text"}' } })
  await act(async () => {
    complete(imported)
    await pending
  })
  expect(screen.queryByRole('region', { name: 'Imported draft review' })).toBeNull()
  expect((text as HTMLTextAreaElement).value).toBe('{"different":"text"}')
  expect(onImport).not.toHaveBeenCalled()
})

it('exposes current write feedback for the always-visible editor status without promising a template save', () => {
  expect(recoveryStatusMessage({ status: 'writing', draftSequence: 3 })).toBe('Updating browser recovery for draft #3…')
  expect(recoveryStatusMessage({ status: 'written', draftSequence: 3, savedAt: 'now', key: 'key' })).toContain(
    'This local draft copy does not save the template.',
  )
  expect(recoveryStatusMessage({ status: 'unavailable', draftSequence: 3, reason: 'Storage disabled' })).toBe(
    'Browser recovery unavailable: Storage disabled',
  )
})
