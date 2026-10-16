import { Button, Space, Tabs, Typography } from 'antd'
import { ExperimentOutlined } from '@ant-design/icons'
import { useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from '@/pages/model-management'
import { ModelingWorkbenchPage } from '@/pages/modeling-settings'
import { NotFoundPage } from '@/pages/not-found'
import { isCurrentModelCenterQuery } from '@/routes/model-center-location'
import './model-center.css'

const { Title } = Typography

export function ModelCenterPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const canModels = useAuthStore((state) => state.can('ai.model.read'))
  const canWorkbench = useAuthStore((state) => state.can('ai.modeling.read'))
  const canManageCatalog = useAuthStore((state) => state.can('ai.modeling.manage'))
  const canRegister = useAuthStore((state) => state.can('ai.model.publish'))
  const canTrain = useAuthStore((state) => state.can('ai.training.operate'))
  const targetId = searchParams.get('targetId') ?? undefined
  const requestedTab = searchParams.get('tab')
  const activeTab = requestedTab === 'workbench' && canWorkbench ? 'workbench'
    : canModels ? 'models' : 'workbench'

  const update = useCallback((changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => value == null ? next.delete(key) : next.set(key, value))
    setSearchParams(next)
  }, [searchParams, setSearchParams])

  const openWorkbench = () => update({ tab: 'workbench', targetId: undefined, modelId: undefined })
  const setTab = (key: string) => update({ tab: key, targetId: undefined, modelId: undefined })

  if (!isCurrentModelCenterQuery(searchParams)) return <NotFoundPage />

  return <main className="model-center-page">
    <header className="model-center-header">
      <div><Title level={2}>模型中心</Title></div>
      <Space>
        {canWorkbench && activeTab !== 'workbench' && <Button type="primary" icon={<ExperimentOutlined />} onClick={openWorkbench}>开始建模</Button>}
      </Space>
    </header>
    <Tabs className="model-center-tabs" activeKey={activeTab} onChange={setTab} items={[
      ...(canModels ? [{ key: 'models', label: '模型列表' }] : []),
      ...(canWorkbench ? [{ key: 'workbench', label: '建模工具' }] : []),
    ]} />
    {activeTab === 'models' && canModels && <ModelManagementPage embedded focusTargetId={targetId} focusModelId={searchParams.get('modelId') ?? undefined} />}
    {activeTab === 'workbench' && canWorkbench && <ModelingWorkbenchPage canManageCatalog={canManageCatalog} canTrain={canTrain} canRegister={canRegister} onGoPredict={id => navigate(`/assistant/performance-prediction?targetId=${encodeURIComponent(id)}`)} onOpenModel={canModels ? (id, modelId) => update({ tab: 'models', targetId: id, modelId }) : undefined} />}
  </main>
}
