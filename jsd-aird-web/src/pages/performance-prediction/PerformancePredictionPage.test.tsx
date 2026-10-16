import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HttpError } from '@/services/http/errors'
import type { PredictionCatalogTarget, PredictionContext, PredictionRequest, PredictionResponse, TargetSuccess } from '@/services/ai-rnd/ai-rnd-types'
import { PerformancePredictionPage } from './PerformancePredictionPage'

const api = vi.hoisted(() => ({ context: vi.fn(), predict: vi.fn(), record: vi.fn() }))
vi.mock('@/services/ai-rnd/prediction-api', () => ({ predictionApi: api }))
vi.mock('@/stores/auth-store', () => ({ useAuthStore: (selector: (state: { user: { organizationId: string; userId: string } }) => unknown) => selector({ user: { organizationId: 'org-1', userId: 'user-1' } }) }))
const storageKey = 'performance-prediction:v2:org-1:user-1'
const targets: PredictionCatalogTarget[] = [
  { targetId: 'gloss-dry', code: 'GLOSS.DRY', name: '60°光泽', unit: 'GU', testMethod: '干膜', category: '膜性能', valueType: 'CONTINUOUS', targetStatus: 'ACTIVE', formalPredictionAvailable: true, unavailableReasons: [] },
  { targetId: 'gloss-wet', code: 'GLOSS.WET', name: '60°光泽', unit: 'GU', testMethod: '湿膜', category: '膜性能', valueType: 'CONTINUOUS', targetStatus: 'ACTIVE', formalPredictionAvailable: true, unavailableReasons: [] },
  { targetId: 'repair', code: 'CUSTOMER.TEST.REPAIR', name: '修复速度', category: '客户测试语义', valueType: 'CONTINUOUS', targetStatus: 'ACTIVE', formalPredictionAvailable: false, unavailableReasons: ['NO_ACTIVE_MODEL'] },
]
function context(ids: string[] = []): PredictionContext {
  return { targets, selected: targets.filter(target => ids.includes(target.targetId)).map(target => ({ targetId: target.targetId, name: target.name, category: target.category, valueType: target.valueType, available: target.formalPredictionAvailable, modelVersionId: `model-${target.targetId}`, unavailableReasons: target.unavailableReasons, formulaRequirement: 'NOT_USED', evidence: {} })),
    requiredInputs: ids.length ? [{ fieldVersionId: 'temp-version', code: 'TEMPERATURE_C', name: '温度', valueType: 'NUMBER', unit: '°C', required: true, supportedRanges: ids.map(targetId => ({ targetId, targetName: '60°光泽', minimum: 20, maximum: 32 })) }] : [],
    materials: [],
  }
}
function success(targetId = 'gloss-dry', outside = false): TargetSuccess {
  return { targetId, status: 'SUCCEEDED', prediction: { resultType: 'CONTINUOUS', value: 80.123456, unit: 'GU', interval: { lower: 78, upper: 82, coverageLevel: 0.9 } }, modelVersionId: `model-${targetId}`, targetVersionId: 'target-v1', inputSchemeId: 'scheme-1', snapshotId: 'snapshot-1', applicability: { status: outside ? 'OUT_OF_DOMAIN' : 'IN_DOMAIN', distance: 0, reasons: [] }, quality: { level: outside ? 'LOW' : 'HIGH', explanation: '', policyVersionId: 'quality-1', evidenceCoverage: 1 }, warnings: outside ? [{ code: 'EXTRAPOLATION', message: 'OUT_OF_DOMAIN', detail: {} }] : [], evidence: { modelVersionId: '', targetVersionId: '', inputSchemeId: '', snapshotId: '', domainPolicyVersionId: '', qualityPolicyVersionId: '' } }
}
function response(request?: PredictionRequest): PredictionResponse { return { requestId: 'request-1', predictionId: 'prediction-1', executionStatus: 'SUCCEEDED', outcomeStatus: 'SUCCEEDED', results: (request?.targets ?? [{ targetId: 'gloss-dry' }]).map(target => success(target.targetId, Number(request?.inputs.TEMPERATURE_C) > 32)) } }
function mount() { return render(<MemoryRouter><PerformancePredictionPage /></MemoryRouter>) }
async function selectTarget(kind: '干膜' | '湿膜' = '干膜') {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '预测指标' }))
  fireEvent.click(await screen.findByText(`60°光泽（GU） · ${kind} · 膜性能`))
  fireEvent.keyDown(screen.getByRole('combobox', { name: '预测指标' }), { key: 'Escape' })
  await screen.findByRole('spinbutton', { name: '温度' })
}
function fillTemperature(value = '25') { fireEvent.change(screen.getByRole('spinbutton', { name: '温度' }), { target: { value } }); fireEvent.blur(screen.getByRole('spinbutton', { name: '温度' })) }

describe('PerformancePredictionPage', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear()
    api.context.mockImplementation((ids?: string[]) => Promise.resolve(context(ids)))
    api.predict.mockImplementation((request: PredictionRequest) => Promise.resolve(response(request)))
    api.record.mockResolvedValue(response())
  })
  it('uses a single initial prompt and truthful unavailable reasons', async () => {
    mount()
    expect(await screen.findByText('选择预测指标后填写实验输入')).toBeInTheDocument()
    expect(screen.queryByText('配方组成')).not.toBeInTheDocument()
    expect(screen.queryByText('结果将在这里逐项展示')).not.toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '预测指标' }))
    expect(await screen.findByText('修复速度 · 尚无已启用模型')).toBeInTheDocument()
    expect(screen.queryByText('客户测试指标（已训练）')).not.toBeInTheDocument()
  })
  it('selects the right same-name target and permits extrapolation beyond training range', async () => {
    mount(); await selectTarget('湿膜'); fillTemperature('35')
    expect(screen.queryByText('配方组成')).not.toBeInTheDocument()
    expect(screen.getByText(/超出历史范围，结果需要实验验证/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始预测' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await screen.findByText('80.1235')
    expect(api.predict.mock.calls[0]?.[0]).toMatchObject({ inputs: { TEMPERATURE_C: 35 }, targets: [{ targetId: 'gloss-wet', expectedModelVersionId: 'model-gloss-wet' }] })
    expect(screen.getAllByText('超出已有实验范围，建议先做实验验证')).toHaveLength(1)
    expect(screen.queryByText('OUT_OF_DOMAIN')).not.toBeInTheDocument()
    expect(screen.queryByText(/预测区间 78/)).not.toBeInTheDocument()
  })
  it('shows an unavailable restored selection once while preserving its input', async () => {
    localStorage.setItem(storageKey, JSON.stringify({ form: { targets: ['repair'], inputs: { TEMPERATURE_C: 25 } } }))
    mount()
    expect(await screen.findByText('修复速度 · 尚无已启用模型')).toBeInTheDocument()
    expect(screen.getAllByText('修复速度 · 尚无已启用模型')).toHaveLength(1)
    expect(screen.queryByText(/当前没有可预测的指标/)).not.toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: '温度' })).toHaveValue('25')
    expect(screen.getByRole('button', { name: '开始预测' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('combobox', { name: '预测指标' }), { key: 'Backspace', code: 'Backspace' })
    expect(await screen.findByText('选择预测指标后填写实验输入')).toBeInTheDocument()
  })
  it('uses a selected percentage X directly without requiring an entire recipe', async () => {
    api.context.mockImplementation((ids?: string[]) => {
      const value = context(ids)
      if (ids?.length) value.requiredInputs.push({ fieldVersionId: 'ratio', code: 'formula:resin', name: '树脂比例', valueType: 'NUMBER', unit: '%', required: true })
      return Promise.resolve(value)
    })
    mount(); await selectTarget(); fillTemperature()
    fireEvent.change(screen.getByRole('spinbutton', { name: '树脂比例' }), { target: { value: '35' } })
    expect(screen.queryByText('配方已完整')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await screen.findByText('80.1235')
    expect(api.predict.mock.calls[0]?.[0]).toEqual({ inputs: { TEMPERATURE_C: 25, 'formula:resin': 35 }, targets: [{ targetId: 'gloss-dry', expectedModelVersionId: 'model-gloss-dry' }] })
  })
  it('retains prior results after edits and restores the form and result on remount', async () => {
    const view = mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' })); await screen.findByText('80.1235')
    fillTemperature('29')
    expect(screen.getByText('80.1235')).toBeInTheDocument()
    expect(screen.getByText('输入已修改，需重新预测')).toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem(storageKey)).toContain('29'))
    view.unmount(); mount()
    expect(await screen.findByRole('spinbutton', { name: '温度' })).toHaveValue('29')
    expect(screen.getByText('80.1235')).toBeInTheDocument()
    expect(screen.getByText('输入已修改，需重新预测')).toBeInTheDocument()
  })
  it('shows local results with the same status as other predictions', async () => {
    api.predict.mockImplementation((request: PredictionRequest) => Promise.resolve({ ...response(request), simulation: true }))
    mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await screen.findByText('80.1235')
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.queryByText(/模拟|仅供预览/)).not.toBeInTheDocument()
  })
  it('submits numeric zero, boolean false and category codes without replacing them with display labels', async () => {
    api.context.mockImplementation((ids?: string[]) => {
      const value = context(ids)
      if (ids?.length) value.requiredInputs.push(
        { fieldVersionId: 'heated', code: 'HEATED', name: '是否加热', valueType: 'BOOLEAN', required: true },
        { fieldVersionId: 'substrate', code: 'SUBSTRATE', name: '基材', valueType: 'CATEGORY', required: true, allowedValues: ['PET_100UM_OPTICAL'] },
      )
      return Promise.resolve(value)
    })
    mount(); await selectTarget(); fillTemperature('0')
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '是否加热' })); fireEvent.click(await screen.findByText('否'))
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '基材' })); fireEvent.click(await screen.findByText('PET 100μm光学膜'))
    fireEvent.click(screen.getByRole('button', { name: '开始预测' })); await screen.findByText('80.1235')
    expect(api.predict.mock.calls[0]?.[0]).toMatchObject({ inputs: { TEMPERATURE_C: 0, HEATED: false, SUBSTRATE: 'PET_100UM_OPTICAL' } })
  })
  it('locks the submitted form and restores a running task without submitting it twice', async () => {
    api.predict.mockResolvedValue({ predictionRecordId: 'record-1', requestId: 'request-1', executionStatus: 'RUNNING', pollAfterMs: 1000 })
    let finish: ((value: PredictionResponse) => void) | undefined
    api.record.mockImplementation(() => new Promise<PredictionResponse>(resolve => { finish = resolve }))
    const view = mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await waitFor(() => expect(api.record).toHaveBeenCalledWith('record-1'))
    expect(screen.getByRole('spinbutton', { name: '温度' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '预测指标' })).toBeDisabled()
    view.unmount(); mount(); await waitFor(() => expect(api.record).toHaveBeenCalledTimes(2))
    await act(async () => { finish?.(response()); await Promise.resolve() })
    expect(await screen.findByText('80.1235')).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: '温度' })).toHaveValue('25')
    expect(api.predict).toHaveBeenCalledOnce()
  })
  it('reuses the idempotency key if refreshed before submission acknowledgement', async () => {
    api.predict.mockImplementationOnce(() => new Promise(() => {}))
    const view = mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await waitFor(() => expect(api.predict).toHaveBeenCalledOnce())
    const key: unknown = api.predict.mock.calls[0]?.[1]
    view.unmount(); mount(); await screen.findByText('80.1235')
    expect(api.predict).toHaveBeenCalledTimes(2)
    expect(api.predict.mock.calls[1]?.[1]).toBe(key)
  })
  it('renders mixed multi-target outcomes together without exposing backend terms', async () => {
    api.predict.mockResolvedValue({ ...response(), outcomeStatus: 'PARTIAL', results: [success(), { targetId: 'gloss-wet', status: 'FAILED', errorCode: 'COMPUTE_UNAVAILABLE', message: 'modelBundle ERROR java.lang.IllegalStateException' }] })
    mount(); await selectTarget(); await selectTarget('湿膜'); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    expect(await screen.findByText('80.1235')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '预测结果' })).toBeInTheDocument()
    expect(screen.getByText('预测服务暂不可用，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByText('部分完成')).toBeInTheDocument()
    expect(screen.queryByText(/java.lang/)).not.toBeInTheDocument()
  })
  it('omits interval and explanation columns when every result has no content for them', async () => {
    const results = [success(), success('gloss-wet')].map(item => ({ ...item, prediction: { resultType: 'CONTINUOUS' as const, value: 80, unit: 'GU' } }))
    api.predict.mockResolvedValue({ ...response(), results })
    mount(); await selectTarget(); await selectTarget('湿膜'); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    await screen.findByText('已完成')
    expect(screen.queryByRole('columnheader', { name: '预测区间' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '说明' })).not.toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '单位' })).toBeInTheDocument()
  })
  it('retries an unacknowledged request with the same key after network timeout', async () => {
    api.predict.mockRejectedValueOnce(new Error('ETIMEDOUT'))
    mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    expect(await screen.findByText('预测请求暂未确认，正在重新连接。')).toBeInTheDocument()
    await screen.findByText('80.1235', {}, { timeout: 4000 })
    expect(api.predict).toHaveBeenCalledTimes(2)
    expect(api.predict.mock.calls[1]?.[1]).toBe(api.predict.mock.calls[0]?.[1])
  })
  it('recovers a missing task without discarding its saved inputs', async () => {
    api.predict.mockResolvedValue({ predictionRecordId: 'record-1', requestId: 'request-1', executionStatus: 'RUNNING', pollAfterMs: 1000 })
    api.record.mockRejectedValue(new HttpError('gone', 'NOT_FOUND', 404))
    mount(); await selectTarget(); fillTemperature(); fireEvent.click(screen.getByRole('button', { name: '开始预测' }))
    expect(await screen.findByText('这条预测记录无法恢复，请使用已保存的输入重新预测。')).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: '温度' })).toHaveValue('25')
    expect(screen.getByRole('button', { name: '开始预测' })).toBeEnabled()
  })
})
