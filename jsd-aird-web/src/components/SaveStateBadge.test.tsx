import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SaveStateBadge } from './SaveStateBadge';

describe('SaveStateBadge', () => {
  it.each([
    ['SAVED', '已保存'],
    ['DIRTY', '未保存'],
    ['SAVING', '保存中'],
  ] as const)('renders %s state', (state, label) => {
    render(<SaveStateBadge state={state} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
