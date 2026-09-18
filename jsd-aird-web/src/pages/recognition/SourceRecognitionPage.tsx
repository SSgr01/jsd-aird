import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileSearchOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Input,
  InputNumber,
  Progress,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal } from '@/components/file-preview';
import {
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchShell,
  DataWorkbookCanvas,
  WorkbenchPanelHeader,
} from '@/features/data-workbench/DataWorkbench';
import type { EditorHandle, EditorSelection } from '@/features/template-workspace/types';
import type {
  DataFieldValueView,
  DataWorkbookFieldDefinition,
  DataWorkbookSnapshot,
} from '@/services/data/data-api';
import {
  sourceRecognitionApi,
  type ExperimentBoundary,
  type RecognitionCandidate,
  type RecognitionFragment,
  type RecognitionIssue,
  type RecognitionJob,
  type SourceOwner,
} from '@/services/source-recognition';

import './source-recognition.css';

type PanelTab = 'data' | 'structure' | 'mapping' | 'boundaries';

const categories = [
  { value: 'BASIC', label: '实验信息' },
  { value: 'FORMULA', label: '配方组成' },
  { value: 'PROCESS', label: '工艺条件' },
  { value: 'TEST', label: '测试结果' },
  { value: 'OTHER', label: '其他记录' },
] as const;
const categoryLabels = Object.fromEntries(categories.map((item) => [item.value, item.label]));
const statusLabel: Record<string, string> = {
  QUEUED: '等待解析',
  PARSING: '正在识别',
  WAITING_MAPPING: '待人工确认',
  COMMITTING: '正在提交',
  COMPLETED: '已完成',
  FAILED: '识别失败',
  CANCELLED: '已取消',
};

export function ExperimentRecognitionPage() {
  return <SourceRecognitionPage owner="EXPERIMENT" />;
}
export function DataRecognitionPage() {
  return <SourceRecognitionPage owner="DATA_CENTER" />;
}

function SourceRecognitionPage({ owner }: { owner: SourceOwner }) {
  const { id = '' } = useParams();
  const nav = useNavigate();
  const { message } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [job, setJob] = useState<RecognitionJob>();
  const [boundaries, setBoundaries] = useState<ExperimentBoundary[]>([]);
  const [candidates, setCandidates] = useState<RecognitionCandidate[]>([]);
  const [unrecognized, setUnrecognized] = useState<RecognitionFragment[]>([]);
  const [issues, setIssues] = useState<RecognitionIssue[]>([]);
  const [selectedCandidateId, setSelectedCandidateId] = useState<string>();
  const [selectedCell, setSelectedCell] = useState<EditorSelection>();
  const [activeTab, setActiveTab] = useState<PanelTab>('data');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [finalizing, setFinalizing] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [profileName, setProfileName] = useState('');

  const apply = useCallback((value: RecognitionJob) => {
    setJob(value);
    setBoundaries(value.boundaries || value.workspace.experiments || []);
    setCandidates(value.workspace.candidates || []);
    setUnrecognized(value.workspace.unrecognizedFragments || []);
    setIssues(value.workspace.issues || []);
    setSelectedCandidateId((current) =>
      current && value.workspace.candidates?.some((item) => item.candidateId === current)
        ? current
        : value.workspace.candidates?.[0]?.candidateId,
    );
  }, []);
  const load = useCallback(
    async (show = true) => {
      if (show) setLoading(true);
      try {
        apply(await sourceRecognitionApi.get(owner, id));
      } catch (error) {
        void message.error(error instanceof Error ? error.message : '识别任务加载失败');
      } finally {
        if (show) setLoading(false);
      }
    },
    [apply, id, message, owner],
  );
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    if (!job || !['QUEUED', 'PARSING'].includes(job.status)) return undefined;
    const timer = window.setInterval(() => void load(false), 1800);
    return () => window.clearInterval(timer);
  }, [job, load]);

  const sampleOptions = useMemo(
    () =>
      boundaries.flatMap((boundary) =>
        boundary.samples.map((sample) => ({
          value: `${boundary.experimentBoundaryId}|${sample.sampleBoundaryId}`,
          label: `${boundary.title} / ${sample.title}`,
        })),
      ),
    [boundaries],
  );
  const workbook = useMemo(
    () => (job ? recognitionWorkbook(job, candidates, boundaries) : undefined),
    [boundaries, candidates, job],
  );
  const selectedCandidate = candidates.find((item) => item.candidateId === selectedCandidateId);
  const selectedField = workbook?.fields.find((field) => field.valuePath === selectedCandidateId);
  const selectedFieldKey = recognitionFieldKey(selectedField);
  const selectedBindingId = selectedField?.bindingId;
  const parsing = Boolean(job && ['QUEUED', 'PARSING'].includes(job.status));
  const reviewCount = candidates.filter(
    (item) => item.status === 'REVIEW_REQUIRED' || item.status === 'SUGGESTED',
  ).length;

  const save = async () => {
    if (!job) return;
    setSaving(true);
    try {
      const withBoundaries = await sourceRecognitionApi.updateBoundaries(
        owner,
        job.id,
        job.recognitionRevision,
        boundaries,
      );
      const saved = await sourceRecognitionApi.updateMappings(
        owner,
        job.id,
        withBoundaries.recognitionRevision,
        {
          candidates,
          unrecognizedFragments: unrecognized,
          issues,
        },
      );
      apply(saved);
      void message.success('识别边界和字段确认已保存');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const finalize = async () => {
    if (!job) return;
    if (boundaries.some((item) => !item.confirmed)) {
      setActiveTab('boundaries');
      void message.warning('请先逐项确认实验和样本边界');
      return;
    }
    setFinalizing(true);
    try {
      const withBoundaries = await sourceRecognitionApi.updateBoundaries(
        owner,
        job.id,
        job.recognitionRevision,
        boundaries,
      );
      const saved = await sourceRecognitionApi.updateMappings(
        owner,
        job.id,
        withBoundaries.recognitionRevision,
        {
          candidates,
          unrecognizedFragments: unrecognized,
          issues,
        },
      );
      const result = await sourceRecognitionApi.finalize(owner, job.id, saved.recognitionRevision);
      void message.success(
        owner === 'EXPERIMENT'
          ? `已创建 ${result.drafts.length} 条实验草稿`
          : `已生成正式 Submission V${result.submissionRevision}${result.drafts.length ? `，并创建 ${result.drafts.length} 条实验草稿` : ''}`,
      );
      const onlyDraft = result.drafts[0];
      if (result.drafts.length === 1 && onlyDraft) nav(`/experiments/${onlyDraft.experimentId}`);
      else await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '最终确认失败');
    } finally {
      setFinalizing(false);
    }
  };

  const addExperiment = () => {
    const index = boundaries.length + 1;
    const experimentBoundaryId = `experiment-${index}`;
    const sampleBoundaryId = `${experimentBoundaryId}/sample-1`;
    setBoundaries([
      ...boundaries,
      {
        experimentBoundaryId,
        title: `实验 ${index}`,
        confirmed: false,
        sourceCoordinates: { kind: 'WHOLE_FILE' },
        sharedConditions: [],
        samples: [
          {
            sampleBoundaryId,
            logicalSampleKey: `SOURCE:${job?.id || 'draft'}:${sampleBoundaryId}`,
            sourceGroupKeys: [],
            title: '样本 1',
          },
        ],
      },
    ]);
  };
  const addSample = (boundaryIndex: number) =>
    setBoundaries((current) =>
      current.map((boundary, index) => {
        if (index !== boundaryIndex) return boundary;
        const next = boundary.samples.length + 1;
        const sampleBoundaryId = `${boundary.experimentBoundaryId}/sample-${next}`;
        return {
          ...boundary,
          confirmed: false,
          samples: [
            ...boundary.samples,
            {
              sampleBoundaryId,
              logicalSampleKey: `SOURCE:${job?.id || 'draft'}:${sampleBoundaryId}`,
              sourceGroupKeys: [],
              title: `样本 ${next}`,
            },
          ],
        };
      }),
    );
  const updateCandidate = (idValue: string, patch: Partial<RecognitionCandidate>) =>
    setCandidates((current) =>
      current.map((item) => (item.candidateId === idValue ? { ...item, ...patch } : item)),
    );
  const updateCategory = (
    candidate: RecognitionCandidate,
    category: RecognitionCandidate['category'],
  ) => {
    if (category !== 'TEST') {
      updateCandidate(candidate.candidateId, {
        category,
        observationId: undefined,
        replicateGroupKey: undefined,
        measurementIndex: undefined,
        status: 'CORRECTED',
      });
      return;
    }
    const replicateGroupKey = candidate.replicateGroupKey || `test:${candidate.fieldCode}`;
    const nextIndex =
      Math.max(
        0,
        ...candidates
          .filter(
            (item) =>
              item.candidateId !== candidate.candidateId &&
              item.experimentBoundaryId === candidate.experimentBoundaryId &&
              item.sampleBoundaryId === candidate.sampleBoundaryId &&
              item.replicateGroupKey === replicateGroupKey,
          )
          .map((item) => item.measurementIndex || 0),
      ) + 1;
    updateCandidate(candidate.candidateId, {
      category,
      observationId: candidate.observationId || candidate.candidateId,
      replicateGroupKey,
      measurementIndex: candidate.measurementIndex || nextIndex,
      status: 'CORRECTED',
    });
  };
  const assignCandidate = (candidate: RecognitionCandidate, value: string) => {
    const [experimentBoundaryId, sampleBoundaryId] = value.split('|');
    if (!experimentBoundaryId || !sampleBoundaryId) return;
    setCandidates((current) =>
      current.map((item) =>
        item.sourceGroupKey === candidate.sourceGroupKey
          ? {
              ...item,
              experimentBoundaryId,
              sampleBoundaryId,
              status: item.status === 'CONFIRMED' ? 'CORRECTED' : item.status,
            }
          : item,
      ),
    );
    setBoundaries((current) =>
      current.map((boundary) => ({
        ...boundary,
        confirmed: false,
        samples: boundary.samples.map((sample) => ({
          ...sample,
          sourceGroupKeys: sample.sourceGroupKeys
            .filter((group) => group !== candidate.sourceGroupKey)
            .concat(
              boundary.experimentBoundaryId === experimentBoundaryId &&
                sample.sampleBoundaryId === sampleBoundaryId
                ? [candidate.sourceGroupKey]
                : [],
            ),
        })),
      })),
    );
  };
  const mapFragment = (fragment: RecognitionFragment) => {
    const firstBoundary = boundaries[0];
    const first = firstBoundary?.samples[0];
    if (!firstBoundary || !first) return;
    const sourceGroupKey = `fragment:${fragment.fragmentId}`;
    setCandidates((current) => [
      ...current,
      {
        candidateId: fragment.fragmentId,
        category: 'OTHER',
        fieldCode: `other_${current.length + 1}`,
        rawText: fragment.rawText,
        parsedValue: fragment.rawText,
        sourceCoordinate: fragment.sourceCoordinate,
        confidence: fragment.confidence,
        parserVersion: job?.parserVersion || 'manual',
        status: 'REVIEW_REQUIRED',
        experimentBoundaryId: firstBoundary.experimentBoundaryId,
        sampleBoundaryId: first.sampleBoundaryId,
        sourceGroupKey,
      },
    ]);
    setBoundaries((current) =>
      current.map((boundary, boundaryIndex) =>
        boundaryIndex !== 0
          ? boundary
          : {
              ...boundary,
              confirmed: false,
              samples: boundary.samples.map((sample, sampleIndex) =>
                sampleIndex !== 0
                  ? sample
                  : {
                      ...sample,
                      sourceGroupKeys: [...new Set([...sample.sourceGroupKeys, sourceGroupKey])],
                    },
              ),
            },
      ),
    );
    setUnrecognized((current) => current.filter((item) => item.fragmentId !== fragment.fragmentId));
    setSelectedCandidateId(fragment.fragmentId);
    setActiveTab('mapping');
  };
  const selectCandidate = (candidate?: RecognitionCandidate, targetTab?: PanelTab) => {
    if (!candidate) return;
    setSelectedCandidateId(candidate.candidateId);
    if (targetTab) setActiveTab(targetTab);
    const sheetId = textValue(candidate.sourceCoordinate.sheetId);
    const address = textValue(candidate.sourceCoordinate.address);
    if (sheetId && address) editorRef.current?.focusRange?.(sheetId, address);
  };
  const handleSelection = (selection: EditorSelection) => {
    setSelectedCell(selection);
    const candidate = candidates.find(
      (item) =>
        textValue(item.sourceCoordinate.sheetId) === selection.sheetId &&
        textValue(item.sourceCoordinate.address).toUpperCase() === selection.address.toUpperCase(),
    );
    if (candidate) setSelectedCandidateId(candidate.candidateId);
  };

  if (loading)
    return (
      <div className="recognition-state">
        <Spin size="large" />
      </div>
    );
  if (!job) return <Empty description="识别任务不存在" />;

  const meta = (
    <Space wrap size={8}>
      <Tag color={owner === 'EXPERIMENT' ? 'purple' : 'blue'}>
        {owner === 'EXPERIMENT' ? '实验本来源' : '数据中心来源'}
      </Tag>
      <Tag>{job.recognitionMode === 'FREEFORM' ? '自由识别' : '模板辅助'}</Tag>
      <Tag
        color={
          job.status === 'COMPLETED' ? 'success' : job.status === 'FAILED' ? 'error' : 'processing'
        }
      >
        {statusLabel[job.status] || job.status}
      </Tag>
      <Typography.Text type="secondary">
        识别版本 {job.recognitionRevision} · {job.parserVersion || '等待解析器'}
      </Typography.Text>
    </Space>
  );
  const notice = parsing ? (
    <Alert
      banner
      showIcon
      type="info"
      message="系统正在读取原文件并识别实验内容，完成后自动更新。"
      description={<Progress percent={job.progress} size="small" status="active" />}
    />
  ) : job.status === 'FAILED' ? (
    <Alert
      banner
      showIcon
      type="error"
      message="识别失败"
      description={job.errorMessage}
      action={
        <Button
          icon={<ReloadOutlined />}
          onClick={() => void sourceRecognitionApi.retry(owner, job.id).then(apply)}
        >
          重新识别
        </Button>
      }
    />
  ) : (
    <Alert
      banner
      showIcon
      type={reviewCount || unrecognized.length ? 'warning' : 'success'}
      message={
        reviewCount || unrecognized.length
          ? `文件已读取，还有 ${reviewCount + unrecognized.length} 项内容需要确认`
          : '文件已读取，请确认字段对应关系和实验边界。'
      }
      description="左侧保留原始文件位置；右侧按字段数据、字段结构、字段映射和实验边界逐项确认。"
    />
  );

  return (
    <DataWorkbenchShell
      breadcrumb={`${owner === 'EXPERIMENT' ? '实验记录本' : '数据中心'} / 来源识别`}
      title={job.sourceFileName}
      canvasLabel={isWorkbook(job) ? '源数据工作区' : '原始文档工作区'}
      leading={
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => nav(owner === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload')}
        >
          返回上传
        </Button>
      }
      meta={meta}
      actions={
        <>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button icon={<FileSearchOutlined />} onClick={() => setPreviewOpen(true)}>
            预览原文件
          </Button>
          <Button
            icon={<SaveOutlined />}
            loading={saving}
            disabled={parsing || job.status === 'FAILED'}
            onClick={() => void save()}
          >
            保存工作区
          </Button>
          <Button
            type="primary"
            icon={<CheckCircleOutlined />}
            loading={finalizing}
            disabled={parsing || job.status === 'FAILED'}
            onClick={() => void finalize()}
          >
            {owner === 'EXPERIMENT' ? '确认并创建实验' : '确认并提交'}
          </Button>
        </>
      }
      notice={notice}
      canvas={
        isWorkbook(job) ? (
          <DataWorkbookCanvas
            ref={editorRef}
            workbook={workbook}
            loading={parsing}
            editorLabel="自由识别源文件"
            onSelectionChange={handleSelection}
          />
        ) : (
          <SourceDocumentCanvas job={job} />
        )
      }
      panel={
        <>
          <Tabs
            className="data-workbench-tabs"
            activeKey={activeTab}
            onChange={(value) => setActiveTab(value as PanelTab)}
            items={[
              { key: 'data', label: '字段数据' },
              { key: 'structure', label: '字段结构' },
              { key: 'mapping', label: reviewCount ? `字段映射 ${reviewCount}` : '字段映射' },
              { key: 'boundaries', label: '实验边界' },
            ]}
          />
          {activeTab === 'data' ? (
            <DataFieldDataBrowser
              workbook={workbook}
              selectedFieldKey={selectedFieldKey}
              selectedCell={selectedCell}
              emptyDescription="识别完成后显示实际字段"
              headerExtra={
                reviewCount ? (
                  <Button size="small" type="primary" onClick={() => setActiveTab('mapping')}>
                    去确认字段映射
                  </Button>
                ) : undefined
              }
              onSelectField={(field) =>
                selectCandidate(candidates.find((item) => item.candidateId === field.valuePath))
              }
              renderFieldExtra={(field) => (
                <Button
                  size="small"
                  onClick={() =>
                    selectCandidate(
                      candidates.find((item) => item.candidateId === field.valuePath),
                      'mapping',
                    )
                  }
                >
                  定位并编辑
                </Button>
              )}
              renderFieldMeta={(field) => (
                <RecognitionFieldMeta field={field} candidates={candidates} />
              )}
            />
          ) : null}
          {activeTab === 'structure' ? (
            <DataFieldStructureBrowser
              workbook={workbook}
              selectedBindingId={selectedBindingId}
              description="结构来自本次自由识别结果，每个标准字段只显示一次"
              onSelectField={(definition) =>
                selectCandidate(
                  candidates.find((item) => recognitionBindingId(item) === definition.bindingId),
                  'data',
                )
              }
            />
          ) : null}
          {activeTab === 'mapping' ? (
            <RecognitionMappingPanel
              candidates={candidates}
              selectedCandidate={selectedCandidate}
              sampleOptions={sampleOptions}
              unrecognized={unrecognized}
              onSelect={selectCandidate}
              onUpdate={updateCandidate}
              onUpdateCategory={updateCategory}
              onAssign={assignCandidate}
              onMapFragment={mapFragment}
              onFocus={(candidate) => selectCandidate(candidate)}
            />
          ) : null}
          {activeTab === 'boundaries' ? (
            <RecognitionBoundaryPanel
              boundaries={boundaries}
              candidates={candidates}
              profileName={profileName}
              onProfileName={setProfileName}
              onChange={setBoundaries}
              onAddExperiment={addExperiment}
              onAddSample={addSample}
              onSaveProfile={() =>
                void sourceRecognitionApi
                  .saveProfile(owner, job.id, profileName, job.recognitionRevision)
                  .then(() => message.success('识别配置已保存'))
              }
            />
          ) : null}
        </>
      }
      footer={issues.length ? <RecognitionIssues issues={issues} /> : undefined}
    >
      <FilePreviewModal
        open={previewOpen}
        onClose={() => setPreviewOpen(false)}
        file={{
          fileName: job.sourceFileName,
          load: () => sourceRecognitionApi.sourceBlob(job.sourceFileId),
        }}
      />
    </DataWorkbenchShell>
  );
}

function RecognitionFieldMeta({
  field,
  candidates,
}: {
  field: DataFieldValueView;
  candidates: RecognitionCandidate[];
}) {
  const candidate = candidates.find((item) => item.candidateId === field.valuePath);
  if (!candidate) return null;
  return (
    <>
      <Tag>{categoryLabels[candidate.category]}</Tag>
      <Tag color={candidate.confidence >= 0.85 ? 'green' : 'orange'}>
        置信度 {Math.round(candidate.confidence * 100)}%
      </Tag>
      <Tag>来源 {locator(candidate.sourceCoordinate)}</Tag>
      {candidate.replicateGroupKey ? (
        <Tag color="blue">重复 {candidate.measurementIndex}</Tag>
      ) : null}
    </>
  );
}

function RecognitionMappingPanel({
  candidates,
  selectedCandidate,
  sampleOptions,
  unrecognized,
  onSelect,
  onUpdate,
  onUpdateCategory,
  onAssign,
  onMapFragment,
  onFocus,
}: {
  candidates: RecognitionCandidate[];
  selectedCandidate?: RecognitionCandidate;
  sampleOptions: Array<{ value: string; label: string }>;
  unrecognized: RecognitionFragment[];
  onSelect: (candidate?: RecognitionCandidate) => void;
  onUpdate: (id: string, patch: Partial<RecognitionCandidate>) => void;
  onUpdateCategory: (
    candidate: RecognitionCandidate,
    category: RecognitionCandidate['category'],
  ) => void;
  onAssign: (candidate: RecognitionCandidate, value: string) => void;
  onMapFragment: (fragment: RecognitionFragment) => void;
  onFocus: (candidate: RecognitionCandidate) => void;
}) {
  const pending = candidates.filter(
    (item) => item.status === 'REVIEW_REQUIRED' || item.status === 'SUGGESTED',
  );
  const visible = pending.length ? pending : candidates;
  const active =
    selectedCandidate && visible.some((item) => item.candidateId === selectedCandidate.candidateId)
      ? selectedCandidate
      : visible[0];
  return (
    <div className="data-panel-body recognition-mapping-panel">
      <WorkbenchPanelHeader
        title="字段对应关系"
        description="确认原文件内容对应的实验字段；选择一项会同步定位左侧来源位置"
      />
      <section className="data-panel-section">
        <Alert
          type={pending.length ? 'warning' : 'success'}
          showIcon
          message={
            pending.length
              ? `还有 ${pending.length} 个字段需要确认`
              : `已确认 ${candidates.length} 个字段`
          }
        />
        <div className="recognition-mapping-list" aria-label="待确认字段">
          {visible.map((candidate) => (
            <button
              type="button"
              key={candidate.candidateId}
              className={candidate.candidateId === active?.candidateId ? 'is-active' : ''}
              onClick={() => onSelect(candidate)}
            >
              <span>
                <strong>{candidateLabel(candidate)}</strong>
                <small>
                  {locator(candidate.sourceCoordinate)} · {categoryLabels[candidate.category]}
                </small>
              </span>
              <Tag
                color={
                  candidate.status === 'REVIEW_REQUIRED' || candidate.status === 'SUGGESTED'
                    ? 'orange'
                    : candidate.status === 'IGNORED'
                      ? 'default'
                      : 'green'
                }
              >
                {recognitionStatusLabel(candidate.status)}
              </Tag>
            </button>
          ))}
        </div>
      </section>
      {active ? (
        <>
          <WorkbenchPanelHeader
            title="当前字段"
            description={`原文：${active.rawText}`}
            extra={
              <Button size="small" onClick={() => onFocus(active)}>
                定位到来源
              </Button>
            }
          />
          <section className="data-panel-section recognition-field-editor">
            <label>
              内容归类
              <Select
                value={active.category}
                options={[...categories]}
                onChange={(value) => onUpdateCategory(active, value)}
              />
            </label>
            <label>
              标准字段
              <Input
                value={active.fieldCode}
                onChange={(event) =>
                  onUpdate(active.candidateId, {
                    fieldCode: event.target.value,
                    status: 'CORRECTED',
                  })
                }
              />
            </label>
            <label>
              解析／修正值
              <Input
                value={displayValue(active.correctedValue ?? active.parsedValue)}
                onChange={(event) =>
                  onUpdate(active.candidateId, {
                    correctedValue: event.target.value,
                    status: 'CORRECTED',
                  })
                }
              />
            </label>
            <label>
              标准单位
              <Input
                value={active.standardUnit}
                placeholder="无单位"
                onChange={(event) =>
                  onUpdate(active.candidateId, {
                    standardUnit: event.target.value,
                    status: 'CORRECTED',
                  })
                }
              />
            </label>
            <label className="recognition-editor-wide">
              样本归属
              <Select
                value={`${active.experimentBoundaryId}|${active.sampleBoundaryId}`}
                options={sampleOptions}
                onChange={(value) => onAssign(active, value)}
              />
            </label>
            {active.category === 'TEST' ? (
              <>
                <label>
                  重复测量组
                  <Input
                    value={active.replicateGroupKey}
                    placeholder="例如 gloss60"
                    onChange={(event) =>
                      onUpdate(active.candidateId, {
                        replicateGroupKey: event.target.value,
                        status: 'CORRECTED',
                      })
                    }
                  />
                </label>
                <label>
                  测量序号
                  <InputNumber
                    min={1}
                    value={active.measurementIndex}
                    onChange={(value) =>
                      onUpdate(active.candidateId, {
                        measurementIndex: value || 1,
                        status: 'CORRECTED',
                      })
                    }
                  />
                </label>
              </>
            ) : null}
            <label className="recognition-editor-wide">
              确认决定
              <Select
                value={active.status}
                options={[
                  { value: 'SUGGESTED', label: '待确认（自动建议）' },
                  { value: 'CONFIRMED', label: '确认采用' },
                  { value: 'CORRECTED', label: '修正后采用' },
                  { value: 'IGNORED', label: '保留原文但不映射' },
                  { value: 'REVIEW_REQUIRED', label: '暂不确认' },
                ]}
                onChange={(status) => onUpdate(active.candidateId, { status })}
              />
            </label>
            <div className="recognition-source-detail recognition-editor-wide">
              <span>
                来源位置 <strong>{locator(active.sourceCoordinate)}</strong>
              </span>
              <span>
                物理来源组 <Typography.Text code>{active.sourceGroupKey}</Typography.Text>
              </span>
              <span>
                识别置信度 <strong>{Math.round(active.confidence * 100)}%</strong>
              </span>
            </div>
          </section>
        </>
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可映射字段" />
      )}
      <WorkbenchPanelHeader
        title="未标准化内容"
        description="原文会完整保留，可在确认含义后转为字段"
      />
      <section className="data-panel-section recognition-fragment-list">
        {unrecognized.length ? (
          unrecognized.map((fragment) => (
            <article key={fragment.fragmentId}>
              <span>
                <strong>{fragment.rawText}</strong>
                <small>{locator(fragment.sourceCoordinate)}</small>
              </span>
              <Button size="small" onClick={() => onMapFragment(fragment)}>
                转为字段
              </Button>
            </article>
          ))
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有待归类片段" />
        )}
      </section>
    </div>
  );
}

function RecognitionBoundaryPanel({
  boundaries,
  candidates,
  profileName,
  onProfileName,
  onChange,
  onAddExperiment,
  onAddSample,
  onSaveProfile,
}: {
  boundaries: ExperimentBoundary[];
  candidates: RecognitionCandidate[];
  profileName: string;
  onProfileName: (value: string) => void;
  onChange: (value: ExperimentBoundary[]) => void;
  onAddExperiment: () => void;
  onAddSample: (boundaryIndex: number) => void;
  onSaveProfile: () => void;
}) {
  const updateBoundary = (index: number, patch: Partial<ExperimentBoundary>) =>
    onChange(
      boundaries.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)),
    );
  const updateSample = (
    boundaryIndex: number,
    sampleIndex: number,
    patch: Partial<ExperimentBoundary['samples'][number]>,
  ) =>
    onChange(
      boundaries.map((boundary, index) =>
        index === boundaryIndex
          ? {
              ...boundary,
              confirmed: false,
              samples: boundary.samples.map((sample, itemIndex) =>
                itemIndex === sampleIndex ? { ...sample, ...patch } : sample,
              ),
            }
          : boundary,
      ),
    );
  return (
    <div className="data-panel-body recognition-boundary-panel">
      <WorkbenchPanelHeader
        title="实验与样本边界"
        description="来源组用于定位原文件；逻辑样本由用户确认，一个样本可以包含多个来源组"
        extra={
          <Button size="small" icon={<PlusOutlined />} onClick={onAddExperiment}>
            增加实验
          </Button>
        }
      />
      <section className="data-panel-section">
        {boundaries.map((boundary, boundaryIndex) => (
          <article className="recognition-boundary-card" key={boundary.experimentBoundaryId}>
            <header>
              <Checkbox
                checked={boundary.confirmed}
                onChange={(event) =>
                  updateBoundary(boundaryIndex, { confirmed: event.target.checked })
                }
              >
                边界已人工确认
              </Checkbox>
              <Tag>{boundary.samples.length} 个样本</Tag>
            </header>
            <Input
              value={boundary.title}
              addonBefore="实验"
              onChange={(event) =>
                updateBoundary(boundaryIndex, { confirmed: false, title: event.target.value })
              }
            />
            <div className="recognition-sample-list">
              {boundary.samples.map((sample, sampleIndex) => {
                const sourceCount = candidates.filter(
                  (item) =>
                    item.experimentBoundaryId === boundary.experimentBoundaryId &&
                    item.sampleBoundaryId === sample.sampleBoundaryId,
                ).length;
                return (
                  <section key={sample.sampleBoundaryId}>
                    <div>
                      <strong>{sample.title}</strong>
                      <Tag color="blue">{sourceCount} 个识别值</Tag>
                    </div>
                    <Input
                      value={sample.title}
                      addonBefore="样本名称"
                      onChange={(event) =>
                        updateSample(boundaryIndex, sampleIndex, { title: event.target.value })
                      }
                    />
                    <Input
                      value={sample.logicalSampleKey}
                      addonBefore="逻辑样本 Key"
                      onChange={(event) =>
                        updateSample(boundaryIndex, sampleIndex, {
                          logicalSampleKey: event.target.value,
                        })
                      }
                    />
                    <div className="recognition-source-groups">
                      <Typography.Text type="secondary">物理来源组</Typography.Text>
                      <div>
                        {sample.sourceGroupKeys.length ? (
                          sample.sourceGroupKeys.map((group) => <Tag key={group}>{group}</Tag>)
                        ) : (
                          <Typography.Text type="secondary">尚未分配</Typography.Text>
                        )}
                      </div>
                    </div>
                  </section>
                );
              })}
              <Button block size="small" onClick={() => onAddSample(boundaryIndex)}>
                增加逻辑样本
              </Button>
            </div>
          </article>
        ))}
      </section>
      <WorkbenchPanelHeader
        title="复用识别规则"
        description="保存本次确认方式供同类文件自动建议，不进入模板中心"
      />
      <section className="data-panel-section">
        <Input
          placeholder="例如：客户A手写UV实验记录"
          value={profileName}
          onChange={(event) => onProfileName(event.target.value)}
        />
        <Button disabled={!profileName.trim()} onClick={onSaveProfile}>
          保存为识别配置
        </Button>
      </section>
    </div>
  );
}

function RecognitionIssues({ issues }: { issues: RecognitionIssue[] }) {
  return (
    <div className="recognition-issues">
      <Typography.Text strong>识别问题</Typography.Text>
      {issues.map((issue) => (
        <Alert
          key={issue.issueId}
          type={
            issue.severity === 'BLOCKER'
              ? 'error'
              : issue.severity === 'WARNING'
                ? 'warning'
                : 'info'
          }
          showIcon
          message={issue.message}
        />
      ))}
    </div>
  );
}

interface SnapshotDocument {
  body?: { dataStream?: string };
  sheets?: Record<string, { name?: string }>;
  sheetOrder?: string[];
}
interface RecognitionStructure {
  textBlocks?: Array<{ content?: string; pageNo?: number }>;
}

function SourceDocumentCanvas({ job }: { job: RecognitionJob }) {
  const snapshot = job.workspace.documentSnapshot as SnapshotDocument | undefined;
  const bodyText = snapshot?.body?.dataStream;
  const blocks = (job.workspace.structureSummary as RecognitionStructure | undefined)?.textBlocks;
  if (bodyText)
    return (
      <pre className="recognition-document-text">
        {bodyText.replace(/\\r\\n/g, '\n') || '文档中没有可显示文本'}
      </pre>
    );
  if (blocks?.length)
    return (
      <div className="recognition-document-text">
        {blocks.map((block, index) => (
          <p key={index}>
            <Tag>第 {block.pageNo || 1} 页</Tag>
            {block.content}
          </p>
        ))}
      </div>
    );
  return (
    <div className="recognition-document-empty">
      <Empty description="原文件已保留；当前解析器未生成内嵌预览，请使用“预览原文件”" />
    </div>
  );
}

function recognitionWorkbook(
  job: RecognitionJob,
  candidates: RecognitionCandidate[],
  boundaries: ExperimentBoundary[],
): DataWorkbookSnapshot {
  const records = boundaries.flatMap((boundary) =>
    boundary.samples.map((sample, sequence) => ({
      recordId: sample.sampleBoundaryId,
      regionId: 'recognition-fields',
      label: `${boundary.title} / ${sample.title}`,
      sequence,
      excluded: false,
    })),
  );
  const definitionMap = new Map<string, DataWorkbookFieldDefinition>();
  const fields: DataFieldValueView[] = candidates.map((candidate) => {
    const bindingId = recognitionBindingId(candidate);
    if (!definitionMap.has(bindingId))
      definitionMap.set(bindingId, {
        componentId: 'recognition-fields',
        bindingId,
        fieldCode: candidate.fieldCode,
        displayName: candidateLabel(candidate),
        labelPath: candidate.labelPath,
        mappingKind: candidateMappingKind(candidate),
        repeatAxis: candidateRepeatAxis(candidate),
        valueType: inferredValueType(candidate),
        unit: candidate.standardUnit,
        required: false,
        identity: candidate.fieldCode === 'sampleIdentity',
        groupPath: categoryLabels[candidate.category],
        sheetId: textValue(candidate.sourceCoordinate.sheetId) || undefined,
        sourceRange: textValue(candidate.sourceCoordinate.address) || undefined,
      });
    return {
      recordId: candidate.sampleBoundaryId,
      recordGroupId: candidate.sampleBoundaryId,
      fieldCode: candidate.fieldCode,
      fieldName: candidateLabel(candidate),
      labelPath: candidate.labelPath,
      bindingId,
      valuePath: candidate.candidateId,
      valueSource: candidate.status === 'CORRECTED' ? 'MANUAL' : 'RECOGNITION',
      valueStatus:
        candidate.status === 'REVIEW_REQUIRED' || candidate.status === 'SUGGESTED'
          ? 'STAGED'
          : 'VALID',
      valueType: inferredValueType(candidate),
      unit: candidate.standardUnit,
      required: false,
      identity: candidate.fieldCode === 'sampleIdentity',
      trainingEligible: candidate.status !== 'IGNORED',
      ragEligible: candidate.status !== 'IGNORED',
      sheetId: textValue(candidate.sourceCoordinate.sheetId) || undefined,
      address: textValue(candidate.sourceCoordinate.address) || undefined,
      rawValue: candidate.rawText,
      normalizedValue: candidate.parsedValue,
      correctedValue: candidate.correctedValue,
      effectiveValue: candidate.correctedValue ?? candidate.parsedValue,
      editable: false,
      excluded: candidate.status === 'IGNORED',
      componentId: 'recognition-fields',
      mappingKind: candidateMappingKind(candidate),
      repeatAxis: candidateRepeatAxis(candidate),
      groupPath: categoryLabels[candidate.category],
      dimensions: {
        experimentBoundaryId: candidate.experimentBoundaryId,
        logicalSampleBoundaryId: candidate.sampleBoundaryId,
        sourceGroupKey: candidate.sourceGroupKey,
        observationId: candidate.observationId,
        replicateGroupKey: candidate.replicateGroupKey,
        measurementIndex: candidate.measurementIndex,
      },
    };
  });
  const snapshot = job.workspace.documentSnapshot || {};
  const snapshotDocument = snapshot as SnapshotDocument;
  const order = snapshotDocument.sheetOrder || Object.keys(snapshotDocument.sheets || {});
  return {
    fileName: job.sourceFileName,
    sourceFileHash: job.sourceSha256,
    format: job.sourceFormat,
    snapshot,
    sheets: order.map((sheetId, index) => ({
      sheetId,
      sheetName: snapshotDocument.sheets?.[sheetId]?.name || sheetId,
      sheetOrder: index,
      selected: true,
      confirmationStatus: 'RECOGNIZED',
    })),
    editable: false,
    regions: [
      {
        regionId: 'recognition-fields',
        name: '自由识别结果',
        structureType: 'FREEFORM',
        recordAxis: 'LOGICAL_SAMPLE',
        fieldCount: definitionMap.size,
        recordCount: records.length,
        fieldGroups: categories.map((category) => ({
          groupId: category.value,
          name: category.label,
          fieldCount: [...definitionMap.values()].filter(
            (item) => item.groupPath === category.label,
          ).length,
        })),
      },
    ],
    fieldDefinitions: [...definitionMap.values()],
    records,
    fields,
  };
}

function recognitionBindingId(candidate: RecognitionCandidate) {
  return `${candidate.category}:${candidate.fieldCode}`;
}
function candidateMappingKind(candidate: RecognitionCandidate) {
  return candidate.category === 'TEST' || candidate.category === 'FORMULA' ? 'REPEAT' : 'SCALAR';
}
function candidateRepeatAxis(candidate: RecognitionCandidate) {
  if (candidate.category === 'TEST') return 'OBSERVATION';
  if (candidate.category === 'FORMULA') return 'MATERIAL';
  return undefined;
}
function recognitionFieldKey(field?: DataFieldValueView) {
  return field
    ? [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|')
    : undefined;
}
function candidateLabel(candidate: RecognitionCandidate) {
  const label = candidate.labelPath
    ?.split(/\s*(?:>|\/|›)\s*/)
    .filter(Boolean)
    .at(-1);
  return label || fieldName(candidate.fieldCode);
}
function fieldName(code: string) {
  const names: Record<string, string> = {
    experimentTitle: '实验名称',
    experimentDate: '实验日期',
    ownerName: '实验人员',
    objective: '实验目的',
    sampleIdentity: '样本标识',
    material: '材料',
    ratio: '比例',
    processStep: '工艺步骤',
    gloss60: '60°光泽',
    hardness: '硬度',
    uvSurfaceDry: 'UV表干',
    adhesion: '附着力',
    viscosity: '黏度',
    solidContent: '固含量',
    testResult: '测试结果',
  };
  return names[code] || code;
}
function inferredValueType(candidate: RecognitionCandidate) {
  if (typeof (candidate.correctedValue ?? candidate.parsedValue) === 'number') return 'NUMBER';
  return 'TEXT';
}
function recognitionStatusLabel(status: RecognitionCandidate['status']) {
  const labels: Record<RecognitionCandidate['status'], string> = {
    SUGGESTED: '待确认',
    REVIEW_REQUIRED: '待确认',
    CONFIRMED: '已确认',
    CORRECTED: '已修正',
    IGNORED: '已忽略',
  };
  return labels[status];
}
function isWorkbook(job: RecognitionJob) {
  return ['XLS', 'XLSX', 'CSV'].includes(job.sourceFormat.toUpperCase());
}
function locator(value: Record<string, unknown>) {
  const sheetName = textValue(value.sheetName);
  const address = textValue(value.address);
  if (sheetName && address) return `${sheetName}!${address}`;
  if (address) return address;
  const pageNo = textValue(value.pageNo);
  if (pageNo) return `第 ${pageNo} 页`;
  const order = textValue(value.order);
  if (order) return `块 ${order}`;
  return textValue(value.kind) || '原文件';
}
function textValue(value: unknown) {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}
function displayValue(value: unknown) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return '';
  }
}
