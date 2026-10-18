/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-return */
import type { HistoryDataset, OptimizationCandidate, OptimizationRun } from '@/services/ai-rnd/optimization-api'

/** Prepare a draft with the frozen template, never with freshly queried experimental values. */
export function historicalCandidateDraft(run: OptimizationRun, candidate: OptimizationCandidate, measured?: number) {
  const request = run.frozenRequest
  const dataset = request.datasetSnapshot as HistoryDataset | undefined
  if (!dataset?.rows.length) throw new Error('本任务没有保存样品与原表位置，请重新选择数据开始优化')
  const selectedIds = request.selectedRowIdsByDataset ? new Set<string>(Object.values(request.selectedRowIdsByDataset as Record<string,string[]>).flat()) : undefined
  const first = dataset.rows.find(row => (!selectedIds || selectedIds.has(row.id)) && typeof row.values[request.target] === 'number' && request.features.every((feature: any) => row.values[feature.code] !== undefined))
  if (!first) throw new Error('没有可用于原表映射的完整样品')
  const key = `candidate:${candidate.id}`
  const model: any = { schemaVersion: 2, title: `${request.targetName} · ${candidate.title}`, formulaItems: [], processSteps: [], testResults: [],
    sampleGroups: [{ sampleKey: key, sourceGroupKey: key, sourceIdentity: candidate.title, sourceContextKey: first.sourceContextKey ?? '' }],
    aiResearch: { runId: run.id, candidateId: candidate.id, predictedResults: candidate.results },
  }
  const geometry = (code: string) => ((first.sourceRefs?.[code] ?? []) as any[]).map(ref => ({
    sheetId: ref.sheetId, sheetName: ref.sheetName, row: ref.row, column: ref.column, cell: ref.cell,
    numberFormat: ref.numberFormat, sourceType: 'AI_PLAN', sampleKey: key, sourceGroupKey: key,
    sourceIdentity: candidate.title, sourceContextKey: first.sourceContextKey ?? '',
  }))
  for (const field of request.features) {
    const column = dataset.columns.find(item => item.code === field.code)
    const value = candidate.inputs[field.code]
    if (!column || value === undefined || value === null) throw new Error(`方案缺少输入：${column?.name ?? field.code}`)
    const common = { sampleKey: key, sourceGroupKey: key, sourceIdentity: candidate.title, sourceContextKey: first.sourceContextKey ?? '',
      labelPath: column.name, testMethod: column.testMethods?.join('、') ?? '', testStage: column.testStage ?? '', measurementComponent: column.measurementComponent,
      sourceRefs: geometry(field.code).map(ref => ({ ...ref, displayValue: value })) }
    if (column.materialId) model.formulaItems.push({ ...common, materialId: column.materialId, materialCode: column.materialCode ?? '',
      materialName: column.name.replace(/比例$/, ''), ratio: value, rawValue: value, unit: column.unit ?? '', compositionBasis: column.compositionBasis })
    else model.processSteps.push({ ...common, field: column.code, operation: column.name, value, unit: column.unit ?? '' })
  }
  const target = dataset.columns.find(column => column.code === request.target)
  model.testResults.push({ testItem: target?.name ?? request.targetName, value: measured ?? null, rawValue: measured ?? null,
    unit: target?.unit ?? '', testMethod: target?.testMethods?.join('、') ?? '', sampleKey: key, sourceGroupKey: key,
    sourceRefs: geometry(request.target).map(ref => ({ ...ref, displayValue: measured ?? '' })) })
  const original = run.frozenBaseline?.sourceWorkbookSnapshot ?? run.frozenBaseline?.templateSnapshot
  const mapped = Object.values(first.sourceRefs ?? {}).some(refs => Array.isArray(refs) && refs.length > 0)
  if (mapped && original?.sheets && Object.keys(original.sheets).length) {
    const workbook = structuredClone(original)
    const locate = (ref: any) => workbook.sheets[ref.sheetId] ?? Object.values(workbook.sheets).find((sheet: any) => sheet.name === ref.sheetName)
    // Clear all observed values from every sample slot, retaining labels, layout and formulas outside those slots.
    for (const row of dataset.rows) for (const refs of Object.values(row.sourceRefs ?? {})) for (const ref of refs as any[]) {
      const sheet: any = locate(ref)
      const cell = sheet?.cellData?.[String(ref.row - 1)]?.[String(ref.column - 1)]
      if (cell) { delete cell.v; delete cell.p; delete cell.f }
    }
    const writes = new Map<string, { ref: ReturnType<typeof geometry>[number]; values: Array<{ value: unknown; component?: string; unit?: string }> }>()
    for (const field of [...request.features, { code: request.target }]) {
      const value = field.code === request.target ? measured : candidate.inputs[field.code]
      if (value === undefined || value === null) continue
      const refs = geometry(field.code)
      if (!refs.length) throw new Error(`缺少原表位置：${dataset.columns.find(item => item.code === field.code)?.name ?? field.code}`)
      const column = dataset.columns.find(item => item.code === field.code)
      for (const ref of refs) {
        const key = `${ref.sheetId}:${ref.sheetName}:${ref.row}:${ref.column}`
        const group = writes.get(key) ?? { ref, values: [] }
        group.values.push({ value, component: column?.measurementComponent, unit: column?.unit }); writes.set(key, group)
      }
    }
    for (const { ref, values } of writes.values()) {
        let value = values[0]!.value
        if (new Set(values.map(item => item.value)).size > 1) {
          const height = values.find(item => item.component === 'HEIGHT'), direction = values.find(item => item.component === 'DIRECTION')
          if (!height || !direction || values.length !== 2) throw new Error('多个不同输入指向同一原表单元格，请在字段映射中确认各自位置')
          value = direction.value === '平整' ? '平整不翘曲' : `${String(direction.value)}${String(height.value)}${height.unit ?? 'cm'}`
        }
        const sheet: any = locate(ref)
        if (!sheet) throw new Error('原表工作表与保存的位置不一致，请重新选择数据开始优化')
        sheet.cellData ??= {}; sheet.cellData[String(ref.row - 1)] ??= {}
        const cell = sheet.cellData[String(ref.row - 1)][String(ref.column - 1)] ??= {}
        delete cell.p; delete cell.f
        cell.v = typeof value === 'number' && ref.numberFormat?.includes('%') ? value / 100 : value
        cell.t = typeof value === 'number' ? 2 : 1
    }
    if (!writes.size) throw new Error('方案没有可写入原表的输入，请检查字段映射')
    model.documentFormat = 'excel'; model.documentSnapshot = workbook
  } else if (!mapped) {
    // Native records have a row/column table, with no uploaded workbook coordinates.
    const fields = [...request.features, { code: request.target }]
    const cells: Record<string, Record<string, unknown>> = { '0': {}, '1': {} }
    fields.forEach((field: any, index: number) => {
      const column = dataset.columns.find(item => item.code === field.code)
      const value = field.code === request.target ? measured : candidate.inputs[field.code]
      cells['0']![String(index)] = { v: `${column?.name ?? field.code}${column?.unit ? `（${column.unit}）` : ''}`, t: 1 }
      cells['1']![String(index)] = value == null ? {} : { v: value, t: typeof value === 'number' ? 2 : 1 }
      const targetItem = field.code === request.target ? model.testResults[0] : column?.materialId
        ? model.formulaItems.find((entry: any) => entry.materialId === column.materialId)
        : model.processSteps.find((entry: any) => entry.field === field.code)
      if (targetItem) targetItem.sourceRefs = [{ sheetId: 'sheet-1', sheetName: '实验方案', row: 2, column: index + 1, displayValue: value ?? '', sourceType: 'AI_PLAN' }]
    })
    model.documentFormat = 'excel'
    model.documentSnapshot = { id: `plan-${candidate.id}`, name: model.title, sheetOrder: ['sheet-1'], sheets: { 'sheet-1': {
      id: 'sheet-1', name: '实验方案', rowCount: 30, columnCount: Math.max(12, fields.length), cellData: cells,
      columnData: Object.fromEntries(fields.map((_: unknown, index: number) => [String(index), { w: 190 }])),
    } } }
  } else throw new Error('任务缺少已保存的原表，请重新选择数据开始优化')
  return { title: model.title, sourceType: 'MANUAL', editModel: model,
    templateVersionId: mapped ? run.frozenBaseline?.templateVersionId : undefined,
    templateSnapshotHash: mapped ? run.frozenBaseline?.templateSnapshotHash : undefined,
    templateSnapshot: mapped ? run.frozenBaseline?.templateSnapshot : model.documentSnapshot }
}
