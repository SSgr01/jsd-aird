import type { EvidenceView } from '@/services/evidence/adapters';
import { canonicalSourceJson } from '@/services/evidence/identity';
import { App, Alert, Button, Drawer, Input, Select, Spin, Typography } from 'antd';
import { ConversationFeedback } from './ConversationFeedback';
import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { httpClient } from '@/services/http/client';
import type { SourceRef, SourceQuote } from '@/services/evidence/protocol';
import { readableExcerpt, sourceLabel, sourcePage } from '@/services/evidence/adapters';
import {
  knowledgeApi,
  type PublishedContent,
  type SourceAnchor,
} from '@/services/knowledge/knowledge-api';
import { agentApi } from '@/services/agents/agent-api';
import { OriginalDocumentViewer } from '@/components/knowledge-document/OriginalDocumentViewer';
import './conversation-reading.css';

/** Mount with a source key. No cached text is rendered until the current access check succeeds. */
export function AuthorizedEvidence({
  source,
  children,
}: {
  source: SourceRef;
  children: ReactNode;
}) {
  const key = canonicalSourceJson(source);
  const [access, setAccess] = useState<{ key: string; status: 'allowed' | 'denied' }>();
  useEffect(() => {
    let current = true;
    setAccess(undefined);
    void httpClient
      .post('/api/v1/assistant/sources/authorize', { sources: [JSON.parse(key)] })
      .then(
        () => {
          if (current) setAccess({ key, status: 'allowed' });
        },
        () => {
          if (current) setAccess({ key, status: 'denied' });
        },
      );
    return () => {
      current = false;
    };
  }, [key]);
  if (!access || access.key !== key)
    return <ConversationFeedback kind="loading" message="正在核对来源访问权限" />;
  if (access.status === 'denied')
    return <ConversationFeedback kind="error" message="来源暂不可访问，权限或资料版本可能已变化" />;
  return <>{children}</>;
}

export function EvidenceDetails({
  source,
  excerpt,
  quote,
}: {
  source: SourceRef;
  excerpt?: string;
  quote?: SourceQuote;
}) {
  const exact =
    quote && canonicalSourceJson(quote.source) === canonicalSourceJson(source) ? quote : undefined;
  return (
    <section aria-label="原文段落" className="evidence-passage">
      {exact && (
        <section aria-label="引用原文">
          <Typography.Text type="secondary">引用原文</Typography.Text>
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
            {exact.text}
          </Typography.Paragraph>
        </section>
      )}
      {!exact && excerpt && (
        <>
          <Typography.Text type="secondary">相关段落</Typography.Text>
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
            {readableExcerpt(excerpt)}
          </Typography.Paragraph>
        </>
      )}
    </section>
  );
}

export function EvidenceViewDetails({ evidence }: { evidence: EvidenceView }) {
  return evidence.source ? (
    <EvidenceDetails source={evidence.source} excerpt={evidence.excerpt} quote={evidence.quote} />
  ) : (
    <Typography.Text>{evidence.title}</Typography.Text>
  );
}

export function EvidenceDetailsButton({ evidence }: { evidence: EvidenceView }) {
  const [open, setOpen] = useState(false);
  const source = evidence.source;
  if (!source) return null;
  return (
    <>
      <Button type="text" size="small" onClick={() => setOpen(true)}>
        预览
      </Button>
      <Drawer
        className="conversation-reader-drawer"
        width="min(820px, 100vw)"
        closable={false}
        open={open}
        onClose={() => setOpen(false)}
        destroyOnClose
      >
        {open && <EvidenceReader evidence={evidence} onClose={() => setOpen(false)} />}
      </Drawer>
    </>
  );
}

/** Shared by quick answers and research. Authorization encloses all cached text and file previews. */
export function EvidenceReader({
  evidence,
  onClose,
  onAnnotate,
}: {
  evidence: EvidenceView;
  onClose: () => void;
  onAnnotate?: (text: string, source: SourceRef) => Promise<void>;
}) {
  const [passage, setPassage] = useState(0);
  const selected = evidence.passages?.[passage] || evidence;
  const source = selected.source;
  const page = sourcePage(source);
  return (
    <section className="conversation-source-reader" aria-label="原文阅读区">
      <header className="conversation-reader-toolbar">
        <strong>资料预览</strong>
        <Button type="text" onClick={onClose}>
          返回对话
        </Button>
      </header>
      <div className="conversation-reader-content">
        <h2>{evidence.title}</h2>
        {source && (
          <p className="conversation-reader-meta">
            {sourceLabel(source)}
            {page ? ` · 第${page}页` : ''}
          </p>
        )}
        {(evidence.passages?.length || 0) > 1 && (
          <Select
            aria-label="选择引用段落"
            value={passage}
            onChange={setPassage}
            options={evidence.passages!.map((item, index) => ({
              value: index,
              label: `${sourcePage(item.source) ? `第${sourcePage(item.source)}页 · ` : ''}段落 ${index + 1}`,
            }))}
          />
        )}
        {source ? (
          <AuthorizedEvidence key={canonicalSourceJson(source)} source={source}>
            <EvidenceDetails source={source} excerpt={selected.excerpt} quote={selected.quote} />
            <SourceDocument
              evidence={{ ...selected, original: evidence.original }}
              onAnnotate={onAnnotate}
            />
          </AuthorizedEvidence>
        ) : evidence.original ? (
          <SourceDocument evidence={evidence} />
        ) : (
          <Alert type="info" message="暂无可阅读的原文，请打开来源页面查看。" />
        )}
      </div>
    </section>
  );
}

function SourceDocument({
  evidence,
  onAnnotate,
}: {
  evidence: EvidenceView;
  onAnnotate?: (text: string, source: SourceRef) => Promise<void>;
}) {
  const { message } = App.useApp();
  const { source, original } = evidence;
  const [published, setPublished] = useState<PublishedContent>();
  const [error, setError] = useState(false);
  const [annotation, setAnnotation] = useState('');
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (source?.kind !== 'KB_PUBLICATION' || !source.publication_id) return;
    let current = true;
    setError(false);
    setPublished(undefined);
    void knowledgeApi
      .publishedContent(source.document_id, source.publication_id)
      .then((value) => {
        if (current) setPublished(value);
      })
      .catch(() => {
        if (current) setError(true);
      });
    return () => {
      current = false;
    };
  }, [source?.kind, source?.document_id, source?.publication_id]);
  const privateFile = source && ['RUN_UPLOAD', 'EXTERNAL_FULLTEXT'].includes(source.kind);
  const url =
    published && source
      ? knowledgeApi.contentUrl(source.document_id, published.publication.versionId)
      : privateFile
        ? agentApi.privateSourceUrl(source.document_id)
        : original?.downloadUrl;
  const load = useCallback(async () => {
    if (published && source)
      return knowledgeApi.contentBlob(source.document_id, published.publication.versionId);
    if (privateFile && url) {
      const response = await fetch(url, { credentials: 'include' });
      if (!response.ok) throw new Error('原文件加载失败或访问权限已变化');
      return response.blob();
    }
    if (original) return original.load();
    throw new Error('原文件暂不可用');
  }, [published, source, privateFile, url, original]);
  const anchoredKeys = source?.anchor?.sourceNodeKeys;
  const selectedKey = published?.sourceNodes.some(
    (node) => node.sourceNodeKey === source?.source_node_key,
  )
    ? source?.source_node_key
    : Array.isArray(anchoredKeys) && typeof anchoredKeys[0] === 'string'
      ? anchoredKeys[0]
      : source?.source_node_key;
  const sourceNodes =
    published?.sourceNodes ||
    (source
      ? [
          {
            sourceNodeKey: selectedKey!,
            nodeNo: 0,
            nodeType: 'paragraph',
            rawText: evidence.excerpt || '',
            sourceAnchor: source.anchor as unknown as SourceAnchor,
            confidence: {},
          },
        ]
      : []);
  return (
    <>
      {source?.kind === 'KB_PUBLICATION' && !published && !error && (
        <Spin aria-label="正在加载原文件" />
      )}
      {error && <Alert type="error" message="原文件加载失败或访问权限已变化" />}
      {(published || privateFile || original) && !error && (
        <>
          {url && (
            <Button className="conversation-original-download" href={url}>
              下载原文件
            </Button>
          )}
          <div className="conversation-original-document">
            <OriginalDocumentViewer
              fileName={published?.originalName || original?.fileName || evidence.title}
              contentType={
                published?.contentType ||
                original?.contentType ||
                ((privateFile && !/\.(docx|xlsx|csv|md|txt)$/i.test(evidence.title)) ||
                evidence.title.toLowerCase().endsWith('.pdf')
                  ? 'application/pdf'
                  : 'application/octet-stream')
              }
              load={load}
              contentUrl={url}
              sourceNodes={sourceNodes}
              selectedSourceNodeKey={selectedKey}
            />
          </div>
        </>
      )}
      {source?.kind === 'WEB_RESULT' && source.url && /^https?:\/\//.test(source.url) && (
        <Button href={source.url} target="_blank" rel="noreferrer">
          打开原网页
        </Button>
      )}
      {onAnnotate && source?.source_node_key && source.content_sha256 && (
        <div className="conversation-annotation">
          <label htmlFor="source-annotation">我的批注</label>
          <Input.TextArea
            id="source-annotation"
            value={annotation}
            onChange={(event) => setAnnotation(event.target.value)}
            maxLength={6000}
          />
          <Button
            disabled={!annotation.trim()}
            loading={saving}
            onClick={() => {
              setSaving(true);
              void onAnnotate(annotation, source)
                .then(() => {
                  setAnnotation('');
                  void message.success('批注已保存');
                })
                .catch(() => void message.error('批注保存失败，请重试'))
                .finally(() => setSaving(false));
            }}
          >
            保存批注
          </Button>
        </div>
      )}
    </>
  );
}
