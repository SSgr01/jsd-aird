import { Button, Space, Tabs, Typography } from 'antd'
import { ExperimentOutlined } from '@ant-design/icons'
import { useCallback, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from '@/pages/model-management'
import { ModelingWorkbenchPage } from '@/pages/modeling-settings'
import './model-center.css'

const { Title } = Typography

export function ModelCenterPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const authStatus = useAuthStore(state => state.status)
  const canModels = useAuthStore((state) => state.can('ai.model.read'))
  const canWorkbench = useAuthStore((state) => state.can('ai.modeling.read'))
  const canManageCatalog = useAuthStore((state) => state.can('ai.modeling.manage'))
  const canRegister = useAuthStore((state) => state.can('ai.model.publish'))
  const canTrain = useAuthStore((state) => state.can('ai.training.operate'))
  const targetId = searchParams.get('targetId') ?? undefined
  const requestedTab = searchParams.get('tab')
  const creatingTarget = searchParams.get('action') === 'create-target'
  const legacySettings = requestedTab === 'settings' || creatingTarget
  const activeTab = legacySettings
    ? targetId && !creatingTarget && canModels ? 'models' : canWorkbench ? 'workbench' : 'models'
    : requestedTab === 'workbench' && canWorkbench ? 'workbench'
      : canModels ? 'models' : 'workbench'
  const modelView = searchParams.get('view') === 'jobs' ? 'jobs' : 'models'

  const update = useCallback((changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => value == null ? next.delete(key) : next.set(key, value))
    setSearchParams(next)
  }, [searchParams, setSearchParams])

  useEffect(() => {
    if (authStatus !== 'authenticated') return
    const legacyKeys = ['action', 'targetName', 'targetUnit', 'targetValueType']
    if (!legacySettings && (!requestedTab || requestedTab === activeTab) && !legacyKeys.some(key => searchParams.has(key))) return
    const next = new URLSearchParams(searchParams)
    next.set('tab', activeTab)
    legacyKeys.forEach(key => next.delete(key))
    if (activeTab === 'workbench') { next.delete('targetId'); next.delete('modelId') }
    setSearchParams(next, { replace: true })
  }, [activeTab, authStatus, legacySettings, requestedTab, searchParams, setSearchParams])

  const openWorkbench = () => update({ tab: 'workbench', targetId: undefined, modelId: undefined, action: undefined })
  const setTab = (key: string) => update({ tab: key, action: undefined, targetId: undefined, modelId: undefined })
  const setView = (view: 'models' | 'jobs') => update({ view })

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
    {activeTab === 'models' && canModels && <ModelManagementPage embedded focusTargetId={targetId} focusModelId={searchParams.get('modelId') ?? undefined} view={modelView} onViewChange={setView} />}
    {activeTab === 'workbench' && canWorkbench && <ModelingWorkbenchPage canManageCatalog={canManageCatalog} canTrain={canTrain} canRegister={canRegister} onGoPredict={id => navigate(`/assistant/performance-prediction?targetId=${encodeURIComponent(id)}`)} onOpenModel={canModels ? (id, modelId) => update({ tab: 'models', targetId: id, modelId, action: undefined }) : undefined} />}
  </main>
}
