import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { DataIssue } from '@/services/data/data-api';
import { DataImportQualityNotice } from './DataImportQualityNotice';

describe('required import reminders', () => {
  it('does not use a field code as the fallback when source coordinates are missing', () => {
    const fieldCode = 'INSTANCE_LOCAL.test.FIELD_123';
    render(<DataImportQualityNotice issues={[{ issueType: 'REQUIRED_MISSING', severity: 'WARNING', status: 'OPEN', fieldCode, detail: {} } as DataIssue]} sheets={[]} />);
    expect(screen.getByText('待补充字段')).toBeInTheDocument();
    expect(screen.queryByText(fieldCode)).not.toBeInTheDocument();
  });
  it('distinguishes saving imported data from submitting an experiment for review', () => {
    const issue = { issueType: 'REQUIRED_MISSING', severity: 'WARNING', status: 'OPEN', sheetId: 'one', address: 'D2', fieldCode: 'date', detail: {} } as DataIssue;
    render(<DataImportQualityNotice issues={[issue]} sheets={[]} experimentDraft />);
    expect(screen.getByText('1 处必填字段未填写，可保存导入；原资料未记录的题目、实验人或日期将保留缺项说明，无需补造。')).toBeTruthy();
    expect(screen.queryByText(/不影响提交/)).toBeNull();
  });
  it('shows a compact unique-cell reminder instead of an error card for older blockers', () => {
    const issue = { issueType: 'REQUIRED_MISSING', severity: 'BLOCKER', status: 'OPEN', sheetId: 'one', address: 'B2', fieldCode: 'name', detail: {} } as DataIssue;
    render(<DataImportQualityNotice issues={[issue, { ...issue, id: 'other-record' }]} sheets={[]} onLocate={() => undefined} />);
    expect(screen.getByText('1 处必填字段未填写，可稍后补充，不影响提交。')).toBeTruthy();
    expect(screen.queryByRole('alert')).toBeNull();
    expect(screen.getAllByRole('button', { hidden: true })).toHaveLength(1);
    expect(screen.queryByText('查看问题和来源位置')).toBeNull();
  });

  it('collapses hundreds of missing fields and limits each page to twenty locations', () => {
    const issues = Array.from({ length: 501 }, (_, index) => ({
      issueType: 'REQUIRED_MISSING', severity: 'WARNING', status: 'OPEN',
      sheetId: `sheet-${index}`, address: 'B1', fieldCode: 'title', detail: {},
    } as DataIssue));
    const { container } = render(<DataImportQualityNotice issues={issues} sheets={[]} onLocate={() => undefined} />);
    expect(container.querySelector('details')).not.toHaveAttribute('open');
    expect(container.querySelectorAll('button.ant-btn-link')).toHaveLength(20);
    expect(screen.getByText('查看缺失字段和来源位置（501 处）')).toBeTruthy();
  });

  it('keeps blockers visible while leaving the long source list collapsed', () => {
    const issues = Array.from({ length: 9 }, (_, index) => ({
      id: `issue-${index}`, issueType: 'SHEET_STRUCTURE_UNSUPPORTED', severity: 'BLOCKER',
      status: 'OPEN', sheetId: `sheet-${index}`, message: '无法可靠读取该页', detail: {},
    } as DataIssue));
    const locate = vi.fn();
    const { container } = render(<DataImportQualityNotice issues={issues} sheets={[]} onLocate={locate} />);
    expect(screen.getByRole('alert')).toHaveTextContent('9 项问题会阻止提交');
    expect(container.querySelector('details')).not.toHaveAttribute('open');
    expect(container.querySelectorAll('li')).toHaveLength(9);
    expect(screen.getByText('查看问题和来源位置')).toBeTruthy();
  });
});
