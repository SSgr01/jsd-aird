import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DocumentComparisonViewer } from './DocumentComparisonViewer';

describe('DocumentComparisonViewer', () => {
  beforeEach(() => {
    setMatchMedia(false);
  });

  it('shows original evidence and confirmed result together on desktop', () => {
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);

    expect(screen.getByText('原文件证据')).toBeInTheDocument();
    expect(screen.getByText('完整确认内容')).toBeInTheDocument();
  });

  it('switches panes instead of squeezing both on narrow screens', async () => {
    setMatchMedia(true);
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);

    expect(screen.getByText('完整确认内容')).toBeInTheDocument();
    fireEvent.click(screen.getByText('原文件'));
    expect(await screen.findByText('原文件证据')).toBeInTheDocument();
    expect(screen.queryByText('完整确认内容')).not.toBeInTheDocument();
  });
});

function setMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', { writable: true, value: vi.fn((query: string) => ({
    matches, media: query, onchange: null,
    addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(),
    removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  })) });
}
