import { reviewFixture } from './__tests__/review-fixture';
import { acknowledgeKnowledgeReview, knowledgeReviewSaveLabel } from './knowledge-review-state';

it.each([
  ['DRAFT', undefined, 'saved', '已保存'], ['DRAFT', 'SUBMITTED', 'saved', '待审核'],
  ['DRAFT', 'APPROVED', 'saved', '审核通过'], ['DRAFT', undefined, 'offline', '尚未保存'],
  ['DRAFT', undefined, 'conflict', '保存冲突'], ['BUILDING', 'APPROVED', 'saved', '发布处理中'],
  ['FAILED', 'APPROVED', 'saved', '发布失败'], ['PUBLISHED', 'APPROVED', 'saved', '已发布'],
  ['SUPERSEDED', 'APPROVED', 'saved', '已被新版本替代'],
] as const)('distinguishes %s / %s / %s from publication', (status, approval, save, expected) => {
  const review = reviewFixture(); review.reviewRevision!.status = status;
  expect(knowledgeReviewSaveLabel(review, approval, save)).toBe(expected);
});

it('preserves all edits made during a save while adopting the new server revision', () => {
  const base = reviewFixture();
  const local = { ...base, title: '透明耐磨涂层', libraryScope: 'EXTERNAL' as const, categoryId: 'new', tags: ['耐磨'],
    reviewRevision: { ...base.reviewRevision!, confirmedDocument: { type: 'doc' as const, content: [] }, excludedReviewNodeIds: ['node'] } };
  const saved = { ...base, title: '光固化涂层（规范化）', reviewRevision: { ...base.reviewRevision!, lockVersion: 1 } };
  expect(acknowledgeKnowledgeReview(base, local, saved)).toEqual({ ...local, reviewRevision: { ...local.reviewRevision, lockVersion: 1 } });
  expect(acknowledgeKnowledgeReview(base, base, saved)).toEqual(saved);
});

it('does not apply a saved response to another revision or document', () => {
  const base = reviewFixture(); const local = { ...base, documentId: 'two' };
  expect(acknowledgeKnowledgeReview(base, local, base)).toBe(local);
});
