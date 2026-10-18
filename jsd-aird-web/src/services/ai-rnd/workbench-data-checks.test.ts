import { describe, expect, it } from 'vitest'
import { checkWorkbenchData } from './workbench-data-checks'

describe('workbench data checks', () => {
  it('normalizes numeric text and identifies omissions, invalid values, constants and conflicting outcomes', () => {
    const result = checkWorkbenchData({ id: 'data', name: '涂层', rowCount: 5, columns: [
      { code: 'x', name: '固含', kind: 'NUMERIC', filledCount: 4 },
      { code: 'material', name: '基材', kind: 'CATEGORICAL', filledCount: 5 },
      { code: 'y', name: '耐磨', kind: 'NUMERIC', filledCount: 5 },
    ], rows: [
      { id: '1', label: '配方 A', title: '', versionId: '', values: { x: 30, material: 'PET', y: 100 } },
      { id: '2', label: '配方 B', title: '', versionId: '', values: { x: '30.0', material: ' PET ', y: 200 } },
      { id: '3', label: '', title: '', versionId: '', values: { material: 'PET', y: 200 } },
      { id: '4', label: '', title: '', versionId: '', values: { x: 'abc', material: 'PET', y: '0x10' } },
      { id: '5', label: '', title: '', versionId: '', values: { x: 40, material: 'PET', y: 300 } },
    ] }, ['x', 'material'], 'y')!
    expect(result.usableRowCount).toBe(3)
    expect(result.excludedRowCount).toBe(2)
    expect(result.fields[0]).toMatchObject({ missingCount: 1, invalidCount: 1 })
    expect(result.fields[2]?.invalidCount).toBe(1)
    expect(result.constantXColumns).toEqual([{ code: 'material', name: '基材' }])
    expect(result.conflictingInputGroupCount).toBe(1)
    expect(result.conflictingExamples[0]).toMatchObject({ results: [100, 200], rowCount: 2 })
    expect(result.conflictingExamples[0]?.records.map(row => row.label)).toEqual(['配方 A', '配方 B'])
  })

  it('waits until both X and Y are selected', () => {
    expect(checkWorkbenchData(undefined, [], '')).toBeUndefined()
  })
})
