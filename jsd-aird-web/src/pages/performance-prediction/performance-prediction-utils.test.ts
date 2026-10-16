import { beforeEach, describe, expect, it } from 'vitest'
import { formatPredictionNumber, inputError, readPredictionDraft, targetLabels } from './performance-prediction-utils'
import type { PredictionInputField } from '@/services/ai-rnd/ai-rnd-types'
const field: PredictionInputField = { fieldVersionId: 'field', code: 'TEMPERATURE_C', name: '温度', valueType: 'NUMBER', required: true, supportedRanges: [{ targetId: 'target', targetName: '光泽', minimum: 20, maximum: 32 }] }
describe('performance prediction input and restore', () => {
  beforeEach(() => localStorage.clear())
  it('accepts zero, false and extrapolation but rejects missing or nonnumeric values', () => {
    expect(inputError(field, 0)).toBe(''); expect(inputError(field, 35)).toBe('')
    expect(inputError({ ...field, valueType: 'BOOLEAN' }, false)).toBe('')
    expect(inputError(field, undefined)).toBe('请填写温度'); expect(inputError(field, NaN)).toBe('请输入有效数值')
  })
  it('uses compact precision without rounding small nonzero predictions to zero', () => {
    expect(formatPredictionNumber(0.000018)).toBe('1.800e-5')
    expect(formatPredictionNumber(0.12345678)).toBe('0.123457')
    expect(formatPredictionNumber(80.123456)).toBe('80.1235')
  })
  it('isolates account drafts and tolerates corrupted saved content', () => {
    localStorage.setItem('org-a:user-a', JSON.stringify({ form: { targets: ['a'], inputs: { TEMPERATURE_C: 28 } } }))
    expect(readPredictionDraft('org-a:user-a').form.inputs.TEMPERATURE_C).toBe(28)
    expect(readPredictionDraft('org-a:user-b').form.targets).toEqual([])
    localStorage.setItem('corrupted', '{bad')
    expect(readPredictionDraft('corrupted').form.targets).toEqual([])
  })
  it('keeps business test conditions while hiding internal method identifiers', () => {
    const labels = targetLabels([
      { targetId: 'uv', name: '耐磨次数', testMethod: 'APP_STEEL_WOOL_ABRASION', testStage: 'UV_CURED' },
      { targetId: 'pu', name: '耐磨次数', testMethod: '钢丝绒耐磨', testStage: 'PU固化后' },
    ])
    expect(labels.get('uv')).toBe('耐磨次数 · UV固化后')
    expect(labels.get('pu')).toBe('耐磨次数 · 钢丝绒耐磨 · PU固化后')
  })
})
