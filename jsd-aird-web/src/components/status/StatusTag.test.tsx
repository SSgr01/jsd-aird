import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusTag } from './StatusTag';
import { formatTaskStatus } from '@/services/project/project-api';

describe('business status presentation', () => {
  it('shows a blocked task in Chinese with the attention tone', () => {
    render(<StatusTag status="BLOCKED" label={formatTaskStatus('BLOCKED')} />);
    expect(screen.getByText('受阻')).toHaveClass('is-warning');
  });

  it('preserves domain-specific completion wording and shared semantics', () => {
    render(<><StatusTag status="COMPLETED" /><StatusTag status="COMPLETED" label="已发布" /></>);
    expect(screen.getByText('已完成')).toHaveClass('is-success');
    expect(screen.getByText('已发布')).toHaveClass('is-success');
  });

  it('does not render missing or unknown states as successful', () => {
    render(<><StatusTag /><StatusTag status="NEW_SERVER_STATE" /></>);
    expect(screen.getByText('—')).toHaveClass('app-status-empty');
    expect(screen.getByText('未知状态')).toHaveClass('is-neutral');
    expect(screen.getByText('未知状态')).toHaveAttribute('title', 'NEW_SERVER_STATE');
  });
});
