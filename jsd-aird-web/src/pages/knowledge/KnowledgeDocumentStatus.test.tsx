import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { KnowledgeDocumentStatus } from './KnowledgeDocumentStatus';
import type { KnowledgeProcessing } from '@/services/knowledge';

const queued = {
  status: 'PROCESSING',
  reviewStatus: 'PENDING_REVIEW',
  lifecycleStatus: 'ACTIVE',
} as const;
const process: KnowledgeProcessing = {
  documentId: 'doc',
  versionId: 'version',
  documentStatus: 'PROCESSING',
  progress: 5,
  attemptCount: 1,
  maxAttempts: 5,
  terminal: false,
  jobStatus: 'RUNNING',
  providerState: 'pending',
  providerTaskId: 'internal-batch-id',
  lastError: 'upstream timeout',
};
const defaults = {
  item: queued,
  processing: process,
  hasPublication: false,
  pendingNewVersion: true,
  canRetry: true,
  retrying: false,
  onRetry: vi.fn(),
};

describe('knowledge document notices', () => {
  it('shows one queue state without claiming an unpublished document has old published content', () => {
    const { container } = render(<KnowledgeDocumentStatus {...defaults} />);
    expect(screen.getByRole('status')).toHaveTextContent('文件正在排队');
    expect(screen.queryByText(/旧发布|当前展示已发布|新版本待校对/)).not.toBeInTheDocument();
    expect(container.querySelector('details')).not.toHaveAttribute('open');
    expect(screen.getByText('upstream timeout')).not.toBeVisible();
    fireEvent.click(screen.getByText('处理详情'));
    expect(screen.getByText('upstream timeout')).toBeVisible();
  });

  it('offers retry only for terminal failure and keeps published content availability explicit', () => {
    const retry = vi.fn();
    render(
      <KnowledgeDocumentStatus
        {...defaults}
        hasPublication
        processing={{ ...process, jobStatus: 'FAILED', terminal: true }}
        onRetry={retry}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('已发布内容仍可查看和检索');
    fireEvent.click(screen.getByRole('button', { name: '重新解析' }));
    expect(retry).toHaveBeenCalledOnce();
  });

  it('does not invite manual retry while automatic backoff is pending', () => {
    render(
      <KnowledgeDocumentStatus {...defaults} processing={{ ...process, jobStatus: 'READY' }} />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('系统将自动重试');
    expect(screen.queryByRole('button', { name: '重新解析' })).not.toBeInTheDocument();
  });
});
