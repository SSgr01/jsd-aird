import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  LoadingOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import { App, Button, Input, Result, Skeleton, Space, Spin, Tabs, Tag, Typography } from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import type { EditorHandle } from '@/features/template-workspace/types';
import { parseExperimentSnapshot } from '@/features/experiment-workspace/experiment-workbook';
import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import {
  actResearchTest,
  createResearchTestRevision,
  getResearchTest,
  listResearchTestVersions,
  saveResearchTest,
  type ResearchTestDetail,
  type ResearchTestType,
  type ResearchTestVersion,
} from '@/services/research-test/research-test-api';
import { httpClient } from '@/services/http/client';
import { downloadBlob, fetchFileBlob } from '@/services/files/file-api';
import { FilePreviewModal } from '@/components/file-preview';
import './research-test.css';
const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));
const DocsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor,
}));
const text: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  RETURNED: '已退回',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};
function resolveReturnTo(state: unknown, fallback: string) {
  if (typeof state !== 'object' || state === null || !('returnTo' in state)) return fallback;
  const value = (state as { returnTo?: unknown }).returnTo;
  return typeof value === 'string' ? value : fallback;
}
function stringValue(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}
function textValue(value: unknown) {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') {
    return value.toString();
  }
  try {
    return JSON.stringify(value) || '';
  } catch {
    return '';
  }
}
async function parseExcelSnapshot(
  blob: Blob,
  fileName: string,
  id: string,
): Promise<Record<string, unknown>> {
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array', cellDates: false });
  const sheets: Record<string, Record<string, unknown>> = {};
  const sheetOrder: string[] = [];
  workbook.SheetNames.forEach((name, index) => {
    const source = workbook.Sheets[name];
    if (!source) return;
    const range = XLSX.utils.decode_range(source['!ref'] || 'A1');
    const sheetId = `research-test-sheet-${index + 1}`;
    const cellData: Record<string, Record<string, { v: string }>> = {};
    for (let row = range.s.r; row <= range.e.r; row += 1) {
      for (let column = range.s.c; column <= range.e.c; column += 1) {
        const cell = source[XLSX.utils.encode_cell({ r: row, c: column })] as
          { v?: unknown; w?: unknown } | undefined;
        if (!cell || cell.v === undefined || cell.v === null) continue;
        const rowData = (cellData[String(row)] ??= {});
        rowData[String(column)] = { v: textValue(cell.w ?? cell.v) };
      }
    }
    sheets[sheetId] = {
      id: sheetId,
      name,
      rowCount: Math.max(range.e.r + 1, 200),
      columnCount: Math.max(range.e.c + 1, 20),
      cellData,
    };
    sheetOrder.push(sheetId);
  });
  return {
    id,
    snapshotFormatVersion: 3,
    name: fileName,
    sheetOrder,
    sheets,
    styles: {},
  };
}
function buildResearchTestSnapshot(detail: ResearchTestDetail): Record<string, unknown> {
  const saved = detail.editModel.documentSnapshot;
  if (
    saved &&
    Object.keys(saved).length &&
    !isUntouchedExperimentWorkbook(saved, detail.summary.name)
  )
    return saved;
  if (detail.templateSnapshot && Object.keys(detail.templateSnapshot).length)
    return detail.templateSnapshot;
  if (detail.summary.documentFormat === 'WORD') {
    return {
      id: detail.versionId,
      snapshotFormatVersion: 5,
      editorMode: 'UNIVER_DOCS',
      documentStyle: {},
      body: { dataStream: '\r\n', textRuns: [], paragraphs: [{ startIndex: 0 }] },
    };
  }
  const sheetId = 'sheet-1';
  return {
    id: detail.versionId,
    snapshotFormatVersion: 3,
    name: detail.summary.name,
    sheetOrder: [sheetId],
    sheets: {
      [sheetId]: {
        id: sheetId,
        name: '工作表1',
        rowCount: 200,
        columnCount: 20,
        cellData: {},
      },
    },
    styles: {},
  };
}
async function loadResearchTestSnapshot(detail: ResearchTestDetail) {
  const saved = detail.editModel.documentSnapshot;
  const hasSavedSnapshot =
    Boolean(saved && Object.keys(saved).length) &&
    !isUntouchedExperimentWorkbook(saved as Record<string, unknown>, detail.summary.name);
  if (
    hasSavedSnapshot ||
    (detail.templateSnapshot && Object.keys(detail.templateSnapshot).length)
  ) {
    return buildResearchTestSnapshot(detail);
  }

  // Uploaded reports are stored as an attachment first. Convert the original workbook to
  // the same Univer snapshot used by the editor so opening “查看” does not show a blank sheet.
  const sourceFileId = detail.summary.sourceFileId || stringValue(detail.editModel.sourceFileId);
  if (sourceFileId && detail.summary.documentFormat === 'EXCEL') {
    try {
      const sourceName = stringValue(detail.editModel.sourceFileName, detail.summary.name);
      return await parseExcelSnapshot(
        await fetchFileBlob(sourceFileId),
        sourceName,
        detail.versionId,
      );
    } catch {
      // Keep the editable blank fallback when the attachment is unavailable or unreadable.
    }
  }
  return buildResearchTestSnapshot(detail);
}
function isUntouchedExperimentWorkbook(snapshot: Record<string, unknown>, title: string) {
  const order = snapshot.sheetOrder;
  if (!Array.isArray(order) || !order.includes('sheet-record') || !order.includes('sheet-配方数据'))
    return false;
  const allowed = new Set([
    '',
    '字段',
    '内容',
    '实验标题',
    '实验目的',
    '实验方案',
    '结果状态',
    '失败原因分类',
    '主要结论',
    '物料',
    '比例',
    '实际量',
    '单位',
    '__itemId',
    '步骤',
    '操作',
    '温度',
    '时长',
    '项目',
    '结果',
    '判定',
    '时间',
    '类型',
    '说明',
    '处置',
    title,
    '未命名记录',
  ]);
  const sheets = snapshot.sheets as
    Record<string, { cellData?: Record<string, Record<string, { v?: unknown }>> }> | undefined;
  const values: string[] = [];
  Object.values(sheets || {}).forEach((sheet) =>
    Object.values(sheet.cellData || {}).forEach((row) =>
      Object.values(row).forEach((cell) => {
        const value = cell.v;
        values.push(
          typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
            ? String(value)
            : '',
        );
      }),
    ),
  );
  return values.every((value) => allowed.has(value));
}
export function ResearchTestWorkspacePage({ type }: { type: ResearchTestType }) {
  const { id = '' } = useParams(),
    nav = useNavigate(),
    location = useLocation(),
    { message, modal } = App.useApp(),
    editor = useRef<EditorHandle>(null),
    saveRef = useRef<() => Promise<boolean>>(() => Promise.resolve(false)),
    autosaveTimer = useRef<number>();
  const [detail, setDetail] = useState<ResearchTestDetail>(),
    [snapshot, setSnapshot] = useState<Record<string, unknown>>(),
    [versions, setVersions] = useState<ResearchTestVersion[]>([]),
    [view, setView] = useState('preview'),
    [saveState, setSaveState] = useState<SaveState>('SAVED'),
    [busy, setBusy] = useState(false),
    [previewOpen, setPreviewOpen] = useState(false),
    [error, setError] = useState<string>();
  const root = type === 'REPORT' ? 'reports' : 'standards';
  const returnTo = resolveReturnTo(location.state, `/research-test/${root}`);
  const load = useCallback(async () => {
    try {
      const d = await getResearchTest(type, id);
      setDetail(d);
      const s = await loadResearchTestSnapshot(d);
      setSnapshot(s);
      setVersions(await listResearchTestVersions(type, id));
      setView(['DRAFT', 'RETURNED'].includes(d.summary.status) ? 'edit' : 'preview');
      setSaveState('SAVED');
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    }
  }, [id, type]);
  useEffect(() => {
    void load();
  }, [load]);
  useEffect(() => {
    const leave = (e: BeforeUnloadEvent) => {
      if (saveState !== 'SAVED') {
        e.preventDefault();
        e.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', leave);
    return () => {
      window.removeEventListener('beforeunload', leave);
      window.clearTimeout(autosaveTimer.current);
    };
  }, [saveState]);
  const save = async () => {
    if (!detail || !snapshot) return false;
    setSaveState('SAVING');
    try {
      const live = editor.current?.getSnapshot() ?? snapshot;
      const model = parseExperimentSnapshot(live, detail.editModel);
      const d = await saveResearchTest(type, id, {
        revision: detail.summary.lockVersion,
        businessNo: detail.summary.businessNo,
        name: String(model.title || detail.summary.name),
        category: detail.summary.category,
        scope: detail.summary.applicableScope,
        ownerName: detail.summary.ownerName,
        date: detail.summary.businessDate,
        visibility: detail.summary.visibility,
        projectId: detail.summary.projectId,
        stageId: detail.summary.stageId,
        taskId: detail.summary.taskId,
        templateVersionId: detail.templateVersionId,
        templateHash: detail.templateSnapshotHash,
        templateSnapshot: detail.templateSnapshot,
        editModel: model,
        memberSnapshot: detail.memberSnapshot,
        effectiveFrom: detail.effectiveFrom,
        effectiveTo: detail.effectiveTo,
      });
      setDetail(d);
      setSaveState('SAVED');
      message.success('草稿已保存');
      return true;
    } catch (e) {
      setSaveState('DIRTY');
      message.error(e instanceof Error ? e.message : '保存失败');
      return false;
    }
  };
  saveRef.current = save;
  const markDirty = () => {
    setSaveState('DIRTY');
    window.clearTimeout(autosaveTimer.current);
    autosaveTimer.current = window.setTimeout(() => void saveRef.current(), 1500);
  };
  const action = async (
    a: 'submit-review' | 'approve' | 'return' | 'archive',
    comment?: string,
  ) => {
    if (!detail) return;
    if (saveState !== 'SAVED') {
      message.warning('请先保存当前内容');
      return;
    }
    setBusy(true);
    try {
      await actResearchTest(type, id, a, detail.summary.lockVersion, comment);
      message.success('状态已更新');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '状态更新失败');
    } finally {
      setBusy(false);
    }
  };
  const reject = () => {
    let reason = '';
    modal.confirm({
      title: '驳回审核',
      content: (
        <Input.TextArea
          placeholder="请输入驳回原因"
          onChange={(e) => {
            reason = e.target.value;
          }}
        />
      ),
      okText: '确认驳回',
      onOk: () => {
        if (!reason.trim()) throw new Error('请填写驳回原因');
        return action('return', reason);
      },
    });
  };
  const revise = () => {
    if (!detail) return;
    void modal.confirm({
      title: '创建修订版本？',
      content: '历史发布版本将永久保留，新版本以草稿继续编辑。',
      onOk: async () => {
        await createResearchTestRevision(type, id, detail.summary.lockVersion, '发布后修订');
        await load();
      },
    });
  };
  const exportFile = async () => {
    if (!detail) return;
    try {
      const r = await httpClient.get<Blob>(
        `/api/v1/${type === 'REPORT' ? 'comprehensive-reports' : 'test-standards'}/${id}/export`,
        { responseType: 'blob' },
      );
      downloadBlob(
        r.data,
        `${detail.summary.name}${versions.length > 0 ? `-V${versions[0]?.versionNo ?? detail.summary.versionNo}` : ''}.${detail.summary.documentFormat === 'WORD' ? 'docx' : 'xlsx'}`,
      );
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导出失败');
    }
  };
  if (error)
    return (
      <Result
        status="error"
        title="研发测试文档加载失败"
        subTitle={error}
        extra={<Button onClick={() => nav(-1)}>返回</Button>}
      />
    );
  if (!detail || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;
  const editable = ['DRAFT', 'RETURNED'].includes(detail.summary.status) && view === 'edit';
  return (
    <section className="workspace-shell template-business-workspace research-test-workspace">
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => nav(returnTo)}>
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary">
              研发测试中心 / {type === 'REPORT' ? '综合测试报告' : '测试标准方法'}
            </Typography.Text>
            <Typography.Text strong>{detail.summary.name}</Typography.Text>
          </span>
          <Tag color={versions.length > 0 ? 'blue' : 'default'}>
            {versions.length > 0 ? `V${versions[0]?.versionNo ?? detail.summary.versionNo}` : '未发布'}
          </Tag>
          <Tag>{text[detail.summary.status]}</Tag>
        </div>
        <Space wrap>
          <SaveStateBadge state={saveState} />
          <Button icon={<DownloadOutlined />} onClick={() => void exportFile()}>
            导出
          </Button>
          {(detail.summary.sourceFileId || stringValue(detail.editModel.sourceFileId)) && (
            <Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>
              原文件
            </Button>
          )}
          {['PUBLISHED', 'ARCHIVED'].includes(detail.summary.status) && (
            <Button onClick={() => revise()}>创建修订</Button>
          )}
          <Button
            type="primary"
            icon={<SaveOutlined />}
            disabled={!editable}
            loading={saveState === 'SAVING'}
            onClick={() => void save()}
          >
            保存草稿
          </Button>
          {['DRAFT', 'RETURNED'].includes(detail.summary.status) && (
            <Button type="primary" loading={busy} onClick={() => void action('submit-review')}>
              提交审核
            </Button>
          )}
          {detail.summary.status === 'PENDING_REVIEW' && (
            <>
              <Button danger onClick={reject}>
                驳回
              </Button>
              <Button type="primary" loading={busy} onClick={() => void action('approve')}>
                审核并发布
              </Button>
            </>
          )}
          {detail.summary.status === 'PUBLISHED' && (
            <Button onClick={() => void action('archive')}>归档</Button>
          )}
        </Space>
      </header>
      <Tabs
        activeKey={view}
        onChange={(key) => {
          if (key === 'versions' && versions.length === 0) {
            message.info('发布后才会生成版本记录');
            return;
          }
          if (saveState !== 'SAVED' && key === 'versions') {
            message.warning('请先保存当前内容');
            return;
          }
          setView(key);
        }}
        items={[
          {
            key: 'preview',
            label: (
              <>
                <EyeOutlined /> 预览
              </>
            ),
          },
          {
            key: 'edit',
            label: '编辑',
            disabled: !['DRAFT', 'RETURNED'].includes(detail.summary.status),
          },
          ...(versions.length > 0 ? [{ key: 'versions', label: '版本记录' }] : []),
        ]}
      />
      {view === 'versions' ? (
        <main className="workspace-canvas project-versions-panel">
          <VersionHistoryPanel
            versions={versions}
            description="发布与修订均保留不可变快照。"
            getLabel={(v) => text[v.status] || v.status}
            onSelect={(v) => {
              void modal.info({
                title: `版本 V${v.versionNo}`,
                content: v.changeSummary || '该版本保存了完整文档快照。',
              });
            }}
          />
        </main>
      ) : (
        <main className={`workspace-canvas ${!editable ? 'document-readonly' : ''}`}>
          <Suspense fallback={<Spin indicator={<LoadingOutlined spin />} />}>
            {detail.summary.documentFormat === 'WORD' ? (
              <DocsEditor
                ref={editor}
                snapshot={snapshot}
                editable={editable}
                onDirty={markDirty}
              />
            ) : (
              <SheetsEditor
                ref={editor}
                snapshot={snapshot}
                bindings={[]}
                editable={editable}
                onEditorValue={() => undefined}
                onDirty={markDirty}
              />
            )}
          </Suspense>
        </main>
      )}
      <FilePreviewModal
        open={previewOpen}
        onClose={() => setPreviewOpen(false)}
        file={
          detail.summary.sourceFileId || stringValue(detail.editModel.sourceFileId)
            ? {
                fileName: stringValue(detail.editModel.sourceFileName, detail.summary.name),
                contentType: stringValue(
                  detail.editModel.sourceContentType,
                  'application/octet-stream',
                ),
                load: () =>
                  fetchFileBlob(
                    detail.summary.sourceFileId || stringValue(detail.editModel.sourceFileId),
                  ),
              }
            : undefined
        }
      />
    </section>
  );
}
