import { ArrowLeftOutlined, BranchesOutlined, ReloadOutlined } from '@ant-design/icons'
import { Alert, App, Button, Card, Descriptions, Empty, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'

import { modelingApi } from '@/services/ai-rnd/modeling-api'
import type { TrainingSampleDetail, TrainingSampleSource, TrainingSampleSummary } from '@/services/ai-rnd/ai-rnd-types'
import './modeling-settings.css'

const sourceText = { DATA_CENTER: '数据中心', EXPERIMENT: '实验记录本' } as const
const statusColor: Record<string, string> = { ACTIVE: 'green', SUSPENDED: 'gold', INVALIDATED: 'red', CURRENT: 'green', SUPERSEDED: 'default', TAKEN_OVER: 'purple' }

export function SampleSourcePage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [items, setItems] = useState<TrainingSampleSummary[]>([])
  const [detail, setDetail] = useState<TrainingSampleDetail>()
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [sourceType, setSourceType] = useState<string>()
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 })
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      if (id) setDetail(await modelingApi.sample(id))
      else {
        const result = await modelingApi.samples({ keyword: keyword || undefined, status, sourceType, page: page.current, size: page.pageSize })
        setItems(result.items)
        setPage((current) => ({ ...current, total: result.total }))
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '样本来源加载失败')
    } finally { setLoading(false) }
  }, [id, keyword, message, page.current, page.pageSize, sourceType, status])

  useEffect(() => { void load() }, [load])

  if (id) return <div className="modeling-settings-page">
    <div className="modeling-header"><div><Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/assistant/model-center/samples')}>返回样本来源</Button><Typography.Title level={2}>样本来源详情</Typography.Title></div><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></div>
    {!detail ? <Card loading={loading}><Empty description="样本不存在或无权查看" /></Card> : <>
      <Card className="modeling-card"><Descriptions bordered size="small" column={2} items={[
        { key: 'key', label: '逻辑样本身份', children: detail.summary.logicalSampleKey },
        { key: 'status', label: '事实状态', children: <Tag color={statusColor[detail.summary.status]}>{detail.summary.status}</Tag> },
        { key: 'authority', label: '当前权威来源', children: detail.summary.authoritySourceType ? sourceText[detail.summary.authoritySourceType] : '暂停供数' },
        { key: 'version', label: '身份版本', children: detail.summary.identityVersion },
      ]} /></Card>
      {detail.summary.status === 'SUSPENDED' && <Alert type="warning" showIcon message="该样本已暂停供数" description="接管实验正在修订；系统不会恢复使用旧的数据中心版本。" />}
      <Card className="modeling-card" title="来源版本链"><Table rowKey="id" pagination={false} scroll={{ x: 1080 }} dataSource={detail.sources} columns={[
        { title: '来源', dataIndex: 'sourceType', width: 130, render: (value: TrainingSampleSource['sourceType']) => <Tag color={value === 'EXPERIMENT' ? 'purple' : 'blue'}>{sourceText[value]}</Tag> },
        { title: '版本', dataIndex: 'sourceVersion', width: 150 },
        { title: '样本边界', dataIndex: 'sampleBoundaryId', width: 180, ellipsis: true },
        { title: '物理来源组', dataIndex: 'sourceGroupKeys', width: 220, ellipsis: true, render: (value: string[]) => value?.join('、') || '-' },
        { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
        { title: '内容哈希', dataIndex: 'contentHash', width: 220, ellipsis: true },
        { title: '事实/坐标', width: 150, render: (_: unknown, source: TrainingSampleSource) => source.redacted ? <Tag color="default">无来源读取权限</Tag> : <Tag color="green">可追溯</Tag> },
        { title: '操作', width: 160, render: (_: unknown, source: TrainingSampleSource) => <Button type="link" disabled={!source.recognitionJobId && !source.experimentId} onClick={() => {
          if (source.sourceType === 'EXPERIMENT' && source.experimentId) navigate(`/experiments/${source.experimentId}`)
          else if (source.recognitionJobId) navigate(`/data/recognition-jobs/${source.recognitionJobId}`)
        }}>打开来源</Button> },
      ]} expandable={{ expandedRowRender: (source) => source.redacted ? <Alert type="info" message="来源值和坐标已按权限隐藏" /> : <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{JSON.stringify({ facts: source.facts, sourceCoordinates: source.sourceCoordinates }, null, 2)}</pre> }} /></Card>
      <Card className="modeling-card" title="身份问题"><Table rowKey="id" pagination={false} dataSource={detail.identityIssues} locale={{ emptyText: '没有待处理的同源身份问题' }} columns={[
        { title: '类型', dataIndex: 'issueType' }, { title: '状态', dataIndex: 'status', render: (value: string) => <Tag>{value}</Tag> },
        { title: '证据', dataIndex: 'evidence', render: (value: Record<string, unknown>) => JSON.stringify(value) },
      ]} /></Card>
    </>}
  </div>

  return <div className="modeling-settings-page">
    <div className="modeling-header"><div><Typography.Title level={2}>样本来源</Typography.Title><Typography.Text className="modeling-muted">查看数据中心与实验记录本投影出的统一事实及接管关系。</Typography.Text></div><Space><Button onClick={() => navigate('/assistant/model-center?tab=settings')}>建模设置</Button><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space></div>
    <Card className="modeling-card"><div className="modeling-toolbar"><div className="modeling-toolbar-filters"><Input.Search allowClear placeholder="搜索逻辑样本身份" value={keyword} onChange={(event) => setKeyword(event.target.value)} onSearch={() => setPage((current) => ({ ...current, current: 1 }))} /><Select allowClear placeholder="事实状态" value={status} onChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })) }} options={['ACTIVE','SUSPENDED','INVALIDATED'].map((value) => ({ value, label: value }))} /><Select allowClear placeholder="权威来源" value={sourceType} onChange={(value) => { setSourceType(value); setPage((current) => ({ ...current, current: 1 })) }} options={Object.entries(sourceText).map(([value, label]) => ({ value, label }))} /></div></div>
      <Table rowKey="id" loading={loading} dataSource={items} locale={{ emptyText: <Empty description="暂无正式样本事实；实验草稿不会出现在这里" /> }} pagination={{ current: page.current, pageSize: page.pageSize, total: page.total, onChange: (current, pageSize) => setPage({ current, pageSize, total: page.total }) }} columns={[
        { title: '逻辑样本身份', dataIndex: 'logicalSampleKey', ellipsis: true },
        { title: '权威来源', dataIndex: 'authoritySourceType', width: 140, render: (value?: keyof typeof sourceText) => value ? sourceText[value] : '暂停供数' },
        { title: '状态', dataIndex: 'status', width: 130, render: (value: string) => <Tag color={statusColor[value]}>{value}</Tag> },
        { title: '来源数', dataIndex: 'sourceCount', width: 100 },
        { title: '更新时间', dataIndex: 'updatedAt', width: 190, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        { title: '操作', width: 110, render: (_: unknown, record: TrainingSampleSummary) => <Button type="link" icon={<BranchesOutlined />} onClick={() => navigate(`/assistant/model-center/samples/${record.id}`)}>查看链路</Button> },
      ]} />
    </Card>
  </div>
}
