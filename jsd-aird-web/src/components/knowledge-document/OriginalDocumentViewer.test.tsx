import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const pdfMock = vi.hoisted(() => {
  const destroy = vi.fn();
  return { destroy, getDocument: vi.fn(() => ({ promise: new Promise(() => undefined), destroy })) };
});

vi.mock('pdfjs-dist/legacy/build/pdf.mjs', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: pdfMock.getDocument,
}));

vi.mock('docx-preview', () => ({
  renderAsync: vi.fn().mockRejectedValue(new Error('broken DOCX archive')),
}));

vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: () => ({
    getTotalSize: () => 0,
    getVirtualItems: () => [],
    measureElement: vi.fn(),
    scrollToIndex: vi.fn(),
  }),
}));

import { OriginalDocumentViewer } from './OriginalDocumentViewer';

describe('OriginalDocumentViewer', () => {
  it('lets pdf.js stream a PDF URL instead of buffering the full file through Axios first', async () => {
    const load = vi.fn<() => Promise<Blob>>();

    render(<OriginalDocumentViewer
      fileName="large-document.pdf"
      contentType="application/pdf"
      load={load}
      contentUrl="/api/v1/knowledge/documents/document-1/versions/version-1/content"
      sourceNodes={[]}
    />);

    await waitFor(() => expect(pdfMock.getDocument).toHaveBeenCalledWith({
      url: '/api/v1/knowledge/documents/document-1/versions/version-1/content',
      withCredentials: true,
    }));
    expect(load).not.toHaveBeenCalled();
  });

  it('shows a preview explanation when the original DOCX is damaged', async () => {
    render(<OriginalDocumentViewer
      fileName="broken.docx"
      contentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document"
      load={() => Promise.resolve(new Blob(['not a DOCX archive']))}
      sourceNodes={[]}
    />);

    expect(await screen.findByText('原文件无法预览')).toBeInTheDocument();
    expect(screen.getByText(/文件可能已损坏或内容与扩展名不符/)).toBeInTheDocument();
  });
});
