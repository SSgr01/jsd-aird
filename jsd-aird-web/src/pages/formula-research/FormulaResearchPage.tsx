import {
  AimOutlined,
  EditOutlined,
  ExperimentOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Col,
  DatePicker,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  AiConversationWorkspace,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace/AiConversationWorkspace';
import { listCategories, type Category } from '@/services/experiments/experiment-api';
import {
  formulaResearchApi,
  type FormulationReadiness,
  type ResearchCandidate,
  type ResearchConfirmationStatus,
  type ResearchDraft,
  type ResearchRun,
  type TargetStatistics,
  type UnresolvedResearchField,
} from '@/services/formula-research';
import { useAuthStore } from '@/stores/auth-store';
import { generateUUID } from '@/utils/uuid';
import {
  draftToFormValues,
  emptyResearchDraft,
  evaluateDraft,
  explainExistingRun,
  formValuesToDraft,
  toResearchRequest,
  type ResearchFormValues,
} from './research-draft';
import { confidenceText, formatResearchNumber, statisticsLevelText, statisticsSummary } from './research-presentation';
import './formula-research.css';

type PageMode = 'FORMULA_PREDICTION' | 'EXPERIMENT_OPTIMIZATION';
type DraftValues = { categoryId: string; ownerName?: string; plannedExperimentDate: Dayjs };
type ChatEntry =
  | { id: string; role: 'USER'; kind: 'TEXT'; text: string }
  | { id: string; role: 'ASSISTANT'; kind: 'TEXT'; text: string }
  | { id: string; role: 'ASSISTANT'; kind: 'CONFIRMATION'; draft: ResearchDraft; status: ResearchConfirmationStatus; unresolved: UnresolvedResearchField[] }
  | { id: string; role: 'ASSISTANT'; kind: 'RUN'; runId: string };

const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED']);
const strategyText: Record<string, string> = {
  CONTROL: '对照方案', REFERENCE: '历史参考', CONSERVATIVE: '保守改进',
  BALANCED: '多目标平衡', EXPLORATORY: '案例范围探索',
};
const modeOptions = [
  { value: 'MINIMIZE', label: '尽量降低' },
  { value: 'MAXIMIZE', label: '尽量提高' },
  { value: 'AT_LEAST', label: '至少达到' },
  { value: 'AT_MOST', label: '不超过' },
  { value: 'MATCH', label: '接近目标值' },
];
const processOptions = [
  { value: 'coatingSolidsPct', label: '涂料固含（%）' },
  { value: 'uvaIntensityMwCm2', label: 'UVA光强（mW/cm²）' },
  { value: 'uvEnergyMjCm2', label: 'UV能量（mJ/cm²）' },
  { value: 'temperatureC', label: '温度（℃）' },
  { value: 'humidityRhPct', label: '湿度（%RH）' },
  { value: 'applicatorSpecUm', label: '绕丝棒规格（μm）' },
];
const statusText: Record<ResearchConfirmationStatus, string> = {
  NEEDS_INPUT: '需要补充', NEEDS_CONFIRMATION: '需要确认', READY: '可以生成', CONFLICT: '条件冲突',
};

function TargetStatisticsCard({ value }: { value: TargetStatistics }) {
  return <Card size="small" className="formula-research-stat-card">
    <Space wrap><Typography.Text strong>{value.name}</Typography.Text><Tag>{statisticsLevelText[value.statisticsLevel]}</Tag>{value.confidence !== 'MODEL' && <Tag color={value.confidence === 'MEDIUM' ? 'blue' : 'gold'}>可信度：{confidenceText[value.confidence]}</Tag>}{value.predictionSource === 'MODEL' && <Tag color="purple">模型评分</Tag>}{value.applicabilityDomain?.status && <Tag>{value.applicabilityDomain.status}</Tag>}</Space>
    <Typography.Paragraph type="secondary" className="formula-research-summary">{statisticsSummary(value)}</Typography.Paragraph>
    {value.predictionSource === 'MODEL' && <Alert type="info" showIcon message={value.predictedClass ? `预测等级：${value.predictedClass}${value.probability !== undefined ? `（达标概率 ${(value.probability * 100).toFixed(1)}%）` : ''}` : `模型预测：${formatResearchNumber(value.pointEstimate)}${value.unit || ''}`} description={value.modelVersionId ? `模型版本 ${value.modelVersionId}` : undefined} />}
    <Space wrap size={[8, 6]}><Tag>{value.caseCount} 条可比较案例</Tag><Tag>{value.exactCount} 条精确结果</Tag>{value.lowerBoundCount > 0 && <Tag color="purple">{value.lowerBoundCount} 条下界证据</Tag>}</Space>
    {value.lowerBoundEvidence?.map((item) => <Alert key={item.analysisRowId} className="formula-research-evidence-alert" type="info" showIcon message={item.minimum === undefined ? item.rawValue : `至少达到 ${item.minimum}${value.unit || ''}仍未失效`} />)}
    {value.cases?.length ? <details><summary>查看相似实验</summary><List size="small" dataSource={value.cases} renderItem={(item) => <List.Item><Space direction="vertical" size={0}><Typography.Text>{item.experimentNo} · {item.title}</Typography.Text><Typography.Text type="secondary">相似度 {(item.similarity * 100).toFixed(1)}% · 实测 {item.rawValue || item.numericValue || item.ordinalValue || '—'}</Typography.Text></Space></List.Item>} /></details> : null}
  </Card>;
}

function CandidateCard({ candidate, selected, selectable, onSelect, targetNames }: { candidate: ResearchCandidate; selected: boolean; selectable: boolean; onSelect: (checked: boolean) => void; targetNames: Map<string, string> }) {
  const rule = candidate.ruleCheck as { afterGeneration?: { checks?: Array<{ code: string; status: string; message: string }> } };
  const checks = rule.afterGeneration?.checks ?? [];
  return <Card className="formula-research-candidate" title={<Space>{selectable && <Checkbox checked={selected} onChange={(event) => onSelect(event.target.checked)} />}<Tag color="blue">{strategyText[candidate.strategy] || candidate.strategy}</Tag><span>{candidate.title}</span></Space>} extra={<Tag color={candidate.confidence === 'MEDIUM' ? 'blue' : 'gold'}>可信度：{confidenceText[candidate.confidence]}</Tag>}>
    <Table size="small" pagination={false} rowKey={(item) => String(item.materialCode || item.materialName)} dataSource={candidate.formula} columns={[{ title: '材料', render: (_, row) => row.materialName || row.materialCode }, { title: '材料编码', dataIndex: 'materialCode' }, { title: '分析比例（%）', dataIndex: 'ratioPercent', render: (value: number | undefined) => value === undefined ? '—' : Number(value.toFixed(4)) }]} />
    <Divider orientation="left" plain>目标证据</Divider>
    <Row gutter={[12, 12]}>{Object.entries(candidate.estimates).map(([key, value]) => <Col xs={24} lg={12} key={key}><TargetStatisticsCard value={{ ...value, name: value.name || targetNames.get(key) || key }} /></Col>)}</Row>
    <Divider orientation="left" plain>规则检查</Divider>
    <Space wrap>{checks.map((item, index) => <Tag key={`${item.code}-${index}`} color={item.status === 'PASSED' ? 'green' : item.status === 'NOT_VERIFIED' ? 'default' : 'red'}>{item.status === 'PASSED' ? '通过' : item.status === 'NOT_VERIFIED' ? '未核验' : '不通过'} · {item.message}</Tag>)}</Space>
  </Card>;
}

function ConfirmationCard({ draft, status, unresolved, readiness, active, running, mode, onEdit, onConfirm }: {
  draft: ResearchDraft;
  status: ResearchConfirmationStatus;
  unresolved: UnresolvedResearchField[];
  readiness?: FormulationReadiness;
  active: boolean;
  running: boolean;
  mode: PageMode;
  onEdit: () => void;
  onConfirm: () => void;
}) {
  const targets = new Map(readiness?.targets.map((item) => [item.targetKey, item]) ?? []);
  return <Card className="formula-research-confirmation" title="条件确认" extra={<Tag color={status === 'READY' ? 'green' : status === 'CONFLICT' ? 'red' : 'gold'}>{statusText[status]}</Tag>}>
    <div className="formula-research-confirm-grid">
      <div><Typography.Text type="secondary">性能目标</Typography.Text><Space wrap>{draft.goals.length ? draft.goals.map((goal) => <Tag key={goal.targetKey} color={goal.mandatory ? 'blue' : undefined}>{targets.get(goal.targetKey)?.name || goal.targetKey} · {modeOptions.find((item) => item.value === goal.mode)?.label || goal.mode}{goal.value !== undefined ? ` ${goal.value}` : ''}</Tag>) : <Typography.Text>尚未选择</Typography.Text>}</Space></div>
      <div><Typography.Text type="secondary">应用条件</Typography.Text><Space wrap>{typeof draft.context.substrate === 'string' ? <Tag>{draft.context.substrate}</Tag> : <Typography.Text>未限定基材</Typography.Text>}<Tag>候选 {draft.candidateCount} 组</Tag></Space></div>
      <div><Typography.Text type="secondary">材料限制</Typography.Text><Space wrap>{draft.constraints.requiredMaterials.map((item) => <Tag color="green" key={`required-${item}`}>必选 {item}</Tag>)}{draft.constraints.forbiddenMaterials.map((item) => <Tag color="red" key={`forbidden-${item}`}>禁用 {item}</Tag>)}{!draft.constraints.requiredMaterials.length && !draft.constraints.forbiddenMaterials.length && <Typography.Text>暂无</Typography.Text>}</Space></div>
      {mode === 'EXPERIMENT_OPTIMIZATION' && <div><Typography.Text type="secondary">基线实验</Typography.Text><Typography.Text>{readiness?.baselines.find((item) => item.analysisRowId === draft.baselineAnalysisRowId)?.experimentNo || '尚未选择'}</Typography.Text></div>}
    </div>
    {unresolved.map((item) => <Alert key={`${item.code}-${item.field}`} type={item.code.includes('CONFLICT') ? 'error' : 'warning'} showIcon message={item.message} />)}
    <Space className="formula-research-confirm-actions"><Button icon={<EditOutlined />} onClick={onEdit}>编辑全部条件</Button><Button type="primary" disabled={!active || status !== 'READY'} loading={running} onClick={onConfirm}>{mode === 'EXPERIMENT_OPTIMIZATION' ? '确认并生成实验方案' : '确认并生成候选'}</Button></Space>
    {!active && <Typography.Text type="secondary">该确认卡已由后续条件更新替代。</Typography.Text>}
  </Card>;
}

function RunResult({ run, current, selected, targetNames, canCreate, onSelect, onCreate }: {
  run?: ResearchRun;
  current: boolean;
  selected: string[];
  targetNames: Map<string, string>;
  canCreate: boolean;
  onSelect: (id: string, checked: boolean) => void;
  onCreate: () => void;
}) {
  if (!run || !terminal.has(run.status)) return <Card className="formula-research-running"><Spin /><Typography.Text>正在检索正式实验并执行规则检查…</Typography.Text></Card>;
  if (run.status === 'FAILED') return <Alert type="error" showIcon message="研究运行失败" description={run.errorMessage} />;
  const targetStats = run.result?.targetStatistics ?? [];
  return <Space direction="vertical" size={14} className="formula-research-full">
    <Card title="本次证据摘要" extra={<Space><Tag>{run.mode}</Tag><Tag color={run.result?.overallConfidence === 'MEDIUM' ? 'blue' : 'gold'}>整体可信度：{confidenceText[run.result?.overallConfidence || 'NONE']}</Tag></Space>}>
      {run.result?.warnings?.map((warning) => <Alert key={warning} type="warning" showIcon message={warning} className="formula-research-warning" />)}
      <Row gutter={[12, 12]}>{targetStats.map((item) => <Col xs={24} lg={12} key={item.targetKey}><TargetStatisticsCard value={item} /></Col>)}</Row>
    </Card>
    <div className="formula-research-candidates-head"><Typography.Title level={4}>候选方案</Typography.Title>{current && canCreate && run.candidates.length > 0 && <Button type="primary" disabled={!selected.length} onClick={onCreate}>将选中方案创建为实验草稿</Button>}</div>
    {run.candidates.length ? run.candidates.map((candidate) => <CandidateCard key={candidate.id} candidate={candidate} selected={selected.includes(candidate.id)} selectable={current && canCreate} onSelect={(checked) => onSelect(candidate.id, checked)} targetNames={targetNames} />) : <Empty description="当前证据和规则下没有可输出的候选方案" />}
  </Space>;
}

export function FormulaResearchPage({ mode }: { mode: PageMode }) {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canCreateExperiment = useAuthStore((state) => state.can('experiment.create'));
  const [form] = Form.useForm<ResearchFormValues>();
  const [draftForm] = Form.useForm<DraftValues>();
  const [readiness, setReadiness] = useState<FormulationReadiness>();
  const [categories, setCategories] = useState<Category[]>([]);
  const [draft, setDraft] = useState<ResearchDraft>(emptyResearchDraft);
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [runs, setRuns] = useState<Record<string, ResearchRun>>({});
  const [activeRunId, setActiveRunId] = useState<string>();
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(true);
  const [parsing, setParsing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [draftOpen, setDraftOpen] = useState(false);
  const [drafting, setDrafting] = useState(false);
  const [conversationId, setConversationId] = useState(generateUUID);
  const pollRefs = useRef<number[]>([]);
  const isOptimization = mode === 'EXPERIMENT_OPTIMIZATION';
  const latestConfirmationId = [...entries].reverse().find((item) => item.kind === 'CONFIRMATION')?.id;

  const loadReadiness = useCallback(async () => {
    setLoading(true);
    try {
      const [ready, categoryRows] = await Promise.all([formulaResearchApi.readiness(), listCategories()]);
      setReadiness(ready);
      setCategories(categoryRows.filter((item) => item.active));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '研究数据就绪状态加载失败');
    } finally { setLoading(false); }
  }, [message]);

  useEffect(() => { void loadReadiness(); return () => pollRefs.current.forEach((timer) => window.clearTimeout(timer)); }, [loadReadiness]);

  const openEditor = useCallback((value = draft) => {
    form.setFieldsValue(draftToFormValues(value));
    setDrawerOpen(true);
  }, [draft, form]);

  const appendConfirmation = useCallback((value: ResearchDraft, serverUnresolved: UnresolvedResearchField[] = [], serverStatus?: ResearchConfirmationStatus) => {
    const local = evaluateDraft(value, mode);
    const unresolved = [...serverUnresolved, ...local.unresolved.filter((item) => !serverUnresolved.some((server) => server.code === item.code && server.field === item.field))];
    const status: ResearchConfirmationStatus = unresolved.some((item) => item.code.includes('CONFLICT')) || serverStatus === 'CONFLICT'
      ? 'CONFLICT' : unresolved.length ? 'NEEDS_INPUT' : serverStatus === 'NEEDS_CONFIRMATION' ? 'NEEDS_CONFIRMATION' : 'READY';
    setEntries((items) => [...items, { id: generateUUID(), role: 'ASSISTANT', kind: 'CONFIRMATION', draft: value, status, unresolved }]);
  }, [mode]);

  const poll = useCallback(async (runId: string) => {
    try {
      const current = await formulaResearchApi.run(runId);
      setRuns((items) => ({ ...items, [runId]: current }));
      if (!terminal.has(current.status)) {
        const timer = window.setTimeout(() => void poll(runId), 1000);
        pollRefs.current.push(timer);
      } else if (current.status === 'FAILED') void message.error(current.errorMessage || '研究运行失败');
    } catch (error) { void message.error(error instanceof Error ? error.message : '研究运行状态获取失败'); }
  }, [message]);

  const submitDraft = useCallback(async (value: ResearchDraft) => {
    const check = evaluateDraft(value, mode);
    if (check.status !== 'READY') { setDraft(value); openEditor(value); void message.warning('请先补充或修正条件'); return; }
    setSubmitting(true); setSelected([]);
    try {
      const request = toResearchRequest(value, readiness?.taskProfileCode || 'UVPU_APPLICATION_FORMULATION', generateUUID());
      const accepted = isOptimization ? await formulaResearchApi.submitExperimentOptimization(request) : await formulaResearchApi.submitFormulaPrediction(request);
      setActiveRunId(accepted.runId);
      setEntries((items) => [...items, { id: generateUUID(), role: 'ASSISTANT', kind: 'RUN', runId: accepted.runId }]);
      await poll(accepted.runId);
    } catch (error) { void message.error(error instanceof Error ? error.message : '研究请求提交失败'); }
    finally { setSubmitting(false); }
  }, [isOptimization, message, mode, openEditor, poll, readiness?.taskProfileCode]);

  const sendQuestion = async () => {
    const text = question.trim();
    if (!text) return;
    setQuestion('');
    setEntries((items) => [...items, { id: generateUUID(), role: 'USER', kind: 'TEXT', text }]);
    const explanation = explainExistingRun(text, activeRunId ? runs[activeRunId] : undefined);
    if (explanation) {
      setEntries((items) => [...items, { id: generateUUID(), role: 'ASSISTANT', kind: 'TEXT', text: explanation }]);
      return;
    }
    setParsing(true);
    try {
      const parsed = await formulaResearchApi.parse({ text, runType: mode, currentDraft: draft });
      setDraft(parsed.draft);
      appendConfirmation(parsed.draft, parsed.unresolvedFields, parsed.confirmationStatus);
      parsed.warnings.forEach((warning) => void message.warning(warning));
    } catch (error) { void message.error(error instanceof Error ? error.message : '条件整理失败，请使用结构化条件编辑'); openEditor(); }
    finally { setParsing(false); }
  };

  const applyEditor = async () => {
    try {
      const values = await form.validateFields();
      const value = formValuesToDraft(values);
      setDraft(value);
      setDrawerOpen(false);
      appendConfirmation(value);
    } catch { /* Ant Form displays field-level validation. */ }
  };

  const resetConversation = () => {
    setConversationId(generateUUID()); setEntries([]); setRuns({}); setActiveRunId(undefined);
    setSelected([]); setQuestion(''); setDraft(emptyResearchDraft()); form.resetFields();
  };

  const createDrafts = async () => {
    if (!activeRunId) return;
    const values = await draftForm.validateFields();
    setDrafting(true);
    try {
      const rows = await formulaResearchApi.createExperimentDrafts(activeRunId, {
        candidateIds: selected, categoryId: values.categoryId, ownerName: values.ownerName,
        plannedExperimentDate: values.plannedExperimentDate.format('YYYY-MM-DD'), idempotencyKey: generateUUID(),
      });
      setDraftOpen(false);
      void message.success(`已创建 ${rows.length} 条实验草稿`);
      if (rows.length === 1) navigate(`/experiments/${rows[0]!.experimentId}`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '实验草稿创建失败'); }
    finally { setDrafting(false); }
  };

  const targetNames = useMemo(() => new Map(readiness?.targets.map((item) => [item.targetKey, item.name]) ?? []), [readiness]);
  const materialOptions = readiness?.materials.map((item) => ({ value: item.materialCode, label: `${item.materialCode}${item.modelAllowed ? '' : ' · 当前模型范围外'}` })) ?? [];
  const currentCheck = evaluateDraft(draft, mode);
  const messages: ConversationMessage[] = entries.map((entry): ConversationMessage => {
    let content: ReactNode;
    if (entry.kind === 'TEXT') content = <Typography.Paragraph className="formula-research-chat-text">{entry.text}</Typography.Paragraph>;
    else if (entry.kind === 'CONFIRMATION') content = <ConfirmationCard draft={entry.draft} status={entry.status} unresolved={entry.unresolved} readiness={readiness} active={entry.id === latestConfirmationId} running={submitting} mode={mode} onEdit={() => openEditor(entry.draft)} onConfirm={() => void submitDraft(entry.draft)} />;
    else content = <RunResult run={runs[entry.runId]} current={entry.runId === activeRunId} selected={selected} targetNames={targetNames} canCreate={canCreateExperiment} onSelect={(id, checked) => setSelected((items) => checked ? [...new Set([...items, id])] : items.filter((item) => item !== id))} onCreate={() => { draftForm.resetFields(); setDraftOpen(true); }} />;
    return { id: entry.id, role: entry.role, content };
  });
  if (parsing) messages.push({ id: 'parsing-current-draft', role: 'ASSISTANT', content: null, pending: true });

  const scopeContent = <Space direction="vertical" size={14} className="formula-research-full">
    <div><Typography.Text type="secondary">运行模式</Typography.Text><div><Tag color="blue">案例、统计与规则</Tag></div></div>
    {isOptimization && <div><Typography.Text type="secondary">当前基线</Typography.Text><Typography.Paragraph ellipsis={{ rows: 2 }}>{readiness?.baselines.find((item) => item.analysisRowId === draft.baselineAnalysisRowId)?.experimentNo || '尚未选择'}</Typography.Paragraph></div>}
    <div><Typography.Text type="secondary">数据就绪</Typography.Text><List size="small" dataSource={readiness?.targets ?? []} renderItem={(item) => <List.Item><span>{item.name}</span><Tag color={item.eligibleCaseCount >= 5 ? 'blue' : item.eligibleCaseCount > 0 ? 'gold' : 'default'}>{item.eligibleCaseCount} 条</Tag></List.Item>} /></div>
  </Space>;

  if (loading) return <div className="formula-research-loading"><Spin size="large" /></div>;
  return <div className="business-page formula-research-page">
    <header className="formula-research-hero">
      <Space align="start"><span className="formula-research-icon">{isOptimization ? <ExperimentOutlined /> : <AimOutlined />}</span><div><Typography.Title level={2}>{isOptimization ? 'AI实验优化' : 'AI配方预测'}</Typography.Title><Typography.Paragraph type="secondary">{isOptimization ? '先说明下一轮目标，再确认唯一基线和精确限制。' : '先用一句话说明性能目标，系统整理后由你确认再生成候选。'}</Typography.Paragraph></div></Space>
    </header>
    <AiConversationWorkspace
      conversations={[{ id: conversationId, title: isOptimization ? '本次实验优化' : '本次配方预测' }]}
      activeConversationId={conversationId}
      messages={messages}
      scopeContent={scopeContent}
      question={question}
      streaming={parsing}
      submitDisabled={submitting}
      assistantLabel="AI配方研发助手"
      pendingLabel="正在整理目标、限制和待确认项"
      scopeTitle="研究范围"
      welcomeTitle={isOptimization ? '从一个明确基线开始优化' : '描述你希望达到的应用性能'}
      welcomeDescription={isOptimization ? '例如：以当前实验为基线，降低初始翘曲，硬度至少达到2H。' : '例如：用于100μm PET光学膜，降低初始翘曲，硬度至少达到2H，给我3组保守方案。'}
      welcomeContent={<Space wrap><Button onClick={() => { setQuestion('降低初始翘曲，硬度至少达到2H'); }}>试用示例目标</Button><Button onClick={() => openEditor()}>直接填写条件</Button></Space>}
      composerTopContent={<Space wrap>{draft.goals.slice(0, 3).map((goal) => <Tag key={goal.targetKey}>{targetNames.get(goal.targetKey) || goal.targetKey}</Tag>)}{typeof draft.context.substrate === 'string' && <Tag>{draft.context.substrate}</Tag>}<Tag color={currentCheck.status === 'READY' ? 'green' : 'gold'}>{statusText[currentCheck.status]}</Tag></Space>}
      composerActions={<Button type="link" icon={<EditOutlined />} onClick={() => openEditor()}>编辑全部条件</Button>}
      onNewConversation={resetConversation}
      onSelectConversation={() => undefined}
      onQuestionChange={setQuestion}
      onSubmit={() => void sendQuestion()}
    />

    <Drawer title="编辑完整研究条件" width={720} open={drawerOpen} onClose={() => setDrawerOpen(false)} extra={<Space><Button onClick={() => setDrawerOpen(false)}>取消</Button><Button type="primary" onClick={() => void applyEditor()}>保存并生成确认卡</Button></Space>}>
      <Form form={form} layout="vertical" initialValues={draftToFormValues(draft)}>
        <Form.Item name="baselineAnalysisRowId" label={isOptimization ? '基线实验' : '参考基线（可选）'} rules={isOptimization ? [{ required: true, message: '请选择唯一的基线实验' }] : undefined}><Select allowClear={!isOptimization} showSearch optionFilterProp="label" placeholder="选择已完成实验" options={readiness?.baselines.map((item) => ({ value: item.analysisRowId, label: `${item.experimentNo} · ${item.sourceIdentity || item.title} · ${item.formulaSummary}` }))} /></Form.Item>
        <Divider orientation="left">性能目标</Divider>
        <Form.List name="goals">{(fields, { add, remove }) => <Space direction="vertical" className="formula-research-full">
          {fields.map((field, index) => <Card size="small" key={field.key} title={`目标 ${index + 1}`} extra={<Button type="link" danger onClick={() => remove(field.name)}>删除</Button>}>
            <Form.Item name={[field.name, 'targetKey']} rules={[{ required: true, message: '请选择性能目标' }]}><Select options={readiness?.targets.map((item) => ({ value: item.targetKey, label: `${item.name} · ${item.message}` }))} /></Form.Item>
            <Row gutter={12}><Col span={8}><Form.Item name={[field.name, 'mode']} rules={[{ required: true }]}><Select options={modeOptions} /></Form.Item></Col><Col span={8}><Form.Item name={[field.name, 'value']}><InputNumber className="formula-research-full" placeholder="目标值（条件目标必填）" /></Form.Item></Col><Col span={8}><Form.Item name={[field.name, 'minimumProbability']}><InputNumber className="formula-research-full" min={0.5} max={0.99} step={0.01} placeholder="必达概率（可选）" /></Form.Item></Col></Row>
            <Space><Form.Item name={[field.name, 'mandatory']} valuePropName="checked" noStyle><Checkbox>必达目标</Checkbox></Form.Item><Form.Item name={[field.name, 'weight']} noStyle><InputNumber min={1} max={3} addonBefore="重要度" /></Form.Item></Space>
          </Card>)}
          <Button block icon={<PlusOutlined />} onClick={() => add({ mode: 'MAXIMIZE', mandatory: false, weight: 1 })}>增加性能目标</Button>
        </Space>}</Form.List>
        <Divider orientation="left">应用与材料限制</Divider>
        <Form.Item name="substrate" label="基材/应用上下文"><Select allowClear showSearch options={[{ value: 'PET_100UM_OPTICAL', label: '100μm PET光学膜' }, { value: 'PET', label: 'PET' }, { value: 'PC', label: 'PC' }, { value: 'PMMA_PC', label: 'PMMA/PC复合板' }]} /></Form.Item>
        <Form.Item name="requiredMaterials" label="必选材料"><Select mode="multiple" showSearch options={materialOptions} /></Form.Item>
        <Form.Item name="forbiddenMaterials" label="禁用材料"><Select mode="multiple" showSearch options={materialOptions} /></Form.Item>
        <Form.List name="fixedMaterials">{(fields, { add, remove }) => <><Typography.Text strong>固定材料比例</Typography.Text>{fields.map((field) => <Row gutter={8} key={field.key}><Col span={13}><Form.Item name={[field.name, 'materialCode']}><Select placeholder="材料" options={materialOptions} /></Form.Item></Col><Col span={8}><Form.Item name={[field.name, 'ratioPercent']}><InputNumber className="formula-research-full" min={0} max={100} addonAfter="%" /></Form.Item></Col><Col span={3}><Button danger type="text" onClick={() => remove(field.name)}>删除</Button></Col></Row>)}<Button type="dashed" onClick={() => add()}>增加固定比例</Button></>}</Form.List>
        <Form.List name="materialRanges">{(fields, { add, remove }) => <><Typography.Paragraph strong className="formula-research-drawer-section">材料比例范围</Typography.Paragraph>{fields.map((field) => <Row gutter={8} key={field.key}><Col span={10}><Form.Item name={[field.name, 'code']}><Select placeholder="材料" options={materialOptions} /></Form.Item></Col><Col span={5}><Form.Item name={[field.name, 'minimum']}><InputNumber className="formula-research-full" placeholder="最小" /></Form.Item></Col><Col span={5}><Form.Item name={[field.name, 'maximum']}><InputNumber className="formula-research-full" placeholder="最大" /></Form.Item></Col><Col span={4}><Button danger type="text" onClick={() => remove(field.name)}>删除</Button></Col></Row>)}<Button type="dashed" onClick={() => add()}>增加材料范围</Button></>}</Form.List>
        <Form.List name="processRanges">{(fields, { add, remove }) => <><Typography.Paragraph strong className="formula-research-drawer-section">工艺参数范围</Typography.Paragraph>{fields.map((field) => <Row gutter={8} key={field.key}><Col span={10}><Form.Item name={[field.name, 'code']}><Select placeholder="工艺参数" options={processOptions} /></Form.Item></Col><Col span={5}><Form.Item name={[field.name, 'minimum']}><InputNumber className="formula-research-full" placeholder="最小" /></Form.Item></Col><Col span={5}><Form.Item name={[field.name, 'maximum']}><InputNumber className="formula-research-full" placeholder="最大" /></Form.Item></Col><Col span={4}><Button danger type="text" onClick={() => remove(field.name)}>删除</Button></Col></Row>)}<Button type="dashed" onClick={() => add()}>增加工艺范围</Button></>}</Form.List>
        <Divider orientation="left">输出与核验</Divider>
        <Row gutter={16}><Col span={12}><Form.Item name="candidateCount" label="候选数量" rules={[{ required: true }]}><InputNumber min={1} max={4} /></Form.Item></Col><Col span={12}><Form.Item name="maxMaterialCount" label="最多材料数"><InputNumber min={1} /></Form.Item></Col></Row>
        <Space><Form.Item name="requireCostCheck" valuePropName="checked"><Checkbox>要求成本核验</Checkbox></Form.Item><Form.Item name="requireInventoryCheck" valuePropName="checked"><Checkbox>要求库存核验</Checkbox></Form.Item></Space>
      </Form>
    </Drawer>

    <Modal open={draftOpen} title="创建下一轮实验草稿" okText="创建草稿" cancelText="取消" confirmLoading={drafting} onCancel={() => setDraftOpen(false)} onOk={() => void createDrafts()}>
      <Alert type="info" showIcon message={`将创建 ${selected.length} 条实验草稿`} description="主要结论保持为空，完成真实实验后再填写。AI来源通过研究运行关联记录追溯。" />
      <Form form={draftForm} layout="vertical" className="formula-research-draft-form">
        <Form.Item name="categoryId" label="实验分类" rules={[{ required: true, message: '请选择实验分类' }]}><Select options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
        <Form.Item name="ownerName" label="负责人（可选）"><Input placeholder="为空时使用当前创建人" /></Form.Item>
        <Form.Item name="plannedExperimentDate" label="计划实验日期" rules={[{ required: true, message: '请选择计划实验日期' }]}><DatePicker className="formula-research-full" /></Form.Item>
      </Form>
    </Modal>
  </div>;
}
