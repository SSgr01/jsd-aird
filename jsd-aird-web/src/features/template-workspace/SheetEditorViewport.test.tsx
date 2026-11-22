import { fireEvent, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { WorkspaceFrame } from '@/components/workspace/WorkspaceFrame';
import { SheetEditorViewport } from './SheetEditorViewport';

describe('shared spreadsheet viewport', () => {
  it('maximizes only the sheet and preserves the mounted editor through restore', () => {
    const mounted = vi.fn();
    const disposed = vi.fn();
    function Editor() {
      useEffect(() => { mounted(); return disposed; }, []);
      return <textarea aria-label="单元格" defaultValue="未保存的内容" />;
    }
    const { container } = render(<WorkspaceFrame maximizable><header>实验详情</header>
      <SheetEditorViewport><Editor /></SheetEditorViewport><aside>字段数据</aside>
    </WorkspaceFrame>);
    const editor = screen.getByRole('textbox');
    fireEvent.click(screen.getByRole('button', { name: '全屏' }));
    expect(container.querySelector('.sheet-editor-viewport')).toHaveAttribute('data-maximized', 'true');
    expect(container.firstElementChild).not.toHaveAttribute('data-maximized');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(container.querySelector('.sheet-editor-viewport')).not.toHaveAttribute('data-maximized');
    expect(screen.getByRole('textbox')).toBe(editor);
    expect(editor).toHaveValue('未保存的内容');
    expect(mounted).toHaveBeenCalledTimes(1);
    expect(disposed).not.toHaveBeenCalled();
  });

  it('keeps capture pages free of interactive chrome', () => {
    render(<SheetEditorViewport fullscreenEnabled={false}><div>表格</div></SheetEditorViewport>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
