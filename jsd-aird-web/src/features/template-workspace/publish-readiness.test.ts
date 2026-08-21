import { describe, expect, it } from 'vitest';

import { publishReadinessBlocker } from './publish-readiness';

describe('publish readiness', () => {
  it('requires a saved draft before publishing', () => {
    expect(publishReadinessBlocker('DIRTY', 'FRESH')).toBe('请先保存当前修改，再发布模板');
    expect(publishReadinessBlocker('SAVING', 'FRESH')).toBe('请先保存当前修改，再发布模板');
  });

  it('requires a fresh review after a successful save', () => {
    expect(publishReadinessBlocker('SAVED', 'REFRESHING')).toBe('正在刷新识别审核状态，请稍候');
    expect(publishReadinessBlocker('SAVED', 'STALE')).toBe('审核状态需要刷新后才能发布');
    expect(publishReadinessBlocker('SAVED', 'FRESH')).toBeUndefined();
  });

  it('aligns the publish state with template review status', () => {
    expect(publishReadinessBlocker('SAVED', 'FRESH', 'NOT_SUBMITTED')).toBe('请先提交模板审核，通过后才能发布');
    expect(publishReadinessBlocker('SAVED', 'FRESH', 'SUBMITTED')).toBe('模板正在审核中，通过后才能发布');
    expect(publishReadinessBlocker('SAVED', 'FRESH', 'REJECTED')).toBe('模板审核未通过，请修改后重新提交审核');
    expect(publishReadinessBlocker('SAVED', 'FRESH', 'APPROVED')).toBeUndefined();
  });
});
