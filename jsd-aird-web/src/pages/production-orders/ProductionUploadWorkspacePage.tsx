import {
  ArrowLeftOutlined,
  DownloadOutlined,
  FileOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Result,
  Skeleton,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { FilePreviewModal } from '@/components/file-preview/FilePreviewModal';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import { usePermission } from '@/components/auth/usePermission';
import { downloadBlob, fetchFileBlob } from '@/services/files/file-api';
import type { EditorHandle } from '@/features/template-workspace/types';
import { WordNativePreview } from '@/features/template-workspace/WordNativePreview';
import {
  ProductionBusinessPanel,
  createEmptyBusinessSnapshot,
  normalizeBusinessSnapshot,
  type ProductionBusinessSnapshot,
  validateBusinessSnapshot,
} from './ProductionBusinessPanel';
import {
  productionUploadApi,
  productionOrderRecordApi,
  type ProductionUpload,
  type ProductionUploadVersion,
} from '@/services/production-orders/production-upload-api';
import '../quality/quality-pages.css';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

type WorkspaceView = 'recognition' | 'preview' | 'edit' | 'material' | 'attachments' | 'versions';

function isWorkbookSnapshot(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const candidate = value as Record<string, unknown>;
  const sheets = candidate.sheets;
  const sheetOrder = candidate.sheetOrder;
  return Boolean(
    sheets &&
    typeof sheets === 'object' &&
    !Array.isArray(sheets) &&
    Object.keys(sheets).length &&
    Array.isArray(sheetOrder) &&
    sheetOrder.length,
  );
}

function withBusinessSnapshot(
  workbook: Record<string, unknown>,
  business: ProductionBusinessSnapshot,
) {
  return { ...workbook, productionBusiness: business };
}

function formatOf(fileName: string): 'XLSX' | 'PHOTO' | 'DOCX' {
  if (/\.xlsx?$/i.test(fileName)) return 'XLSX';
  if (/\.(png|jpe?g|gif|webp|bmp|tiff?)$/i.test(fileName)) return 'PHOTO';
  return 'DOCX';
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

async function parseExcelSnapshot(blob: Blob, fileName: string): Promise<Record<string, unknown>> {
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array', cellDates: false });
  const sheets: Record<string, Record<string, unknown>> = {};
  const sheetOrder: string[] = [];
  workbook.SheetNames.forEach((name, index) => {
    const source = workbook.Sheets[name];
    if (!source) return;
    const range = XLSX.utils.decode_range(source['!ref'] || 'A1');
    const id = `production-sheet-${index + 1}`;
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
    sheets[id] = {
      id,
      name,
      rowCount: Math.max(range.e.r + 1, 200),
      columnCount: Math.max(range.e.c + 1, 20),
      cellData,
    };
    sheetOrder.push(id);
  });
  return {
    id: `production-${Date.now()}`,
    snapshotFormatVersion: 3,
    name: fileName,
    sheetOrder,
    sheets,
    styles: {},
  };
}

export function ProductionUploadWorkspacePage() {
  const { uploadId = '' } = useParams();
  const navigate = useNavigate();
  const canUpdate = usePermission('production.update');
  const canSubmit = usePermission('production.submit');
  const editorRef = useRef<EditorHandle>(null);
  const [record, setRecord] = useState<ProductionUpload>();
  const [versions, setVersions] = useState<ProductionUploadVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<ProductionUploadVersion>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [business, setBusiness] = useState<ProductionBusinessSnapshot>(createEmptyBusinessSnapshot);
  const [view, setView] = useState<WorkspaceView>('preview');
  const [editorReady, setEditorReady] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [sourcePreviewOpen, setSourcePreviewOpen] = useState(false);
  const [selectingTemplate, setSelectingTemplate] = useState<string>();
  const [error, setError] = useState<string>();
  const [photoUrl, setPhotoUrl] = useState<string>();
  const [msg, holder] = message.useMessage();

  const load = useCallback(async () => {
    setLoading(true);
    setDirty(false);
    setSelectedVersion(undefined);
    setView('preview');
    setEditorReady(false);
    try {
      const [detail, history] = await Promise.all([
        productionOrderRecordApi.get(uploadId),
        productionOrderRecordApi.versions(uploadId).catch(() => []),
      ]);
      setRecord(detail);
      setVersions(history);
      if (formatOf(detail.originalName) === 'PHOTO') {
        const blob = await fetchFileBlob(detail.fileId);
        const url = URL.createObjectURL(blob);
        setPhotoUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return url;
        });
      }
      if (formatOf(detail.originalName) === 'XLSX') {
        const storedSnapshot = detail.workbookSnapshot;
        const storedBusiness = storedSnapshot?.productionBusiness;
        let loadedSnapshot = storedSnapshot;
        if (!isWorkbookSnapshot(loadedSnapshot)) {
          loadedSnapshot = history.find((item) =>
            isWorkbookSnapshot(item.workbookSnapshot),
          )?.workbookSnapshot;
        }
        if (!isWorkbookSnapshot(loadedSnapshot)) {
          loadedSnapshot = await parseExcelSnapshot(
            await fetchFileBlob(detail.fileId),
            detail.originalName,
          );
        }
        const recoveredSnapshot = {
          ...loadedSnapshot,
          productionBusiness: storedBusiness ?? loadedSnapshot.productionBusiness,
        };
        setSnapshot(recoveredSnapshot);
        setBusiness(normalizeBusinessSnapshot(recoveredSnapshot.productionBusiness));
        setEditorReady(false);
      } else if (formatOf(detail.originalName) === 'DOCX') {
        setSnapshot(detail.workbookSnapshot ?? { wordPatches: [] });
      }
      setError(undefined);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '生产单文件加载失败');
    } finally {
      setLoading(false);
    }
  }, [uploadId]);

  useEffect(
    () => () => {
      if (photoUrl) URL.revokeObjectURL(photoUrl);
    },
    [photoUrl],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const saveDraft = async (
    businessOverride: ProductionBusinessSnapshot = business,
    silent = false,
  ): Promise<boolean> => {
    if (!record || !snapshot || !['XLSX', 'DOCX'].includes(formatOf(record.originalName)))
      return false;
    const validationError = validateBusinessSnapshot(businessOverride);
    if (validationError) {
      msg.error(validationError);
      return false;
    }
    setSaving(true);
    try {
      const liveSnapshot = editorRef.current?.getSnapshot();
      const current =
        formatOf(record.originalName) === 'XLSX'
          ? isWorkbookSnapshot(liveSnapshot)
            ? liveSnapshot
            : isWorkbookSnapshot(snapshot)
              ? snapshot
              : undefined
          : (liveSnapshot ?? snapshot);
      if (!current) {
        msg.error('工作簿尚未加载完成，为避免覆盖原数据，本次未保存，请稍后重试');
        return false;
      }
      const nextSnapshot = withBusinessSnapshot(current, businessOverride);
      await productionOrderRecordApi.saveBatch({
        records: [
          { id: record.id, workbookSnapshot: nextSnapshot, lockVersion: record.lockVersion },
        ],
      });
      const saved = await productionOrderRecordApi.get(record.id);
      setRecord(saved);
      setSnapshot(nextSnapshot);
      setBusiness(businessOverride);
      setDirty(false);
      if (!silent) msg.success('生产单草稿已保存');
      return true;
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '保存失败');
      return false;
    } finally {
      setSaving(false);
    }
  };

  const publish = async () => {
    if (!record || selectedVersion) return;
    if (!record.productionName?.trim() || !record.orderNo?.trim()) {
      msg.error('发布前请先填写生产单名称和订单号');
      return;
    }
    const businessError = validateBusinessSnapshot(business);
    if (businessError) {
      msg.error(businessError);
      return;
    }
    if (business.status === 'NOT_ORDERED') {
      msg.error('请先提交下单，再发布生产单');
      return;
    }
    if (business.status === 'VOID') {
      msg.error('已作废生产单不能发布');
      return;
    }
    if (dirty) {
      msg.warning('当前内容尚未保存，请先保存草稿后再发布');
      return;
    }
    setPublishing(true);
    try {
      if (!record.workbookSnapshot) {
        const saved = await saveDraft();
        if (!saved) return;
      }
      await productionOrderRecordApi.publish(record.id);
      msg.success('生产单已发布，并生成新版本');
      const [detail, history] = await Promise.all([
        productionOrderRecordApi.get(record.id),
        productionOrderRecordApi.versions(record.id),
      ]);
      setRecord(detail);
      setVersions(history);
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '发布失败');
    } finally {
      setPublishing(false);
    }
  };

  const chooseTemplate = async (templateVersionId: string) => {
    setSelectingTemplate(templateVersionId);
    try {
      await productionUploadApi.selectTemplate(uploadId, templateVersionId);
      msg.success('模板已选择，系统开始重新识别');
      await load();
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '模板选择失败');
    } finally {
      setSelectingTemplate(undefined);
    }
  };

  const restoreVersion = (version: ProductionUploadVersion) => {
    if (!isExcel) return;
    setSnapshot(version.workbookSnapshot);
    setBusiness(normalizeBusinessSnapshot(version.workbookSnapshot.productionBusiness));
    setSelectedVersion(undefined);
    setView('edit');
    setEditorReady(false);
    setDirty(true);
    msg.info(`已载入版本 V${version.versionNo}，保存草稿后生效`);
  };

  const switchView = (next: WorkspaceView) => {
    if (next === 'versions' && versions.length === 0) {
      msg.info('发布后才会生成版本记录');
      return;
    }
    if (next === 'versions' && dirty) {
      msg.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    const leavingWorkbook =
      ['preview', 'edit'].includes(view) && !['preview', 'edit'].includes(next);
    const enteringWorkbook =
      !['preview', 'edit'].includes(view) && ['preview', 'edit'].includes(next);
    if (leavingWorkbook) {
      const current = editorRef.current?.getSnapshot();
      if (isWorkbookSnapshot(current)) {
        setSnapshot(withBusinessSnapshot(current, business));
      }
    }
    if (leavingWorkbook || enteringWorkbook) setEditorReady(false);
    setSelectedVersion(undefined);
    setView(next);
  };

  const exportRecord = async () => {
    if (!record) return;
    setExporting(true);
    try {
      const blob = await productionOrderRecordApi.export(record.id);
      const extension = record.originalName.includes('.')
        ? record.originalName.slice(record.originalName.lastIndexOf('.'))
        : '';
      const baseName = record.productionName || record.originalName || '生产单';
      const fileName = extension && !baseName.toLowerCase().endsWith(extension.toLowerCase())
        ? `${baseName}${extension}`
        : baseName;
      downloadBlob(blob, fileName);
      msg.success('生产单已导出');
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '生产单导出失败');
    } finally {
      setExporting(false);
    }
  };

  if (loading)
    return (
      <div className="workspace-shell">
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    );
  if (error || !record) {
    return (
      <Result
        status="error"
        title="生产单文件加载失败"
        subTitle={error || '记录不存在'}
        extra={<Button onClick={() => navigate('/production-orders/list')}>返回生产单查看</Button>}
      />
    );
  }

  const format = formatOf(record.originalName);
  const isExcel = format === 'XLSX';
  const isPhoto = format === 'PHOTO';
  const displayedSnapshot = selectedVersion?.workbookSnapshot ?? snapshot;
  const latestVersion = versions[0];
  const readonly = !['edit', 'material', 'attachments'].includes(view) || Boolean(selectedVersion);
  const editable = (isExcel || format === 'DOCX') && !readonly && canUpdate;
  const waitingForEditor = isExcel && view === 'edit' && !editorReady;
  const saveState: SaveState = saving ? 'SAVING' : dirty ? 'DIRTY' : 'SAVED';
  const workspaceActions = () => (
    <>
      <SaveStateBadge state={saveState} />
      <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportRecord()}>
        导出
      </Button>
      <Button icon={<FileOutlined />} onClick={() => setSourcePreviewOpen(true)}>
        原文
      </Button>
      {selectedVersion && <Button onClick={() => setSelectedVersion(undefined)}>返回</Button>}
      {!selectedVersion && (
        <>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!editable || waitingForEditor}
            onClick={() => void saveDraft()}
          >
            保存草稿
          </Button>
          <Button
            type="primary"
            loading={publishing}
            disabled={!editable || !canSubmit || dirty || saving}
            onClick={() => void publish()}
          >
            发布
          </Button>
        </>
      )}
    </>
  );
  return (
    <section className="workspace-shell template-business-workspace quality-excel-workspace production-upload-workspace">
      {holder}
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/production-orders/list')}
          >
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              生产单管理 / 生产单查看
            </Typography.Text>
            <Typography.Text strong>
              {record.originalName}
            </Typography.Text>
          </span>
          <Tag color={latestVersion ? 'blue' : 'default'}>
            {latestVersion ? `V${latestVersion.versionNo}` : '未发布'}
          </Tag>
        </div>
        <Space className="production-workspace-actions production-desktop-actions" wrap>
          {workspaceActions()}
        </Space>
      </header>
      <Space className="production-mobile-actions" wrap>
        {workspaceActions()}
      </Space>
      <nav className="workspace-view-tabs" aria-label="生产单文件工作区">
        <Tabs
          activeKey={view}
          onChange={(key) => switchView(key as WorkspaceView)}
          items={[
            { key: 'preview', label: '预览' },
            { key: 'edit', label: '编辑', disabled: !isExcel && format !== 'DOCX' },
            ...(versions.length > 0 ? [{ key: 'versions', label: '版本记录' }] : []),
            { key: 'material', label: '实际投料单', disabled: !isExcel },
            { key: 'attachments', label: '工艺附件', disabled: !isExcel },
          ]}
        />
      </nav>
      <div
        className={`workspace-main-stage ${['material', 'attachments'].includes(view) ? 'is-business-tab' : ''}`}
      >
        <main
          className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''} ${['material', 'attachments'].includes(view) ? 'production-business-panel-standalone' : ''}`}
        >
          {view === 'recognition' ? (
            <section className="quality-version-panel">
              <Typography.Title level={4}>
                {isPhoto ? '图片识别结果' : 'XLSX 识别结果'}
              </Typography.Title>
              <Alert
                type={isPhoto && record.matchMode === 'USER_REVIEW' ? 'warning' : 'success'}
                showIcon
                message={
                  record.matchMode === 'EXACT_MANIFEST'
                    ? '已通过模板清单精确匹配'
                    : record.matchMode === 'SIMILAR_AUTO'
                      ? '已自动匹配相似模板'
                      : record.matchMode === 'USER_SELECTED_TEMPLATE'
                        ? '已使用所选模板解析'
                        : isPhoto
                          ? '需要选择图片识别模板'
                          : '未使用模板，已按原文件解析'
                }
                description={
                  record.failureMessage ??
                  record.recognitionResult?.message ??
                  (isPhoto && record.matchMode === 'USER_REVIEW'
                    ? '图片识别需要先选择已发布模板，确认后才会调用图片识别模型。'
                    : `匹配分数：${Math.round((record.templateMatchScore ?? 0) * 100)}%`)
                }
              />
              <Card size="small" title="候选模板" style={{ marginTop: 16 }}>
                {(record.recognitionResult?.templateCandidates ?? []).length ? (
                  record.recognitionResult?.templateCandidates?.map((item) => (
                    <div
                      key={item.templateVersionId}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'space-between',
                        gap: 12,
                      }}
                    >
                      <span>
                        <b>{item.templateName}</b> · {item.templateCode} ·{' '}
                        {Math.round(item.score * 100)}%
                      </span>
                      {['USER_REVIEW', 'NO_TEMPLATE'].includes(record.matchMode ?? '') && (
                        <Button
                          size="small"
                          type="primary"
                          loading={selectingTemplate === item.templateVersionId}
                          disabled={Boolean(selectingTemplate)}
                          onClick={() => void chooseTemplate(item.templateVersionId)}
                        >
                          选择此模板
                        </Button>
                      )}
                    </div>
                  ))
                ) : (
                  <Empty description="没有可匹配的已发布 XLSX 模板" />
                )}
              </Card>
              <Card size="small" title="抽取数据" style={{ marginTop: 16 }}>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                  {JSON.stringify(record.recognitionResult?.data ?? {}, null, 2)}
                </pre>
              </Card>
            </section>
          ) : view === 'versions' && !selectedVersion ? (
            <VersionHistoryPanel
              versions={versions}
              onSelect={setSelectedVersion}
              getLabel={() => '发布版本'}
            />
          ) : view === 'material' || view === 'attachments' ? (
            <section className="quality-record-editor production-business-panel-shell">
              <div className="quality-record-editor-title">
                <Typography.Title level={4} style={{ margin: 0 }}>
                  {view === 'material' ? '实际投料单' : '工艺附件'}
                </Typography.Title>
                <Typography.Text type="secondary">
                  {view === 'material'
                    ? '上传并查看实际投料单图片'
                    : '上传、下载并管理生产工艺文件'}
                </Typography.Text>
              </div>
              <ProductionBusinessPanel
                value={business}
                editable={editable}
                section={view === 'material' ? 'material' : 'attachments'}
                onAttachmentsChange={(next) => saveDraft(next, true)}
                onChange={(next) => {
                  setBusiness(next);
                  setDirty(true);
                }}
              />
            </section>
          ) : (
            <section className="quality-record-editor">
              <div className="quality-record-editor-title">
                <Typography.Title level={4} style={{ margin: 0 }}>
                  {selectedVersion
                    ? `版本 V${selectedVersion.versionNo}`
                    : view === 'edit'
                      ? '编辑数据'
                      : '数据预览'}
                </Typography.Title>
                {selectedVersion && (
                  <Space wrap>
                    <Typography.Text type="secondary">
                      {selectedVersion.createdBy} ·{' '}
                      {new Date(selectedVersion.createdAt).toLocaleString()}
                    </Typography.Text>
                    {canUpdate && isExcel && (
                      <Button size="small" onClick={() => restoreVersion(selectedVersion)}>
                        以此版本恢复草稿
                      </Button>
                    )}
                  </Space>
                )}
              </div>
              {isExcel && displayedSnapshot ? (
                <div className="quality-excel-canvas" key={selectedVersion?.id ?? 'current'}>
                  <Suspense
                    fallback={
                      <div className="quality-excel-loading">
                        <Spin tip="正在加载 Excel 工作区" />
                      </div>
                    }
                  >
                    <SheetsEditor
                      ref={editorRef}
                      snapshot={displayedSnapshot}
                      bindings={[]}
                      editable={editable}
                      onReady={() => setEditorReady(true)}
                      onDirty={() => {
                        if (editorReady) setDirty(true);
                      }}
                      onEditorValue={() => undefined}
                    />
                  </Suspense>
                </div>
              ) : isPhoto ? (
                photoUrl ? (
                  <div style={{ padding: 24, textAlign: 'center' }}>
                    <img
                      src={photoUrl}
                      alt={record.originalName}
                      style={{ maxWidth: '100%', maxHeight: '72vh', objectFit: 'contain' }}
                    />
                  </div>
                ) : (
                  <Empty description="图片预览加载中" />
                )
              ) : (
                <WordNativePreview
                  versionId={record.id}
                  snapshot={displayedSnapshot ?? {}}
                  documentStructure={undefined}
                  editable={editable}
                  bindings={[]}
                  onDirty={() => setDirty(true)}
                  onEditorValue={() => undefined}
                  onPatch={(operation) =>
                    setSnapshot((current) => ({
                      ...(current ?? {}),
                      wordPatches: [
                        ...(((current ?? {}).wordPatches as unknown[]) ?? []),
                        operation,
                      ],
                    }))
                  }
                  loadPreview={() => fetchFileBlob(record.fileId)}
                />
              )}
            </section>
          )}
        </main>
      </div>
      <FilePreviewModal
        open={sourcePreviewOpen}
        onClose={() => setSourcePreviewOpen(false)}
        file={{
          fileName: record.originalName,
          contentType: record.contentType,
          size: record.size,
          load: () => fetchFileBlob(record.fileId),
        }}
      />
    </section>
  );
}
