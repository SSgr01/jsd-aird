import { Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { targetLabels } from '../performance-prediction/performance-prediction-utils'
import { candidateNote, conditionText, fieldName, formatPredictionNumber, formulaMaterialName, goalMet, resultPrediction, resultText, type FormulaCompleted } from './formula-prediction-utils'
import type { FormulaCandidate, FormulaMaterial } from '@/services/ai-rnd/formula-design-api'

export function FormulaCandidateTable({ completed, stale = false }: { completed: FormulaCompleted; stale?: boolean }) {
  const { run, context, form } = completed
  const materials = new Map<string, FormulaMaterial>(context.materialIntersection.map(material => [material.materialId, material]))
  run.candidates.forEach(candidate => candidate.formula.components?.forEach(component => { if (!materials.has(component.materialId)) materials.set(component.materialId, component) }))
  const labels = targetLabels(context.selected, false)
  const notes = run.candidates.map(candidateNote)
  const sharedNote = notes.length > 0 && notes[0] && notes.every(note => note === notes[0]) ? notes[0] : ''
  const hasNotes = !sharedNote && notes.some(Boolean)
  const columns: ColumnsType<FormulaCandidate> = [
    { title: '方案', width: 75, fixed: 'left', render: (_, candidate) => <strong>方案 {candidate.candidateNo}</strong> },
    ...Array.from(materials.values()).map((material, index) => ({ title: `${formulaMaterialName(material, index)}（%）`, width: 120, render: (_: unknown, candidate: FormulaCandidate) => {
      const component = candidate.formula.components?.find(item => item.materialId === material.materialId)
      return component?.ratio == null ? '未记录' : formatPredictionNumber(component.ratio)
    } })),
    ...context.requiredInputs.map(field => ({ title: `${fieldName(field)}${field.unit ? `（${field.unit}）` : ''}`, width: 130, render: (_: unknown, candidate: FormulaCandidate) => conditionText(candidate.inputs?.[field.code] ?? run.frozenEvidence?.fixedInputs?.[field.code]) })),
    ...context.selected.map(target => ({ title: `预测 ${labels.get(target.targetId) ?? target.name}${target.unit ? `（${target.unit}）` : ''}`, width: 155, fixed: 'right' as const, render: (_: unknown, candidate: FormulaCandidate) => {
      const result = candidate.targetResults.targets?.find(item => item.targetId === target.targetId), value = resultPrediction(result)
      const met = goalMet(form.goals[target.targetId], target, result)
      return <div className="formula-predicted-value"><strong>{resultText(result)}</strong>{value?.resultType === 'BINARY' && <small>{value.positiveClass}概率 {(value.probability * 100).toFixed(1)}%</small>}{!stale && met !== undefined && <small className={met ? 'formula-goal-met' : 'formula-field-error'}>{met ? '达到目标' : '未达到目标'}</small>}</div>
    } })),
    ...(hasNotes ? [{ title: '说明', width: 170, render: (_: unknown, candidate: FormulaCandidate) => <span className="formula-range-notice">{candidateNote(candidate)}</span> }] : []),
  ]
  return <>{sharedNote && <div className="formula-shared-note formula-range-notice">{sharedNote}</div>}<Table rowKey="id" size="middle" pagination={false} columns={columns} dataSource={run.candidates} scroll={{ x: 'max-content' }} /></>
}
