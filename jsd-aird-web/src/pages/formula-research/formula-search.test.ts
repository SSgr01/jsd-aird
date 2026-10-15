import { describe, expect, it } from 'vitest'
import { feasibleFormulaSeed } from './formula-search'

describe('AI proportion search starting point', () => {
  it('does not require known proportions for variable materials', () => {
    const result = feasibleFormulaSeed([{ materialId: 'resin' }, { materialId: 'additive' }])
    expect(result.reduce((sum, item) => sum + item.ratio, 0)).toBeCloseTo(100)
  })
  it('preserves fixed proportions and tight variable bounds with decimal values', () => {
    const result = feasibleFormulaSeed([
      { materialId: 'fixed', fixed: true, ratio: 12.3456 },
      { materialId: 'small', minimum: 0.1, maximum: 0.2 },
      { materialId: 'resin', minimum: 20, maximum: 90 },
    ])
    expect(result[0]!.ratio).toBe(12.3456)
    expect(result[1]!.ratio).toBeCloseTo(0.2)
    expect(result.reduce((sum, item) => sum + item.ratio, 0)).toBeCloseTo(100)
  })
  it.each([
    [{ materialId: 'x', fixed: true, ratio: 100 }],
    [{ materialId: 'x', maximum: 40 }],
    [{ materialId: 'x', minimum: 90 }, { materialId: 'y', minimum: 20 }],
    [{ materialId: 'x', fixed: true }, { materialId: 'y' }],
    [{ materialId: 'x', minimum: 70, maximum: 20 }],
    [{ materialId: 'x', maximum: Infinity }],
    [{ materialId: 'x', minimum: -1 }],
    [{ materialId: 'x' }, { materialId: 'x' }],
  ])('rejects impossible, missing or invalid constraints: %j', (...materials) => {
    expect(() => feasibleFormulaSeed(materials)).toThrow()
  })
  it('handles many materials and zero-capacity ingredients', () => {
    const result = feasibleFormulaSeed(Array.from({ length: 30 }, (_, index) => ({
      materialId: String(index), maximum: index === 0 ? 0 : index === 1 ? 0.01 : 100,
    })))
    expect(result[0]!.ratio).toBe(0)
    expect(result[1]!.ratio).toBeCloseTo(0.01)
    expect(result.reduce((sum, item) => sum + item.ratio, 0)).toBeCloseTo(100)
  })
})
