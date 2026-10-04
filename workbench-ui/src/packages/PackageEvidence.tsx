import { useEffect, useState } from 'react'
import { PackageData } from './PackageData'
import type { MigrationPreview, MountedPackage, ParameterSource } from './PackageData'

/** Added beside existing workbench pages; every number and source comes from the host projection. */
export function PackageEvidence({
  data,
  caseId,
  revision,
  refresh,
  onSaved,
  lang,
}: {
  data: PackageData
  caseId: string
  revision?: string
  refresh: number
  onSaved: () => void
  lang: 'de' | 'en'
}) {
  const [packages, setPackages] = useState<MountedPackage[]>([])
  const [sources, setSources] = useState<ParameterSource[]>([])
  const [editable, setEditable] = useState(false)
  const [target, setTarget] = useState('')
  const [preview, setPreview] = useState<MigrationPreview>()
  const [status, setStatus] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setPreview(undefined)
    setTarget('')
    setSources([])
    setEditable(false)
    Promise.all([data.index(controller.signal), data.wrapper(caseId, 'parameters', controller.signal)])
      .then(([index, document]) => {
        if (controller.signal.aborted) return
        setPackages(index.data.packages)
        setSources(document.data.parameterSources ?? [])
        setEditable(document.data.binding?.editableCase === true)
        setStatus(document.data.succeeded ? '' : (document.data.diagnostics?.[0]?.message ?? 'Calculation failed'))
      })
      .catch((error) => {
        if (!controller.signal.aborted) setStatus(String(error))
      })
    return () => controller.abort()
  }, [data, caseId, refresh])
  async function act(action: () => Promise<unknown>, saved = false) {
    setBusy(true)
    setStatus(lang === 'de' ? 'Wird geprüft…' : 'Checking…')
    try {
      await action()
      setStatus(lang === 'de' ? 'Abgeschlossen.' : 'Completed.')
      if (saved) {
        setPreview(undefined)
        onSaved()
      }
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <section
      className="sheet package-evidence"
      aria-label={lang === 'de' ? 'Paketquellen und Migration' : 'Package sources and migration'}
    >
      <details>
        <summary>
          {lang === 'de' ? 'Paketversionen und Parameterquellen' : 'Package versions and parameter sources'}
        </summary>
        <ul>
          {packages.map((pkg) => (
            <li key={pkg.mount}>
              <strong>
                {pkg.id}@{pkg.version}
              </strong>{' '}
              · {pkg.resourceCount} {lang === 'de' ? 'Ressourcen' : 'resources'} · {pkg.caseCount}{' '}
              {lang === 'de' ? 'Fälle' : 'cases'}
              <div>
                <small>{pkg.revision}</small>
              </div>
            </li>
          ))}
        </ul>
        <p>{lang === 'de' ? 'Paketressourcen bleiben schreibgeschützt.' : 'Package resources remain read-only.'}</p>
        <table>
          <caption>
            {lang === 'de'
              ? 'Datierte Parameterquellen (Enddatum exklusiv)'
              : 'Dated parameter sources (end date exclusive)'}
          </caption>
          <thead>
            <tr>
              <th>{lang === 'de' ? 'Fall / Parameter' : 'Case / parameter'}</th>
              <th>Set</th>
              <th>{lang === 'de' ? 'Stichtag / Gültigkeit' : 'Date / validity'}</th>
              <th>{lang === 'de' ? 'Quelle' : 'Source'}</th>
            </tr>
          </thead>
          <tbody>
            {sources.map((source) => (
              <tr key={`${source.case}/${source.key}/${source.set}`}>
                <th scope="row">
                  {source.case} / {source.key}
                </th>
                <td>
                  {source.set}
                  <br />
                  {source.overriddenByCase
                    ? lang === 'de'
                      ? 'Durch Fall überschrieben'
                      : 'Overridden by case'
                    : source.effectiveLayer}
                </td>
                <td>
                  {source.effectiveDate ?? '—'}
                  <br />[{source.validFrom ?? '…'}, {source.validUntil ?? '…'})<br />
                  {source.mode}
                  {source.validForDate === false && (
                    <strong>{lang === 'de' ? ' Außerhalb der Gültigkeit' : ' Outside validity'}</strong>
                  )}
                </td>
                <td>
                  {source.packageId}@{source.packageVersion}
                  <br />
                  {source.resource}
                  <br />
                  <small>{source.sha256}</small>
                  <br />
                  {source.reference}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      {editable && (
        <details>
          <summary>
            {lang === 'de' ? 'Explizite Migration des bearbeitbaren Falls' : 'Explicit editable-case migration'}
          </summary>
          <label>
            {lang === 'de' ? 'Zielbindung' : 'Target binding'}{' '}
            <select
              value={target}
              disabled={busy}
              onChange={(event) => {
                setTarget(event.target.value)
                setPreview(undefined)
              }}
            >
              <option value="">{lang === 'de' ? 'Ziel auswählen' : 'Choose target'}</option>
              {packages.flatMap((pkg) =>
                pkg.cases.map((item) => (
                  <option key={item.id} value={item.id}>
                    {pkg.id}@{pkg.version} / {item.caseId} ({item.schema}@{item.schemaVersion ?? 'legacy'})
                  </option>
                )),
              )}
            </select>
          </label>
          <button
            disabled={busy || !revision || !target}
            onClick={() =>
              act(async () => {
                const result = await data.previewMigration(caseId, revision!, target)
                setPreview(result.data)
              })
            }
          >
            {lang === 'de' ? 'Migration prüfen' : 'Preview migration'}
          </button>
          {preview && (
            <div>
              <p>
                {preview.source.schema}@{preview.source.version ?? 'legacy'} → {preview.target.schema}@
                {preview.target.version ?? 'legacy'}
              </p>
              <h3>{lang === 'de' ? 'Bisheriger Quelltext' : 'Original source'}</h3>
              <pre>{preview.original}</pre>
              <h3>{lang === 'de' ? 'Vorgeschlagener Quelltext' : 'Proposed source'}</h3>
              <pre>{preview.candidate}</pre>
              <h3>{lang === 'de' ? 'Berechnete Differenz' : 'Calculated difference'}</h3>
              <pre>{JSON.stringify(preview.difference, null, 2)}</pre>
              <ul>
                {preview.diagnostics.map((finding, i) => (
                  <li key={i}>
                    {finding.code}: {finding.message}
                  </li>
                ))}
              </ul>
              <button disabled={busy} onClick={() => act(() => data.applyMigration(caseId, preview.reviewToken), true)}>
                {lang === 'de' ? 'Geprüfte Migration anwenden' : 'Apply reviewed migration'}
              </button>
            </div>
          )}
          <button disabled={busy || !revision} onClick={() => act(() => data.restore(caseId, revision!, true), true)}>
            {lang === 'de' ? 'Rückgängig' : 'Undo'}
          </button>
          <button disabled={busy || !revision} onClick={() => act(() => data.restore(caseId, revision!, false), true)}>
            {lang === 'de' ? 'Wiederholen' : 'Redo'}
          </button>
        </details>
      )}
      <p role="status" aria-live="polite">
        {status}
      </p>
    </section>
  )
}
