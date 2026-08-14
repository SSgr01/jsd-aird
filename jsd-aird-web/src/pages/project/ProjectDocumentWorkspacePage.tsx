import { ArrowLeftOutlined, EyeOutlined, FileExcelOutlined, FileWordOutlined, SaveOutlined } from '@ant-design/icons';
import { Button, Result, Skeleton, Space, Tabs, Tag, Typography, message } from 'antd';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import type { DocumentStructure, EditorHandle, TemplateBinding } from '@/features/template-workspace/types';
import { DocumentOutlinePanel } from '@/features/template-workspace/DocumentOutlinePanel';
import { deriveDocumentStructureFromSnapshot } from '@/features/template-workspace/document-structure';
import { templateApi } from '@/services/templates/template-api';
import { projectDocumentApi, type ProjectDocumentDetail } from '@/services/project/project-document-api';

const SheetsEditor = lazy(async () => ({ default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor }));
const DocsEditor = lazy(async () => ({ default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor }));

function isExcelSnapshot(snapshot: Record<string, unknown> | undefined): boolean {
  return !!snapshot && ('sheets' in snapshot || 'sheetOrder' in snapshot);
}

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
  const [dirty, setDirty] = useState(false);
  const [view, setView] = useState<'preview' | 'edit'>('edit');
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const doc = await projectDocumentApi.get(projectId, documentId);
        let nextSnapshot = doc.contentSnapshot;
        let nextSchema = doc.contentSchema ?? {};
        let nextMapping = (doc.contentMapping ?? []) as TemplateBinding[];
        let nextData = doc.contentData ?? {};
        let nextStructure: DocumentStructure | undefined = (doc.contentStructure as DocumentStructure | undefined)
          ?? deriveDocumentStructureFromSnapshot(nextSnapshot as Record<string, unknown>, doc.id);
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
        setDocument(doc); setSnapshot(nextSnapshot ?? {}); setSchema(nextSchema); setMapping(nextMapping); setData(nextData); setDocumentStructure(nextStructure);
      } catch (reason) { if (active) setError(reason instanceof Error ? reason.message : '项目文档加载失败'); }
    })();
    return () => { active = false; };
  }, [documentId, projectId]);

  const save = async () => {
    if (!document) return;
    const current = editorRef.current?.getSnapshot() ?? snapshot ?? {};
    await projectDocumentApi.saveContent(projectId, documentId, { snapshot: current, schema, mapping, data });
    setSnapshot(current); setDirty(false);
    setDocumentStructure(deriveDocumentStructureFromSnapshot(current, document.id));
    message.success('项目文档已保存');
  };

  if (error) return <Result status="error" title="项目文档加载失败" subTitle={error} />;
  if (!document || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;
  const word = !isExcelSnapshot(snapshot);
  return <section className="workspace-shell template-business-workspace">
    <header className="workspace-header"><div className="workspace-identity">
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/projects/${projectId}?section=documents`)}>返回</Button>
      <span className="workspace-title-block"><Typography.Text type="secondary" className="workspace-breadcrumb">项目管理 / 项目文档</Typography.Text><Typography.Text strong>{document.title}</Typography.Text></span>
      <span className="workspace-meta-item">{word ? <><FileWordOutlined /> Word</> : <><FileExcelOutlined /> Excel</>}</span><Tag color="gold">草稿</Tag>
    </div><Space>{view === 'edit' && <Button icon={<SaveOutlined />} type={dirty ? 'primary' : 'default'} disabled={!dirty} onClick={() => void save()}>保存草稿</Button>}</Space></header>
    <nav className="workspace-view-tabs"><Tabs activeKey={view} onChange={(key) => setView(key as 'preview' | 'edit')} items={[{ key: 'preview', label: <><EyeOutlined /> 预览</> }, { key: 'edit', label: '编辑' }, { key: 'versions', label: '版本记录', disabled: true }]} /></nav>
    <div className="workspace-main-stage"><div className={`template-workspace-grid prototype-workspace-grid ${word ? 'word-workspace-grid project-word-workspace' : ''}`}>
      {word && <DocumentOutlinePanel structure={documentStructure} onSelect={(node) => editorRef.current?.focusNode?.(node)} />}
      <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}><Suspense fallback={<Skeleton active />}>
        {word ? <div className={view === 'preview' ? 'document-readonly' : undefined}><DocsEditor ref={editorRef} snapshot={snapshot} editable={view === 'edit'} onDirty={() => setDirty(true)} /></div>
          : <SheetsEditor ref={editorRef} snapshot={snapshot} editable={view === 'edit'} bindings={mapping} onDirty={() => setDirty(true)} onEditorValue={() => undefined} />}
      </Suspense></main>
    </div></div>
  </section>;
}
