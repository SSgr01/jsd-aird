import { Button, Space, Tabs, Typography } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useCallback, useEffect, useMemo } from 'react'
import { useSearchParams } from 'react-router-dom'

import { useAuthStore } from '@/stores/auth-store'
import { ModelManagementPage } from '@/pages/model-management'
import { ModelingSettingsPage } from '@/pages/modeling-settings'
import './model-center.css'

const { Title, Text } = Typography

export function ModelCenterPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const canModels = useAuthStore((state) => state.can('ai.model.read'))
  const canSettings = useAuthStore((state) => state.can('ai.modeling.read'))
  const canManageSettings = useAuthStore((state) => state.can('ai.modeling.manage'))
  const targetId = searchParams.get('targetId') ?? undefined
  const action = searchParams.get('action')
  const requestedTab = searchParams.get('tab')
  const activeTab = useMemo<'models' | 'settings'>(() => {
    if (action === 'create-target' && canSettings) return 'settings'
    if (requestedTab === 'settings' && canSettings) return 'settings'
    if (requestedTab === 'models' && canModels) return 'models'
    return canModels ? 'models' : 'settings'
  }, [action, canModels, canSettings, requestedTab])
  const modelView = searchParams.get('view') === 'jobs' ? 'jobs' : 'models'

  const update = useCallback((changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => value == null ? next.delete(key) : next.set(key, value))
    setSearchParams(next)
  }, [searchParams, setSearchParams])

  useEffect(() => {
    if (action === 'create-target' && canSettings && requestedTab !== 'settings') { update({ tab: 'settings' }); return }
    if (!requestedTab || (requestedTab === 'models' && canModels) || (requestedTab === 'settings' && canSettings)) return
    update({ tab: activeTab })
  }, [action, activeTab, canModels, canSettings, requestedTab, update])

  const openCreateTarget = () => update({ tab: 'settings', action: 'create-target', targetId: undefined })
  const openSettings = (id: string) => update({ tab: 'settings', targetId: id, action: undefined })
  const openModels = (id?: string) => update({ tab: 'models', targetId: id, action: undefined })
  const setTab = (key: string) => update({ tab: key, action: undefined })
  const setView = (view: 'models' | 'jobs') => update({ view })

  return <main className="model-center-page">
    <header className="model-center-header">
      <div><Title level={2}>模型中心</Title><Text type="secondary">在同一个入口管理模型列表与建模配置。</Text></div>
      <Space>{canManageSettings && <Button type="primary" icon={<PlusOutlined />} onClick={openCreateTarget}>新增预测目标</Button>}</Space>
    </header>
    <Tabs className="model-center-tabs" activeKey={activeTab} onChange={setTab} items={[
      ...(canModels ? [{ key: 'models', label: '模型列表' }] : []),
      ...(canSettings ? [{ key: 'settings', label: '建模设置' }] : []),
    ]} />
    {activeTab === 'models' && canModels && <ModelManagementPage embedded focusTargetId={targetId} view={modelView} onViewChange={setView} onOpenSettings={canSettings ? openSettings : undefined} />}
    {activeTab === 'settings' && canSettings && <ModelingSettingsPage embedded initialTargetId={targetId} openCreateTarget={action === 'create-target'} onCreateTargetOpened={() => update({ action: undefined })} onOpenModels={openModels} />}
  </main>
}
