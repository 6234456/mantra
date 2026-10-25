import { useEffect, useRef, useState } from 'react'
import { recoveryByteLimit } from './recovery'
import type { useTemplateDraftRecovery } from './useTemplateDraftRecovery'

type Recovery = ReturnType<typeof useTemplateDraftRecovery>

function download(json: string, name: string) {
  const url = URL.createObjectURL(new Blob([json], { type: 'application/json;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** Explicit recovery choices keep stored bytes separate from the current editable draft. */
export function TemplateRecoveryPanel({ recovery, restored }: { recovery: Recovery; restored: boolean }) {
  const [json, setJson] = useState('')
  const [fileError, setFileError] = useState('')
  const [filePending, setFilePending] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const sequence = useRef(0)
  useEffect(
    () => () => {
      sequence.current++
    },
    [],
  )
  useEffect(() => {
    if (recovery.local || recovery.imported || restored || recovery.status.state === 'error') setExpanded(true)
  }, [recovery.local, recovery.imported, restored, recovery.status.state])

  async function readFile(file: File | undefined) {
    if (!file) return
    const version = ++sequence.current
    recovery.clearImported()
    setFilePending(true)
    setFileError('')
    if (file.size > recoveryByteLimit) {
      const reason = 'The backup file exceeds the 2 MiB limit. The current draft was not changed.'
      setFileError(reason)
      recovery.importFailure(reason)
      setFilePending(false)
      return
    }
    try {
      const raw = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
      if (sequence.current !== version) return
      setJson(raw)
      recovery.validateImport(raw)
    } catch {
      if (sequence.current !== version) return
      const reason = 'The backup file could not be read as valid UTF-8. The current draft was not changed.'
      setFileError(reason)
      recovery.importFailure(reason)
    } finally {
      if (sequence.current === version) setFilePending(false)
    }
  }

  const local = recovery.local
  const imported = recovery.imported
  const stem =
    recovery.backup?.status === 'ready' ? recovery.backup.draft.caseId.replace(/[^a-zA-Z0-9._-]/g, '-') : 'template'

  return (
    <details
      className="sheet template-draft-recovery"
      data-testid="template-recovery-panel"
      open={expanded}
      onToggle={(event) => setExpanded(event.currentTarget.open)}
    >
      <summary>Draft recovery and JSON backup</summary>
      <p>
        Browser recovery keeps source, raw example inputs and unapplied fields in this browser. It does not save
        template files or trusted engine results.
      </p>
      <p role="status" data-testid="template-recovery-status" data-recovery-state={recovery.status.state}>
        {recovery.status.message}
      </p>
      {restored && (
        <p role="status" data-testid="template-restored-status">
          Restored · not validated. Preview the complete draft after applying or canceling pending fields.
        </p>
      )}
      {(recovery.status.state === 'error' || recovery.status.state === 'paused') && (
        <button onClick={recovery.retryStorage}>Retry browser backup</button>
      )}
      {local && (
        <section aria-label="Browser recovery copy">
          <h3>A browser draft was found.</h3>
          {local.backup.status === 'ready' && <p>Browser copy from {local.backup.draft.savedAt}.</p>}
          <p data-testid="template-recovery-local-match">
            {recovery.localMatch?.compatible
              ? 'This browser draft matches the captured sources. It has not been validated or saved.'
              : recovery.localMatch?.reason}
          </p>
          <button disabled={!recovery.localMatch?.compatible} onClick={recovery.restoreLocal}>
            Restore browser draft
          </button>
          <button onClick={recovery.discard}>Discard browser draft</button>
          <button onClick={() => download(local.raw, `${stem}-browser-draft.json`)}>Download browser draft</button>
        </section>
      )}
      <h3>Portable draft backup</h3>
      <p>
        Downloading preserves the current draft without writing workspace files. Inspecting a file does not restore it.
      </p>
      <button
        disabled={recovery.backup?.status !== 'ready'}
        aria-describedby={recovery.backup?.status === 'invalid' ? 'template-backup-export-reason' : undefined}
        onClick={() => {
          if (recovery.backup?.status === 'ready') download(recovery.backup.json, `${stem}-draft.json`)
        }}
      >
        Download draft backup
      </button>
      {recovery.backup?.status === 'invalid' && <p id="template-backup-export-reason">{recovery.backup.reason}</p>}
      <label>
        Import backup file
        <input
          type="file"
          accept=".json,application/json"
          aria-label="Import draft backup file"
          onChange={(event) => {
            void readFile(event.target.files?.[0])
            event.target.value = ''
          }}
        />
      </label>
      <label>
        Draft backup JSON
        <textarea
          aria-label="Draft backup JSON"
          spellCheck={false}
          value={json}
          onChange={(event) => {
            sequence.current++
            recovery.clearImported()
            setFilePending(false)
            setJson(event.target.value)
            setFileError('')
          }}
        />
      </label>
      <button disabled={filePending} onClick={() => recovery.validateImport(json)}>
        Validate backup JSON
      </button>
      {fileError && <p role="alert">{fileError}</p>}
      {imported && (
        <section aria-label="Imported recovery copy">
          <p data-testid="template-recovery-import-match">
            {recovery.importedMatch?.compatible
              ? recovery.importedMatch.foreignWorkspace
                ? 'This backup is from another workspace. Its case and captured source graph match; Restore explicitly to use it here.'
                : 'Backup JSON matches this captured source. Restore explicitly to replace the current draft.'
              : recovery.importedMatch?.reason}
          </p>
          <button
            disabled={filePending || imported.raw !== json || !recovery.importedMatch?.compatible}
            onClick={recovery.restoreImported}
          >
            Restore imported draft
          </button>
          {imported.raw && (
            <button onClick={() => download(imported.raw, `${stem}-imported-draft.json`)}>
              Download imported draft
            </button>
          )}
        </section>
      )}
    </details>
  )
}
