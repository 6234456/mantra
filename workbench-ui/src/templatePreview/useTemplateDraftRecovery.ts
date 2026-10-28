import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { Envelope } from '../types'
import type { TemplateSources } from './client'
import { createBackup, matchBackup, parseBackup, type RecoveryDraft, type RecoveryDraftState } from './recovery'
import { clearRecoveryStorage, readRecoveryStorage, recoveryStorageKey, writeRecoveryStorage } from './recoveryStorage'

export interface RecoveryContext extends RecoveryDraftState {
  workspaceId?: string
  caseId: string
  snapshot?: Envelope<TemplateSources>
  loading: boolean
  conflict: boolean
}

export interface RecoveryCopy {
  raw: string
  backup: ReturnType<typeof parseBackup>
}

type RecoveryState = 'idle' | 'waiting' | 'saved' | 'paused' | 'error'

function storageIdentity(workspaceId: string | undefined, caseId: string): string {
  try {
    return typeof workspaceId === 'string' &&
      /^[a-f0-9]{64}$/.test(workspaceId) &&
      typeof caseId === 'string' &&
      caseId.trim()
      ? recoveryStorageKey(workspaceId, caseId)
      : ''
  } catch {
    return ''
  }
}

export function recoveryContextChanged(context: RecoveryContext): boolean {
  return (
    !!context.snapshot &&
    (context.snapshot.data.documents.some(
      (source) => source.editable && context.documents[source.handle] !== source.text,
    ) ||
      Object.keys(context.inputs).length > 0 ||
      context.pending.layoutSettings.length > 0 ||
      context.pending.styleClass !== null ||
      !!context.pending.schemaProperty)
  )
}

/** Browser copies contain source and raw fields only. They never carry calculation authority. */
export function useTemplateDraftRecovery({
  context,
  getCurrent,
  onRestore,
  storage,
}: {
  context: RecoveryContext
  getCurrent: () => RecoveryContext
  onRestore: (draft: RecoveryDraft, mode: 'local' | 'import') => boolean
  storage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
}) {
  const [local, setLocal] = useState<RecoveryCopy>()
  const [imported, setImported] = useState<RecoveryCopy>()
  const [status, setStatus] = useState<{ state: RecoveryState; message: string }>({ state: 'idle', message: '' })
  const [retry, setRetry] = useState(0)
  const latest = useRef({ context, getCurrent, onRestore })
  useLayoutEffect(() => {
    latest.current = { context, getCurrent, onRestore }
  })
  const identity = storageIdentity(context.workspaceId, context.caseId)
  const activeIdentity = useRef('')
  const expectedRaw = useRef<string | null>(null)
  const owned = useRef(false)
  const paused = useRef(true)
  const suspended = useRef<Envelope<TemplateSources> | undefined>(undefined)
  const generation = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  const cancel = useCallback(() => {
    generation.current++
    if (timer.current !== undefined) clearTimeout(timer.current)
    timer.current = undefined
  }, [])

  const failure = useCallback((message: string, state: 'error' | 'paused' = 'error') => {
    setStatus({ state, message })
  }, [])

  const flush = useCallback(() => {
    const current = latest.current.getCurrent()
    if (
      !current.workspaceId ||
      !current.caseId ||
      !current.snapshot ||
      current.loading ||
      paused.current ||
      suspended.current ||
      activeIdentity.current !== storageIdentity(current.workspaceId, current.caseId)
    )
      return
    if (!recoveryContextChanged(current)) {
      if (!owned.current || expectedRaw.current === null) return
      const result = clearRecoveryStorage(current.workspaceId, current.caseId, expectedRaw.current, storage)
      if (result.status !== 'cleared') {
        paused.current = true
        failure(result.reason, result.status === 'conflict' ? 'paused' : 'error')
        return
      }
      expectedRaw.current = null
      owned.current = false
      setStatus({ state: 'idle', message: 'No unsaved draft changes need a browser backup.' })
      return
    }
    const backup = createBackup(current.snapshot, current)
    if (backup.status !== 'ready') {
      failure(backup.reason)
      return
    }
    // Identical content does not need a new timestamp-only write.
    if (expectedRaw.current !== null) {
      const previous = parseBackup(expectedRaw.current)
      if (
        previous.status === 'ready' &&
        JSON.stringify({ ...previous.draft, savedAt: '' }) === JSON.stringify({ ...backup.draft, savedAt: '' })
      ) {
        const stored = readRecoveryStorage(current.workspaceId, current.caseId, storage)
        if (stored.status === 'stored' && stored.raw === expectedRaw.current) {
          setStatus({
            state: 'saved',
            message: `Draft changes are backed up in this browser (${previous.draft.savedAt}).`,
          })
        } else {
          paused.current = true
          failure(
            stored.status === 'unavailable'
              ? stored.reason
              : 'The browser backup changed in another page. Retry to review the stored copy.',
            stored.status === 'unavailable' ? 'error' : 'paused',
          )
        }
        return
      }
    }
    const result = writeRecoveryStorage(current.workspaceId, current.caseId, backup.json, expectedRaw.current, storage)
    if (result.status !== 'written') {
      if (result.status === 'conflict') paused.current = true
      failure(result.reason, result.status === 'conflict' ? 'paused' : 'error')
      return
    }
    expectedRaw.current = result.raw
    owned.current = true
    setStatus({ state: 'saved', message: `Draft changes are backed up in this browser (${backup.draft.savedAt}).` })
  }, [failure, storage])

  useEffect(() => {
    cancel()
    activeIdentity.current = identity
    owned.current = false
    paused.current = true
    expectedRaw.current = null
    setLocal(undefined)
    setImported(undefined)
    if (!identity || !context.workspaceId || !context.caseId) {
      setStatus({
        state: 'idle',
        message:
          'Browser recovery is unavailable: the server did not provide a valid workspace identity. Draft preview remains available.',
      })
      return cancel
    }
    const read = readRecoveryStorage(context.workspaceId, context.caseId, storage)
    if (read.status === 'unavailable') failure(read.reason)
    else if (read.status === 'stored') {
      expectedRaw.current = read.raw
      setLocal({ raw: read.raw, backup: read.backup })
      setStatus({
        state: 'paused',
        message: 'A browser draft was found. Restore or discard it before automatic backup can replace it.',
      })
    } else {
      paused.current = false
      setStatus({
        state: 'idle',
        message: 'No browser draft was found. Edited source and fields will be backed up here.',
      })
    }
    return cancel
    // Identity, rather than the baseline revision, owns the storage slot.
  }, [identity, context.workspaceId, context.caseId, storage, cancel, failure])

  useEffect(() => {
    if (suspended.current && context.snapshot && context.snapshot !== suspended.current && !context.loading) {
      suspended.current = undefined
    }
    cancel()
    if (!identity || context.loading || !context.snapshot || paused.current || suspended.current) return
    const current = latest.current.getCurrent()
    if (recoveryContextChanged(current)) {
      const candidate = current.snapshot ? createBackup(current.snapshot, current) : undefined
      if (candidate?.status === 'invalid') {
        failure(candidate.reason)
        return
      }
      const previous = expectedRaw.current === null ? undefined : parseBackup(expectedRaw.current)
      if (
        candidate?.status === 'ready' &&
        (previous?.status !== 'ready' ||
          JSON.stringify({ ...previous.draft, savedAt: '' }) !== JSON.stringify({ ...candidate.draft, savedAt: '' }))
      ) {
        setStatus({ state: 'waiting', message: 'Current draft changes have not been backed up yet.' })
      }
    }
    const epoch = generation.current
    timer.current = setTimeout(() => {
      if (generation.current === epoch) flush()
    }, 200)
    return cancel
    // Source and raw-field changes drive writes, not preview responses or backup status renders.
  }, [
    identity,
    context.snapshot,
    context.documents,
    context.inputs,
    context.pending,
    context.loading,
    retry,
    cancel,
    flush,
    failure,
  ])

  useEffect(() => {
    function leaving() {
      cancel()
      flush()
    }
    window.addEventListener('pagehide', leaving)
    window.addEventListener('beforeunload', leaving)
    return () => {
      window.removeEventListener('pagehide', leaving)
      window.removeEventListener('beforeunload', leaving)
      cancel()
    }
    // The ref supplies the last committed source and raw fields at the lifecycle event.
  }, [cancel, flush])

  useEffect(() => {
    function externalChange(event: StorageEvent) {
      if (
        !activeIdentity.current ||
        (event.key !== null && event.key !== activeIdentity.current) ||
        (event.key !== null && event.newValue === expectedRaw.current)
      )
        return
      if (storage && event.storageArea && event.storageArea !== storage) return
      cancel()
      paused.current = true
      failure(
        'Another page changed the browser backup. Retry to review that copy before automatic replacement.',
        'paused',
      )
    }
    window.addEventListener('storage', externalChange)
    return () => window.removeEventListener('storage', externalChange)
  }, [cancel, failure, storage])

  const backup = useMemo(
    () =>
      context.snapshot
        ? createBackup(context.snapshot, {
            documents: context.documents,
            inputs: context.inputs,
            pending: context.pending,
          })
        : undefined,
    [context.snapshot, context.documents, context.inputs, context.pending],
  )

  function compatibility(copy: RecoveryCopy | undefined, mode: 'local' | 'import') {
    if (!copy) return undefined
    if (copy.backup.status !== 'ready')
      return { compatible: false, foreignWorkspace: false, reason: copy.backup.reason }
    if (context.conflict)
      return {
        compatible: false,
        foreignWorkspace: false,
        reason: 'Resolve the source revision conflict before restoring a draft.',
      }
    if (context.loading || !context.snapshot)
      return {
        compatible: false,
        foreignWorkspace: false,
        reason: 'Capture a fresh source snapshot before restoring this draft.',
      }
    return matchBackup(copy.backup.draft, context.snapshot, mode)
  }

  function discard() {
    cancel()
    const current = latest.current.getCurrent()
    if (!current.workspaceId || !current.caseId) return
    const result = clearRecoveryStorage(current.workspaceId, current.caseId, expectedRaw.current, storage)
    if (result.status !== 'cleared') {
      paused.current = true
      failure(result.reason, result.status === 'conflict' ? 'paused' : 'error')
      return
    }
    expectedRaw.current = null
    owned.current = false
    paused.current = false
    setLocal(undefined)
    setStatus({ state: 'idle', message: 'Browser draft discarded.' })
    setRetry((value) => value + 1)
  }

  function prepareReload() {
    cancel()
    suspended.current = latest.current.getCurrent().snapshot
    discard()
  }

  function retryStorage() {
    cancel()
    const current = latest.current.getCurrent()
    if (!current.workspaceId || !current.caseId) return
    const read = readRecoveryStorage(current.workspaceId, current.caseId, storage)
    if (read.status === 'unavailable') {
      failure(read.reason)
      return
    }
    if (read.status === 'stored' && (!owned.current || read.raw !== expectedRaw.current)) {
      expectedRaw.current = read.raw
      owned.current = false
      paused.current = true
      setLocal({ raw: read.raw, backup: read.backup })
      failure('A browser draft was found. Restore or discard it before automatic backup can replace it.', 'paused')
      return
    }
    if (read.status === 'empty') {
      expectedRaw.current = null
      owned.current = false
      setLocal(undefined)
    }
    paused.current = false
    setRetry((value) => value + 1)
  }

  function restore(copy: RecoveryCopy | undefined, mode: 'local' | 'import') {
    if (!copy || copy.backup.status !== 'ready') return
    const current = latest.current.getCurrent()
    if (!current.snapshot || current.loading || current.conflict) return
    const match = matchBackup(copy.backup.draft, current.snapshot, mode)
    if (!match.compatible) {
      failure(match.reason ?? 'This backup does not match the captured source.')
      return
    }
    if (!latest.current.onRestore(copy.backup.draft, mode)) return
    cancel()
    if (mode === 'import' && local && !owned.current) {
      paused.current = true
      setStatus({
        state: 'paused',
        message:
          'Imported draft restored. Restore or discard the existing browser copy before automatic backup can replace it.',
      })
      return
    }
    owned.current = true
    paused.current = false
    suspended.current = undefined
    setLocal(undefined)
    setStatus({ state: 'waiting', message: 'Draft restored. Its source and fields have not been validated or saved.' })
    setRetry((value) => value + 1)
  }

  function validateImport(raw: string) {
    setImported({ raw, backup: parseBackup(raw) })
  }
  function importFailure(reason: string) {
    setImported({ raw: '', backup: { status: 'invalid', reason } })
  }

  return {
    local,
    imported,
    backup,
    status,
    localMatch: compatibility(local, 'local'),
    importedMatch: compatibility(imported, 'import'),
    restoreLocal: () => restore(local, 'local'),
    restoreImported: () => restore(imported, 'import'),
    discard,
    prepareReload,
    retryStorage,
    flushNow: () => {
      cancel()
      flush()
    },
    leaveContext: cancel,
    validateImport,
    importFailure,
    clearImported: () => setImported(undefined),
  }
}
