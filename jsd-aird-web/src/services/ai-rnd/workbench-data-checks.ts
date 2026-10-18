import type { HistoryDataset } from './optimization-api'

export interface WorkbenchDataChecks {
  selectedRowCount: number
  usableRowCount: number
  excludedRowCount: number
  fields: Array<{ code: string; name: string; role: string; missingCount: number; invalidCount: number; distinctCount: number }>
  constantXColumns: Array<{ code: string; name: string }>
  duplicateInputGroupCount: number
  conflictingInputGroupCount: number
  conflictingRowCount: number
  conflictingExamples: Array<{ inputs: Record<string, unknown>; results: unknown[]; rowCount: number; records: Array<{ label: string; source: string; result: unknown }> }>
}

function checkedValue(value: unknown, numeric: boolean): { value?: string | number; problem?: 'missing' | 'invalid' } {
  if (value === undefined || value === null || typeof value === 'string' && !value.trim()) return { problem: 'missing' }
  if (numeric) {
    if ((typeof value !== 'number' && typeof value !== 'string') || !Number.isFinite(Number(value))) return { problem: 'invalid' }
    if (typeof value === 'string' && !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(value.trim())) return { problem: 'invalid' }
    return { value: Number(value) }
  }
  return typeof value === 'string' || typeof value === 'number' ? { value: String(value).trim() } : { problem: 'invalid' }
}

export function checkWorkbenchData(dataset: HistoryDataset | undefined, xCodes: string[], yCode: string): WorkbenchDataChecks | undefined {
  if (!dataset || !xCodes.length || !yCode) return undefined
  const columns = [...xCodes, yCode].map(code => dataset.columns.find(column => column.code === code))
  if (columns.some(column => !column)) return undefined
  const fields: WorkbenchDataChecks['fields'] = columns.map((column, index) => ({
    code: column!.code, name: column!.name, role: index === xCodes.length ? 'Y' : 'X', missingCount: 0, invalidCount: 0, distinctCount: 0,
  }))
  const distinct = columns.map(() => new Set<string | number>())
  const groups = new Map<string, { inputs: Record<string, unknown>; records: WorkbenchDataChecks['conflictingExamples'][number]['records'] }>()
  let usableRowCount = 0
  dataset.rows.forEach((row, index) => {
    const values = columns.map((column, i) => {
      const checked = checkedValue(row.values[column!.code], column!.kind === 'NUMERIC')
      if (checked.problem === 'missing') fields[i]!.missingCount++
      if (checked.problem === 'invalid') fields[i]!.invalidCount++
      return checked
    })
    if (values.some(value => value.problem)) return
    usableRowCount++
    values.forEach((value, i) => distinct[i]!.add(value.value!))
    const inputValues = values.slice(0, -1).map(value => value.value)
    const key = JSON.stringify(inputValues)
    const group = groups.get(key) ?? { inputs: Object.fromEntries(xCodes.map((code, i) => [code, inputValues[i]])), records: [] }
    group.records.push({ label: row.label || row.title || `第 ${index + 1} 条记录`, source: row.sourceDatasetName ?? '', result: values.at(-1)!.value })
    groups.set(key, group)
  })
  fields.forEach((field, i) => { field.distinctCount = distinct[i]!.size })
  const conflicts = [...groups.values()].filter(group => new Set(group.records.map(record => record.result)).size > 1)
  return {
    selectedRowCount: dataset.rows.length, usableRowCount, excludedRowCount: dataset.rows.length - usableRowCount, fields,
    constantXColumns: fields.slice(0, -1).filter(field => field.distinctCount === 1).map(({ code, name }) => ({ code, name })),
    duplicateInputGroupCount: [...groups.values()].filter(group => group.records.length > 1).length,
    conflictingInputGroupCount: conflicts.length, conflictingRowCount: conflicts.reduce((sum, group) => sum + group.records.length, 0),
    conflictingExamples: conflicts.slice(0, 5).map(group => ({ inputs: group.inputs, results: [...new Set(group.records.map(record => record.result))], rowCount: group.records.length, records: group.records.slice(0, 5) })),
  }
}
