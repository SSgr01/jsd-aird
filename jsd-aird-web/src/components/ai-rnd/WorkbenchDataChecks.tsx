import { Collapse, Table, Typography } from 'antd'
import type { WorkbenchDataChecks as DataChecks } from '@/services/ai-rnd/workbench-data-checks'
import './training-evaluation.css'

export function WorkbenchDataChecks({ checks }: { checks?: DataChecks }) {
  if (!checks) return null
  const fieldProblems = checks.fields.filter(field => field.missingCount || field.invalidCount)
  const constants = checks.constantXColumns
  if (!fieldProblems.length && !constants.length && !checks.duplicateInputGroupCount) return null
  return <div className="workbench-data-checks">
    <Typography.Text strong>数据检查</Typography.Text>
    {checks.excludedRowCount > 0 && <p>{checks.excludedRowCount} 条记录因所选字段缺失或无效而不参与训练；其余 {checks.usableRowCount} 条保留。</p>}
    {fieldProblems.length > 0 && <Table size="small" rowKey="code" pagination={false} dataSource={fieldProblems} columns={[
      { title: '字段', render: (_, field) => `${field.role} · ${field.name}` },
      { title: '缺失记录', dataIndex: 'missingCount', width: 100 },
      { title: '无效值记录', dataIndex: 'invalidCount', width: 110 },
    ]} />}
    {constants.length > 0 && <p>取值相同的 X：{constants.map(field => field.name).join('、')}。这些列无法区分实验记录，建议取消选择。</p>}
    {checks.conflictingInputGroupCount > 0 ? <>
      <p>{checks.conflictingInputGroupCount} 组相同 X 对应不同 Y，涉及 {checks.conflictingRowCount} 条记录。请核对实测值，或补充能区分这些实验的配方、材料和工艺字段。</p>
      <Collapse ghost size="small" items={[{ key: 'conflicts', label: '查看相关记录', children: <Table size="small" pagination={false} rowKey={group => JSON.stringify(group.inputs)} dataSource={checks.conflictingExamples} scroll={{ x: 600 }} columns={[
        { title: '相同输入 X', render: (_, group) => Object.entries(group.inputs).map(([code, value]) => `${checks.fields.find(field => field.code === code)?.name ?? code}：${String(value)}`).join('；') },
        { title: '实测 Y', width: 160, render: (_, group) => group.results.map(String).join('、') },
        { title: '实验记录', render: (_, group) => group.records.map(record => [record.source, record.label].filter(Boolean).join(' · ')).join('；') },
      ]} /> }]} />
    </> : checks.duplicateInputGroupCount > 0 && <p>{checks.duplicateInputGroupCount} 组记录的 X 相同；交叉验证时会放在同一组，避免重复记录影响验证结果。</p>}
  </div>
}
