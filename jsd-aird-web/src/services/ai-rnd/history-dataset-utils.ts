import type { HistoryDataset, HistoryDatasetColumn, HistoryDatasetRow } from './optimization-api'

const normalizedFieldName = (name: string) => name.normalize('NFKC').trim().replace(/\s+/g, ' ').toLocaleLowerCase()
const normalizedUnit = (unit?: string) => (unit ?? '').normalize('NFKC').trim().replace(/\s+/g, '').toLocaleLowerCase()
const compatibleColumn = (left: HistoryDatasetColumn, right: HistoryDatasetColumn) =>
  left.kind === right.kind && normalizedUnit(left.unit) === normalizedUnit(right.unit) && JSON.stringify((left.testMethods ?? []).map(normalizedFieldName).sort()) === JSON.stringify((right.testMethods ?? []).map(normalizedFieldName).sort())
  && (left.testStage ?? '') === (right.testStage ?? '') && (left.materialId ?? '') === (right.materialId ?? '')
  && (left.compositionBasis ?? '') === (right.compositionBasis ?? '') && (left.measurementComponent ?? '') === (right.measurementComponent ?? '')
  && left.semanticType === right.semanticType && left.mappingStatus === right.mappingStatus

export const isMappedFormulaColumn = (column: HistoryDatasetColumn) => {
  const id = column.materialId ?? ''
  const prefix = `formula:${id}`
  return column.kind === 'NUMERIC' && column.mappingStatus !== 'UNMAPPED'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
    && (column.code === prefix || column.code.startsWith(`${prefix}|`))
}

export const isUnmappedFormulaColumn = (column: HistoryDatasetColumn) =>
  column.semanticType === 'MATERIAL_RATIO' && column.mappingStatus === 'UNMAPPED'

/**
 * Combine matching experiment fields by stable code, or by an unambiguous
 * normalized name/type/unit match when table-specific codes differ.
 */
export function mergeHistoryDatasets(sources: HistoryDataset[]): HistoryDataset | undefined {
  if (!sources.length) return undefined
  if (sources.length === 1) {
    const source = sources[0]!
    return {
      ...source,
      sourceDatasetIds: [source.id],
      rows: source.rows.map(row => ({ ...row, sourceDatasetId: source.id, sourceDatasetName: source.name })),
    }
  }

  const firstColumns = sources[0]!.columns
  const columns: HistoryDatasetColumn[] = []
  const codesBySource: Array<Map<string, string>> = sources.map(() => new Map<string, string>())
  for (const column of firstColumns) {
    const name = normalizedFieldName(column.name)
    const firstNameCount = firstColumns.filter(item => normalizedFieldName(item.name) === name && compatibleColumn(column, item)).length
    const matchingCodes = [column.code]
    let common = true
    for (const source of sources.slice(1)) {
      const sameCode = source.columns.find(item => item.code === column.code && compatibleColumn(column, item))
      const sameName = source.columns.filter(item => normalizedFieldName(item.name) === name && compatibleColumn(column, item))
      const match = sameCode ?? (firstNameCount === 1 && sameName.length === 1 ? sameName[0] : undefined)
      if (!match) { common = false; break }
      matchingCodes.push(match.code)
    }
    if (!common) continue
    columns.push({ ...column })
    matchingCodes.forEach((code, index) => codesBySource[index]!.set(column.code, code))
  }
  const rows: HistoryDatasetRow[] = sources.flatMap((source, sourceIndex) => source.rows.map(row => ({
    ...row,
    sourceDatasetId: source.id,
    sourceDatasetName: source.name,
    values: Object.fromEntries([...codesBySource[sourceIndex]!.entries()]
      .flatMap(([canonicalCode, sourceCode]) => row.values[sourceCode] === undefined ? [] : [[canonicalCode, row.values[sourceCode]]])),
    sourceRefs: Object.fromEntries([...codesBySource[sourceIndex]!.entries()]
      .flatMap(([canonicalCode, sourceCode]) => row.sourceRefs?.[sourceCode] === undefined ? [] : [[canonicalCode, row.sourceRefs[sourceCode]]])),
  })))
  for (const column of columns) {
    column.filledCount = rows.filter(row => {
      const value = row.values[column.code]
      return value !== undefined && value !== null && value !== ''
    }).length
  }

  return {
    id: sources[0]!.id,
    name: sources.map(source => source.name).join('、'),
    rowCount: rows.length,
    sourceDatasetIds: sources.map(source => source.id),
    columns,
    projectionVersion: sources[0]!.projectionVersion,
    issues: sources.flatMap((source, index) => (source.issues ?? []).map(issue => ({ ...issue, columnCode: [...codesBySource[index]!.entries()].find(([, code]) => code === issue.columnCode)?.[0] ?? issue.columnCode }))),
    rows,
  }
}

export function selectedRowIdsByDataset(
  selectedDatasetIds: string[],
  rows: HistoryDatasetRow[],
  selectedRowIds: Record<string, string[]>,
) {
  return Object.fromEntries(selectedDatasetIds.map(datasetId => [
    datasetId,
    selectedRowIds[datasetId] ?? rows.filter(row => row.sourceDatasetId === datasetId).map(row => row.id),
  ]))
}

export const columnValues = (dataset: HistoryDataset | undefined, code: string) => (dataset?.rows ?? [])
  .map(row => row.values[code]).filter(value => value !== undefined && value !== null && value !== '')
export const columnLevels = (dataset: HistoryDataset | undefined, code: string) => new Set(columnValues(dataset, code)).size
export const materialColumn = isMappedFormulaColumn
export const fixedMaterial = (dataset: HistoryDataset | undefined, column: HistoryDatasetColumn) => materialColumn(column)
  && column.kind === 'NUMERIC' && columnLevels(dataset, column.code) === 1
