import { Alert, Button, Input, Select, Space, Table, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { modelingWorkbenchApi, type ModelingExperiment } from '@/services/ai-rnd/modeling-workbench-api'
import { workbenchRequestMessage } from './modeling-workbench-feedback'

export function ModelingExperimentSelector({ id, selected, onChange, disabled }: {
  id: string; selected: string[]; onChange: (ids: string[]) => void; disabled: boolean
}) {
  const [batches, setBatches] = useState<Array<{ id: string; count: number }>>([])
  const [batch, setBatch] = useState<string>()
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [rows, setRows] = useState<ModelingExperiment[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    let active = true
    void modelingWorkbenchApi.batches(id).then(data => { if (active) setBatches(data.items) }).catch(e => { if (active) setError(workbenchRequestMessage(e)) })
    return () => { active = false }
  }, [id, retry])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError(''); setRows([])
    void modelingWorkbenchApi.experiments(id, { batch, keyword, page, size: 20 }, controller.signal)
      .then(data => { if (!controller.signal.aborted) { setRows(data.items); setTotal(data.total) } })
      .catch(e => { if (!controller.signal.aborted) setError(workbenchRequestMessage(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [id, batch, keyword, page, retry])
  return <Space direction="vertical" style={{ width: '100%' }}>
    <Space wrap>
      <Select aria-label="筛选日期批次" placeholder="全部日期批次" allowClear disabled={disabled} value={batch} style={{ minWidth: 290 }}
        options={batches.map(item => { const [date, source] = item.id.split(':'); const labels: Record<string, string> = { EXCEL_IMPORT: '模板导入', TEMPLATE: '模板录入', MANUAL: '在线录入', PROJECT: '项目实验', OCR_IMPORT: '图片导入' }; return { value: item.id, label: `${date} · ${labels[source ?? ''] ?? '其他来源'}（${item.count}份）` } })}
        onChange={value => { setBatch(value); setPage(1) }} />
      <Input.Search aria-label="筛选实验" placeholder="实验编号、标题或负责人" allowClear onSearch={value => { setKeyword(value); setPage(1) }} disabled={disabled} />
      <Typography.Text>已选 {selected.length} 份实验（跨页保留，最多500份）</Typography.Text>
      <Button disabled={disabled || !selected.length} onClick={() => onChange([])}>清空选择</Button>
      <Button disabled={disabled} onClick={() => setRetry(value => value + 1)}>重试加载</Button>
    </Space>
    <Typography.Text type="secondary">日期批次按创建日期与录入方式归类；筛选和翻页不会自动选择实验。表头勾选只选择当前页。</Typography.Text>
    {error && <Alert type="error" showIcon message={error} />}
    <Table<ModelingExperiment> rowKey="id" size="small" dataSource={rows} loading={loading}
      rowSelection={{ selectedRowKeys: selected, preserveSelectedRowKeys: true,
        getCheckboxProps: row => ({ disabled: disabled || loading || (!selected.includes(row.id) && selected.length >= 500) }),
        onChange: keys => onChange(keys.map(String).slice(0, 500)) }}
      pagination={{ current: page, pageSize: 20, total, showSizeChanger: false, onChange: setPage, showTotal: count => `共 ${count} 份实验` }}
      columns={[{ title: '实验编号', dataIndex: 'label' }, { title: '实验标题', dataIndex: 'title' }, { title: '负责人', dataIndex: 'owner' }, { title: '实验日期', dataIndex: 'date' }]} />
  </Space>
}
