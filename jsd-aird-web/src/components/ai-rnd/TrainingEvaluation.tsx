import { Collapse, Table, Tag, Typography } from 'antd'
import type { WorkbenchDataChecks as DataChecks } from '@/services/ai-rnd/workbench-data-checks'
import { WorkbenchDataChecks } from './WorkbenchDataChecks'
import './training-evaluation.css'

const algorithms: Record<string, string> = {
  LINEAR_REGRESSION: '线性回归', RIDGE: '岭回归', GAUSSIAN_PROCESS: '高斯过程', RANDOM_FOREST: '随机森林',
  LIGHTGBM: 'LightGBM', XGBOOST: 'XGBoost', CATBOOST: 'CatBoost', LOGISTIC_REGRESSION: '逻辑回归',
}
const labels: Record<string, string> = {
  mae: '平均绝对误差（MAE）', rmse: '均方根误差（RMSE）', r2: '拟合度（R²）', accuracy: '准确率', macroF1: '宏平均 F1', logLoss: '概率损失',
}
const hints: Record<string, string> = {
  mae: '预测与实测平均相差多少，越小越好。', rmse: '对较大的误差更敏感，越小越好。',
  r2: '越接近 1 越好；为负数表示不如直接用平均值预测。', accuracy: '预测类别正确的比例，越高越好。',
  macroF1: '综合各类别的识别表现，越高越好。', logLoss: '预测概率与实测类别的偏差，越小越好。',
}
function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}
function metricValue(key: string, value: unknown, unit?: string | null) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  if (key === 'accuracy' || key === 'macroF1') return `${(value * 100).toFixed(1)}%`
  const text = Number(value.toFixed(4)).toString()
  return (key === 'mae' || key === 'rmse') && unit ? `${text} ${unit}` : text
}

export function TrainingEvaluation({ metrics, numeric, unit }: { metrics: Record<string, unknown>; numeric: boolean; unit?: string | null }) {
  const keys = numeric ? ['mae', 'rmse', 'r2'] : ['accuracy', 'macroF1', 'logLoss']
  const training = asRecord(metrics.trainingMetrics)
  const baseline = asRecord(metrics.baselineMetrics)
  const selectionMetric = typeof metrics.selectionMetric === 'string' ? metrics.selectionMetric : numeric ? 'mae' : 'logLoss'
  const comparisons: Array<Record<string, unknown> & { key: string }> = Array.isArray(metrics.comparison) ? metrics.comparison.map((item, index) => {
    const row = asRecord(item)
    return { ...row, key: typeof row.candidateId === 'string' ? row.candidateId : `${String(row.algorithm)}:${index}` }
  }) : []
  const selectedId = metrics.selectedCandidateId
  return <div className="training-evaluation">
    <p className="training-selection-rule">自动选择：<strong>交叉验证{labels[selectionMetric] ?? selectionMetric}最小</strong>{typeof metrics.validationFolds === 'number' ? ` · ${metrics.validationFolds} 折交叉验证` : ''}{numeric ? ` · 误差${unit ? `单位：${unit}` : '沿用原表数值，不是百分制得分'}` : ''}</p>
    <Table size="small" rowKey="key" pagination={false} dataSource={keys.filter(key => typeof metrics[key] === 'number').map(key => ({ key }))} scroll={{ x: 620 }} columns={[
      { title: '指标', width: 185, render: (_, row) => labels[row.key] },
      { title: '交叉验证', width: 135, render: (_, row) => <strong>{metricValue(row.key, metrics[row.key], unit)}</strong> },
      ...(Object.keys(training).length ? [{ title: '训练数据', width: 135, render: (_: unknown, row: { key: string }) => metricValue(row.key, training[row.key], unit) }] : []),
      { title: '含义', render: (_, row) => hints[row.key] },
    ]} />
    {Object.keys(training).length > 0 && <Typography.Text type="secondary" className="training-evaluation-note">训练数据表现反映对已有记录的拟合；交叉验证用于检查对未参与该轮训练记录的预测。</Typography.Text>}
    {typeof baseline[selectionMetric] === 'number' && typeof metrics[selectionMetric] === 'number' && <p className="training-baseline">{numeric ? '仅用平均值预测' : '仅按已有类别比例预测'}的交叉验证{labels[selectionMetric]}：{metricValue(selectionMetric, baseline[selectionMetric], unit)}{Number(metrics[selectionMetric]) >= Number(baseline[selectionMetric]) ? '。当前模型没有优于此结果，建议补充相关输入字段或实验记录。' : '。'}</p>}
    {comparisons.length > 1 && <Collapse className="training-method-comparison" ghost items={[{ key: 'methods', label: `预测方法对比（${comparisons.length} 组）`, children: <Table size="small" rowKey="key" pagination={{ pageSize: 8, hideOnSinglePage: true, showSizeChanger: false }} dataSource={comparisons} scroll={{ x: 680 }} columns={[
      { title: '预测方法', width: 170, render: (_, row) => <>{algorithms[String(row.algorithm)] ?? String(row.algorithm)}{selectedId && row.candidateId === selectedId ? <Tag color="blue">已选</Tag> : null}</> },
      { title: '参数', width: 220, render: (_, row) => Object.entries(asRecord(row.parameters)).map(([key, value]) => `${({ alpha: '正则化强度', noiseLevel: '噪声', optimizerRestarts: '参数优化重启次数', minSamplesLeaf: '叶节点最少记录', minChildSamples: '叶节点最少记录', numLeaves: '叶节点数', depth: '树深', C: 'C' } as Record<string, string>)[key] ?? key} ${String(value)}`).join(' · ') || '默认' },
      ...keys.map(key => ({ title: labels[key], width: 150, render: (_: unknown, row: Record<string, unknown>) => metricValue(key, row[key], unit) })),
    ]} /> }]} />}
    <WorkbenchDataChecks checks={metrics.dataChecks as DataChecks | undefined} />
  </div>
}
