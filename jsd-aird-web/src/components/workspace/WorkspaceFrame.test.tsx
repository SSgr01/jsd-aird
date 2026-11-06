import { WorkspaceFrame, WorkspaceMaximizeButton } from './WorkspaceFrame';
import { fireEvent, render, screen } from '@testing-library/react';
import { useEffect, useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => {
  document.body.style.overflow = '';
  document.documentElement.style.overflow = '';
});

describe('workspace display mode', () => {
  it('is opt-in and leaves ordinary containers without fullscreen controls', () => {
    render(<WorkspaceFrame><WorkspaceMaximizeButton /><p>普通列表</p></WorkspaceFrame>);
    expect(screen.queryByRole('button', { name: '全屏' })).not.toBeInTheDocument();
  });

  it('keeps the editor, draft, selection, scroll and undo history through repeated toggles', () => {
    const mounted = vi.fn(); const disposed = vi.fn();
    function Editor() {
      const [value, setValue] = useState('原文');
      const history = useRef(['原文']);
      useEffect(() => { mounted(); return disposed; }, []);
      return <><textarea aria-label="文档正文" value={value} onChange={(event) => {
        history.current.push(event.target.value); setValue(event.target.value);
      }} /><button onClick={() => { history.current.pop(); setValue(history.current.at(-1) || ''); }}>撤销</button></>;
    }
    render(<WorkspaceFrame maximizable><WorkspaceMaximizeButton /><Editor /></WorkspaceFrame>);
    const editor = screen.getByRole<HTMLTextAreaElement>('textbox', { name: '文档正文' });
    fireEvent.change(editor, { target: { value: '尚未保存的中文修改' } });
    editor.setSelectionRange(2, 5); editor.scrollTop = 80;
    for (let i = 0; i < 3; i++) {
      fireEvent.click(screen.getByRole('button', { name: '全屏' }));
      expect(document.body).toHaveClass('workspace-is-maximized');
      fireEvent.click(screen.getByRole('button', { name: '还原' }));
    }
    expect(screen.getByRole('textbox', { name: '文档正文' })).toBe(editor);
    expect(editor).toHaveValue('尚未保存的中文修改');
    expect(editor.selectionStart).toBe(2); expect(editor.selectionEnd).toBe(5);
    expect(editor.scrollTop).toBe(80); expect(mounted).toHaveBeenCalledTimes(1); expect(disposed).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '撤销' }));
    expect(editor).toHaveValue('原文');
  });

  it('lets overlays and IME consume Escape, and restores the previous scroll lock on unmount', () => {
    document.body.style.overflow = 'auto'; document.documentElement.style.overflow = 'clip';
    const { unmount } = render(<WorkspaceFrame maximizable><WorkspaceMaximizeButton /></WorkspaceFrame>);
    fireEvent.click(screen.getByRole('button', { name: '全屏' }));
    const overlay = document.createElement('div'); overlay.setAttribute('role', 'dialog');
    vi.spyOn(overlay, 'getClientRects').mockReturnValue([new DOMRect(0, 0, 100, 100)] as unknown as DOMRectList);
    document.body.appendChild(overlay);
    // Simulate an overlay closing itself before the event reaches window.
    overlay.addEventListener('keydown', () => overlay.remove());
    fireEvent.keyDown(overlay, { key: 'Escape' });
    expect(screen.getByRole('button', { name: '还原' })).toBeInTheDocument();
    fireEvent.compositionStart(window);
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByRole('button', { name: '还原' })).toBeInTheDocument();
    fireEvent.compositionEnd(window);
    fireEvent.keyDown(window, { key: 'Escape', isComposing: true });
    expect(screen.getByRole('button', { name: '还原' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByRole('button', { name: '全屏' })).toBeInTheDocument();
    expect(document.body.style.overflow).toBe('auto');
    fireEvent.click(screen.getByRole('button', { name: '全屏' }));
    unmount();
    expect(document.body.style.overflow).toBe('auto');
    expect(document.documentElement.style.overflow).toBe('clip');
    expect(document.body).not.toHaveClass('workspace-is-maximized');
  });
});
