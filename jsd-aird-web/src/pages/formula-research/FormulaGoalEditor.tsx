import { InputNumber, Select } from 'antd'
import type { ReactNode } from 'react'
import type { FormulaTarget } from '@/services/ai-rnd/formula-design-api'
import { defaultGoal, goalError, isPreference, operatorName, type FormulaForm } from './formula-prediction-utils'

type Goal = FormulaForm['goals'][string]
export function FormulaGoalEditor({ target, goal, caption, disabled, touched, onBlur, onChange }: { target: FormulaTarget; goal: Goal; caption: string; disabled: boolean; touched: boolean; onBlur: () => void; onChange: (patch: Partial<Goal>) => void }) {
  const error = touched ? goalError(target, goal) : ''
  const range = Array.isArray(goal.value) ? goal.value : [undefined, undefined]
  const match = typeof goal.value === 'object' && !Array.isArray(goal.value) ? goal.value : undefined
  const numericProps = { disabled, onBlur, status: error ? 'error' as const : undefined }
  const field = (label: string, value: ReactNode) => <label className="formula-small-field"><span>{label}</span>{value}</label>
  return <div className="formula-goal-editor">
    <Select aria-label={`${caption}目标方式`} value={goal.operator} disabled={disabled} options={(target.operators ?? ['AT_LEAST']).map(operator => ({ value: operator, label: operatorName(operator, target.valueType) }))} onChange={operator => onChange({ ...defaultGoal(target, operator), weight: goal.weight })} />
    <div className="formula-goal-values">
      {target.valueType !== 'CONTINUOUS' ? <>
        {field(target.valueType === 'ORDINAL' ? '目标等级' : '目标类别', <Select aria-label={`${caption}目标类别`} disabled={disabled} onBlur={onBlur} status={error ? 'error' : undefined} value={match?.label} onChange={label => onChange({ value: { label } })} options={(target.classes ?? []).map(label => ({ value: label, label }))} placeholder="请选择" />)}
        {field('最低概率（%）', <InputNumber {...numericProps} aria-label={`${caption}最低概率`} value={goal.minimumProbability == null ? undefined : goal.minimumProbability * 100} onChange={value => onChange({ minimumProbability: value == null ? undefined : value / 100 })} />)}
      </> : isPreference(goal.operator) ? <span className="formula-field-hint">用于方案排序</span> : goal.operator === 'RANGE' ? <>
        {field('最小目标值', <InputNumber {...numericProps} aria-label={`${caption}最小目标值`} value={range[0]} addonAfter={target.unit} onChange={value => onChange({ value: [value ?? undefined, range[1]] })} />)}
        {field('最大目标值', <InputNumber {...numericProps} aria-label={`${caption}最大目标值`} value={range[1]} addonAfter={target.unit} onChange={value => onChange({ value: [range[0], value ?? undefined] })} />)}
      </> : goal.operator === 'MATCH' ? <>
        {field('目标值', <InputNumber {...numericProps} aria-label={`${caption}目标值`} value={match?.target} addonAfter={target.unit} onChange={value => onChange({ value: { ...match, target: value ?? undefined } })} />)}
        {field('允许偏差（±）', <InputNumber {...numericProps} aria-label={`${caption}允许偏差`} value={match?.tolerance} addonAfter={target.unit} onChange={value => onChange({ value: { ...match, tolerance: value ?? undefined } })} />)}
      </> : field('目标值', <InputNumber {...numericProps} aria-label={`${caption}目标值`} value={typeof goal.value === 'number' ? goal.value : undefined} addonAfter={target.unit} onChange={value => onChange({ value: value ?? undefined })} />)}
    </div>
    {error && <small role="alert" className="formula-field-error">{error}</small>}
  </div>
}
