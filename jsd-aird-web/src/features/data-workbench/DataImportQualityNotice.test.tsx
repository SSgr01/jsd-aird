import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DataIssue } from '@/services/data/data-api';
import { DataImportQualityNotice } from './DataImportQualityNotice';

describe('required import reminders', () => {
  it('shows a compact unique-cell reminder instead of an error card for older blockers', () => {
    const issue = { issueType: 'REQUIRED_MISSING', severity: 'BLOCKER', status: 'OPEN', sheetId: 'one', address: 'B2', fieldCode: 'name', detail: {} } as DataIssue;
    render(<DataImportQualityNotice issues={[issue, { ...issue, id: 'other-record' }]} sheets={[]} onLocate={() => undefined} />);
    expect(screen.getByText('1 处必填字段未填写，可稍后补充，不影响提交。')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.queryByText('查看问题和来源位置')).toBeNull();
  });
});
