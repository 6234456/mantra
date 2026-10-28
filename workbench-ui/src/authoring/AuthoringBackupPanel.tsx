import { useEffect, useRef, useState } from 'react'
import { createRecoveryBackup, importRecoveryBackup, recoveryByteLimit } from './recovery'
import type { RecoveryDraft, RecoveryState } from './recovery'
import type { AuthoringRecording } from './service'
import type { DraftRecoveryStatus } from './useDraftRecovery'

export interface AuthoringBackupPanelProps {
  recording: AuthoringRecording
  state: RecoveryState
  inputs: Record<string, string>
  recoveryStatus: DraftRecoveryStatus
  onImport: (draft: RecoveryDraft) => void
}

export function recoveryStatusMessage(result: DraftRecoveryStatus): string {
  switch (result.status) {
    case 'idle':
      return 'Browser recovery has not copied a user draft yet.'
    case 'writing':
      return `Updating browser recovery for draft #${result.draftSequence}…`
    case 'written':
      return `Browser recovery updated for draft #${result.draftSequence}. This local draft copy does not save the template.`
    case 'superseded':
      return 'A newer recovery request took precedence; this draft copy was not confirmed in browser storage.'
    case 'invalid':
    case 'unavailable':
      return `Browser recovery ${result.status}: ${result.reason}`
  }
}

/** Portable source backups are deliberately separate from engine validation and template saving. */
export function AuthoringBackupPanel({
  recording,
  state,
  inputs,
  recoveryStatus,
  onImport,
}: AuthoringBackupPanelProps) {
  const [json, setJson] = useState('')
  const [candidate, setCandidate] = useState<RecoveryDraft>()
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const generation = useRef(0)
  const mounted = useRef(true)
  useEffect(() => {
    generation.current++
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  function begin(): number {
    const request = ++generation.current
    setBusy(true)
    setError('')
    setMessage('')
    setCandidate(undefined)
    return request
  }

  function current(request: number): boolean {
    return mounted.current && generation.current === request
  }

  async function download() {
    const request = begin()
    const result = await createRecoveryBackup(recording, state, inputs)
    if (!current(request)) return
    setBusy(false)
    if (result.status !== 'ready') {
      setError(result.reason)
      return
    }
    setJson(result.json)
    let url: string | undefined
    try {
      url = URL.createObjectURL(new Blob([result.json], { type: 'application/json;charset=utf-8' }))
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `${recording.case.replace(/[^a-zA-Z0-9._-]+/g, '-')}.draft-backup.json`
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      setMessage(
        `Backup JSON prepared from draft #${result.draftSequence}; engine revalidation is required after import.`,
      )
    } catch {
      setError(
        'The browser could not start the download. Copy the complete Backup JSON below to keep this source draft.',
      )
    } finally {
      if (url !== undefined) {
        const temporaryUrl = url
        setTimeout(() => URL.revokeObjectURL(temporaryUrl), 0)
      }
    }
  }

  async function validateJson(text: string, request: number) {
    const result = await importRecoveryBackup(recording, text)
    if (!current(request)) return
    setBusy(false)
    if (result.status !== 'ready') {
      setError(result.reason)
      return
    }
    setCandidate(result.draft)
    setMessage('The backup passed source and history checks. The imported draft still needs an engine preview.')
  }

  async function importFile(file: File) {
    const request = begin()
    if (file.size > recoveryByteLimit) {
      setBusy(false)
      setError('Draft backup exceeds the 2 MiB limit; the file was not read or restored.')
      return
    }
    try {
      const text = await file.text()
      if (!current(request)) return
      setJson(text)
      await validateJson(text, request)
    } catch {
      if (current(request)) {
        setBusy(false)
        setError('The selected draft backup could not be read; the current editor draft was kept.')
      }
    }
  }

  return (
    <details className="author-card author-backup" data-author-region="backup">
      <summary>Draft recovery and JSON backup</summary>
      <p role="status" aria-live="polite" data-testid="draft-recovery-status">
        {recoveryStatusMessage(recoveryStatus)}
      </p>
      <p>
        A backup contains the complete source set, source history and unsubmitted buffers. It contains no trusted
        preview or validation. The maximum size is 2 MiB; source and history are never truncated.
      </p>
      <button disabled={busy} onClick={() => void download()}>
        Download draft backup JSON
      </button>
      <label className="author-field">
        Import draft backup JSON file
        <input
          type="file"
          accept=".json,application/json"
          disabled={busy}
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = ''
            if (file) void importFile(file)
          }}
        />
      </label>
      <label className="author-field">
        Backup JSON
        <textarea
          rows={6}
          maxLength={recoveryByteLimit}
          value={json}
          onChange={(event) => {
            generation.current++
            setBusy(false)
            setJson(event.target.value)
            setCandidate(undefined)
            setMessage('')
            setError('')
          }}
        />
      </label>
      <button disabled={busy || !json.trim()} onClick={() => void validateJson(json, begin())}>
        Validate backup JSON
      </button>
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
      {candidate && (
        <section aria-label="Imported draft review">
          <h3>Review imported draft</h3>
          <p>
            {Object.keys(candidate.documents).length} source documents · {candidate.history.past.length} undo
            transactions · {candidate.history.future.length} redo transactions · {Object.keys(candidate.inputs).length}{' '}
            unsubmitted buffers
          </p>
          <p>
            Saved source baseline: <code>{candidate.baseDigest}</code>
          </p>
          <p>
            Restoring replaces the current editor draft. It does not save the template or restore a trusted preview.
          </p>
          <button
            onClick={() => {
              onImport(candidate)
              setCandidate(undefined)
              setMessage('Imported source draft selected for restore; the engine must validate it again.')
            }}
          >
            Restore imported draft
          </button>
        </section>
      )}
    </details>
  )
}
