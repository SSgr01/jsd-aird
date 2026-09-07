import { ArrowLeftOutlined, CloudUploadOutlined, DownloadOutlined, EyeOutlined, HistoryOutlined, SaveOutlined } from '@ant-design/icons';
import { Button, Result, Skeleton, Space, Tabs, Tag, Typography, message } from 'antd';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { DocumentStructure, EditorHandle, TemplateBinding } from '@/features/template-workspace/types';
import { DocumentOutlinePanel } from '@/features/template-workspace/DocumentOutlinePanel';
import { deriveDocumentStructureFromSnapshot } from '@/features/template-workspace/document-structure';
import { templateApi } from '@/services/templates/template-api';
import { projectDocumentApi, type ProjectDocumentDetail, type ProjectDocumentVersion } from '@/services/project/project-document-api';
import { formatTime } from '@/utils/date';
import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import { downloadBlob } from '@/services/files/file-api';

const SheetsEditor = lazy(async () => ({ default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor }));
const DocsEditor = lazy(async () => ({ default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor }));

function blankSnapshot(format: ProjectDocumentDetail['format'], id: string, title: string): Record<string, unknown> {
  if (format === 'XLSX') {
    return {
      id,
      snapshotFormatVersion: 3,
      name: title,
      sheetOrder: ['sheet-1'],
      sheets: {
        'sheet-1': {
          id: 'sheet-1',
          name: 'Sheet1',
          rowCount: 200,
          columnCount: 26,
          cellData: {},
        },
      },
      styles: {},
    };
  }
  return {
    id,
    snapshotFormatVersion: 5,
    editorMode: 'UNIVER_DOCS',
    title,
    body: {
      dataStream: '\r\n',
      textRuns: [],
      paragraphs: [{ startIndex: 0 }],
      customRanges: [],
    },
    documentStyle: {
      pageSize: { width: 595, height: 842 },
      marginTop: 72,
      marginRight: 72,
      marginBottom: 72,
      marginLeft: 72,
    },
  };
}

export function ProjectDocumentWorkspacePage() {
  const { id: projectId = '', documentId = '' } = useParams();
  const navigate = useNavigate();
  const editorRef = useRef<EditorHandle>(null);
  const [document, setDocument] = useState<ProjectDocumentDetail>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [schema, setSchema] = useState<Record<string, unknown>>({});
  const [mapping, setMapping] = useState<TemplateBinding[]>([]);
  const [data, setData] = useState<Record<string, unknown>>({});
  const [documentStructure, setDocumentStructure] = useState<DocumentStructure>();
  const [view, setView] = useState<'preview' | 'edit' | 'versions'>('edit');
  const [error, setError] = useState<string>();
  const [versions, setVersions] = useState<ProjectDocumentVersion[]>([]);
  const [versionLoading, setVersionLoading] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>('SAVED');
  const [selectedVersion, setSelectedVersion] = useState<ProjectDocumentVersion>();

  useEffect(() => {
    let active = true;
    setSaveState('SAVED');
    void (async () => {
      try {
        const doc = await projectDocumentApi.get(projectId, documentId);
        const history = await projectDocumentApi.listVersions(projectId, documentId).catch(() => [] as ProjectDocumentVersion[]);
        let nextSnapshot = doc.contentSnapshot;
        let nextSchema = doc.contentSchema ?? {};
        let nextMapping = (doc.contentMapping ?? []) as TemplateBinding[];
        let nextData = doc.contentData ?? {};
        let nextStructure: DocumentStructure | undefined = doc.contentStructure
          ?? deriveDocumentStructureFromSnapshot(nextSnapshot, doc.id);
        if (!nextSnapshot && doc.templateVersionId) {
          try {
            const template = await templateApi.getEditModel(doc.templateVersionId);
            nextStructure = template.documentStructure;
            nextSnapshot = template.snapshotFileId && template.snapshotHash
              ? await templateApi.downloadSnapshot(template.snapshotFileId)
              : template.inlineSnapshot ?? {};
            nextSchema = template.schema;
            nextMapping = template.mapping;
            nextData = template.data ?? {};
            await projectDocumentApi.saveContent(projectId, documentId, { snapshot: nextSnapshot, schema: nextSchema, mapping: nextMapping, data: nextData });
          } catch (snapshotError) {
            // 模板快照对象存储读取失败时降级为空白模板，保证页面可打开
            console.warn('模板快照加载失败，降级为空白文档', snapshotError);
            if (!nextSnapshot) {
              const lowerTitle = doc.title.toLowerCase();
              const looksExcel = doc.format === 'XLSX'
                || lowerTitle.endsWith('.xlsx') || lowerTitle.endsWith('.xls');
              nextSnapshot = blankSnapshot(looksExcel ? 'XLSX' : 'DOCX', doc.id, doc.title);
            }
          }
        }
        if (!nextSnapshot && (doc.format === 'DOCX' || doc.format === 'XLSX' || doc.format === 'OTHER')) {
          const lowerTitle = doc.title.toLowerCase();
          const looksExcel = doc.format === 'XLSX'
            || lowerTitle.endsWith('.xlsx') || lowerTitle.endsWith('.xls');
          nextSnapshot = blankSnapshot(looksExcel ? 'XLSX' : 'DOCX', doc.id, doc.title);
          await projectDocumentApi.saveContent(projectId, documentId, {
            snapshot: nextSnapshot,
            schema: nextSchema,
            mapping: nextMapping,
            data: nextData,
          });
        }
        if (!active) return;
        setDocument(doc); setVersions(history); setSnapshot(nextSnapshot ?? {}); setSchema(nextSchema); setMapping(nextMapping); setData(nextData); setDocumentStructure(nextStructure);
        setSelectedVersion(undefined);
        setView('edit');
        setSaveState('SAVED');
      } catch (reason) { if (active) setError(reason instanceof Error ? reason.message : '项目文档加载失败'); }
    })();
    return () => { active = false; };
  }, [documentId, projectId]);

  const save = async () => {
    if (!document) return;
    setSaveState('SAVING');
    try {
      const current = editorRef.current?.getSnapshot() ?? snapshot ?? {};
      await projectDocumentApi.saveContent(projectId, documentId, { snapshot: current, schema, mapping, data });
      setSnapshot(current);
      setDocumentStructure(deriveDocumentStructureFromSnapshot(current, document.id));
      setSaveState('SAVED');
      message.success('项目文档已保存');
    } catch (reason) {
      setSaveState('DIRTY');
      message.error(reason instanceof Error ? reason.message : '保存失败');
    }
  };

  const publish = async () => {
    if (!document) return;
    setSaveState('SAVING');
    try {
      const current = editorRef.current?.getSnapshot() ?? snapshot ?? {};
      const published = await projectDocumentApi.publish(projectId, documentId, {
        snapshot: current,
        schema,
        mapping,
        data,
      });
      setDocument(published);
      setSnapshot(current);
      await loadVersions();
      setSaveState('SAVED');
      message.success('项目文档已发布');
      setView('versions');
    } catch (reason) {
      setSaveState('DIRTY');
      message.error(reason instanceof Error ? reason.message : '发布失败');
    }
  };

  const exportDocument = async () => {
    if (!document) return;
    try {
      const blob = await projectDocumentApi.exportDocument(projectId, documentId);
      const extension = document.format === 'XLSX' ? 'xlsx' : document.format === 'DOCX' ? 'docx' : 'bin';
      const fileName = document.title.toLowerCase().endsWith(`.${extension}`) ? document.title : `${document.title}.${extension}`;
      downloadBlob(blob, fileName);
      message.success('项目文档已导出');
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '项目文档导出失败');
    }
  };

  const changeView = (key: 'preview' | 'edit' | 'versions') => {
    if (saveState !== 'SAVED' && key === 'versions') {
      message.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    setSelectedVersion(undefined);
    setView(key);
  };

  const loadVersions = async () => {
    if (!documentId) return;
    setVersionLoading(true);
    try {
      const list = await projectDocumentApi.listVersions(projectId, documentId);
      setVersions(list);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '版本记录加载失败');
    } finally {
      setVersionLoading(false);
    }
  };

  useEffect(() => {
    if (view === 'versions') void loadVersions();
  }, [view, documentId, projectId]);

  if (error) return <Result status="error" title="项目文档加载失败" subTitle={error} />;
  if (!document || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;
  const word = document.format === 'DOCX';
  const editable = document.status !== 'ARCHIVED' && view === 'edit' && !selectedVersion;
  return <section className="workspace-shell template-business-workspace">
    <header className="workspace-header"><div className="workspace-identity">
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/projects/${projectId}?section=documents`)}>返回</Button>
      <span className="workspace-title-block"><Typography.Text type="secondary" className="workspace-breadcrumb">项目管理 / 项目文档</Typography.Text><Typography.Text strong>{document.title}</Typography.Text></span>
      <Tag color="blue">{versions.length ? `V${versions[0]?.versionNo ?? ''}` : '未发布'}</Tag>
    </div><Space wrap><SaveStateBadge state={saveState} /><Button icon={<DownloadOutlined />} onClick={() => void exportDocument()}>导出</Button>{document.status !== 'ARCHIVED' && <Button type="primary" className="workspace-save-button" icon={<SaveOutlined />} loading={saveState === 'SAVING'} disabled={!editable} onClick={() => void save()}>保存草稿</Button>}{document.status !== 'ARCHIVED' && <Button type="primary" className="workspace-publish-button" icon={<CloudUploadOutlined />} loading={saveState === 'SAVING'} disabled={!editable || saveState !== 'SAVED'} onClick={() => void publish()}>发布</Button>}</Space></header>
    <nav className="workspace-view-tabs"><Tabs activeKey={view} onChange={(key) => changeView(key as 'preview' | 'edit' | 'versions')} items={[{ key: 'preview', label: <><EyeOutlined /> 预览</> }, { key: 'edit', label: '编辑', disabled: document.status === 'ARCHIVED' }, { key: 'versions', label: <><HistoryOutlined /> 版本记录</> }]} /></nav>
    <div className="workspace-main-stage">
      {view === 'versions' ? (
        <main className="workspace-canvas project-versions-panel">
          {versionLoading ? <Skeleton active paragraph={{ rows: 8 }} /> : (
            <div className="project-versions-content">
              {selectedVersion ? <HistoricalProjectVersion version={selectedVersion} word={word} onBack={() => setSelectedVersion(undefined)} /> : (
                <VersionHistoryPanel
                  versions={versions}
                  onSelect={setSelectedVersion}
                  emptyDescription="暂无版本记录"
                  getLabel={(item) => item.status === 'PUBLISHED'
                    ? '发布版本'
                    : item.status === 'DRAFT'
                      ? '草稿版本'
                      : '归档版本'}
                />
              )}
            </div>
          )}
        </main>
      ) : (
        <div className={`template-workspace-grid prototype-workspace-grid ${word ? 'word-workspace-grid project-word-workspace' : ''}`}>
            {word && <DocumentOutlinePanel structure={documentStructure} onSelect={(node) => editorRef.current?.focusNode?.(node)} />}
            <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}><Suspense fallback={<Skeleton active />}>
              {word ? <div className={!editable ? 'document-readonly' : undefined}><DocsEditor ref={editorRef} snapshot={snapshot} editable={editable} onDirty={() => setSaveState('DIRTY')} /></div>
                : <div className={!editable ? 'document-readonly' : undefined}><SheetsEditor ref={editorRef} snapshot={snapshot} editable={editable} bindings={mapping} onEditorValue={() => undefined} onDirty={() => setSaveState('DIRTY')} /></div>}
            </Suspense></main>
          </div>
      )}
    </div>
  </section>;
}

function HistoricalProjectVersion({ version, word, onBack }: { version: ProjectDocumentVersion; word: boolean; onBack: () => void }) {
  const content = readVersionRecord(version.contentJsonb);
  const historicalSnapshot = readVersionRecord(
    content.contentSnapshot
      ?? content.content_snapshot
      ?? content.snapshot
      ?? (hasSnapshotShape(content) ? content : undefined),
    blankSnapshot(word ? 'DOCX' : 'XLSX', version.documentId, `版本 V${version.versionNo}`),
  );
  const historicalMapping = readVersionArray<TemplateBinding>(
    content.contentMapping ?? content.content_mapping ?? content.mapping,
  );
  return <div className="project-version-preview">
    <header className="project-versions-header"><Button onClick={onBack}>返回版本列表</Button><Typography.Title level={4}>版本 V{version.versionNo}</Typography.Title><Typography.Text type="secondary">{formatTime(version.createdAt)} · {version.createdBy ?? '未知'}</Typography.Text></header>
    <main className="workspace-canvas document-readonly"><Suspense fallback={<Skeleton active />}>{word
      ? <DocsEditor snapshot={historicalSnapshot} editable={false} onDirty={() => undefined} />
      : <SheetsEditor snapshot={historicalSnapshot} editable={false} bindings={historicalMapping} onEditorValue={() => undefined} onDirty={() => undefined} />}</Suspense></main>
  </div>;
}

function readVersionRecord(value: unknown, fallback: Record<string, unknown> = {}): Record<string, unknown> {
  const parsed = parseVersionJson(value);
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : fallback;
}

function readVersionArray<T>(value: unknown, fallback: T[] = []): T[] {
  const parsed = parseVersionJson(value);
  return Array.isArray(parsed) ? parsed as T[] : fallback;
}

function parseVersionJson(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch { return undefined; }
}

function hasSnapshotShape(value: Record<string, unknown>) {
  return 'body' in value || 'sheets' in value || 'sheetOrder' in value;
}
