import React, { lazy, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './ui/App'
import './style.css'

const root = createRoot(document.getElementById('root')!)
if (window.location.pathname.replace(/\/+$/, '') === '/template-preview') {
  const TemplateDraftPreviewPage = lazy(() => import('./templatePreview/TemplateDraftPreviewPage'))
  root.render(
    <React.StrictMode>
      <Suspense fallback={<p role="status">Opening template draft preview…</p>}>
        <TemplateDraftPreviewPage />
      </Suspense>
    </React.StrictMode>,
  )
} else if (
  import.meta.env.VITE_WORKBENCH_MODE !== 'live' &&
  window.location.pathname.replace(/\/+$/, '') === '/authoring'
) {
  const AuthoringPrototype = lazy(() => import('./authoring/AuthoringPrototype'))
  root.render(
    <React.StrictMode>
      <Suspense fallback={<p role="status">Opening authoring prototype…</p>}>
        <AuthoringPrototype />
      </Suspense>
    </React.StrictMode>,
  )
} else {
  root.render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  )
}
