import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HttpError } from '@/services/http/errors'
import type { FormulaRequest, ResearchRun } from '@/services/ai-rnd/formula-design-api'
import { formulaContext, formulaRun } from '@/test/formula-prediction-fixtures'
import { FormulaPredictionPage } from './FormulaPredictionPage'
import { buildFormulaRequest, emptyFormulaForm, syncFormulaForm } from './formula-prediction-utils'

const api = vi.hoisted(() => ({ context: vi.fn(), submit: vi.fn(), run: vi.fn() }))
vi.mock('@/services/ai-rnd/formula-design-api', () => ({ formulaDesignApi: api }))
vi.mock('@/stores/auth-store', () => ({ useAuthStore: (selector: (state: { user: { organizationId: string; userId: string }; can: () => boolean }) => unknown) => selector({ user: { organizationId: 'org-1', userId: 'user-1' }, can: () => true }) }))
const storageKey = 'formula-prediction:v3:org-1:user-1'
let submitted: FormulaRequest | undefined
function mount() { return render(<MemoryRouter><FormulaPredictionPage /></MemoryRouter>) }
async function select(kind = '干膜') {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: '目标性能' }))
  fireEvent.click(await screen.findByText(`60°光泽（GU） · ${kind} · 膜性能`))
  fireEvent.keyDown(screen.getByRole('combobox', { name: '目标性能' }), { key: 'Escape' })
  await screen.findByRole('spinbutton', { name: new RegExp(`${kind}.*目标值`) })
}
function fillGoal(value = '80', kind = '干膜') { const input = screen.getByRole('spinbutton', { name: new RegExp(`${kind}.*目标值`) }); fireEvent.change(input, { target: { value } }); fireEvent.blur(input) }
function frozenRequest() {
  const context = formulaContext(['dry-gloss']), form = syncFormulaForm({ ...emptyFormulaForm(), targets: ['dry-gloss'] }, context)
  form.goals['dry-gloss']!.value = 80
  return buildFormulaRequest(form, context)
}

describe('FormulaPredictionPage', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/'); submitted = undefined
    api.context.mockImplementation((ids?: string[]) => Promise.resolve(formulaContext(ids)))
    api.submit.mockImplementation((request: FormulaRequest) => { submitted = request; return Promise.resolve({ runId: 'run-1', executionStatus: 'QUEUED', modelBindings: {}, pollAfterMs: 1000 }) })
    api.run.mockImplementation(() => Promise.resolve(formulaRun(submitted)))
  })
  it('starts with one prompt and no empty material, condition or result cards', async () => {
    mount()
    expect(await screen.findByText('选择目标性能后设置目标值')).toBeInTheDocument()
    expect(screen.queryByText('选择目标后显示共同材料')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成配方方案' })).not.toBeInTheDocument()
  })
  it('selects a same-name target by ID and predicts proportions without requiring a starting recipe', async () => {
    mount(); await select('湿膜'); fillGoal('80', '湿膜')
    expect(screen.queryByText('多目标权重')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '生成配方方案' }))
    await screen.findByText('85.1235')
    expect(submitted?.typedGoals[0]).toMatchObject({ targetId: 'wet-gloss', expectedModelVersionId: 'model-wet', value: 80 })
    expect(submitted?.searchSpace.variableMaterials).toHaveLength(2)
    expect(screen.getByRole('columnheader', { name: '丙烯酸树脂（%）' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '温度（°C）' })).toBeInTheDocument()
    expect(screen.getByText('27')).toBeInTheDocument()
    expect(screen.queryByText(/综合分/)).not.toBeInTheDocument()
    expect(screen.queryByText('SUCCEEDED')).not.toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.queryByText(/模拟|仅供预览/)).not.toBeInTheDocument()
  })
  it('retains results after constraint edits and restores both the edited form and original result', async () => {
    const view = mount(); await select(); fillGoal(); fireEvent.click(screen.getByRole('button', { name: '生成配方方案' })); await screen.findByText('85.1235')
    fireEvent.click(screen.getByText(/^材料比例限制/))
    const maximum = screen.getByRole('spinbutton', { name: '丙烯酸树脂最大比例' })
    act(() => maximum.focus()); fireEvent.change(maximum, { target: { value: '90' } })
    expect(maximum).toHaveFocus()
    expect(screen.getByText('需重新生成')).toBeInTheDocument(); expect(screen.getByText('85.1235')).toBeInTheDocument(); expect(screen.queryByText('达到目标')).not.toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem(storageKey)).toContain('90'))
    view.unmount(); mount(); await screen.findByRole('spinbutton', { name: /干膜.*目标值/ })
    fireEvent.click(screen.getByText(/^材料比例限制/))
    expect(screen.getByRole('spinbutton', { name: '丙烯酸树脂最大比例' })).toHaveValue('90')
    expect(screen.getByText('85.1235')).toBeInTheDocument(); expect(screen.getByText('需重新生成')).toBeInTheDocument()
  })
  it('restores fixed conditions from a linked old run instead of replacing them with default ranges', async () => {
    const request = frozenRequest(); request.fixedInputs = { TEMPERATURE_C: 40 }; request.searchSpace.variableInputs = {}
    api.run.mockResolvedValue(formulaRun(request))
    window.history.replaceState({}, '', '/assistant/formula-prediction?runId=run-1')
    mount(); await screen.findByText('85.1235'); await screen.findByRole('spinbutton', { name: /干膜.*目标值/ })
    fireEvent.click(screen.getByText(/^实验条件/))
    expect(screen.getByRole('spinbutton', { name: '温度' })).toHaveValue('40')
    expect(api.submit).not.toHaveBeenCalled()
  })
  it('identifies infeasible ratio totals with a concrete amount and keeps generation blocked', async () => {
    mount(); await select(); fillGoal(); fireEvent.click(screen.getByText(/^材料比例限制/))
    fireEvent.change(screen.getByRole('spinbutton', { name: '流平剂最小比例' }), { target: { value: '50' } })
    fireEvent.change(screen.getByRole('spinbutton', { name: '流平剂最大比例' }), { target: { value: '60' } })
    expect(await screen.findByText(/比例下限合计 110%，超出 10%/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成配方方案' })).toBeDisabled()
    expect(api.submit).not.toHaveBeenCalled()
  })
  it('locks a running request and resumes it on refresh without submitting again', async () => {
    let finish: ((run: ResearchRun) => void) | undefined
    api.run.mockImplementation(() => new Promise<ResearchRun>(resolve => { finish = resolve }))
    const view = mount(); await select(); fillGoal(); fireEvent.click(screen.getByRole('button', { name: '生成配方方案' }))
    await waitFor(() => expect(api.run).toHaveBeenCalledWith('run-1'))
    expect(screen.getByRole('combobox', { name: '目标性能' })).toBeDisabled()
    expect(screen.getByRole('spinbutton', { name: /干膜.*目标值/ })).toBeDisabled()
    view.unmount(); mount(); await waitFor(() => expect(api.run).toHaveBeenCalledTimes(2))
    await act(async () => { finish?.(formulaRun(submitted)); await Promise.resolve() })
    expect(await screen.findByText('85.1235')).toBeInTheDocument()
    expect(api.submit).toHaveBeenCalledOnce()
  })
  it('reuses the request key when refreshed before the submission acknowledgement', async () => {
    api.submit.mockImplementationOnce(() => new Promise(() => {}))
    const view = mount(); await select(); fillGoal(); fireEvent.click(screen.getByRole('button', { name: '生成配方方案' }))
    await waitFor(() => expect(api.submit).toHaveBeenCalledOnce())
    const key: unknown = api.submit.mock.calls[0]?.[1]
    view.unmount(); mount(); await screen.findByText('85.1235')
    expect(api.submit).toHaveBeenCalledTimes(2); expect(api.submit.mock.calls[1]?.[1]).toBe(key)
  })
  it('recovers from a missing task while retaining the submitted inputs', async () => {
    api.run.mockRejectedValue(new HttpError('gone', 'NOT_FOUND', 404))
    mount(); await select(); fillGoal('82'); fireEvent.click(screen.getByRole('button', { name: '生成配方方案' }))
    expect(await screen.findByText('这条方案无法恢复，请使用已保存的输入重新生成。')).toBeInTheDocument()
    expect(screen.getByRole('spinbutton', { name: /干膜.*目标值/ })).toHaveValue('82')
    expect(screen.getByRole('button', { name: '生成配方方案' })).toBeEnabled()
    expect(new URLSearchParams(window.location.search).has('runId')).toBe(false)
  })
  it('allows removing unavailable restored targets and never calls them ready', async () => {
    const request = frozenRequest(), run = formulaRun(request)
    api.run.mockResolvedValue(run)
    api.context.mockImplementation((ids?: string[]) => { const context = formulaContext(ids); return Promise.resolve({ ...context, targets: context.targets.map(target => ({ ...target, available: false, unavailableReasons: ['NO_ACTIVE_MODEL'] })), selected: context.selected.map(target => ({ ...target, available: false, unavailableReasons: ['NO_ACTIVE_MODEL'] })) }) })
    window.history.replaceState({}, '', '/assistant/formula-prediction?runId=run-1')
    mount(); await screen.findByText('85.1235')
    expect(await screen.findByText(/干膜.*尚无已启用模型/)).toBeInTheDocument()
    expect(screen.queryByText('已选择模型')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成配方方案' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('combobox', { name: '目标性能' }), { key: 'Backspace', code: 'Backspace' })
    await waitFor(() => expect(screen.queryByRole('spinbutton', { name: /干膜.*目标值/ })).not.toBeInTheDocument())
    expect(screen.getByText('85.1235')).toBeInTheDocument()
  })
  it('blocks incompatible input units before submitting a multi-target run', async () => {
    api.context.mockImplementation((ids?: string[]) => Promise.resolve({ ...formulaContext(ids), configurationConflicts: ids && ids.length > 1 ? [{ fieldCode: 'TEMPERATURE_C', definitions: ['NUMBER|°C', 'NUMBER|K'] }] : [] }))
    mount(); await select(); fillGoal(); await select('湿膜'); fillGoal('80', '湿膜')
    expect(await screen.findByText('所选指标的实验条件类型、单位或可选类别不同，请调整指标组合。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成配方方案' })).toBeDisabled(); expect(api.submit).not.toHaveBeenCalled()
  })
  it('retains numeric zero, boolean false and the real category code in a request', async () => {
    api.context.mockImplementation((ids?: string[]) => { const context = formulaContext(ids); if (ids?.length) context.requiredInputs.push({ code: 'HEATED', name: '是否加热', valueType: 'BOOLEAN', fieldVersionId: 'heated', required: true }, { code: 'SUBSTRATE', name: '基材', valueType: 'CATEGORY', fieldVersionId: 'substrate', required: true, allowedValues: ['PET_100UM_OPTICAL'] }); return Promise.resolve(context) })
    mount(); await select(); fillGoal()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '温度设置方式' })); fireEvent.click(await screen.findByText('固定值'))
    fireEvent.change(screen.getByRole('spinbutton', { name: '温度' }), { target: { value: '0' } })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '是否加热' })); fireEvent.click(await screen.findByText('否'))
    fireEvent.click(screen.getByRole('button', { name: '生成配方方案' })); await screen.findByText('85.1235')
    expect(submitted?.fixedInputs).toEqual({ TEMPERATURE_C: 0, HEATED: false, SUBSTRATE: 'PET_100UM_OPTICAL' })
  })
  it('shows a shared extrapolation reminder only once across candidates', async () => {
    api.run.mockImplementation(() => { const run = formulaRun(submitted); run.candidates[0]!.targetResults.targets![0]!.applicability = { status: 'OUT_OF_DOMAIN' }; run.candidates.push({ ...run.candidates[0]!, id: 'candidate-2', candidateNo: 2 }); run.actualCandidateCount = 2; return Promise.resolve(run) })
    mount(); await select(); fillGoal(); fireEvent.click(screen.getByRole('button', { name: '生成配方方案' }))
    await screen.findByText('方案 2')
    expect(screen.getAllByText('超出已有实验范围，建议先做实验验证')).toHaveLength(1)
  })
})
