import { render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const pdfMock = vi.hoisted(() => {
  const destroy = vi.fn();
  return { destroy, getDocument: vi.fn(() => ({ promise: new Promise(() => undefined), destroy })) };
});

vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerSrc: '' },
  getDocument: pdfMock.getDocument,
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
});
