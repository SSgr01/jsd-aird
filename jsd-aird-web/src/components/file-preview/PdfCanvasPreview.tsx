import { Alert, Spin } from 'antd';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist';
import { useEffect, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

interface PdfCanvasPreviewProps {
  blob: Blob;
}

/**
 * Render PDF pages in our own canvas instead of relying on the browser's
 * embedded PDF plug-in. Mobile Safari and embedded WebViews commonly download
 * a blob URL when that plug-in is unavailable.
 */
export function PdfCanvasPreview({ blob }: PdfCanvasPreviewProps) {
  const [pdf, setPdf] = useState<PDFDocumentProxy>();
  const [error, setError] = useState<string>();
  const parent = useRef<HTMLDivElement>(null);
  const virtual = useVirtualizer({
    count: pdf?.numPages ?? 0,
    getScrollElement: () => parent.current,
    estimateSize: () => 900,
    overscan: 2,
  });

  useEffect(() => {
    let active = true;
    let task: PDFDocumentLoadingTask | undefined;
    setPdf(undefined);
    setError(undefined);

    void (async () => {
      const pdfjs = await import('pdfjs-dist');
      pdfjs.GlobalWorkerOptions.workerSrc = new URL(
        'pdfjs-dist/build/pdf.worker.min.mjs',
        import.meta.url,
      ).toString();
      const data = new Uint8Array(await blob.arrayBuffer());
      if (!active) return;
      task = pdfjs.getDocument({ data });
      const value = await task.promise;
      if (active) setPdf(value);
    })().catch(() => {
      if (active) setError('PDF 预览加载失败，请下载原文件查看');
    });

    return () => {
      active = false;
      void task?.destroy();
    };
  }, [blob]);

  if (error) return <div className="file-preview-pdf-state"><Alert type="warning" showIcon message={error} /></div>;
  if (!pdf) return <div className="file-preview-pdf-state"><Spin /></div>;

  return (
    <div className="file-preview-pdf-pages" ref={parent}>
      <div className="file-preview-pdf-virtual-canvas" style={{ height: virtual.getTotalSize() }}>
        {virtual.getVirtualItems().map((row) => (
          <div
            className="file-preview-pdf-page-slot"
            data-index={row.index}
            key={row.key}
            ref={virtual.measureElement}
            style={{ transform: `translateY(${row.start}px)` }}
          >
            <PdfCanvasPage pdf={pdf} pageNo={row.index + 1} />
          </div>
        ))}
      </div>
    </div>
  );
}

function PdfCanvasPage({ pdf, pageNo }: { pdf: PDFDocumentProxy; pageNo: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [page, setPage] = useState<PDFPageProxy>();

  useEffect(() => {
    let active = true;
    setPage(undefined);
    void pdf.getPage(pageNo).then((value) => {
      if (active) setPage(value);
    });
    return () => {
      active = false;
    };
  }, [pageNo, pdf]);

  useEffect(() => {
    if (!page || !canvas.current) return;
    const viewport = page.getViewport({ scale: 1.25 });
    const ratio = window.devicePixelRatio || 1;
    const context = canvas.current.getContext('2d');
    if (!context) return;

    canvas.current.width = viewport.width * ratio;
    canvas.current.height = viewport.height * ratio;
    canvas.current.style.width = `${viewport.width}px`;
    canvas.current.style.height = `${viewport.height}px`;
    const renderTask = page.render({
      canvas: canvas.current,
      canvasContext: context,
      viewport,
      transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0],
    });
    void renderTask.promise.catch(() => undefined);
    return () => renderTask.cancel();
  }, [page]);

  return (
    <figure className="file-preview-pdf-page">
      <div className="file-preview-pdf-canvas-frame">
        <canvas ref={canvas} />
      </div>
      <figcaption>第 {pageNo} 页</figcaption>
    </figure>
  );
}
