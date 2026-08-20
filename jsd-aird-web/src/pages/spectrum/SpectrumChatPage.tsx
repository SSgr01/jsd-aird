import { EyeOutlined, LineChartOutlined, SearchOutlined } from '@ant-design/icons';
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
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import {
  AiConversationWorkspace,
  type ConversationItem,
  type ConversationMessage,
} from '@/components/ai-conversation-workspace';
import { FilePreviewModal, type FilePreviewDescriptor } from '@/components/file-preview';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import {
  spectrumApi,
  type SpectrumCategory,
  type SpectrumChart,
  type SpectrumMessage,
  type SpectrumResult,
  type SpectrumSession,
} from '@/services/spectrum';

const competitorQuestion = /竞品|单峰|叠加|可靠参考|明确归因|官能团|成分/;
const wait = (milliseconds: number) =>
  new Promise((resolve) => window.setTimeout(resolve, milliseconds));
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
};

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

function StructuredValue({ value }: { value: unknown }) {
  if (typeof value === 'string') {
    const structured = parseStructuredObject(value);
    if (structured) return <StructuredValue value={structured} />;
  }
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
        {Object.entries(value).map(([key, item]) => (
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

function StructuredResultItem({ item, index }: { item: unknown; index: number }) {
  const object = parseStructuredObject(item);
  if (!object)
    return (
      <div className="spectrum-result-item-card">
        <StructuredValue value={item} />
      </div>
    );
  return (
    <article className="spectrum-result-item-card">
      <div className="spectrum-result-item-index">分析项 {index + 1}</div>
      <div className="spectrum-result-fields">
        {Object.entries(object).map(([key, value]) => (
          <div key={key} className="spectrum-result-field">
            <Typography.Text type="secondary">{fieldLabel(key)}</Typography.Text>
            <StructuredValue value={value} />
          </div>
        ))}
      </div>
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
          <StructuredResultItem key={`result-item-${index}`} item={item} index={index} />
        ))}
      </div>
    </div>
  );
}

function PeakMappingTable({ value }: { value: unknown }) {
  if (!isUnknownArray(value)) return null;
  const rows = value.filter(isRecord);
  if (!rows.length) return null;
  const cell = (row: Record<string, unknown>, key: string) => (
    <StructuredValue value={row[key]} />
  );
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

function AssistantResult({
  result,
  fallbackContent,
  warnings,
  citations,
  onPreview,
}: {
  result: SpectrumResult;
  fallbackContent?: string;
  warnings: string[];
  citations: SpectrumMessage['citations'];
  onPreview: (chartId: string) => void;
}) {
  const confidence = printValue(result.confidence);
  const failed = result.analysisStatus === 'FAILED';
  const partial = result.analysisStatus === 'PARTIAL';
  const hasReferenceAnalysis = Boolean(result.referenceAvailability);
  const hasSinglePeakReferences = result.referenceAvailability?.hasSinglePeakReferences === true;
  const mappingStatement = result.referenceAvailability?.statement ||
    '当前材料不足以建立样品峰与单峰参考峰的映射，只能描述谱形相似性。';
  const summaryObject = parseStructuredObject(result.answerMarkdown);
  const detailSections = [
    ['observations', '主要观察结果', result.observations],
    ['comparisons', '整体谱形与批次差异', result.comparisons],
    ['candidateInterpretations', '候选特征、基团或成分解释', result.candidateInterpretations],
    ['overlapCandidates', '可能的叠加峰', result.overlapCandidates],
    ['unmatchedFeatures', '未找到可靠参考证据的峰', result.unmatchedFeatures],
    [
      'conflicts',
      '冲突点与不确定性',
      Array.isArray(result.conflicts) && result.conflicts.length ? result.conflicts : result.uncertainty,
    ],
    ['suggestedValidationExperiments', '建议验证实验', result.suggestedValidationExperiments],
    ['evidence', '分析依据', result.evidence],
    ['testConditionLimitations', '测试条件限制', result.testConditionLimitations],
  ].filter(([, , value]) => isUnknownArray(value) && value.length) as [string, string, unknown][];
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {failed ? (
        <Alert
          type="error"
          showIcon
          message="图谱 AI 分析失败"
          description={result.errorMessage || result.answerMarkdown || '模型未返回有效分析结果，请重新分析。'}
        />
      ) : null}
      {!failed ? (
        <div className="spectrum-result-status">
          <div className="spectrum-result-status-main">
            <div>
              <Typography.Text strong>AI图谱分析</Typography.Text>
              <Typography.Paragraph type="secondary">
                {partial ? '部分结果已按证据边界过滤' : '分析已完成，结论仅供专业人员复核'}
              </Typography.Paragraph>
            </div>
            <Space wrap>
              <Tag color={partial ? 'orange' : 'green'}>{partial ? '部分结果' : '已完成'}</Tag>
              <Tag color="gold">待专业人员复核</Tag>
              {confidence && <Tag color="blue">置信度：{confidence}</Tag>}
              {result.evidenceSufficiency && (
                <Tag>{result.evidenceSufficiency.replaceAll('_', ' ')}</Tag>
              )}
            </Space>
          </div>
          {hasReferenceAnalysis && hasSinglePeakReferences ? (
            <Typography.Text type="secondary">
              {mappingStatement}
            </Typography.Text>
          ) : null}
        </div>
      ) : null}
      {partial && warnings.length ? (
        <Alert
          type="warning"
          showIcon
          message="部分结果已按证据边界收敛"
          description={warnings.join('；')}
        />
      ) : null}
      {!failed && (
        <MarkdownContent
          value={
            summaryObject
              ? '模型返回了结构化分析内容，以下展示已通过证据校验的分析项。'
              : result.answerMarkdown || fallbackContent || '模型未返回有效分析摘要，请重新分析。'
          }
        />
      )}
      {!failed && hasReferenceAnalysis && !hasSinglePeakReferences && (
        <Alert type="info" showIcon message="暂不能建立峰位映射" description={mappingStatement} />
      )}
      {!failed && isUnknownArray(result.aiReviewFocus) && result.aiReviewFocus.length ? (
        <section className="spectrum-result-focus">
          <div className="spectrum-result-section-heading">
            <div>
              <Typography.Text strong>AI建议复核重点</Typography.Text>
              <Typography.Text type="secondary">这些是待实验或专业人员确认的事项，不代表已完成复核</Typography.Text>
            </div>
          </div>
          <ul>
            {result.aiReviewFocus.map((item, index) => (
              <li key={`focus-${index}`}><StructuredValue value={item} /></li>
            ))}
          </ul>
        </section>
      ) : null}
      {!failed && <PeakMappingTable value={result.peakMappings} />}
      {!failed && detailSections.length ? (
        <Collapse
          ghost
          items={detailSections.map(([key, title, value]) => ({
            key,
            label: title,
            children: <ResultSection title={title} value={value} />,
          }))}
        />
      ) : null}
      {result.conclusionBoundary && (
        <Typography.Text type="secondary">结论边界：{result.conclusionBoundary}</Typography.Text>
      )}
      {citations.length ? (
        <div>
          <Typography.Text strong>依据图谱</Typography.Text>
          <div style={{ marginTop: 6 }}>
            <Space wrap>
              {citations.map((item) => (
                <Tag
                  key={`${item.chartId}-${item.page}`}
                  icon={<EyeOutlined />}
                  color="geekblue"
                  onClick={() => onPreview(item.chartId)}
                  style={{ cursor: 'pointer' }}
                >
                  {item.title} · 第 {item.page} 页
                </Tag>
              ))}
            </Space>
          </div>
        </div>
      ) : null}
    </Space>
  );
}

export function SpectrumChatPage() {
  const { message: toast } = App.useApp();
  const [searchParams] = useSearchParams();
  const [categories, setCategories] = useState<SpectrumCategory[]>([]);
  const [charts, setCharts] = useState<SpectrumChart[]>([]);
  const [sessions, setSessions] = useState<SpectrumSession[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string>();
  const [messages, setMessages] = useState<SpectrumMessage[]>([]);
  const [selectedCharts, setSelectedCharts] = useState<string[]>([]);
  const [pageSelections, setPageSelections] = useState<Record<string, number[]>>({});
  const [scopeKeyword, setScopeKeyword] = useState('');
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(true);
  const [streaming, setStreaming] = useState(false);
  const [streamStage, setStreamStage] = useState('正在读取图谱文件');
  const [pageChart, setPageChart] = useState<SpectrumChart>();
  const [pageOptions, setPageOptions] = useState<number[]>([]);
  const [pageValue, setPageValue] = useState<number[]>([]);
  const [pageLoading, setPageLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const loadBase = useCallback(async () => {
    setLoading(true);
    try {
      const [categoryList, chartPage, sessionList] = await Promise.all([
        spectrumApi.categories(),
        spectrumApi.listCharts({ size: 100 }),
        spectrumApi.sessions(),
      ]);
      setCategories(categoryList);
      setCharts(chartPage.items);
      setSessions(sessionList);
      const routeChartIds = searchParams.get('chartIds')?.split(',').filter(Boolean) || [];
      if (routeChartIds.length)
        setSelectedCharts(
          routeChartIds.filter((id) => chartPage.items.some((item) => item.id === id)),
        );
    } catch {
      setCategories([]);
      setCharts([]);
      setSessions([]);
    } finally {
      setLoading(false);
    }
  }, [searchParams]);

  useEffect(() => {
    void loadBase();
  }, [loadBase]);

  const selectSession = async (id: string) => {
    setActiveSessionId(id);
    try {
      const session = await spectrumApi.session(id);
      setMessages(session.messages);
    } catch {
      setMessages([]);
    }
  };

  const createConversation = async () => {
    try {
      const session = await spectrumApi.createSession();
      setSessions((current) => [session, ...current]);
      setActiveSessionId(session.id);
      setMessages([]);
      setQuestion('');
    } catch {
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
      if (activeSessionId === item.id) {
        setActiveSessionId(undefined);
        setMessages([]);
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
  const selectedChartItems = useMemo(
    () => charts.filter((item) => selectedCharts.includes(item.id)),
    [charts, selectedCharts],
  );

  const scopeContent = (
    <div className="spectrum-chat-scope-content">
      <Input
        allowClear
        prefix={<SearchOutlined />}
        placeholder="搜索图谱、样品、批号"
        value={scopeKeyword}
        onChange={(event) => setScopeKeyword(event.target.value)}
      />
      <Typography.Text type="secondary">
        已选择 {selectedCharts.length} 张图谱（可多选）
      </Typography.Text>
      {loading ? (
        <Spin />
      ) : chartsByCategory.length ? (
        <Collapse
          ghost
          items={chartsByCategory.map(({ category, charts: categoryCharts }) => ({
            key: category.id,
            label: (
              <Space>
                <LineChartOutlined />
                {category.name}
                <Tag>{categoryCharts.length}</Tag>
              </Space>
            ),
            children: (
              <Space direction="vertical" style={{ width: '100%' }}>
                {categoryCharts.map((chart) => (
                  <div key={chart.id} className="spectrum-chat-chart-option">
                    <Checkbox
                      checked={selectedCharts.includes(chart.id)}
                      onChange={(event) =>
                        setSelectedCharts((current) =>
                          event.target.checked
                            ? [...current, chart.id]
                            : current.filter((id) => id !== chart.id),
                        )
                      }
                    >
                      <Typography.Text ellipsis={{ tooltip: chart.title }}>
                        {chart.title}
                      </Typography.Text>
                    </Checkbox>
                    <Button type="link" size="small" onClick={() => void openPagePicker(chart)}>
                      选页
                    </Button>
                  </div>
                ))}
              </Space>
            ),
          }))}
        />
      ) : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可选图谱，请先上传" />
      )}
    </div>
  );

  const composerTopContent = selectedChartItems.length ? (
    <div className="spectrum-composer-selection" aria-label="已选择的图谱">
      <Typography.Text className="spectrum-composer-selection-label">
        本次分析图谱
      </Typography.Text>
      <div className="spectrum-composer-selection-list">
        {selectedChartItems.map((item) => (
          <Tag
            key={item.id}
            closable
            onClose={() =>
              setSelectedCharts((current) => current.filter((id) => id !== item.id))
            }
            title={item.title}
          >
            {item.title}
          </Tag>
        ))}
      </div>
      <Typography.Text type="secondary" className="spectrum-composer-selection-count">
        {selectedChartItems.length} 张
      </Typography.Text>
    </div>
  ) : null;

  const descriptor = (chart: SpectrumChart): FilePreviewDescriptor => ({
    fileName: chart.originalName,
    contentType: chart.contentType,
    size: chart.size,
    load: () => spectrumApi.contentBlob(chart.id),
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
  const conversationMessages: ConversationMessage[] = messages.map((item) => ({
    id: item.id,
    role: item.role,
    pending:
      item.role === 'ASSISTANT' &&
      streaming &&
      !item.content &&
      !Object.keys(item.result || {}).length,
    content:
      item.role === 'ASSISTANT' ? (
        Object.keys(item.result || {}).length ? (
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
        ) : null
      ) : (
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
          {item.content}
        </Typography.Paragraph>
      ),
  }));

  const submit = async () => {
    if (!selectedCharts.length) return;
    if (!question.trim()) return;
    setStreaming(true);
    setStreamStage('正在读取图谱文件');
    const optimisticUser: SpectrumMessage = {
      id: `local-user-${Date.now()}`,
      role: 'USER',
      content: question.trim(),
      citations: [],
      result: {},
      warnings: [],
      createdAt: new Date().toISOString(),
    };
    const optimisticAssistant: SpectrumMessage = {
      id: `local-assistant-${Date.now()}`,
      role: 'ASSISTANT',
      content: '',
      citations: [],
      result: {},
      warnings: [],
      createdAt: new Date().toISOString(),
    };
    setMessages((current) => [...current, optimisticUser, optimisticAssistant]);
    const scenarioTemplate = competitorQuestion.test(question)
      ? 'COMPETITOR_DECOMPOSITION'
      : undefined;
    try {
      const submitted = await spectrumApi.submitChat({
        sessionId: activeSessionId,
        question,
        chartIds: selectedCharts,
        pageSelections,
        scenarioTemplate,
      });
      setActiveSessionId(submitted.sessionId);
      setQuestion('');
      try {
        await spectrumApi.streamAnalysis(submitted.analysisRunId, (event, data) => {
          if ((event === 'status' || event === 'snapshot') && data && typeof data === 'object') {
            const status = data as { stage?: unknown; progress?: unknown };
            if (typeof status.stage === 'string' && status.stage) setStreamStage(status.stage);
            else if (typeof status.progress === 'number')
              setStreamStage(`正在处理图谱（${status.progress}%）`);
          }
          if (event === 'error' && data && typeof data === 'object') {
            const error = data as { message?: unknown };
            if (typeof error.message === 'string') setStreamStage(error.message);
          }
        });
      } catch {
        setStreamStage('实时进度连接中断，正在恢复任务状态');
      }
      let analysis = await spectrumApi.analysis(submitted.analysisRunId);
      for (
        let attempt = 0;
        attempt < 80 && !['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(analysis.status);
        attempt += 1
      ) {
        await wait(1000);
        analysis = await spectrumApi.analysis(submitted.analysisRunId);
      }
      if (['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(analysis.status)) {
        const session = await spectrumApi.session(submitted.sessionId);
        setMessages(session.messages);
        setSessions(await spectrumApi.sessions());
        window.setTimeout(() => {
          void spectrumApi.sessions().then(setSessions).catch(() => undefined);
        }, 1200);
        if (analysis.status === 'FAILED') setQuestion('');
      }
    } catch (error) {
      setMessages((current) =>
        current
          .filter((item) => item.id !== optimisticAssistant.id)
          .concat({
            ...optimisticUser,
            content: `提交失败：${error instanceof Error ? error.message : '图谱分析任务提交失败'}`,
          }),
      );
    } finally {
      setStreaming(false);
      setStreamStage('正在读取图谱文件');
    }
  };

  const quickQuestions = [
    '请先总结这些图谱的主要观察结果、依据和不确定性。',
    '竞品图谱中的哪些峰可能对应这些单峰参考图谱？',
    '哪些峰可能是多个成分叠加形成的？哪些峰没有可靠参考证据？',
    '当前图片是否足以支持明确归因？请给出建议验证实验。',
  ];

  return (
    <>
      <AiConversationWorkspace
        conversations={conversationItems}
        activeConversationId={activeSessionId}
        messages={conversationMessages}
        scopeContent={scopeContent}
        composerTopContent={composerTopContent}
        scopeTitle="选择参与分析的图谱"
        welcomeTitle="从图谱开始提问"
        welcomeDescription="选择左侧图谱，输入问题后开始视觉观察和受控解释。"
        scopeSummary={
          <Typography.Text type="secondary">
            图谱只作为视觉证据输入。AI
            解释可能的特征峰、基团、候选成分、冲突点和验证建议，不直接给出确定配方。
          </Typography.Text>
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
        loading={loading}
        streaming={streaming}
        onNewConversation={() => void createConversation()}
        onSelectConversation={(id) => void selectSession(id)}
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
    </>
  );
}
