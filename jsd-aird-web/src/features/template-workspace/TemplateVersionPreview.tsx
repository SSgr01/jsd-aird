import { DocsEditor, SheetsEditor } from '@/features/template-workspace/editor-runtime';
import { ContentLoading } from '@/components/loading/ContentLoading';
import { trackSnapshot } from './workspace-loading';
import { markPerformance } from '@/services/performance/metrics';
import { Suspense, useEffect, useMemo, useState } from 'react';
import { Result } from 'antd';
import { templateApi } from '@/services/templates/template-api';
import type { TemplateWorkspace } from './types';
import { preloadWorkspaceEditor } from './preload-editor';

/**
 * 模板中心工作台的只读复用组件：按 templateVersionId 加载模板快照，
 * 用 Univer 渲染 Word/Excel 模板内容。用于项目文档等场景内嵌查看，
 * 避免整页跳转到模板中心工作台。
 */


function snapshotVersion(snapshot: Record<string, unknown>, fallback: number) {
  const value = snapshot.snapshotFormatVersion;
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : fallback;
}

interface Props {
  versionId: string;
  height?: number;
}

export default function TemplateVersionPreview({ versionId, height = 560 }: Props) {
  const [loading, setLoading] = useState(true);
  const [workspace, setWorkspace] = useState<TemplateWorkspace>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    markPerformance('workspace-open');
    let active = true;
    setLoading(true);
    setError(undefined);
    setWorkspace(undefined);
    setSnapshot(undefined);
    void (async () => {
      try {
        const model = await templateApi.getEditModel(versionId);
        if (!active) return;
        preloadWorkspaceEditor(model.format);
        const loaded =
          model.snapshotFileId && model.snapshotHash
            ? await trackSnapshot(templateApi.downloadSnapshot(model.snapshotFileId))
            : (model.inlineSnapshot ?? {});
        if (model.format === 'DOCX' && snapshotVersion(loaded, 0) < 5) {
          throw new Error('该 Word 模板使用旧编辑快照，请在模板中心重新导入原始 DOCX 后再查看');
        }
        if (!active) return;
        setWorkspace(model);
        setSnapshot(loaded);
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : '模板内容加载失败');
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
    };
  }, [versionId]);

  const noop = useMemo(() => () => undefined, []);

  if (loading) {
    return (
      <div className="pm-documents-preview" style={{ height }}>
        <div className="pm-documents-preview-loading">
          <ContentLoading label="下载文档…" />
        </div>
      </div>
    );
  }

  if (error || !workspace || !snapshot) {
    return (
      <div className="pm-documents-preview" style={{ height }}>
        <Result status="warning" title={error ?? '模板内容加载失败'} />
      </div>
    );
  }

  return (
    <div className="pm-documents-preview" style={{ height }}>
      <Suspense
        fallback={
          <div className="pm-documents-preview-loading">
            <ContentLoading label="加载编辑器…" />
          </div>
        }
      >
        {workspace.format === 'DOCX' ? (
          <div className="document-readonly">
            <DocsEditor snapshot={snapshot} editable={false} onDirty={noop} />
          </div>
        ) : (
          <SheetsEditor
            snapshot={snapshot}
            editable={false}
            bindings={workspace.mapping}
            onDirty={noop}
            onEditorValue={noop}
          />
        )}
      </Suspense>
    </div>
  );
}
