import { Alert, Button, Spin } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { FilePreviewDescriptor } from '@/components/file-preview';
import { triggerNativeDownload } from '@/services/files';

export function OriginalDocumentCanvas({ file }: { file: FilePreviewDescriptor }) {
  const [url, setUrl] = useState<string>();
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const bodyRef = useRef<HTMLDivElement>(null);
  const styleRef = useRef<HTMLDivElement>(null);
  const image = /\.(png|jpe?g|gif|webp|bmp|tiff?)$/i.test(file.fileName);

  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    setLoading(true);
    setError(undefined);
    const render = async () => {
      try {
        const blob = await file.load();
        if (!active) return;
        if (image) {
          objectUrl = URL.createObjectURL(blob);
          setUrl(objectUrl);
        } else {
          if (!bodyRef.current || !styleRef.current) return;
          bodyRef.current.replaceChildren();
          styleRef.current.replaceChildren();
          const { renderAsync } = await import('docx-preview');
          await renderAsync(blob, bodyRef.current, styleRef.current, {
            breakPages: true, renderHeaders: true, renderFooters: true, useBase64URL: true,
          });
        }
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : '原文件预览失败');
      } finally {
        if (active) setLoading(false);
      }
    };
    void render();
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [file.fileName, file.downloadUrl, image]);

  const downloadOriginal = async () => {
    if (file.downloadUrl) {
      triggerNativeDownload(file.downloadUrl, file.fileName);
      return;
    }
    const blob = await file.load();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = file.fileName;
    anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return <div className="data-original-document-canvas" aria-label="原文件预览">
    {loading ? <Spin tip="正在加载原文件"><div style={{ minHeight: 240 }} /></Spin> : null}
    {error ? <Alert type="warning" showIcon message={error}
      action={<Button onClick={() => void downloadOriginal()}>下载原文件</Button>} /> : null}
    {image && url ? <img src={url} alt={file.fileName} style={{ maxWidth: '100%', height: 'auto' }} /> : null}
    {!image ? <div className="file-preview-docx"><div ref={styleRef} /><div ref={bodyRef} /></div> : null}
  </div>;
}
