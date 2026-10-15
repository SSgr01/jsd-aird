import { Navigate, useParams, useSearchParams } from 'react-router-dom'

import { useAuthStore } from '@/stores/auth-store'

/** Preserve bookmarks without exposing the retired configuration workflow. */
export function LegacyModelCenterRedirect({ models = false }: { models?: boolean }) {
  const { targetId } = useParams<{ targetId: string }>()
  const [searchParams] = useSearchParams()
  const canModels = useAuthStore(state => state.can('ai.model.read'))
  const target = targetId ?? searchParams.get('targetId')
  const creatingTarget = searchParams.get('action') === 'create-target'
  const openModels = canModels && !creatingTarget && (models || Boolean(target))
  const next = new URLSearchParams({ tab: openModels ? 'models' : 'workbench' })
  if (openModels) {
    if (target) next.set('targetId', target)
    const modelId = searchParams.get('modelId')
    if (modelId) next.set('modelId', modelId)
    if (searchParams.get('view') === 'jobs') next.set('view', 'jobs')
  }
  return <Navigate to={`/assistant/model-center?${next.toString()}`} replace />
}
