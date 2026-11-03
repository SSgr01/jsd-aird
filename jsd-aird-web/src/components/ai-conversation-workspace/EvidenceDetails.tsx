import type { EvidenceView } from '@/services/evidence/adapters';
import { canonicalSourceJson } from '@/services/evidence/identity';
import { Button, Modal, Typography } from 'antd';
import { ConversationFeedback } from './ConversationFeedback';
import { useEffect, useState, type ReactNode } from 'react';
import { httpClient } from '@/services/http/client';
import type { SourceRef, SourceQuote } from '@/services/evidence/protocol';

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
  const parentSpan = Boolean(source.anchor?.parent_chunk_id &&
    typeof source.anchor.span_start === 'number' && typeof source.anchor.span_end === 'number');
  return (
    <section aria-label="来源与证据位置" style={{ overflowWrap: 'anywhere' }}>
      <Typography.Text strong>{source.title}</Typography.Text>
      <Typography.Paragraph type="secondary">
        版本：{source.publication_id || source.version_id} · 位置：{source.source_node_key}
      </Typography.Paragraph>
      {parentSpan && (
        <Typography.Paragraph type="secondary">
          父块片段 · Unicode 码点 [{String(source.anchor.span_start)}, {String(source.anchor.span_end)})
          · 完整父块哈希：{String(source.anchor.parent_content_sha256)}
        </Typography.Paragraph>
      )}
      {exact && (
        <section aria-label="精确原文跨度">
          <Typography.Text type="secondary">
            {parentSpan ? '片段内精确引文' : '精确引文'} · {exact.quote_id} · [{exact.start}, {exact.end})
          </Typography.Text>
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
            {exact.text}
          </Typography.Paragraph>
        </section>
      )}
      {!exact && excerpt && (
        <>
          <Typography.Text type="secondary">
            {parentSpan ? '父块片段预览（不代表完整父块或精确引文）' : '原文节点预览（不代表完整节点或精确引文）'}
          </Typography.Text>
          <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{excerpt}</Typography.Paragraph>
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
        证据位置
      </Button>
      <Modal
        title="来源与证据"
        open={open}
        footer={null}
        onCancel={() => setOpen(false)}
        destroyOnClose
      >
        {open && (
          <AuthorizedEvidence key={canonicalSourceJson(source)} source={source}>
            <EvidenceViewDetails evidence={evidence} />
          </AuthorizedEvidence>
        )}
      </Modal>
    </>
  );
}
