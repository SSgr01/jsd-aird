import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { DocumentComparisonViewer } from './DocumentComparisonViewer';

let observedWidth = 1200;

describe('DocumentComparisonViewer', () => {
  beforeEach(() => {
    observedWidth = 1200;
    Object.defineProperty(globalThis, 'ResizeObserver', {
      configurable: true,
      value: class {
        private readonly callback: ResizeObserverCallback;
        constructor(callback: ResizeObserverCallback) { this.callback = callback; }
        observe(target: Element) {
          this.callback([{ target, contentRect: { width: observedWidth } } as ResizeObserverEntry], this as unknown as ResizeObserver);
        }
        disconnect() {}
        unobserve() {}
      },
    });
  });

  it('shows original evidence and confirmed result together on a wide container', () => {
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);

    expect(screen.getByText('原文件证据')).toBeInTheDocument();
    expect(screen.getByText('完整确认内容')).toBeInTheDocument();
    expect(screen.getByRole('separator', { name: '调整原文件与解析结果的宽度' })).toBeInTheDocument();
  });

  it('switches panes instead of squeezing them when the comparison container is narrow', async () => {
    observedWidth = 790;
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);

    expect(screen.getByText('完整确认内容')).toBeInTheDocument();
    fireEvent.click(screen.getByText('原文件'));
    expect(await screen.findByText('原文件证据')).toBeInTheDocument();
    expect(screen.queryByText('完整确认内容')).not.toBeInTheDocument();
  });

  it('supports keyboard resizing and resets to the default split with Home', () => {
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);
    const divider = screen.getByRole('separator', { name: '调整原文件与解析结果的宽度' });

    fireEvent.keyDown(divider, { key: 'ArrowRight' });
    expect(divider).toHaveAttribute('aria-valuenow', '47');
    fireEvent.keyDown(divider, { key: 'Home' });
    expect(divider).toHaveAttribute('aria-valuenow', '44');
  });

  it('can focus one pane and restore the split without unmounting the other pane', () => {
    render(<DocumentComparisonViewer original={<div>原文件证据</div>} result={<div>完整确认内容</div>} />);

    fireEvent.click(screen.getByRole('button', { name: '仅看解析结果' }));
    expect(screen.getByText('原文件证据').closest('section')).toHaveAttribute('hidden');
    expect(screen.getByText('完整确认内容')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '恢复双栏' }));
    expect(screen.getByText('原文件证据').closest('section')).not.toHaveAttribute('hidden');
    expect(screen.getByRole('separator', { name: '调整原文件与解析结果的宽度' })).toBeInTheDocument();
  });
});
