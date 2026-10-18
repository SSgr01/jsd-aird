import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TrainingEvaluation } from './TrainingEvaluation'

describe('training evaluation', () => {
  it('separates fitted and held-out metrics, explains units and marks the selected candidate', () => {
    render(<TrainingEvaluation numeric unit="次" metrics={{ selectedCandidateId: 'ridge-1', selectionMetric: 'mae', validationFolds: 4,
      mae: 44.4253, rmse: 60, r2: .7231, trainingMetrics: { mae: .5, rmse: .8, r2: .99 }, baselineMetrics: { mae: 80 },
      comparison: [{ candidateId: 'linear-1', algorithm: 'LINEAR_REGRESSION', mae: 70 }, { candidateId: 'ridge-1', algorithm: 'RIDGE', parameters: { alpha: .01 }, mae: 44.4253 }],
    }} />)
    expect(screen.getAllByText('训练数据').length).toBeGreaterThan(0)
    expect(screen.getAllByText('交叉验证').length).toBeGreaterThan(0)
    expect(screen.getByText('44.4253 次')).toBeInTheDocument()
    expect(screen.getByText('0.5 次')).toBeInTheDocument()
    expect(screen.getByText(/交叉验证平均绝对误差（MAE）最小/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('预测方法对比（2 组）'))
    expect(screen.getByText('岭回归')).toBeInTheDocument()
    expect(screen.getByText('已选')).toBeInTheDocument()
    expect(screen.getByText('正则化强度 0.01')).toBeInTheDocument()
  })

  it('shows classification scores as percentages while keeping probability loss numeric', () => {
    render(<TrainingEvaluation numeric={false} metrics={{ accuracy: .5, macroF1: .3333, logLoss: .6931,
      trainingMetrics: { accuracy: 1, macroF1: 1, logLoss: .01 }, selectionMetric: 'logLoss' }} />)
    expect(screen.getByText('50.0%')).toBeInTheDocument()
    expect(screen.getAllByText('100.0%')).toHaveLength(2)
    expect(screen.getByText('0.6931')).toBeInTheDocument()
    expect(screen.getByText(/交叉验证概率损失最小/)).toBeInTheDocument()
  })
})
