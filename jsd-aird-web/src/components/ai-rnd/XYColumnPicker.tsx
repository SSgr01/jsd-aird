import { Card, Checkbox, Empty, Input, Radio, Space, Tag, Typography } from 'antd'
import { useMemo, useState, type ReactNode } from 'react'
import type { HistoryDataset, HistoryDatasetColumn } from '@/services/ai-rnd/optimization-api'
import { columnValues, isUnmappedFormulaColumn, materialColumn } from '@/services/ai-rnd/history-dataset-utils'
import './xy-column-picker.css'

const { Text } = Typography
export function XYColumnPicker({ dataset, mode, xColumns, yColumn, onXChange, onYChange, yExtra, lockedXCodes = [] }: {
  dataset: HistoryDataset; mode: 'modeling' | 'optimization'; xColumns: string[]; yColumn?: string
  onXChange: (values: string[]) => void; onYChange: (value: string) => void; yExtra?: ReactNode; lockedXCodes?: string[]
}) {
  const [xQuery, setXQuery] = useState('')
  const [yQuery, setYQuery] = useState('')
  const statistics = useMemo(() => new Map(dataset.columns.map(column => [column.code, columnValues(dataset,column.code)])), [dataset])
  const values = (code: string) => statistics.get(code) ?? []
  const levels = (code: string) => new Set(values(code)).size
  const fixed = (column: HistoryDatasetColumn) => materialColumn(column) && column.kind === 'NUMERIC' && levels(column.code) === 1
  const minimum = mode === 'modeling' ? 6 : 4
  const xReason = (column: HistoryDatasetColumn) => column.code === yColumn ? '已用作 Y'
    : column.filledCount < minimum ? `有效记录 ${column.filledCount}/${minimum} 条`
      : levels(column.code) < 2 && !fixed(column) ? '取值未变化' : ''
  const yReason = (column: HistoryDatasetColumn) => {
    if (mode === 'optimization' && column.kind !== 'NUMERIC') return '当前优化需要数值结果'
    if (column.filledCount < minimum) return `有效记录 ${column.filledCount}/${minimum} 条`
    if (levels(column.code) < 2) return '结果未变化'
    if (column.kind === 'CATEGORICAL') {
      const counts = new Map<string, number>()
      values(column.code).forEach(value => counts.set(String(value), (counts.get(String(value)) ?? 0) + 1))
      if ([...counts.values()].some(count => count < 2)) return '部分类别少于 2 条记录'
    }
    return ''
  }
  const label = (column: HistoryDatasetColumn, side: 'X' | 'Y', reason: string) => <span className="workbench-column-option">
    <strong>{column.name}{side === 'X' && isUnmappedFormulaColumn(column) ? <Tag color="warning">未绑定材料</Tag> : null}</strong>
    <small>{column.kind === 'NUMERIC' ? '数值' : '分类'}{column.unit ? ` · ${column.unit}` : ''} · {column.filledCount}/{dataset.rows.length} 条有值 · {levels(column.code)} 种{side === 'X' ? '取值' : '结果'}{side === 'X' && isUnmappedFormulaColumn(column) ? ' · 不能用于配方预测' : ''}</small>
    {side === 'X' && fixed(column) && <small className="field-choice-fixed-label">固定 {String(values(column.code)[0])}{column.unit ?? ''}{lockedXCodes.includes(column.code) ? ' · 已纳入配方' : ''}</small>}
    {reason && <small className="field-choice-reason">{reason}</small>}
  </span>
  const visible = (query: string) => dataset.columns.filter(column => column.name.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  const xDisabled = (column: HistoryDatasetColumn) => Boolean(xReason(column)) || lockedXCodes.includes(column.code)
  const xChoices = visible(xQuery).sort((left, right) => Number(xDisabled(left)) - Number(xDisabled(right)))
  const yChoices = visible(yQuery).sort((left, right) => Number(Boolean(yReason(left))) - Number(Boolean(yReason(right))))
  const selectedY = dataset.columns.find(column => column.code === yColumn)
  return <div className="workbench-xy-layout">
    <Card className="workbench-select-card" title={<span>选择 X <Text type="secondary">· 作为预测输入，可多选</Text></span>}>
      <Input aria-label="搜索 X 字段" placeholder="搜索字段名称" allowClear value={xQuery} onChange={event => setXQuery(event.target.value)} className="field-choice-search" />
      <Checkbox.Group className="workbench-column-choice-list" value={xColumns} onChange={values => onXChange([...new Set([...values.map(String), ...lockedXCodes])].filter(code => code !== yColumn))}>
        {xChoices.map(column => <Checkbox key={column.code} value={column.code} disabled={xDisabled(column)} className={fixed(column) ? 'field-choice-fixed' : undefined}>{label(column, 'X', xReason(column))}</Checkbox>)}
      </Checkbox.Group>
      {!xChoices.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的字段" />}
      {xColumns.length > 0 && <div className="workbench-selection-summary"><Text strong>已选 {xColumns.length} 列</Text><Space wrap>{xColumns.map(code => <Tag key={code} closable={!lockedXCodes.includes(code)} onClose={() => onXChange(xColumns.filter(item => item !== code))}>{dataset.columns.find(column => column.code === code)?.name ?? code}</Tag>)}</Space></div>}
    </Card>
    <Card className="workbench-select-card" title={<span>选择 Y <Text type="secondary">· {mode === 'modeling' ? '每次训练预测一个结果' : '选择本轮优化的结果'}</Text></span>}>
      <Input aria-label="搜索 Y 字段" placeholder="搜索字段名称" allowClear value={yQuery} onChange={event => setYQuery(event.target.value)} className="field-choice-search" />
      <Radio.Group className="workbench-column-choice-list" value={yColumn} onChange={event => { const value = String(event.target.value); onYChange(value); onXChange(xColumns.filter(code => code !== value)) }}>
        {yChoices.map(column => <Radio key={column.code} value={column.code} disabled={Boolean(yReason(column))}>{label(column, 'Y', yReason(column))}</Radio>)}
      </Radio.Group>
      {!yChoices.length && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有匹配的字段" />}
      {selectedY && <div className="workbench-selection-summary workbench-y-detail"><Text strong>当前预测结果：{selectedY.name}</Text><Text type="secondary">{selectedY.kind === 'NUMERIC' ? '数值预测' : '类别预测'}{selectedY.unit ? ` · 单位 ${selectedY.unit}` : ''}</Text>{mode === 'modeling' && <Text type="secondary">{selectedY.kind === 'NUMERIC' ? '训练结果显示 MAE、RMSE 和 R²，误差单位与本列相同。' : '训练结果显示准确率、宏平均 F1 和概率损失。'}</Text>}</div>}
      {yExtra}
    </Card>
  </div>
}

export function SelectedDataIssues({ dataset, xColumns, yColumn }: { dataset?: HistoryDataset; xColumns: string[]; yColumn?: string }) {
  const codes = new Set([...xColumns, ...(yColumn ? [yColumn] : [])])
  const rows = new Set(dataset?.rows.map(row => row.id))
  const issues = dataset?.issues?.filter(issue => (!issue.rowId || rows.has(issue.rowId)) && codes.has(issue.columnCode)) ?? []
  if (!issues.length) return null
  return <details className="selected-data-issues"><summary>{issues.length} 处记录需要检查</summary>{issues.map((issue, index) => <p key={`${issue.rowId}:${issue.columnCode}:${index}`}><strong>{issue.sampleNo} · {dataset?.columns.find(column => column.code === issue.columnCode)?.name}</strong>：{issue.rawValue}。{issue.message}</p>)}</details>
}
