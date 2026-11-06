import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as XLSX from 'xlsx';

import { downloadBlob } from '@/services/files';
import { WorkspaceFrame, WorkspaceMaximizeButton } from '@/components/workspace/WorkspaceFrame';
import { FilePreviewModal } from './FilePreviewModal';
import type { FilePreviewDescriptor } from './file-preview-utils';

const pdfLifecycle = vi.hoisted(() => ({ mount: vi.fn(), dispose: vi.fn() }));
const renderDocx = vi.hoisted(() => vi.fn());
vi.mock('@/services/files', () => ({ downloadBlob: vi.fn(), triggerNativeDownload: vi.fn() }));
vi.mock('docx-preview', () => ({ renderAsync: renderDocx }));
vi.mock('./PdfCanvasPreview', () => ({
  PdfCanvasPreview: function PdfPreview() {
    useEffect(() => { pdfLifecycle.mount(); return pdfLifecycle.dispose; }, []);
    return <div className="file-preview-pdf-pages" data-testid="pdf-preview">
      <button onKeyDown={(event) => { if (event.key === 'Escape') event.preventDefault(); }}>预览选项</button>
      <p>第 1 页</p>
    </div>;
  },
}));

afterEach(() => { vi.clearAllMocks(); });

function textFile(): FilePreviewDescriptor {
  const blob = Object.assign(new Blob(['原始试验记录']), { text: () => Promise.resolve('原始试验记录') });
  return { fileName: '原始试验记录.txt', load: vi.fn().mockResolvedValue(blob) };
}

describe('attachment preview fullscreen', () => {
  it('keeps the loaded file and scroll container across repeated toggles and downloads the same blob', async () => {
    const file = textFile();
    render(<FilePreviewModal open file={file} onClose={vi.fn()} />);
    const content = await screen.findByText('原始试验记录');
    content.scrollTop = 80;
    for (let index = 0; index < 3; index++) {
      fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
      expect(screen.getByRole('dialog')).toHaveClass('file-preview-modal-maximized');
      expect(screen.getByRole('button', { name: '还原预览' })).toHaveAttribute('aria-pressed', 'true');
      fireEvent.click(screen.getByRole('button', { name: '还原预览' }));
    }
    expect(screen.getByText('原始试验记录')).toBe(content);
    expect(content.scrollTop).toBe(80);
    expect(file.load).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: /下载原文件/ }));
    expect(downloadBlob).toHaveBeenCalledWith(await vi.mocked(file.load).mock.results[0]?.value, file.fileName);
    expect(file.load).toHaveBeenCalledTimes(1);
  });

  it('retains the PDF instance and lets consumed Escape and IME input take priority', async () => {
    const file = { fileName: '原始记录.pdf', load: vi.fn().mockResolvedValue(new Blob(['pdf'])) };
    const close = vi.fn();
    render(<FilePreviewModal open file={file} onClose={close} />);
    const pdf = await screen.findByTestId('pdf-preview');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    const options = screen.getByRole('button', { name: '预览选项' });
    fireEvent.keyDown(options, { key: 'Escape', keyCode: 27 });
    expect(screen.getByRole('button', { name: '还原预览' })).toBeInTheDocument();
    fireEvent.compositionStart(pdf);
    fireEvent.keyDown(pdf, { key: 'Escape', keyCode: 27 });
    fireEvent.compositionEnd(pdf);
    fireEvent.keyDown(pdf, { key: 'Escape', keyCode: 27, isComposing: true });
    expect(screen.getByRole('button', { name: '还原预览' })).toBeInTheDocument();
    fireEvent.keyDown(pdf, { key: 'Escape', keyCode: 27 });
    expect(screen.getByRole('button', { name: '全屏预览' })).toBeInTheDocument();
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByTestId('pdf-preview')).toBe(pdf);
    expect(pdfLifecycle.mount).toHaveBeenCalledTimes(1);
    expect(pdfLifecycle.dispose).not.toHaveBeenCalled();
    fireEvent.keyDown(pdf, { key: 'Escape', keyCode: 27 });
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('preserves the selected spreadsheet sheet and table while changing display mode', async () => {
    const workbook = XLSX.utils.book_new();
    for (const name of ['试验数据', '复测记录']) {
      XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([['样品', '附着力'], [name, 12.6]]), name);
    }
    const bytes = XLSX.write(workbook, { type: 'array', bookType: 'xlsx' }) as ArrayBuffer;
    const blob = Object.assign(new Blob([bytes]), { arrayBuffer: () => Promise.resolve(bytes) });
    const file = { fileName: '原始记录.xlsx', load: vi.fn().mockResolvedValue(blob) };
    render(<FilePreviewModal open file={file} onClose={vi.fn()} />);
    const tab = await screen.findByRole('tab', { name: '复测记录' });
    fireEvent.click(tab);
    const table = document.querySelector('.ant-table-body')!;
    table.scrollLeft = 140;
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    fireEvent.click(screen.getByRole('button', { name: '还原预览' }));
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(document.querySelector('.ant-table-body')).toBe(table);
    expect(table.scrollLeft).toBe(140);
    expect(file.load).toHaveBeenCalledTimes(1);
  });

  it('lets an overflow menu close from its window listener before restoring the preview', async () => {
    const file = textFile();
    const close = vi.fn();
    render(<FilePreviewModal open file={file} onClose={close} />);
    const content = await screen.findByText('原始试验记录');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    const popup = document.createElement('div');
    popup.className = 'ant-tabs-dropdown';
    vi.spyOn(popup, 'getClientRects').mockReturnValue([new DOMRect(0, 0, 100, 100)] as unknown as DOMRectList);
    document.body.appendChild(popup);
    const dismissMenu = vi.fn(() => popup.remove());
    window.addEventListener('keydown', dismissMenu, { once: true });
    fireEvent.keyDown(content, { key: 'Escape', keyCode: 27 });
    expect(dismissMenu).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: '还原预览' })).toBeInTheDocument();
    expect(close).not.toHaveBeenCalled();
    fireEvent.keyDown(content, { key: 'Escape', keyCode: 27 });
    expect(screen.getByRole('button', { name: '全屏预览' })).toBeInTheDocument();
  });

  it('handles Escape when focus is on the modal panel outside its content wrapper', async () => {
    const close = vi.fn();
    render(<FilePreviewModal open file={textFile()} onClose={close} />);
    await screen.findByText('原始试验记录');
    const panel = screen.getByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    fireEvent.keyDown(panel, { key: 'Escape', keyCode: 27 });
    expect(screen.getByRole('button', { name: '全屏预览' })).toBeInTheDocument();
    expect(close).not.toHaveBeenCalled();
    fireEvent.keyDown(panel, { key: 'Escape', keyCode: 27 });
    expect(close).toHaveBeenCalledTimes(1);
  });

  it('renders Word after its container mounts and retains the rendered document across toggles', async () => {
    renderDocx.mockImplementation((_blob: Blob, body: HTMLElement) => {
      body.textContent = 'Word 原始试验记录';
      return Promise.resolve();
    });
    const file = { fileName: '原始记录.docx', load: vi.fn().mockResolvedValue(new Blob(['word'])) };
    render(<FilePreviewModal open file={file} onClose={vi.fn()} />);
    const content = await screen.findByText('Word 原始试验记录');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    fireEvent.click(screen.getByRole('button', { name: '还原预览' }));
    expect(screen.getByText('Word 原始试验记录')).toBe(content);
    expect(renderDocx).toHaveBeenCalledTimes(1);
    expect(file.load).toHaveBeenCalledTimes(1);
  });

  it('shows a Word rendering failure with download still available in fullscreen', async () => {
    renderDocx.mockRejectedValueOnce(new Error('Word 文件损坏'));
    const file = { fileName: '原始记录.docx', load: vi.fn().mockResolvedValue(new Blob(['word'])) };
    render(<FilePreviewModal open file={file} onClose={vi.fn()} />);
    await screen.findByText('Word 文件损坏');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Word 文件损坏');
    expect(screen.getAllByRole('button', { name: /下载原文件/ })).toHaveLength(2);
  });

  it('closes directly from fullscreen, restores focus and reopens as a normal modal', async () => {
    const file = textFile();
    function Preview() {
      const [open, setOpen] = useState(false);
      return <><button onClick={() => setOpen(true)}>打开附件</button>
        <FilePreviewModal open={open} file={file} onClose={() => setOpen(false)} /></>;
    }
    render(<Preview />);
    const trigger = screen.getByRole('button', { name: '打开附件' });
    trigger.focus();
    fireEvent.click(trigger);
    await screen.findByText('原始试验记录');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    fireEvent.click(screen.getByRole('button', { name: '关闭文件预览' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
    fireEvent.click(trigger);
    await screen.findByText('原始试验记录');
    expect(screen.getByRole('dialog')).not.toHaveClass('file-preview-modal-maximized');
    expect(screen.getByRole('button', { name: '全屏预览' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('restores the attachment before closing it and leaves the underlying fullscreen workspace active', async () => {
    const file = textFile();
    function PreviewWorkspace() {
      const [open, setOpen] = useState(false);
      return <WorkspaceFrame maximizable><WorkspaceMaximizeButton />
        <button onClick={() => setOpen(true)}>打开附件</button>
        <FilePreviewModal open={open} file={file} onClose={() => setOpen(false)} />
      </WorkspaceFrame>;
    }
    render(<PreviewWorkspace />);
    fireEvent.click(screen.getByRole('button', { name: '全屏' }));
    fireEvent.click(screen.getByRole('button', { name: '打开附件' }));
    const content = await screen.findByText('原始试验记录');
    fireEvent.click(screen.getByRole('button', { name: '全屏预览' }));
    fireEvent.keyDown(content, { key: 'Escape', keyCode: 27 });
    expect(screen.getByRole('button', { name: '全屏预览' })).toBeInTheDocument();
    expect(document.body).toHaveClass('workspace-is-maximized');
    fireEvent.keyDown(content, { key: 'Escape', keyCode: 27 });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(document.body).toHaveClass('workspace-is-maximized');
    fireEvent.keyDown(window, { key: 'Escape', keyCode: 27 });
    expect(document.body).not.toHaveClass('workspace-is-maximized');
  });
});
