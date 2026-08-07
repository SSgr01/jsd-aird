import { Alert, Spin } from 'antd';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';

import { UniverSheetsEditor } from '@/features/template-workspace/UniverSheetsEditor';
import { templateApi } from '@/services/templates/template-api';

export function TemplateImportRenderPage() {
  const { importJobId } = useParams<{ importJobId: string }>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [error, setError] = useState<string>();
  const [renderReady, setRenderReady] = useState(false);

  useEffect(() => {
    if (!importJobId) return;
    let cancelled = false;
    void templateApi
      .getImportRenderContext(importJobId)
      .then((context) => {
        if (!cancelled && context.ready) setSnapshot(context.snapshot);
        if (!cancelled && !context.ready) setError('渲染快照尚未准备好');
      })
      .catch(() => {
        if (!cancelled) setError('无法读取模板渲染快照');
      });
    return () => {
      cancelled = true;
    };
  }, [importJobId]);

  useEffect(() => {
    if (!snapshot) return;
    setRenderReady(false);
    const renderWindow = window as Window & {
      templateRenderReady?: boolean;
      getSpreadsheetRangeRect?: (sheetId: string, range: string) => DOMRect | null;
    };
    renderWindow.templateRenderReady = false;
    renderWindow.getSpreadsheetRangeRect = () =>
      document.querySelector('.univer-editor-surface')?.getBoundingClientRect() ?? null;
    return () => {
      renderWindow.templateRenderReady = false;
      renderWindow.getSpreadsheetRangeRect = undefined;
    };
  }, [snapshot]);

  const markReady = () => {
    setRenderReady(true);
    const renderWindow = window as Window & {
      templateRenderReady?: boolean;
    };
    renderWindow.templateRenderReady = true;
  };

  return (
    <main
      data-template-render-ready={renderReady ? 'true' : snapshot ? 'pending' : 'false'}
      style={{ height: '100vh', minHeight: 600, minWidth: 1200, background: '#fff' }}
    >
      {error ? <Alert type="warning" message={error} /> : null}
      {!snapshot && !error ? <Spin style={{ margin: 40 }} /> : null}
      {snapshot ? (
        <UniverSheetsEditor
          snapshot={snapshot}
          bindings={[]}
          editable={false}
          onDirty={() => undefined}
          onEditorValue={() => undefined}
          onReady={markReady}
        />
      ) : null}
    </main>
  );
}
