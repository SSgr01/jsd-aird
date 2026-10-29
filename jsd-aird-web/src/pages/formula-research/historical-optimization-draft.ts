/* eslint-disable @typescript-eslint/no-explicit-any, @typescript-eslint/no-unsafe-assignment, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-argument, @typescript-eslint/no-unsafe-return */
import type { HistoryDataset, OptimizationCandidate, OptimizationRun } from '@/services/ai-rnd/optimization-api'
import { generateUUID } from '@/utils/uuid'
import { workbookCellInput } from '@/features/data-workbench/cell-format'

function position(ref: any) {
  if (Number.isInteger(ref.row) && Number.isInteger(ref.column) && ref.row > 0 && ref.column > 0) return { row: ref.row, column: ref.column }
  const match = String(ref.cellRange ?? ref.cell ?? '').match(/^\$?([A-Za-z]+)\$?(\d+)$/)
  if (!match) return undefined
  const column = [...match[1]!.toUpperCase()].reduce((value, char) => value * 26 + char.charCodeAt(0) - 64, 0)
  return { row: Number(match[2]), column }
}

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
  const geometry = (code: string) => ((first.sourceRefs?.[code] ?? []) as any[]).filter(ref => position(ref)).map(ref => ({
    sheetId: ref.sheetId, sheetName: ref.sheetName, ...position(ref)!, cell: ref.cell ?? ref.cellRange,
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
      materialName: column.name.replace(/(?:比例|用量)$/, ''), ratio: value, ...(column.semanticType === 'MATERIAL_AMOUNT' ? { actualQty: value } : {}), rawValue: value, unit: column.unit ?? '', compositionBasis: column.compositionBasis })
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
    // Unified lab templates mark all business value cells, including values
    // that are not numeric training columns and therefore have no sourceRefs.
    // Clear those too so a proposed experiment never inherits old results.
    for (const sheet of Object.values<any>(workbook.sheets)) {
      for (const row of Object.values<any>(sheet.cellData ?? {})) {
        for (const cell of Object.values<any>(row ?? {})) {
          if (['VALUE', 'MATERIAL_QTY'].includes(cell?.custom?.lab?.role)) { delete cell.v; delete cell.p; delete cell.f }
        }
      }
    }
    // Clear all observed values from every sample slot, retaining labels, layout and formulas outside those slots.
    for (const row of dataset.rows) for (const refs of Object.values(row.sourceRefs ?? {})) for (const ref of refs as any[]) {
      const sheet: any = locate(ref); const pos = position(ref)
      const cell = pos && sheet?.cellData?.[String(pos.row - 1)]?.[String(pos.column - 1)]
      if (cell) { delete cell.v; delete cell.p; delete cell.f }
    }
    const writes = new Map<string, { ref: ReturnType<typeof geometry>[number]; values: Array<{ value: unknown; component?: string; unit?: string }> }>()
    const amounts = dataset.columns.filter(column => column.semanticType === 'MATERIAL_AMOUNT' && column.unit?.toLowerCase() === 'g')
    const totalMass = amounts.reduce((total, column) => total + Number(first.values[column.code] ?? 0), 0)
    for (const field of [...request.features, { code: request.target }]) {
      let value = field.code === request.target ? measured : candidate.inputs[field.code]
      if (value === undefined || value === null) continue
      const refs = geometry(field.code)
      if (!refs.length) throw new Error(`缺少原表位置：${dataset.columns.find(item => item.code === field.code)?.name ?? field.code}`)
      const column = dataset.columns.find(item => item.code === field.code)
      if (column?.semanticType === 'MATERIAL_AMOUNT' && refs.length !== 1) throw new Error('该材料对应多次投料，请先确认各次投料用量')
      if (column?.semanticType === 'MATERIAL_RATIO' && amounts.some(amount => amount.materialId === column.materialId)) {
        if (amounts.some(amount => typeof first.values[amount.code] !== 'number') || !Number.isFinite(totalMass) || totalMass <= 0) throw new Error('原配方总用量无效，请先确认配方用量')
        if (refs.length !== 1) throw new Error('该材料对应多次投料，请先确认各次投料用量')
        value = Number(value) * totalMass / 100
      }
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
        const style = typeof cell.s === 'string' ? workbook.styles?.[cell.s] : cell.s
        const pattern = style?.n?.pattern ?? ref.numberFormat ?? ''
        cell.v = workbookCellInput(value, pattern)
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

/** Place selected proposals into distinct records of the same frozen workbook. */
export function historicalCandidatesDraft(run: OptimizationRun, candidates: OptimizationCandidate[], measurements: Record<string, number | undefined> = {}) {
  if (!candidates.length) throw new Error('请先选择要生成实验的方案')
  const dataset = run.frozenRequest.datasetSnapshot as HistoryDataset | undefined
  if (!dataset?.rows.length) throw new Error('本任务没有保存样品与原表位置，请重新选择数据开始优化')
  const refsFor = (row: typeof dataset.rows[number], code: string): any[] => Array.isArray(row.sourceRefs?.[code]) ? row.sourceRefs[code] : []
  const selectedIds = run.frozenRequest.selectedRowIdsByDataset ? new Set<string>(Object.values(run.frozenRequest.selectedRowIdsByDataset as Record<string, string[]>).flat()) : undefined
  const first = dataset.rows.find(row => (!selectedIds || selectedIds.has(row.id)) && typeof row.values[run.frozenRequest.target] === 'number' && run.frozenRequest.features.every((field: any) => row.values[field.code] !== undefined))
  if (!first) throw new Error('没有可用于原表映射的完整样品')
  const original: any = run.frozenBaseline?.sourceWorkbookSnapshot ?? run.frozenBaseline?.templateSnapshot
  const anchorCode = run.frozenRequest.features.find((field: any) => refsFor(first, field.code).some(ref => {
    const point = position(ref), sheet = original?.sheets?.[ref.sheetId]
    return point && sheet?.columnData?.[String(point.column - 1)]?.custom?.lab?.sampleKey
  }))?.code ?? run.frozenRequest.features[0]?.code ?? ''
  let rows = dataset.rows.filter(row => row.sourceContextKey === first.sourceContextKey && run.frozenRequest.features.every((field: any) => refsFor(row, field.code).some(position)))
  if (rows.length < candidates.length && anchorCode) {
    const anchor = refsFor(first, anchorCode).find(position), point = position(anchor), sheet = original?.sheets?.[anchor?.sheetId]
    if (point && sheet?.columnData) {
      const slots = Object.entries(sheet.columnData as Record<string, any>).filter(([, column]) => column?.custom?.lab?.sampleKey).map(([index]) => Number(index) + 1).sort((a, b) => a - b)
      rows = slots.map(column => ({ ...first, id: `${first.id}:slot:${column}`, sourceRefs: Object.fromEntries(Object.entries(first.sourceRefs ?? {}).map(([code, values]) => [code,
        (Array.isArray(values) ? values : []).map(ref => {
          const location = position(ref)
          return location && ref.sheetId === anchor.sheetId && location.column === point.column ? { ...ref, row: location.row, column } : ref
        })])) }))
    }
  }
  if (rows.length < candidates.length) throw new Error(`原表中可定位的对照组不足 ${candidates.length} 个，请先在实验表中扩展对照组`)
  const seen = new Set<string>()
  const parts = candidates.map((candidate, index) => {
    const row = rows[index]!
    const featureRef = refsFor(row, anchorCode).find(position)
    const pos = position(featureRef)
    const identity = `${featureRef?.sheetId}:${pos?.row}:${pos?.column}`
    if (!pos || seen.has(identity)) throw new Error('对照组位置不明确，请先核对原表字段映射')
    seen.add(identity)
    const scoped = { ...run, frozenRequest: { ...run.frozenRequest, datasetSnapshot: { ...dataset, rows: [row] }, selectedRowIdsByDataset: { selected: [row.id] } } }
    return { row, candidate, sampleKey: generateUUID(), draft: historicalCandidateDraft(scoped, candidate, measurements[candidate.id]) }
  })
  const combined = parts[0]!.draft
  const model: any = combined.editModel
  model.title = `${run.frozenRequest.targetName} · 配方优化`
  model.aiResearch = { runId: run.id, candidates: parts.map(({ candidate, sampleKey, draft }) => ({ candidateId: candidate.id, sampleKey, predictedResults: draft.editModel.aiResearch.predictedResults })) }
  const workbook: any = model.documentSnapshot
  const getCell = (snapshot: any, ref: any) => {
    const point = position(ref)
    const sheet: any = snapshot.sheets[ref.sheetId] ?? Object.values(snapshot.sheets).find((item: any) => item.name === ref.sheetName)
    if (!point || !sheet) throw new Error('对照组位置与原表不一致，请重新选择数据')
    sheet.cellData ??= {}; sheet.cellData[String(point.row - 1)] ??= {}
    return sheet.cellData[String(point.row - 1)][String(point.column - 1)] ??= {}
  }
  const occupied = new Map<string, unknown>()
  for (const { row, candidate, sampleKey, draft } of parts) {
    const local: any = draft.editModel
    const groupName = candidate.title.replace(/^候选/, '')
    for (const item of [...local.sampleGroups, ...local.formulaItems, ...local.processSteps, ...local.testResults]) {
      item.sampleKey = sampleKey; item.sourceGroupKey = sampleKey; item.sourceIdentity = groupName
      for (const ref of item.sourceRefs ?? []) { ref.sampleKey = sampleKey; ref.sourceGroupKey = sampleKey }
    }
    if (draft !== combined) {
      model.sampleGroups.push(...local.sampleGroups)
      model.formulaItems.push(...local.formulaItems)
      model.processSteps.push(...local.processSteps)
      model.testResults.push(...local.testResults)
    }
    for (const item of [...local.formulaItems, ...local.processSteps, ...local.testResults]) {
      for (const ref of item.sourceRefs ?? []) {
        const point = position(ref), from = getCell(local.documentSnapshot, ref), to = getCell(workbook, ref)
        const key = `${ref.sheetId}:${point?.row}:${point?.column}`
        if ('v' in from && occupied.has(key) && occupied.get(key) !== from.v) throw new Error('多个方案的不同值指向同一单元格，请先核对字段位置')
        if ('v' in from) occupied.set(key, from.v)
        if (draft !== combined) { delete to.v; delete to.p; delete to.f; if ('v' in from) to.v = from.v; if ('t' in from) to.t = from.t }
      }
    }
    const label = dataset.columns.find(column => ['样品/方案编号', '实验编号'].includes(column.name))
    for (const ref of label ? refsFor(row, label.code) : []) {
      if (!position(ref)) continue
      const cell = getCell(workbook, ref); delete cell.p; delete cell.f; cell.v = groupName; cell.t = 1
    }
    const ref = refsFor(row, anchorCode).find(position)
    const point = position(ref)
    const sheet: any = ref && (workbook.sheets[ref.sheetId] ?? Object.values(workbook.sheets).find((item: any) => item.name === ref.sheetName))
    const column = point && sheet?.columnData?.[String(point.column - 1)]?.custom?.lab
    if (column?.sampleKey) {
      column.sampleKey = sampleKey
      for (const cells of Object.values<any>(sheet.cellData ?? {})) {
        const cell = cells?.[String(point!.column - 1)]
        if (cell?.custom?.lab?.role === 'VALUE' && ['sample.number', 'sample.experimentNo'].includes(cell.custom.lab.fieldCode)) {
          delete cell.p; delete cell.f; cell.v = groupName; cell.t = 1
        }
      }
    }
  }
  return { ...combined, title: model.title }
}
