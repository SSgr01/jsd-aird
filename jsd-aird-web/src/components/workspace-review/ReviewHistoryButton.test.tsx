import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { ReviewHistoryButton } from './ReviewHistoryButton';

it('shows review transitions and rejection comments', async () => {
  render(<ReviewHistoryButton items={[
    { action: 'REJECTED', comment: '请补充结果', operatorName: '审核人', createdAt: '2026-11-04T00:00:00Z' },
    { action: 'PENDING_REVIEW', operatorName: '提交人', createdAt: '2026-11-03T00:00:00Z' },
  ]} />);
  fireEvent.click(screen.getByRole('button', { name: /审核记录/ }));
  expect(await screen.findByText('已退回')).toBeInTheDocument();
  expect(screen.getByText('提交审核')).toBeInTheDocument();
  expect(screen.getByText('请补充结果')).toBeInTheDocument();
  expect(screen.getByText(/审核人/)).toBeInTheDocument();
});
