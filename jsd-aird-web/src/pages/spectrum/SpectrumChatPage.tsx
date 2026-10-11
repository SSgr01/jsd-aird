import {
  ArrowLeftOutlined,
  EyeOutlined,
  FileImageOutlined,
  LineChartOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Collapse,
  Empty,
  Input,
  Modal,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import {
  AiConversationWorkspace,
  type ConversationItem,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace';
import { FilePreviewModal, type FilePreviewDescriptor } from '@/components/file-preview';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import { StreamingMarkdownContent } from '@/components/markdown/StreamingMarkdownContent';
import {
  spectrumApi,
  type SpectrumCategory,
  type SpectrumChart,
  type SpectrumMessage,
  type SpectrumMessageReplacement,
  type SpectrumResult,
  type SpectrumResultPresentation,
  type SpectrumSession,
} from '@/services/spectrum';
import {
  getSpectrumAnalysisStreamMetadata,
  getSpectrumAnalysisStreams,
  isTerminalSpectrumAnalysisStatus,
  startSpectrumAnalysisStream,
  subscribeSpectrumAnalysisStream,
  subscribeSpectrumAnalysisStreams,
} from '@/services/spectrum/spectrum-analysis-stream';
import {
  isRepeatedSpectrumText,
  remainingSpectrumDetails,
  resolveSpectrumPresentation,
} from './spectrum-result-presentation';

const findingLabels: Record<SpectrumResultPresentation['primaryIntent'], string> = {
  ATTRIBUTION: '归因依据',
  COMPARISON: '主要差异',
  FEATURE_INTERPRETATION: '特征解释',
  VALIDATION: '判断依据',
  OVERVIEW: '主要观察',
};
const resultFieldLabels: Record<string, string> = {
  evidence: '依据',
  confidence: '置信度',
  uncertainty: '不确定性',
  interpretation: '可能解释',
  testConditionLimitations: '测试条件限制',
  peak: '特征峰',
  peakPosition: '峰位',
  reference: '参考证据',
  referenceChart: '参考图谱',
  candidateComponent: '候选成分',
  functionalGroup: '可能官能团',
  overlapReason: '叠加原因',
  feature: '图谱特征',
  region: '区域',
  description: '说明',
  observation: '观察结果',
  comparison: '差异说明',
  possibleInterpretation: '可能解释',
  reason: '判断依据',
  experiment: '验证实验',
  purpose: '验证目的',
  samplePeak: '样品峰',
  referencePeak: '参考峰',
  deviation: '偏差',
  supportLevel: '支持度',
  possibleOverlap: '可能叠加',
};
const structuredValueLabels: Record<string, string> = {
  LOW: '低',
  MEDIUM: '中等',
  HIGH: '高',
  SUFFICIENT: '证据较充分',
  PARTIALLY_SUFFICIENT: '证据部分充分',
  INSUFFICIENT: '证据不足',
  INSUFFICIENT_FOR_MAPPING: '不足以建立峰位映射',
  INSUFFICIENT_FOR_INTERPRETATION: '不足以解释',
  SUFFICIENT_FOR_POSSIBLE_INTERPRETATION_ONLY: '仅支持候选解释',
};
const boundaryLabels: Record<string, string> = {
  POSSIBLE_INTERPRETATIONS_ONLY_NO_DEFINITIVE_FORMULA:
    '仅支持候选解释，不能据此确定配方或唯一化学归因。',
};
const hiddenResultFields = new Set([
  'evidenceId',
  'evidenceIds',
  'referenceChartId',
  'referenceChartIds',
]);
const itemTitleFields = [
  'feature',
  'region',
  'peak',
  'peakPosition',
  'samplePeak',
  'candidateComponent',
  'functionalGroup',
  'title',
  'name',
];

function printValue(value: unknown) {
  if (value === undefined || value === null || value === '') return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUnknownArray(value: unknown): value is unknown[] {
  return Array.isArray(value);
}

function parseStructuredObject(value: unknown): Record<string, unknown> | undefined {
  if (isRecord(value)) return value;
  if (typeof value !== 'string' || !value.trim().startsWith('{')) return undefined;
  try {
    const parsed: unknown = JSON.parse(value);
    return isRecord(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

function fieldLabel(key: string) {
  return (
    resultFieldLabels[key] ||
    key.replace(/([A-Z])/g, ' $1').replace(/^./, (value) => value.toUpperCase())
  );
}

function visibleObjectEntries(value: Record<string, unknown>, excluded: string[] = []) {
  return Object.entries(value).filter(
    ([key]) => !hiddenResultFields.has(key) && !excluded.includes(key),
  );
}

function StructuredValue({ value }: { value: unknown }) {
  if (typeof value === 'string') {
    const structured = parseStructuredObject(value);
    if (structured) return <StructuredValue value={structured} />;
    const localized = structuredValueLabels[value.trim().toUpperCase()];
    if (localized) return <MarkdownContent value={localized} />;
  }
  if (typeof value === 'boolean') return <MarkdownContent value={value ? '是' : '否'} />;
  if (isUnknownArray(value)) {
    return (
      <ul className="spectrum-result-nested-list">
        {value.map((item, index) => (
          <li key={`nested-${index}`}>
            <StructuredValue value={item} />
          </li>
        ))}
      </ul>
    );
  }
  if (isRecord(value)) {
    return (
      <div className="spectrum-result-nested">
        {visibleObjectEntries(value).map(([key, item]) => (
          <div key={key} className="spectrum-result-field spectrum-result-field-nested">
            <Typography.Text type="secondary">{fieldLabel(key)}</Typography.Text>
            <StructuredValue value={item} />
          </div>
        ))}
      </div>
    );
  }
  return <MarkdownContent value={printValue(value)} />;
}

function StructuredResultItem({ item }: { item: unknown }) {
  const object = parseStructuredObject(item);
  if (!object)
    return (
      <div className="spectrum-result-item-card">
        <StructuredValue value={item} />
      </div>
    );
  const titleKey = itemTitleFields.find((key) => {
    const value = object[key];
    return (typeof value === 'string' || typeof value === 'number') && String(value).trim();
  });
  const title = titleKey ? String(object[titleKey]) : '';
  const entries = visibleObjectEntries(object, titleKey ? [titleKey] : []);
  return (
    <article className="spectrum-result-item-card">
      {title ? <div className="spectrum-result-item-title">{title}</div> : null}
      {entries.length ? (
        <div className="spectrum-result-fields">
          {entries.map(([key, value]) => (
            <div key={key} className="spectrum-result-field">
              <Typography.Text type="secondary">{fieldLabel(key)}</Typography.Text>
              <StructuredValue value={value} />
            </div>
          ))}
        </div>
      ) : null}
    </article>
  );
}

function ResultSection({ title, value }: { title: string; value: unknown }) {
  if (!isUnknownArray(value) || !value.length) return null;
  return (
    <div className="spectrum-result-section">
      <Typography.Text strong>{title}</Typography.Text>
      <div className="spectrum-result-list">
        {value.map((item, index) => (
          <StructuredResultItem key={`result-item-${index}`} item={item} />
        ))}
      </div>
    </div>
  );
}

function PeakMappingTable({ value }: { value: unknown }) {
  if (!isUnknownArray(value)) return null;
  const rows = value.filter(isRecord);
  if (!rows.length) return null;
  const cell = (row: Record<string, unknown>, key: string) => <StructuredValue value={row[key]} />;
  return (
    <section className="spectrum-result-mapping">
      <div className="spectrum-result-section-heading">
        <div>
          <Typography.Text strong>峰位对应关系</Typography.Text>
          <Typography.Text type="secondary">仅展示通过证据校验的候选对应</Typography.Text>
        </div>
        <Tag color="blue">{rows.length} 条</Tag>
      </div>
      <div className="spectrum-result-table-wrap">
        <table className="spectrum-result-table">
          <thead>
            <tr>
              <th>样品峰</th>
              <th>参考峰</th>
              <th>偏差</th>
              <th>对应支持</th>
              <th>可能重叠</th>
              <th>证据与不确定性</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`mapping-${index}`}>
                <td>{cell(row, 'samplePeak')}</td>
                <td>{cell(row, 'referencePeak')}</td>
                <td>{cell(row, 'deviation')}</td>
                <td>{cell(row, 'supportLevel')}</td>
                <td>{cell(row, 'possibleOverlap')}</td>
                <td>
                  <div className="spectrum-result-table-evidence">
                    {cell(row, 'reason')}
                    {row.uncertainty ? <div>{cell(row, 'uncertainty')}</div> : null}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

type AssistantResultProps = {
  result: SpectrumResult;
  fallbackContent?: string;
  warnings: string[];
  citations: SpectrumMessage['citations'];
  onPreview: (chartId: string) => void;
};

function CitationList({
  citations,
  onPreview,
}: Pick<AssistantResultProps, 'citations' | 'onPreview'>) {
  if (!citations.length) return null;
  return (
    <div className="spectrum-result-citations">
      <Typography.Text strong>依据图谱</Typography.Text>
      <div className="spectrum-result-citation-list">
        {citations.map((item) => (
          <div className="spectrum-result-citation-row" key={`${item.chartId}-${item.page}`}>
            <FileImageOutlined aria-hidden="true" />
            <button
              type="button"
              className="spectrum-result-citation-title"
              onClick={() => onPreview(item.chartId)}
            >
              {item.title} · 第 {item.page} 页
            </button>
            <Button
              size="small"
              type="text"
              icon={<EyeOutlined />}
              onClick={() => onPreview(item.chartId)}
            >
              预览
            </Button>
          </div>
        ))}
      </div>
    </div>
  );
}

function V3StringSection({
  title,
  values,
  limit,
  ordered = false,
}: {
  title: string;
  values: string[];
  limit?: number;
  ordered?: boolean;
}) {
  const displayed = typeof limit === 'number' ? values.slice(0, limit) : values;
  if (!displayed.length) return null;
  const List = ordered ? 'ol' : 'ul';
  return (
    <section className="spectrum-result-v3-section">
      <Typography.Text strong>{title}</Typography.Text>
      <List>
        {displayed.map((value, index) => (
          <li key={`${title}-${index}`}>
            <MarkdownContent value={value} />
          </li>
        ))}
      </List>
    </section>
  );
}

function V3AssistantResult({ result, citations, onPreview }: AssistantResultProps) {
  const answer = result.answer || {
    summary: '',
    confirmed: [],
    metadata: [],
    notConfirmed: [],
    reasonableInference: [],
    missingEvidence: [],
    nextStep: [],
  };
  const failed = result.analysisStatus === 'FAILED';

  return (
    <div className="spectrum-result-shell spectrum-result-v3">
      {failed ? (
        <Alert
          type="error"
          showIcon
          message="图谱 AI 分析失败"
          description={
            result.errorMessage || answer.notConfirmed[0] || '模型未返回有效分析结果，请重新分析。'
          }
        />
      ) : null}
      {!failed && answer.summary ? (
        <section className="spectrum-result-summary">
          <Typography.Text strong>分析结论</Typography.Text>
          <MarkdownContent value={answer.summary} />
        </section>
      ) : null}
      {!failed ? <V3StringSection title="图例与样品标记" values={answer.metadata || []} /> : null}
      {!failed && result.assessment?.items?.length ? (
        <section className="spectrum-result-assessment">
          <Typography.Text strong>{result.assessment.title}</Typography.Text>
          <div className="spectrum-result-assessment-grid">
            {result.assessment.items.map((item, index) => (
              <div key={`${item.label}-${index}`} className="spectrum-result-assessment-item">
                <Typography.Text type="secondary">{item.label}</Typography.Text>
                <Typography.Text>{item.value}</Typography.Text>
                {item.source ? (
                  <Typography.Text type="secondary">来源：{item.source}</Typography.Text>
                ) : null}
              </div>
            ))}
          </div>
        </section>
      ) : null}
      {!failed ? <V3StringSection title="图上观察与对比" values={answer.confirmed} /> : null}
      {!failed ? <V3StringSection title="方法学判断" values={answer.reasonableInference} /> : null}
      {!failed ? <V3StringSection title="暂不能确认" values={answer.notConfirmed} /> : null}
      {!failed ? <V3StringSection title="建议补充的信息" values={answer.missingEvidence} /> : null}
      {!failed ? <V3StringSection title="建议验证" values={answer.nextStep} ordered /> : null}
      <CitationList citations={citations} onPreview={onPreview} />
    </div>
  );
}

export function AssistantResult(props: AssistantResultProps) {
  if ((props.result.schemaVersion ?? 0) >= 4) {
    const failed = props.result.analysisStatus === 'FAILED';
    return (
      <div className="spectrum-result-shell spectrum-result-natural">
        {failed ? (
          <Alert
            type="error"
            showIcon
            message="图谱 AI 分析失败"
            description={props.result.errorMessage || props.result.answerMarkdown}
          />
        ) : (
          <MarkdownContent value={props.result.answerMarkdown} />
        )}
        <CitationList citations={props.citations} onPreview={props.onPreview} />
      </div>
    );
  }
  if (props.result.schemaVersion === 3 && props.result.answer) {
    return <V3AssistantResult {...props} />;
  }
  return <LegacyAssistantResult {...props} />;
}

function LegacyAssistantResult({
  result,
  fallbackContent,
  citations,
  onPreview,
}: AssistantResultProps) {
  const failed = result.analysisStatus === 'FAILED';
  const presentation = resolveSpectrumPresentation(result, fallbackContent);
  const [detailsOpen, setDetailsOpen] = useState(false);
  useEffect(() => {
    setDetailsOpen(false);
  }, [presentation.conclusion, presentation.version]);
  const displayedTexts = [
    presentation.conclusion,
    ...presentation.keyFindings,
    ...presentation.validationSteps,
  ];
  const rawDetailSections = [
    ['observations', '主要观察结果', result.observations],
    ['comparisons', '整体谱形与批次差异', result.comparisons],
    ['candidateInterpretations', '候选特征、基团或成分解释', result.candidateInterpretations],
    ['overlapCandidates', '可能的叠加峰', result.overlapCandidates],
    ['unmatchedFeatures', '未找到可靠参考证据的峰', result.unmatchedFeatures],
    [
      'conflicts',
      '冲突点与不确定性',
      Array.isArray(result.conflicts) && result.conflicts.length
        ? result.conflicts
        : result.uncertainty,
    ],
    ['suggestedValidationExperiments', '建议验证实验', result.suggestedValidationExperiments],
    ['testConditionLimitations', '测试条件限制', result.testConditionLimitations],
    ['aiReviewFocus', 'AI 建议复核重点', result.aiReviewFocus],
  ] as [string, string, unknown][];
  const detailSections = rawDetailSections
    .filter(([key]) => presentation.detailSectionKeys.includes(key))
    .map(
      ([key, title, value]) =>
        [
          key,
          key === 'suggestedValidationExperiments' && presentation.validationSteps.length
            ? '更多验证实验'
            : title,
          isUnknownArray(value) ? remainingSpectrumDetails(value, displayedTexts) : [],
        ] as [string, string, unknown[]],
    )
    .filter(([, , value]) => value.length);
  const mappingStatement = result.referenceAvailability?.statement || '';
  const showMappingStatement =
    result.referenceAvailability?.hasSinglePeakReferences === true &&
    mappingStatement &&
    !isRepeatedSpectrumText(mappingStatement, displayedTexts);
  const boundary = result.conclusionBoundary
    ? boundaryLabels[result.conclusionBoundary] ||
      (/^[A-Z0-9_]+$/.test(result.conclusionBoundary) ? '' : result.conclusionBoundary)
    : '';
  const showBoundary =
    boundary &&
    !result.evidenceSufficiency?.startsWith('INSUFFICIENT') &&
    !isRepeatedSpectrumText(boundary, displayedTexts);
  const hasPeakMappings = isUnknownArray(result.peakMappings) && result.peakMappings.length > 0;
  const hasDetails =
    hasPeakMappings ||
    detailSections.length > 0 ||
    Boolean(showMappingStatement) ||
    Boolean(showBoundary);
  return (
    <div className="spectrum-result-shell">
      {failed ? (
        <Alert
          type="error"
          showIcon
          message="图谱 AI 分析失败"
          description={
            result.errorMessage || result.answerMarkdown || '模型未返回有效分析结果，请重新分析。'
          }
        />
      ) : null}
      {!failed && (
        <section className="spectrum-result-summary" aria-label="分析结论">
          <MarkdownContent value={presentation.conclusion} />
        </section>
      )}
      {!failed && presentation.keyFindings.length ? (
        <section className="spectrum-result-key-points">
          <Typography.Text strong>{findingLabels[presentation.primaryIntent]}</Typography.Text>
          <ul>
            {presentation.keyFindings.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>
      ) : null}
      {!failed && presentation.validationSteps.length ? (
        <section className="spectrum-result-validation">
          <Typography.Text strong>建议验证实验</Typography.Text>
          <ol>
            {presentation.validationSteps.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ol>
        </section>
      ) : null}
      {!failed && hasDetails ? (
        <Collapse
          className="spectrum-result-details"
          ghost
          activeKey={detailsOpen ? ['analysis-details'] : []}
          onChange={(keys) =>
            setDetailsOpen((Array.isArray(keys) ? keys : [keys]).includes('analysis-details'))
          }
          items={[
            {
              key: 'analysis-details',
              label: '查看分析详情',
              children: (
                <div className="spectrum-result-detail-body">
                  {showMappingStatement ? (
                    <Typography.Text type="secondary">{mappingStatement}</Typography.Text>
                  ) : null}
                  {hasPeakMappings ? <PeakMappingTable value={result.peakMappings} /> : null}
                  {detailSections.map(([key, title, value]) => (
                    <ResultSection key={key} title={title} value={value} />
                  ))}
                  {showBoundary ? (
                    <Typography.Text type="secondary" className="spectrum-result-boundary">
                      结论边界：{boundary}
                    </Typography.Text>
                  ) : null}
                </div>
              ),
            },
          ]}
        />
      ) : null}
      <CitationList citations={citations} onPreview={onPreview} />
    </div>
  );
}

export function StreamingSpectrumResult({
  answer,
  isStreaming = true,
  hasConnectionError = false,
}: {
  answer: string;
  isStreaming?: boolean;
  hasConnectionError?: boolean;
}) {
  return (
    <div className="spectrum-result-shell spectrum-result-natural spectrum-result-streaming" aria-live="polite">
      {answer ? (
        <>
          {isStreaming ? (
            <div className="spectrum-streaming-progress" role="status">
              <Spin size="small" />
              <div>
                <Typography.Text strong>正在生成分析回答</Typography.Text>
                <Typography.Text type="secondary">
                  {hasConnectionError ? '连接暂时中断，正在尝试恢复…' : '回答内容会实时显示'}
                </Typography.Text>
              </div>
            </div>
          ) : null}
          <div className="spectrum-streaming-answer" aria-label="分析回答">
            <StreamingMarkdownContent value={answer} isStreaming={isStreaming} />
          </div>
        </>
      ) : isStreaming ? (
        <div className="spectrum-streaming-progress" role="status">
          <Spin size="small" />
          <div>
            <Typography.Text strong>正在生成分析回答</Typography.Text>
            <Typography.Text type="secondary">
              {hasConnectionError ? '连接暂时中断，正在尝试恢复…' : '回答内容会实时显示'}
            </Typography.Text>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function SpectrumStreamAnswer({
  analysisRunId,
  isStreaming,
  hasConnectionError = false,
}: {
  analysisRunId: string;
  isStreaming: boolean;
  hasConnectionError?: boolean;
}) {
  const subscribe = useCallback(
    (listener: () => void) => subscribeSpectrumAnalysisStream(analysisRunId, listener),
    [analysisRunId],
  );
  const getSnapshot = useCallback(
    () => getSpectrumAnalysisStreams().get(analysisRunId)?.answer || '',
    [analysisRunId],
  );
  const answer = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  return (
    <StreamingSpectrumResult
      answer={answer}
      isStreaming={isStreaming}
      hasConnectionError={hasConnectionError}
    />
  );
}

export function SpectrumChatPage() {
  const { message: toast } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [categories, setCategories] = useState<SpectrumCategory[]>([]);
  const [charts, setCharts] = useState<SpectrumChart[]>([]);
  const [unavailableChartIds, setUnavailableChartIds] = useState<Set<string>>(() => new Set());
  const [categoriesLoading, setCategoriesLoading] = useState(true);
  const [chartsLoading, setChartsLoading] = useState(true);
  const [sessions, setSessions] = useState<SpectrumSession[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | undefined>(
    () =>
      searchParams.get('sessionId') ||
      window.sessionStorage.getItem('spectrum.chat.activeSessionId') ||
      undefined,
  );
  const [messages, setMessages] = useState<SpectrumMessage[]>([]);
  const [terminalAnalysisRunIds, setTerminalAnalysisRunIds] = useState<Set<string>>(() => new Set());
  const [selectedCharts, setSelectedCharts] = useState<string[]>([]);
  const [pageSelections, setPageSelections] = useState<Record<string, number[]>>({});
  const [sessionContextLoading, setSessionContextLoading] = useState(Boolean(activeSessionId));
  const [scopeKeyword, setScopeKeyword] = useState('');
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const streams = useSyncExternalStore(
    subscribeSpectrumAnalysisStreams,
    getSpectrumAnalysisStreamMetadata,
    getSpectrumAnalysisStreamMetadata,
  );
  const [pageChart, setPageChart] = useState<SpectrumChart>();
  const [pageOptions, setPageOptions] = useState<number[]>([]);
  const [pageValue, setPageValue] = useState<number[]>([]);
  const [pageLoading, setPageLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const activeSessionRef = useRef(activeSessionId);
  const chartItemsRef = useRef<SpectrumChart[]>([]);
  const initialSessionIdRef = useRef(activeSessionId);
  const sessionLoadSequenceRef = useRef(0);
  const sessionSelectionVersionRef = useRef(0);
  const refreshedAnalysisRunsRef = useRef(new Set<string>());

  const loadBase = useCallback(async () => {
    setLoading(true);
    setCategoriesLoading(true);
    setChartsLoading(true);
    const categoryRequest = spectrumApi
      .categories()
      .then(setCategories)
      .catch(() => setCategories([]))
      .finally(() => setCategoriesLoading(false));
    const chartRequest = spectrumApi
      .listCharts({ size: 100 })
      .then((chartPage) => {
        const mergedCharts = [
          ...chartPage.items,
          ...chartItemsRef.current.filter(
            (chart) => !chartPage.items.some((item) => item.id === chart.id),
          ),
        ];
        chartItemsRef.current = mergedCharts;
        setCharts(mergedCharts);
        const routeChartIds = searchParams.get('chartIds')?.split(',').filter(Boolean) || [];
        if (!initialSessionIdRef.current && routeChartIds.length)
          setSelectedCharts(
            routeChartIds.filter((id) => chartPage.items.some((item) => item.id === id)),
          );
      })
      .catch(() => {
        setCharts(chartItemsRef.current);
      })
      .finally(() => setChartsLoading(false));
    void spectrumApi.sessions().then(
      (sessionList) =>
        setSessions((current) => {
          const merged = new Map(sessionList.map((item) => [item.id, item]));
          current.forEach((item) => merged.set(item.id, item));
          return [...merged.values()].sort(
            (left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt),
          );
        }),
      () => undefined,
    );
    await Promise.all([categoryRequest, chartRequest]);
    setLoading(false);
  }, [searchParams]);

  useEffect(() => {
    void loadBase();
  }, [loadBase]);

  const loadSessionMessages = useCallback(async (id: string) => {
    const sequence = ++sessionLoadSequenceRef.current;
    try {
      const session = await spectrumApi.session(id);
      if (sequence !== sessionLoadSequenceRef.current || activeSessionRef.current !== id) return;
      setMessages(session.messages);
      const savedChartIds = session.chartIds || [];
      const routeChartIds = searchParams.get('chartIds')?.split(',').filter(Boolean) || [];
      const restoredChartIds = savedChartIds.length
        ? savedChartIds
        : initialSessionIdRef.current === id
          ? routeChartIds
          : [];
      setSelectedCharts(
        restoredChartIds,
      );
      setPageSelections(session.pageSelections || {});
      const unresolvedChartIds = restoredChartIds.filter(
        (chartId) => !chartItemsRef.current.some((chart) => chart.id === chartId),
      );
      if (unresolvedChartIds.length) {
        setSessionContextLoading(true);
        void Promise.all(
          unresolvedChartIds.map((chartId) =>
            spectrumApi.chart(chartId).catch(() => undefined),
          ),
        ).then((resolvedCharts) => {
          if (sequence !== sessionLoadSequenceRef.current || activeSessionRef.current !== id) return;
          const availableCharts = resolvedCharts.filter(
            (chart): chart is SpectrumChart => Boolean(chart),
          );
          const unavailable = new Set(
            unresolvedChartIds.filter((chartId) => !availableCharts.some((chart) => chart.id === chartId)),
          );
          const mergedCharts = [
            ...chartItemsRef.current,
            ...availableCharts.filter(
              (chart) => !chartItemsRef.current.some((item) => item.id === chart.id),
            ),
          ];
          chartItemsRef.current = mergedCharts;
          setCharts(mergedCharts);
          setUnavailableChartIds(unavailable);
          setSessionContextLoading(false);
        });
      } else {
        setUnavailableChartIds(new Set());
        setSessionContextLoading(false);
      }
      session.messages.forEach((item) => {
        if (item.role === 'ASSISTANT' && item.analysisRunId)
          refreshedAnalysisRunsRef.current.add(item.analysisRunId);
      });
      const assistantRuns = new Set(
        session.messages
          .filter((item) => item.role === 'ASSISTANT' && item.analysisRunId)
          .map((item) => item.analysisRunId),
      );
      const pendingRuns = new Set(
        session.messages
          .filter((item) => item.role === 'USER' && item.analysisRunId)
          .map((item) => item.analysisRunId)
          .filter((runId): runId is string => Boolean(runId) && !assistantRuns.has(runId)),
      );
      pendingRuns.forEach((analysisRunId) => {
        void spectrumApi
          .analysis(analysisRunId)
          .then((analysis) => {
            if (!isTerminalSpectrumAnalysisStatus(analysis.status)) {
              void startSpectrumAnalysisStream(id, analysisRunId);
              return;
            }
            setTerminalAnalysisRunIds((current) => new Set(current).add(analysisRunId));
            void spectrumApi
              .session(id)
              .then((latest) => {
                if (activeSessionRef.current === id) setMessages(latest.messages);
              })
              .catch(() => undefined);
          })
          .catch(() => undefined);
      });
    } catch {
      if (sequence === sessionLoadSequenceRef.current && activeSessionRef.current === id) {
        setMessages([]);
        setSelectedCharts([]);
        setPageSelections({});
        setUnavailableChartIds(new Set());
        setSessionContextLoading(false);
        window.sessionStorage.removeItem('spectrum.chat.activeSessionId');
        activeSessionRef.current = undefined;
        setActiveSessionId(undefined);
      }
    }
  }, [searchParams]);

  useEffect(() => {
    activeSessionRef.current = activeSessionId;
    if (!activeSessionId) {
      setMessages([]);
      setSessionContextLoading(false);
      return;
    }
    void loadSessionMessages(activeSessionId);
  }, [activeSessionId, loadSessionMessages]);

  useEffect(() => {
    if (activeSessionId)
      window.sessionStorage.setItem('spectrum.chat.activeSessionId', activeSessionId);
    else window.sessionStorage.removeItem('spectrum.chat.activeSessionId');
  }, [activeSessionId]);

  useEffect(() => {
    streams.forEach((stream) => {
      if (
        stream.sessionId !== activeSessionId ||
        !isTerminalSpectrumAnalysisStatus(stream.status) ||
        refreshedAnalysisRunsRef.current.has(stream.analysisRunId)
      )
        return;
      refreshedAnalysisRunsRef.current.add(stream.analysisRunId);
      void spectrumApi
        .session(stream.sessionId)
        .then((session) => {
          if (activeSessionRef.current === stream.sessionId) setMessages(session.messages);
        })
        .catch(() => refreshedAnalysisRunsRef.current.delete(stream.analysisRunId));
    });
  }, [activeSessionId, streams]);

  const selectSession = (id: string) => {
    sessionSelectionVersionRef.current += 1;
    window.sessionStorage.setItem('spectrum.chat.activeSessionId', id);
    if (activeSessionRef.current === id) {
      setSessionContextLoading(true);
      void loadSessionMessages(id);
      return;
    }
    activeSessionRef.current = id;
    setMessages([]);
    setSelectedCharts([]);
    setPageSelections({});
    setUnavailableChartIds(new Set());
    setSessionContextLoading(true);
    setActiveSessionId(id);
  };

  const createConversation = async () => {
    try {
      const session = await spectrumApi.createSession();
      setSessions((current) => [session, ...current]);
      sessionSelectionVersionRef.current += 1;
      activeSessionRef.current = session.id;
      window.sessionStorage.setItem('spectrum.chat.activeSessionId', session.id);
      setActiveSessionId(session.id);
      setMessages([]);
      setSelectedCharts([]);
      setPageSelections({});
      setUnavailableChartIds(new Set());
      setSessionContextLoading(false);
      setQuestion('');
    } catch {
      activeSessionRef.current = undefined;
      setActiveSessionId(undefined);
    }
  };

  const renameConversation = async (item: ConversationItem, title: string) => {
    try {
      await spectrumApi.renameSession(item.id, title);
      setSessions(await spectrumApi.sessions());
      toast.success('会话标题已更新');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '会话标题更新失败');
      throw error;
    }
  };

  const deleteConversation = async (item: ConversationItem) => {
    try {
      await spectrumApi.deleteSession(item.id);
      setSessions(await spectrumApi.sessions());
      if (activeSessionRef.current === item.id) {
        sessionSelectionVersionRef.current += 1;
        activeSessionRef.current = undefined;
        window.sessionStorage.removeItem('spectrum.chat.activeSessionId');
        setActiveSessionId(undefined);
        setMessages([]);
        setSelectedCharts([]);
        setPageSelections({});
        setUnavailableChartIds(new Set());
        setSessionContextLoading(false);
        setQuestion('');
      }
      toast.success('会话已删除');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '会话删除失败');
      throw error;
    }
  };

  const openPagePicker = async (chart: SpectrumChart) => {
    setPageChart(chart);
    setPageLoading(true);
    try {
      const pages = await spectrumApi.pages(chart.id);
      setPageOptions(pages.map((item) => item.pageNo));
      setPageValue(pageSelections[chart.id] || [1]);
    } catch {
      setPageOptions([1]);
      setPageValue([1]);
    } finally {
      setPageLoading(false);
    }
  };

  const savePagePicker = () => {
    if (!pageChart) return;
    setPageSelections((current) => ({
      ...current,
      [pageChart.id]: pageValue.length ? pageValue : [1],
    }));
    setPageChart(undefined);
  };

  const selectedChartItems = useMemo(
    () => selectedCharts.map((id) =>
      charts.find((item) => item.id === id) || {
        id,
        title: unavailableChartIds.has(id) ? '图谱不可用，请移除后重新选择' : '正在恢复图谱…',
      },
    ),
    [charts, selectedCharts, unavailableChartIds],
  );
  const hasUnavailableCharts = selectedCharts.some((id) => unavailableChartIds.has(id));

  const filteredCharts = useMemo(() => {
    const value = scopeKeyword.trim().toLowerCase();
    return value
      ? charts.filter((item) =>
          `${item.title} ${item.originalName} ${item.sampleName || ''} ${item.batchNo || ''}`
            .toLowerCase()
            .includes(value),
        )
      : charts;
  }, [charts, scopeKeyword]);

  const chartsByCategory = useMemo(
    () =>
      categories
        .map((category) => ({
          category,
          charts: filteredCharts.filter((item) => item.categoryId === category.id),
        }))
        .filter((item) => item.charts.length),
    [categories, filteredCharts],
  );

  const updateChartSelection = (chartId: string, checked: boolean) => {
    setSelectedCharts((current) => {
      if (checked) return current.includes(chartId) || current.length >= 12 ? current : [...current, chartId];
      return current.filter((id) => id !== chartId);
    });
    if (!checked) {
      setUnavailableChartIds((current) => {
        const next = new Set(current);
        next.delete(chartId);
        return next;
      });
      setPageSelections((current) => {
        const next = { ...current };
        delete next[chartId];
        return next;
      });
    } else if (selectedCharts.length >= 12 && !selectedCharts.includes(chartId)) {
      toast.warning('每个会话最多引用 12 张图谱');
    }
  };

  const scopeContent = (
    <div className="spectrum-chat-scope-content">
      <Input
        className="spectrum-chat-scope-search"
        allowClear
        prefix={<SearchOutlined />}
        placeholder="搜索图谱、样品、批号"
        value={scopeKeyword}
        onChange={(event) => setScopeKeyword(event.target.value)}
      />
      <div className="spectrum-chat-scope-summary">
        <span>当前引用</span>
        <strong>{selectedCharts.length}</strong>
        <Typography.Text type="secondary">张图谱，可跨分类多选</Typography.Text>
      </div>
      {categoriesLoading || chartsLoading ? (
        <div className="spectrum-chat-scope-state">
          <Spin size="small" />
          <Typography.Text type="secondary">正在加载图谱</Typography.Text>
        </div>
      ) : chartsByCategory.length ? (
        <Collapse
          className="spectrum-chat-category-list"
          ghost
          items={chartsByCategory.map(({ category, charts: categoryCharts }) => ({
            key: category.id,
            label: (
              <span className="spectrum-chat-category-label">
                <span>
                  <LineChartOutlined />
                  {category.name}
                </span>
                <b>{categoryCharts.length}</b>
              </span>
            ),
            children: (
              <div className="spectrum-chat-chart-list">
                {categoryCharts.map((chart) => (
                  <div
                    key={chart.id}
                    className={`spectrum-chat-chart-option${selectedCharts.includes(chart.id) ? ' is-selected' : ''}`}
                  >
                    <Checkbox
                      checked={selectedCharts.includes(chart.id)}
                      onChange={(event) => updateChartSelection(chart.id, event.target.checked)}
                    >
                      <span className="spectrum-chat-chart-copy">
                        <Typography.Text ellipsis={{ tooltip: chart.title }}>
                          {chart.title}
                        </Typography.Text>
                        <Typography.Text type="secondary" ellipsis={{ tooltip: chart.originalName }}>
                          {chart.originalName}
                        </Typography.Text>
                      </span>
                    </Checkbox>
                    <Button type="link" size="small" onClick={() => void openPagePicker(chart)}>
                      选页
                    </Button>
                  </div>
                ))}
              </div>
            ),
          }))}
        />
      ) : (
        <div className="spectrum-chat-scope-state">
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可选图谱，请先上传" />
        </div>
      )}
    </div>
  );

  const composerTopContent = (
    <div className="spectrum-composer-selection" aria-label="分析设置与已选择的图谱">
      <Typography.Text className="spectrum-composer-selection-label">本次图谱</Typography.Text>
      <div className="spectrum-composer-selection-list">
        {selectedChartItems.length ? (
          selectedChartItems.map((item) => (
            <Tag
              key={item.id}
              closable
              onClose={() => {
                setSelectedCharts((current) => current.filter((id) => id !== item.id));
                setUnavailableChartIds((current) => {
                  const next = new Set(current);
                  next.delete(item.id);
                  return next;
                });
                setPageSelections((current) => {
                  const next = { ...current };
                  delete next[item.id];
                  return next;
                });
              }}
              title={item.title}
            >
              {item.title}
            </Tag>
          ))
        ) : (
          <Typography.Text type="secondary">
            {sessionContextLoading ? '正在恢复本会话的图谱范围…' : '请先从左侧选择图谱'}
          </Typography.Text>
        )}
      </div>
      <Typography.Text type="secondary" className="spectrum-composer-selection-count">
        {selectedChartItems.length} 张
      </Typography.Text>
      {hasUnavailableCharts ? (
        <Typography.Text type="danger" className="spectrum-composer-selection-hint">
          部分图谱已删除或无权访问，请移除后重新选择。
        </Typography.Text>
      ) : null}
    </div>
  );

  const descriptor = (chart: SpectrumChart): FilePreviewDescriptor => ({
    fileName: chart.originalName,
    contentType: chart.contentType,
    size: chart.size,
    load: () => spectrumApi.previewBlob(chart.id),
    downloadUrl: spectrumApi.contentUrl(chart.id),
  });
  const previewById = (chartId: string) => {
    const chart = charts.find((item) => item.id === chartId);
    if (chart) setPreviewFile(descriptor(chart));
  };

  const conversationItems: ConversationItem[] = sessions.map((item) => ({
    id: item.id,
    title: item.title,
    updatedAt: item.updatedAt,
  }));
  const assistantRunIds = new Set(
    messages
      .filter((item) => item.role === 'ASSISTANT' && item.analysisRunId)
      .map((item) => item.analysisRunId),
  );
  const activeSessionStreams = [...streams.values()].filter(
    (stream) =>
      stream.sessionId === activeSessionId && !isTerminalSpectrumAnalysisStatus(stream.status),
  );
  const streaming = activeSessionStreams.length > 0;
  const streamStage = activeSessionStreams.at(-1)?.stage || '正在读取图谱文件';
  const conversationMessages: ConversationMessage[] = [];
  messages.forEach((item) => {
    if (item.role === 'USER') {
      conversationMessages.push({
        id: item.id,
        role: item.role,
        editableText: item.analysisRunId ? item.content : undefined,
        content: (
          <Typography.Paragraph className="ai-message-text">{item.content}</Typography.Paragraph>
        ),
      });
      if (item.analysisRunId && !assistantRunIds.has(item.analysisRunId)) {
        const stream = streams.get(item.analysisRunId);
        const terminal =
          terminalAnalysisRunIds.has(item.analysisRunId) ||
          (stream ? isTerminalSpectrumAnalysisStatus(stream.status) : false);
        conversationMessages.push({
          id: `stream-${item.analysisRunId}`,
          role: 'ASSISTANT',
          content: terminal ? (
            <Typography.Text type="secondary">分析已完成，正在载入完整回答…</Typography.Text>
          ) : stream ? (
            <SpectrumStreamAnswer
              analysisRunId={item.analysisRunId}
              isStreaming
              hasConnectionError={Boolean(stream.error)}
            />
          ) : null,
        });
      }
      return;
    }
    const stream = item.analysisRunId ? streams.get(item.analysisRunId) : undefined;
    const streamingThisAnswer =
      stream !== undefined && !isTerminalSpectrumAnalysisStatus(stream.status);
    conversationMessages.push({
      id: item.analysisRunId ? `stream-${item.analysisRunId}` : item.id,
      role: item.role,
      content: streamingThisAnswer ? (
        stream ? (
          <SpectrumStreamAnswer
            analysisRunId={stream.analysisRunId}
            isStreaming
            hasConnectionError={Boolean(stream.error)}
          />
        ) : null
      ) : Object.keys(item.result || {}).length ? (
        <AssistantResult
          result={item.result}
          fallbackContent={item.content}
          warnings={item.warnings}
          citations={item.citations}
          onPreview={previewById}
        />
      ) : item.content ? (
        <Alert
          type={item.warnings.length ? 'error' : 'info'}
          showIcon
          message={item.content}
          description={item.warnings.length ? item.warnings.join('；') : undefined}
        />
      ) : null,
    });
  });

  const submit = async (questionOverride?: string, replacement?: SpectrumMessageReplacement) => {
    if (!selectedCharts.length || hasUnavailableCharts || sessionContextLoading || submitting || streaming) return;
    const submittedQuestion = (questionOverride ?? question).trim();
    if (!submittedQuestion) return;
    setSubmitting(true);
    const initialSelectionVersion = sessionSelectionVersionRef.current;
    const initialSessionId = activeSessionRef.current;
    const expectedMessages = messages;
    const optimisticUser: SpectrumMessage = {
      id: `local-user-${Date.now()}`,
      role: 'USER',
      content: submittedQuestion,
      citations: [],
      result: {},
      warnings: [],
      createdAt: new Date().toISOString(),
    };
    if (replacement) {
      const targetIndex = messages.findIndex((item) => item.id === replacement.replaceFromMessageId);
      if (targetIndex < 0) {
        setSubmitting(false);
        toast.error('找不到要编辑的问题，请刷新会话后重试');
        return;
      }
      setMessages((current) => [...current.slice(0, targetIndex), optimisticUser]);
    } else if (initialSessionId) {
      setMessages((current) => [...current, optimisticUser]);
    }
    try {
      const submitted = await spectrumApi.submitChat({
        sessionId: initialSessionId,
        question: submittedQuestion,
        chartIds: selectedCharts,
        pageSelections,
        ...replacement,
      });
      if (sessionSelectionVersionRef.current === initialSelectionVersion) {
        activeSessionRef.current = submitted.sessionId;
        window.sessionStorage.setItem('spectrum.chat.activeSessionId', submitted.sessionId);
        setActiveSessionId(submitted.sessionId);
        setQuestion('');
      }
      void startSpectrumAnalysisStream(submitted.sessionId, submitted.analysisRunId);
      void spectrumApi
        .sessions()
        .then(setSessions)
        .catch(() => undefined);
      setSessions((current) => {
        const matching = current.find((session) => session.id === submitted.sessionId);
        if (!matching) return current;
        return [
          { ...matching, updatedAt: new Date().toISOString() },
          ...current.filter((session) => session.id !== submitted.sessionId),
        ];
      });
      const session = await spectrumApi.session(submitted.sessionId);
      if (activeSessionRef.current === submitted.sessionId) {
        setMessages(session.messages);
        setSelectedCharts(session.chartIds);
        setPageSelections(session.pageSelections);
        session.messages.forEach((item) => {
          if (item.role === 'ASSISTANT' && item.analysisRunId)
            refreshedAnalysisRunsRef.current.add(item.analysisRunId);
        });
      }
    } catch (error) {
      if (replacement && initialSessionId && activeSessionRef.current === initialSessionId) {
        try {
          const session = await spectrumApi.session(initialSessionId);
          setMessages(session.messages);
          setSelectedCharts(session.chartIds);
          setPageSelections(session.pageSelections);
        } catch {
          setMessages(expectedMessages);
        }
        toast.error(error instanceof Error ? error.message : '编辑后重新分析失败');
      } else if (initialSessionId && activeSessionRef.current === initialSessionId)
        setMessages((current) =>
          current
            .filter((item) => item.id !== optimisticUser.id)
            .concat({
              ...optimisticUser,
              content: `提交失败：${error instanceof Error ? error.message : '图谱分析任务提交失败'}`,
            }),
        );
      else toast.error(error instanceof Error ? error.message : '图谱分析任务提交失败');
    } finally {
      setSubmitting(false);
    }
  };

  const quickQuestions = [
    '请先总结这些图谱的主要观察结果、依据和不确定性。',
    '竞品图谱中的哪些峰可能对应这些单峰参考图谱？',
    '哪些峰可能是多个成分叠加形成的？哪些峰没有可靠参考证据？',
    '当前图片是否足以支持明确归因？请给出建议验证实验。',
  ];

  return (
    <div className="business-page spectrum-chat-page">
      <div className="page-heading">
        <Button
          type="text"
          shape="circle"
          className="assistant-page-back spectrum-page-back"
          icon={<ArrowLeftOutlined />}
          aria-label="返回上一页"
          onClick={() => navigate(-1)}
        />
        <div className="spectrum-page-heading-copy">
          <div className="spectrum-page-title-row">
            <span className="spectrum-page-mark" aria-hidden="true">
              <LineChartOutlined />
            </span>
            <Typography.Title level={2}>AI图谱分析</Typography.Title>
          </div>
          <Typography.Text type="secondary">
            把需要分析的图谱加入会话，再直接提问；后续追问会沿用本会话范围。
          </Typography.Text>
        </div>
      </div>
      <AiConversationWorkspace
        conversations={conversationItems}
        activeConversationId={activeSessionId}
        messages={conversationMessages}
        loading={loading || submitting}
        scopeContent={scopeContent}
        composerTopContent={composerTopContent}
        assistantLabel="AI图谱助手"
        submitDisabled={!selectedCharts.length || hasUnavailableCharts || sessionContextLoading || submitting}
        scopeTitle="选择参与分析的图谱"
        welcomeTitle="开始图谱分析对话"
        welcomeDescription="新会话先添加一张图谱；同一会话后续提问会沿用当前范围。"
        scopeSummary={
          <div className="spectrum-welcome-boundary">
            <LineChartOutlined aria-hidden="true" />
            <Typography.Text type="secondary">
              回答仅依据本会话引用的图谱提供观察、候选解释和验证建议。
            </Typography.Text>
          </div>
        }
        pendingLabel={streamStage}
        welcomeContent={
          <div className="spectrum-quick-questions">
            <Typography.Text strong>可以这样问</Typography.Text>
            <Space wrap>
              {quickQuestions.map((item) => (
                <Button key={item} size="small" onClick={() => setQuestion(item)}>
                  {item}
                </Button>
              ))}
            </Space>
          </div>
        }
        question={question}
        streaming={streaming}
        onNewConversation={() => void createConversation()}
        onSelectConversation={(id) => void selectSession(id)}
        onEditMessage={(item, value) => {
          const expectedLastMessageId = messages.at(-1)?.id;
          if (!item.editableText || !item.id || !expectedLastMessageId) {
            toast.error('该问题没有可恢复的图谱范围，无法安全重跑');
            return;
          }
          void submit(value, {
            replaceFromMessageId: item.id,
            expectedLastMessageId,
          });
        }}
        onRenameConversation={renameConversation}
        onDeleteConversation={deleteConversation}
        onQuestionChange={setQuestion}
        onSubmit={() => void submit()}
      />
      <Modal
        open={Boolean(pageChart)}
        title={`选择“${pageChart?.title || ''}”的分析页面`}
        okText="保存选择"
        cancelText="取消"
        onCancel={() => setPageChart(undefined)}
        onOk={savePagePicker}
      >
        {pageLoading ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <Spin />
          </div>
        ) : (
          <>
            <Typography.Paragraph type="secondary">
              PDF 可选择多个页面；图谱图片默认只有第 1 页。未选择时将使用第 1 页。
            </Typography.Paragraph>
            <Checkbox.Group
              value={pageValue}
              onChange={(values) => setPageValue(values.map((value) => Number(value)))}
              options={pageOptions.map((value) => ({ label: `第 ${value} 页`, value }))}
            />
          </>
        )}
      </Modal>
      <FilePreviewModal
        open={Boolean(previewFile)}
        file={previewFile}
        onClose={() => setPreviewFile(undefined)}
      />
    </div>
  );
}
