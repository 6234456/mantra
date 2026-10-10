import { useEffect, useMemo, useRef, useState } from 'react'
import { writeRecovery } from './recovery'
import type { RecoveryState, RecoveryWriteResult } from './recovery'
import type { AuthoringRecording } from './service'

export type DraftRecoveryStatus =
  { status: 'idle' } | { status: 'writing'; draftSequence: number } | RecoveryWriteResult

/** A result belongs to its complete source snapshot and pending buffers, not only its draft sequence. */
export function useDraftRecovery({
  recording,
  state,
  inputs,
  enabled,
}: {
  recording: AuthoringRecording
  state: RecoveryState
  inputs: Record<string, string>
  enabled: boolean
}): DraftRecoveryStatus {
  const snapshot = useMemo(
    () => ({
      baseDocuments: state.baseDocuments,
      baseRevisions: state.baseRevisions,
      documents: state.documents,
      history: state.history,
      draftSequence: state.draftSequence,
      inputs,
    }),
    [state.baseDocuments, state.baseRevisions, state.documents, state.history, state.draftSequence, inputs],
  )
  const [completed, setCompleted] = useState<{
    recording: AuthoringRecording
    snapshot: typeof snapshot
    result: RecoveryWriteResult
  }>()
  const requests = useRef(0)
  useEffect(() => {
    const request = ++requests.current
    let active = true
    if (enabled)
      void writeRecovery(recording, snapshot, snapshot.inputs).then((result) => {
        if (active && request === requests.current) setCompleted({ recording, snapshot, result })
      })
    return () => {
      active = false
    }
  }, [recording, snapshot, enabled])
  if (!enabled) return { status: 'idle' }
  return completed?.recording === recording && completed.snapshot === snapshot
    ? completed.result
    : { status: 'writing', draftSequence: snapshot.draftSequence }
}
