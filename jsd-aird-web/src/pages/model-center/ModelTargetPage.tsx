import { useEffect } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { ModelingSettingsPage } from '@/pages/modeling-settings'

/**
 * Full-page target workflow. The data loading and mutation logic stays in the
 * existing modeling settings module; this wrapper only provides a clear page
 * boundary so a target is not hidden inside a narrow side drawer.
 */
export function ModelTargetPage() {
  const { targetId } = useParams<{ targetId: string }>()
  const navigate = useNavigate()

  useEffect(() => {
    if (!targetId) navigate('/assistant/model-center?tab=settings', { replace: true })
  }, [navigate, targetId])

  if (!targetId) {
    return null
  }

  return <main className="model-center-page model-target-page-route">
    <ModelingSettingsPage
      initialTargetId={targetId}
      fullPageTarget
      onOpenModels={(id) => navigate(`/assistant/model-center?tab=models&targetId=${encodeURIComponent(id)}`)}
    />
  </main>
}
