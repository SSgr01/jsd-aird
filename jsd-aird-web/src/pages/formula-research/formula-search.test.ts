import { describe, expect, it } from 'vitest'
import { feasibleFormulaSeed } from './formula-search'

describe('gram formula search starting point', () => {
  it('uses independent ranges without normalizing or limiting totals', () => {
    const result = feasibleFormulaSeed([{ materialId: 'a', fixed: true, ratio: 250 }, { materialId: 'b', minimum: 40, maximum: 120 }])
    expect(result).toEqual([{ materialId: 'a', ratio: 250, unit: 'g', amountKnown: true }, { materialId: 'b', ratio: 80, unit: 'g', amountKnown: true }])
  })
  it.each([
    [{ materialId: 'x', fixed: true, ratio: 100 }],
    [{ materialId: 'x', maximum: 40 }],
    [{ materialId: 'x', minimum: 70, maximum: 20 }],
    [{ materialId: 'x', minimum: -1, maximum: 2 }],
    [{ materialId: 'x', minimum: 1, maximum: Infinity }],
    [{ materialId: 'x', minimum: 0, maximum: 1 }, { materialId: 'x', minimum: 0, maximum: 1 }],
  ])('rejects missing or invalid constraints: %j', (...materials) => {
    expect(() => feasibleFormulaSeed(materials)).toThrow()
  })
  it('preserves zero and decimal quantities', () => {
    const result = feasibleFormulaSeed([{ materialId: 'a', fixed: true, ratio: 0 }, { materialId: 'b', minimum: 0.1, maximum: 0.2 }])
    expect(result[0]!.ratio).toBe(0)
    expect(result[1]!.ratio).toBeCloseTo(0.15)
  })
})
