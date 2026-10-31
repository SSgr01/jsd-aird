import { Alert, Button, Empty, Select, Space, Spin, Tabs } from 'antd';
import { CloseOutlined } from '@ant-design/icons';
import { useLayoutEffect, useRef, useState, type MouseEvent } from 'react';
import { MarkdownContent } from '@/components/markdown/MarkdownContent';
import {
  agentApi,
  citedMarkdown,
  type Candidate,
  type LiteratureReport,
  type SourceRef,
} from '@/services/agents/agent-api';
import { ResearchQualityPanel } from './ResearchQualityPanel';
import { reportAssessment } from './research-presentation';

export function ResearchReportReader({
  report,
  reports,
  loading,
  error,
  onRetry,
  onClose,
  onSelect,
  onSource,
  onCitation,
}: {
  report?: LiteratureReport;
  reports: LiteratureReport[];
  loading: boolean;
  error: boolean;
  onRetry: () => void;
  onClose: () => void;
  onSelect: (id: string) => void;
  onSource: (source: SourceRef) => void;
  onCitation: (event: MouseEvent<HTMLElement>, evidence: Record<string, Candidate>) => void;
}) {
  const [tab, setTab] = useState('body');
  const [outline, setOutline] = useState<Array<{ text: string; element: HTMLElement }>>([]);
  const body = useRef<HTMLDivElement>(null);
  const assessment = reportAssessment(report);
  const textLines = (report?.text || '').replace(/\r\n?/g, '\n').split('\n');
  // The reader already shows the report title. Do not render the same heading twice.
  const duplicateTitle = /^#\s+(.+?)\s*$/.exec(textLines[0] || '')?.[1] === report?.title;
  const displayText = duplicateTitle ? textLines.slice(1).join('\n') : report?.text || '';
  useLayoutEffect(() => {
    setTab('body');
  }, [report?.id]);
  useLayoutEffect(() => {
    if (tab !== 'body') return;
    const headings = Array.from(body.current?.querySelectorAll<HTMLElement>('h1,h2,h3') || []);
    setOutline(headings.map((element) => ({ text: element.textContent || '', element })));
    const scroll = body.current?.closest('.research-report-reader-content');
    if (scroll) scroll.scrollTop = 0;
  }, [report?.id, report?.text, tab]);
  // Count cited documents, not chunks. Two publications of one document remain distinguishable.
  const sources = [
    ...new Map(
      Object.values(report?.evidence || {}).map((item) => [
        JSON.stringify([
          item.source.kind,
          item.source.document_id,
          item.source.publication_id,
          item.source.version_id,
          item.source.url,
          item.source.resource_id,
        ]),
        item,
      ]),
    ).values(),
  ];
  return (
    <aside className="research-report-reader" aria-label="报告阅读区">
      <header className="research-report-reader-toolbar">
        <strong>研究报告</strong>
        <Button type="text" icon={<CloseOutlined />} aria-label="关闭报告阅读区" onClick={onClose}>
          返回对话
        </Button>
      </header>
      {loading && !report ? (
        <div className="research-reader-state" role="status">
          <Spin /> 正在加载已保存报告…
        </div>
      ) : error ? (
        <div className="research-reader-state">
          <Alert
            type="error"
            message="报告加载失败，或访问权限已变化"
            action={<Button onClick={onRetry}>重试</Button>}
          />
        </div>
      ) : !report ? (
        <div className="research-reader-state">
          <Empty description="这份报告暂时不可用，请重新加载或从保存历史中选择。" />
          <Button onClick={onRetry}>重新加载报告</Button>
        </div>
      ) : (
        <>
          <div className="research-report-reader-heading">
            <h2 title={report.title}>{report.title}</h2>
            <div className="research-report-reader-meta">
              <span>报告草稿 · 保存修订 {report.revision}</span>
              <span>{assessment.label}</span>
            </div>
            <p className="research-report-notice" role="note">
              {assessment.detail}
            </p>
            <div className="research-report-reader-actions">
              {reports.length > 1 && (
                <Select
                  aria-label="切换已保存报告"
                  value={report.id}
                  onChange={(id) => onSelect(id)}
                  popupMatchSelectWidth={false}
                  options={reports.map((item) => ({ value: item.id, label: item.title }))}
                />
              )}
              <Button href={agentApi.wordUrl(report.id)}>
                {assessment.passed ? '下载 Word' : '下载 Word 草稿'}
              </Button>
            </div>
          </div>
          <Tabs
            className="research-report-reader-tabs"
            activeKey={tab}
            onChange={setTab}
            items={[
              { key: 'body', label: '正文' },
              { key: 'sources', label: `来源（${sources.length}）` },
              { key: 'quality', label: '证据与核查' },
            ]}
          />
          <div className="research-report-reader-content">
            {tab === 'body' && (
              <>
                {!!outline.length && (
                  <details className="research-report-outline">
                    <summary>报告目录</summary>
                    <nav aria-label="报告目录">
                      {outline.map((heading, index) => (
                        <button
                          key={index}
                          onClick={() =>
                            heading.element.scrollIntoView?.({ block: 'start', behavior: 'auto' })
                          }
                        >
                          {heading.text}
                        </button>
                      ))}
                    </nav>
                  </details>
                )}
                <article
                  ref={body}
                  className="research-report-body"
                  aria-label="报告正文"
                  onClick={(event) => onCitation(event, report.evidence)}
                >
                  <MarkdownContent value={citedMarkdown(displayText)} />
                </article>
              </>
            )}
            {tab === 'sources' && (
              <section aria-label="报告来源">
                <p className="harness-muted">
                  点击查看固定版本原文。正文中的引用可直接定位到采用的片段。
                </p>
                {!sources.length && (
                  <Empty description="此报告未登记来源，请勿作为有原文依据的结论使用。" />
                )}
                {sources.map((item) => (
                  <div key={item.evidence_id} className="research-report-source">
                    <strong>{item.source.title}</strong>
                    <Space>
                      <span className="harness-muted">
                        {item.source.kind === 'KB_PUBLICATION'
                          ? '知识库固定发布版本'
                          : '读取时来源快照'}
                      </span>
                      <Button onClick={() => onSource(item.source)}>查看原文</Button>
                    </Space>
                  </div>
                ))}
              </section>
            )}
            {tab === 'quality' && (
              <ResearchQualityPanel
                frozen
                matrix={report.evidence_matrix}
                review={report.quality_review}
                onQuote={(quote) => onSource(quote.source)}
              />
            )}
          </div>
        </>
      )}
    </aside>
  );
}
