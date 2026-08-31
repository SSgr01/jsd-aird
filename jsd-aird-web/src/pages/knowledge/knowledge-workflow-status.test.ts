import { describe, expect, it } from 'vitest';

import { documentWorkflowStatus, reviewQueueStatus } from './knowledge-workflow-status';

describe('knowledge workflow status', () => {
  it.each([
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'DRAFT' }, '待校对'],
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'BUILDING' }, '发布处理中'],
    [{ status: 'READY', reviewStatus: 'PENDING_REVIEW', reviewRevisionStatus: 'FAILED' }, '发布失败'],
    [{ status: 'READY', reviewStatus: 'PUBLISHED', reviewRevisionStatus: 'PUBLISHED' }, '已发布'],
    [{ status: 'READY', reviewStatus: 'REJECTED', reviewRevisionStatus: 'FAILED' }, '已驳回'],
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
