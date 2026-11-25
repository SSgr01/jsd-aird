import { describe, expect, it } from 'vitest';
import { linkedExperimentStatusText } from './experiment-status';

describe('linked experiment lifecycle', () => {
  it('shows the current published status', () => {
    expect(linkedExperimentStatusText([{ experimentId: 'one', status: 'COMPLETED' }])).toBe('实验：已发布');
  });
  it('summarizes mixed statuses without treating every experiment as a draft', () => {
    expect(linkedExperimentStatusText([{ experimentId: 'one', status: 'COMPLETED' }, { experimentId: 'two', status: 'DRAFT' }]))
      .toBe('已生成 2 个实验：已发布 1、草稿 1');
  });
  it('does not infer unpublished when status is unavailable', () => {
    expect(linkedExperimentStatusText()).toBe('实验状态暂不可用');
  });
});
