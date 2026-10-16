import { describe, expect, it } from 'vitest'
import { mergeHistoryDatasets, selectedRowIdsByDataset } from './history-dataset-utils'
import type { HistoryDataset } from './optimization-api'

const table = (id: string, name: string, unit = '°C'): HistoryDataset => ({
  id, name, rowCount: 1,
  columns: [
    { code: 'input:temperature', name: '温度', kind: 'NUMERIC', unit, filledCount: 1 },
    { code: 'result:gloss', name: '光泽', kind: 'NUMERIC', unit: 'GU', filledCount: 1 },
    { code: `only:${id}`, name: `单表字段 ${id}`, kind: 'NUMERIC', unit: '', filledCount: 1 },
  ],
  rows: [{ id: `row-${id}`, versionId: `version-${id}`, label: `样本-${id}`, title: name, values: { 'input:temperature': 20, 'result:gloss': 80, [`only:${id}`]: 1 } }],
})

describe('history-dataset-utils', () => {
  it('merges matching fields and keeps only columns with matching types and units', () => {
    const merged = mergeHistoryDatasets([table('a', '表 A'), table('b', '表 B')])!
    expect(merged.name).toBe('表 A、表 B')
    expect(merged.sourceDatasetIds).toEqual(['a', 'b'])
    expect(merged.rows.map(row => row.sourceDatasetId)).toEqual(['a', 'b'])
    expect(merged.columns.map(column => column.code)).toEqual(['input:temperature', 'result:gloss'])
    expect(merged.rows[0]?.values).toEqual({ 'input:temperature': 20, 'result:gloss': 80 })
  })

  it('does not merge the same field code when its unit differs', () => {
    const merged = mergeHistoryDatasets([table('a', '表 A'), table('b', '表 B', '℉')])!
    expect(merged.columns.map(column => column.code)).toEqual(['result:gloss'])
  })

  it('keeps same-named results separate when known measurement methods differ', () => {
    const first = table('a', '表 A'), second = table('b', '表 B')
    first.columns[1]!.testMethods = ['60°']
    second.columns[1]!.testMethods = ['20°']
    expect(mergeHistoryDatasets([first, second])!.columns.map(column => column.code)).toEqual(['input:temperature'])
  })

  it('aligns a field with a different code when its name, type, and unit match uniquely', () => {
    const first = table('a', '表 A')
    const second: HistoryDataset = {
      ...table('b', '表 B'),
      columns: [
        { code: 'field:oven-temp', name: '温度', kind: 'NUMERIC', unit: '°C', filledCount: 1 },
        { code: 'field:measured-gloss', name: '光泽', kind: 'NUMERIC', unit: 'GU', filledCount: 1 },
      ],
      rows: [{ id: 'row-b', versionId: 'version-b', label: '样本-b', title: '表 B', values: { 'field:oven-temp': 26, 'field:measured-gloss': 84 }, sourceRefs: { 'field:oven-temp': [{ sheetId: 'sheet-1', row: 2, column: 3 }] } }],
    }

    const merged = mergeHistoryDatasets([first, second])!

    expect(merged.columns.map(column => column.code)).toEqual(['input:temperature', 'result:gloss'])
    expect(merged.rows[1]?.values).toEqual({ 'input:temperature': 26, 'result:gloss': 84 })
    expect(merged.rows[1]?.sourceRefs?.['input:temperature']).toEqual([{ sheetId: 'sheet-1', row: 2, column: 3 }])
  })

  it('defaults every row in each selected table to checked and preserves explicit choices', () => {
    const merged = mergeHistoryDatasets([table('a', '表 A'), table('b', '表 B')])!
    expect(selectedRowIdsByDataset(['a', 'b'], merged.rows, {})).toEqual({ a: ['row-a'], b: ['row-b'] })
    expect(selectedRowIdsByDataset(['a', 'b'], merged.rows, { a: [], b: ['row-b'] })).toEqual({ a: [], b: ['row-b'] })
  })
})
