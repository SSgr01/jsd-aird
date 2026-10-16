import { Alert, App, Button, Empty, Input, Modal, Space, Table, Typography, Upload } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useMemo, useState } from 'react'

import { testMetricCatalogApi, type TestMetricCatalogImport, type TestMetricCatalogIndicator } from '@/services/ai-rnd/test-metric-catalog-api'
import { workbenchRequestMessage } from './modeling-workbench-feedback'
import { stageFile } from '@/services/files/file-api'
import './test-metric-catalog.css'

const { Text } = Typography

type Props = {
  canManage: boolean
}

export function TestMetricCatalogManager({ canManage }: Props) {
  const { message } = App.useApp()
  const [open, setOpen] = useState(false)
  const [catalogImport, setCatalogImport] = useState<TestMetricCatalogImport>()
  const [items, setItems] = useState<TestMetricCatalogIndicator[]>([])
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState('')

  const visibleItems = useMemo(() => {
    const term = keyword.trim().toLocaleLowerCase()
    if (!term) return items
    return items.filter(item => [item.atomicName, item.rawItemName, item.rawTestMethod]
      .some(value => value?.toLocaleLowerCase().includes(term)))
  }, [items, keyword])

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const latest = await testMetricCatalogApi.latest()
      setCatalogImport(latest)
      setItems(latest ? (await testMetricCatalogApi.indicators(latest.id)).content : [])
    } catch (cause) {
      setError(workbenchRequestMessage(cause))
    } finally {
      setLoading(false)
    }
  }

  const importFile = async (file: File) => {
    if (!/\.xlsx?$/i.test(file.name)) {
      void message.error('请选择 XLS 或 XLSX 文件')
      return
    }
    setImporting(true)
    setError('')
    try {
      const staged = await stageFile(file, 'AI_MODELING_CATALOG')
      const imported = await testMetricCatalogApi.importFile(staged.fileId)
      setCatalogImport(imported)
      setItems((await testMetricCatalogApi.indicators(imported.id)).content)
      setKeyword('')
      void message.success(`已导入 ${imported.proposalCount} 项指标`)
    } catch (cause) {
      const text = workbenchRequestMessage(cause)
      setError(text)
      void message.error(text)
    } finally {
      setImporting(false)
    }
  }

  const columns: ColumnsType<TestMetricCatalogIndicator> = [
    { title: '预测结果', dataIndex: 'atomicName', width: 180, render: (value: string, item) => <span>{value}{item.suggestedUnit ? <Text type="secondary"> · {item.suggestedUnit}</Text> : null}</span> },
    { title: '原表项目', dataIndex: 'rawItemName', width: 140, ellipsis: true },
    { title: '测试方法', dataIndex: 'rawTestMethod', width: 220, ellipsis: true, render: (value: unknown) => typeof value === 'string' && value.trim() ? value : '—' },
    { title: '影响因素参考', dataIndex: 'rawInfluenceFactors', ellipsis: true, render: (value: unknown) => typeof value === 'string' && value.trim() ? value : '—' },
  ]

  return <>
    <Button onClick={() => { setOpen(true); void load() }}>指标清单</Button>
    <Modal title="指标清单" open={open} onCancel={() => setOpen(false)} footer={null} width={940} destroyOnHidden>
      <div className="catalog-manager-intro">
        <Text>指标名称、单位和测试方法参考；示例结果不参与训练。</Text>
        <Space wrap>
          {catalogImport && <Text type="secondary">{catalogImport.sourceName} · {items.length} 项</Text>}
          <Button onClick={() => void load()} loading={loading}>刷新</Button>
          {canManage && <Upload accept=".xls,.xlsx" showUploadList={false} beforeUpload={file => { void importFile(file); return false }}>
            <Button type="primary" loading={importing}>导入清单</Button>
          </Upload>}
        </Space>
      </div>
      {error && <Alert className="catalog-manager-error" type="error" showIcon message={error} />}
      {catalogImport && <Input.Search allowClear value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="搜索名称、项目或测试方法" className="catalog-manager-search" />}
      {!catalogImport && !loading ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有导入清单" />
        : <Table<TestMetricCatalogIndicator> size="small" rowKey="id" loading={loading || importing} dataSource={visibleItems} pagination={{ pageSize: 8, showSizeChanger: false, showTotal: total => `共 ${total} 项` }} columns={columns} />}
    </Modal>
  </>
}
