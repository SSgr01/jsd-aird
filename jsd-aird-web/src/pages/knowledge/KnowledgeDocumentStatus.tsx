import { Button } from 'antd';
import { InlineNotice } from '@/components/inline-notice/InlineNotice';
import type { KnowledgeDocument, KnowledgeProcessing, KnowledgeReview } from '@/services/knowledge';
import { documentWorkflowStatus } from './knowledge-workflow-status';

export function KnowledgeDocumentStatus({
  item,
  processing,
  review,
  hasPublication,
  pendingNewVersion,
  canRetry,
  retrying,
  onRetry,
}: {
  item: Pick<
    KnowledgeDocument,
    'status' | 'reviewStatus' | 'reviewRevisionStatus' | 'lifecycleStatus' | 'parseError'
  >;
  processing?: KnowledgeProcessing;
  review?: KnowledgeReview;
  hasPublication: boolean;
  pendingNewVersion: boolean;
  canRetry: boolean;
  retrying: boolean;
  onRetry: () => void;
}) {
  const revision = review?.reviewRevision;
  const parsing = item.status !== 'READY';
  const workflow = documentWorkflowStatus(item, processing);
  const failed = parsing && ['red', 'error'].includes(workflow.color);
  const retryAvailable = failed && Boolean(processing?.terminal) && canRetry;
  let message: string | undefined;
  let tone: 'info' | 'warning' | 'error' = 'info';
  let busy = false;

  if (item.lifecycleStatus === 'DISABLED') {
    message = '此文档已停用，不参与检索。';
    tone = 'warning';
  } else if (revision?.status === 'FAILED') {
    message = '发布未完成，请进入校对页重试。';
    tone = 'error';
  } else if (parsing) {
    tone = failed ? 'error' : workflow.color === 'orange' ? 'warning' : 'info';
    busy = !failed && workflow.color !== 'orange';
    message = failed
      ? '文件解析未完成，请重新解析或稍后重试。'
      : workflow.label === '等待重试'
        ? '文件暂未处理完成，系统将自动重试。'
        : item.status === 'PENDING_PROVIDER'
          ? '文件处理暂不可用，请稍后重试。'
          : item.status === 'QUEUED' || processing?.providerState === 'pending'
            ? '文件正在排队，处理完成后可校对内容。'
            : '正在处理文件，完成后可校对内容。';
    if (hasPublication) message += ' 已发布内容仍可查看和检索。';
  } else if (revision?.status === 'BUILDING') {
    message = '正在发布，完成后页面会自动更新。';
    busy = true;
  } else if (pendingNewVersion) {
    message = hasPublication
      ? '新版本待校对，当前展示已发布内容。'
      : '文件已处理完成，校对并发布后即可检索。';
  } else if (review?.parseRun?.parserMode === 'AGENT_FALLBACK') {
    message = '请重点核对表格、图片和字段对应关系。';
    tone = 'warning';
  }

  if (!message) return null;
  if (!parsing && pendingNewVersion && review?.parseRun?.parserMode === 'AGENT_FALLBACK') {
    message += ' 请重点核对表格、图片和字段对应关系。';
  }
  const errors = [
    ...new Set(
      [
        processing?.documentError,
        processing?.parseRunError,
        processing?.lastError,
        item.parseError,
        revision?.failureReason,
      ].filter(Boolean),
    ),
  ];
  const details =
    errors.length > 0 || processing?.providerTaskId ? (
      <>
        {errors.map((error) => (
          <p key={error}>{error}</p>
        ))}
        {processing && (
          <p>
            尝试次数：{processing.attemptCount} / {processing.maxAttempts}
          </p>
        )}
        {processing?.nextAttemptAt && (
          <p>下次尝试：{new Date(processing.nextAttemptAt).toLocaleString('zh-CN')}</p>
        )}
        {processing?.providerTaskId && <p>处理编号：{processing.providerTaskId}</p>}
      </>
    ) : undefined;
  return (
    <InlineNotice
      tone={tone}
      busy={busy}
      details={details}
      action={
        retryAvailable ? (
          <Button size="small" loading={retrying} onClick={onRetry}>
            重新解析
          </Button>
        ) : undefined
      }
    >
      {message}
    </InlineNotice>
  );
}
