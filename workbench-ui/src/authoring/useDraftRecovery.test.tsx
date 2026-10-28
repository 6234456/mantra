// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { writeRecovery } from './recovery'
import type { RecoveryState, RecoveryWriteResult } from './recovery'
import { useDraftRecovery } from './useDraftRecovery'
import type { AuthoringRecording } from './service'

vi.mock('./recovery', () => ({ writeRecovery: vi.fn() }))

const write = vi.mocked(writeRecovery)
const recording = { case: 'test.mantra' } as AuthoringRecording
const state: RecoveryState = {
  baseDocuments: { 'test.mantra': '(source)' },
  baseRevisions: { 'test.mantra': 'saved-revision' },
  documents: { 'test.mantra': '(source)' },
  history: { past: [], future: [] },
  draftSequence: 0,
}

beforeEach(() => write.mockReset())
afterEach(cleanup)

it('does not announce a browser recovery until the complete current request is confirmed', async () => {
  let acknowledge!: (result: RecoveryWriteResult) => void
  write.mockReturnValueOnce(new Promise((resolve) => (acknowledge = resolve)))
  const inputs = { field: 'Pending input' }
  const { result } = renderHook(() => useDraftRecovery({ recording, state, inputs, enabled: true }))
  expect(result.current).toEqual({ status: 'writing', draftSequence: 0 })
  expect(write).toHaveBeenCalledWith(recording, expect.objectContaining(state), inputs)
  await act(async () => acknowledge({ status: 'written', draftSequence: 0, savedAt: 'now', key: 'key' }))
  expect(result.current).toEqual({ status: 'written', draftSequence: 0, savedAt: 'now', key: 'key' })
})

it('ignores a late acknowledgment for an older unsubmitted buffer even when the draft sequence is unchanged', async () => {
  let acknowledgeOlder!: (result: RecoveryWriteResult) => void
  write
    .mockReturnValueOnce(new Promise((resolve) => (acknowledgeOlder = resolve)))
    .mockResolvedValueOnce({ status: 'written', draftSequence: 0, savedAt: 'newer', key: 'newer' })
  const { result, rerender } = renderHook((inputs) => useDraftRecovery({ recording, state, inputs, enabled: true }), {
    initialProps: { field: 'Older buffer' },
  })
  rerender({ field: 'Newest buffer' })
  await waitFor(() => expect(result.current).toMatchObject({ status: 'written', savedAt: 'newer' }))
  await act(async () => acknowledgeOlder({ status: 'unavailable', draftSequence: 0, reason: 'An older write failed' }))
  expect(result.current).toEqual({ status: 'written', draftSequence: 0, savedAt: 'newer', key: 'newer' })
  expect(write.mock.lastCall?.[2]).toEqual({ field: 'Newest buffer' })
})

it('shows the new request immediately and never reuses a successful status for newer source edits', async () => {
  write.mockResolvedValueOnce({ status: 'written', draftSequence: 0, savedAt: 'earlier', key: 'earlier' })
  let acknowledge!: (result: RecoveryWriteResult) => void
  write.mockReturnValueOnce(new Promise((resolve) => (acknowledge = resolve)))
  const inputs = {}
  const { result, rerender } = renderHook(
    (source) => useDraftRecovery({ recording, state: source, inputs, enabled: true }),
    { initialProps: state },
  )
  await waitFor(() => expect(result.current.status).toBe('written'))
  rerender({ ...state, documents: { 'test.mantra': '(changed source)' }, draftSequence: 1 })
  expect(result.current).toEqual({ status: 'writing', draftSequence: 1 })
  await act(async () => acknowledge({ status: 'unavailable', draftSequence: 1, reason: 'Browser storage is full' }))
  expect(result.current).toEqual({ status: 'unavailable', draftSequence: 1, reason: 'Browser storage is full' })
})

it('does not rewrite unchanged source data after unrelated editor state renders', async () => {
  write.mockResolvedValue({ status: 'written', draftSequence: 0, savedAt: 'confirmed', key: 'key' })
  const inputs = { field: 'Input' }
  const { result, rerender } = renderHook(
    (source) => useDraftRecovery({ recording, state: source, inputs, enabled: true }),
    { initialProps: state },
  )
  await waitFor(() => expect(result.current.status).toBe('written'))
  rerender({ ...state })
  expect(write).toHaveBeenCalledOnce()
  expect(result.current.status).toBe('written')
})

it('keeps recovery idle before user edits and disregards a result after recovery is disabled', async () => {
  let acknowledge!: (result: RecoveryWriteResult) => void
  write.mockReturnValueOnce(new Promise((resolve) => (acknowledge = resolve)))
  const inputs = {}
  const { result, rerender } = renderHook((enabled) => useDraftRecovery({ recording, state, inputs, enabled }), {
    initialProps: false,
  })
  expect(result.current).toEqual({ status: 'idle' })
  expect(write).not.toHaveBeenCalled()
  rerender(true)
  expect(result.current.status).toBe('writing')
  rerender(false)
  await act(async () => acknowledge({ status: 'written', draftSequence: 0, savedAt: 'old', key: 'old' }))
  expect(result.current).toEqual({ status: 'idle' })
})
