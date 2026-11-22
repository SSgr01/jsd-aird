import { describe, expect, it } from 'vitest';

import { documentWorkflowStatus, reviewQueueStatus } from './knowledge-workflow-status';

describe('knowledge workflow status', () => {
  it.each([
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'DRAFT' }, '待校对'],
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'BUILDING' }, '发布处理中'],
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'FAILED' }, '发布失败'],
    [{ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'PUBLISHED' }, '已发布'],
    [{ status: 'READY', reviewStatus: 'REJECTED', reviewRevisionStatus: 'FAILED' }, '已驳回'],
    [{ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'BUILDING' }, '发布处理中'],
    [{ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'FAILED' }, '发布失败'],
    [{ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'DRAFT' }, '已发布 · 有修订草稿'],
  ] as const)('maps %o to %s', (input, expected) => {
    expect(documentWorkflowStatus(input)).toMatchObject({ label: expected });
  });

  it('does not describe an already published READY document as pending review', () => {
    expect(documentWorkflowStatus({ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'PUBLISHED' }).label)
      .toBe('已发布');
  });

  it('shows a failed publication distinctly in the review queue', () => {
    expect(reviewQueueStatus({ reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'FAILED' }).label)
      .toBe('发布失败');
  });
});


describe('live parsing status', () => {
  const item = { status: 'PROCESSING', reviewStatus: 'PENDING_REVIEW' } as const;
  const processing = { documentId: 'doc', versionId: 'version', documentStatus: 'PROCESSING', progress: 5, attemptCount: 3, maxAttempts: 5, terminal: false, lastError: 'timeout' } as const;
  it('shows retry backoff instead of the stale document status', () => {
    expect(documentWorkflowStatus(item, { ...processing, jobStatus: 'READY' }).label).toBe('等待重试');
  });
  it('shows terminal failure even when the document still says processing', () => {
    expect(documentWorkflowStatus(item, { ...processing, jobStatus: 'FAILED', terminal: true }).label).toBe('解析失败');
  });
  it.each([['pending', '远端排队中'], ['running', '远端处理中'], ['done', '正在处理解析结果']])('shows remote state %s', (providerState, label) => {
    expect(documentWorkflowStatus(item, { ...processing, jobStatus: 'RUNNING', providerState }).label).toBe(label);
  });
});
