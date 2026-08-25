import { ArrowLeftOutlined, HistoryOutlined, EyeOutlined, SaveOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Result, Skeleton, Space, Spin, Tabs, Tag, Typography, message } from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { usePermission } from '@/components/auth/usePermission';
import { fetchFileBlob } from '@/services/files/file-api';
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

type WorkspaceView = 'recognition' | 'preview' | 'edit' | 'business' | 'versions';

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
  const [view, setView] = useState<WorkspaceView>('recognition');
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [selectingTemplate, setSelectingTemplate] = useState<string>();
  const [error, setError] = useState<string>();
  const [photoUrl, setPhotoUrl] = useState<string>();
  const [msg, holder] = message.useMessage();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [detail, history] = await Promise.all([
        productionOrderRecordApi.get(uploadId).catch(() => productionUploadApi.get(uploadId)),
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
        const loadedSnapshot = detail.workbookSnapshot ??
          (await parseExcelSnapshot(await fetchFileBlob(detail.fileId), detail.originalName));
        setSnapshot(loadedSnapshot);
        setBusiness(normalizeBusinessSnapshot(loadedSnapshot.productionBusiness));
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

  useEffect(() => () => {
    if (photoUrl) URL.revokeObjectURL(photoUrl);
  }, [photoUrl]);

  useEffect(() => {
    void load();
  }, [load]);

  const saveDraft = async (): Promise<boolean> => {
    if (!record || !snapshot || !['XLSX', 'DOCX'].includes(formatOf(record.originalName))) return false;
    const validationError = validateBusinessSnapshot(business);
    if (validationError) {
      msg.error(validationError);
      return false;
    }
    setSaving(true);
    try {
      const current = editorRef.current?.getSnapshot() ?? snapshot;
      const nextSnapshot = { ...current, productionBusiness: business };
      await productionOrderRecordApi.saveBatch({
        records: [{ id: record.id, workbookSnapshot: nextSnapshot, lockVersion: record.lockVersion }],
      });
      const saved = await productionOrderRecordApi.get(record.id);
      setRecord(saved);
      setSnapshot(nextSnapshot);
      setDirty(false);
      msg.success('生产单草稿已保存');
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
    setView('business');
    setDirty(true);
    msg.info(`已载入版本 V${version.versionNo}，保存草稿后生效`);
  };

  const switchView = (next: WorkspaceView) => {
    if (next === 'versions' && dirty) {
      msg.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    setSelectedVersion(undefined);
    setView(next);
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
  const readonly = !['edit', 'business'].includes(view) || Boolean(selectedVersion);
  const editable = (isExcel || format === 'DOCX') && !readonly && canUpdate;
  const saveState: SaveState = saving ? 'SAVING' : dirty ? 'DIRTY' : 'SAVED';
  const meta = [record.productionName, record.orderNo, record.productName, record.category]
    .filter(Boolean)
    .join(' · ');

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
          <div>
            <Typography.Text type="secondary">生产单管理 / 生产单查看</Typography.Text>
            <Typography.Title level={4} style={{ margin: 0 }}>
              {record.originalName}
            </Typography.Title>
            {(meta || record.manufactureDate) && (
              <Typography.Text type="secondary">
                {meta}
                {record.manufactureDate ? ` · 制造日期 ${record.manufactureDate}` : ''}
              </Typography.Text>
            )}
          </div>
          <Tag color={latestVersion ? 'blue' : 'default'}>
            {latestVersion ? `V${latestVersion.versionNo}` : '未发布'}
          </Tag>
        </div>
        <Space>
          <SaveStateBadge state={saveState} />
          {selectedVersion && <Button onClick={() => setSelectedVersion(undefined)}>返回</Button>}
          {!selectedVersion && (
            <>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                loading={saving}
                disabled={!editable}
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
        </Space>
      </header>
      <nav className="workspace-view-tabs" aria-label="生产单文件工作区">
        <Tabs
          activeKey={view}
          onChange={(key) => switchView(key as WorkspaceView)}
          items={[
            { key: 'recognition', label: '识别结果' },
            {
              key: 'preview',
              label: (
                <>
                  <EyeOutlined /> 预览
                </>
              ),
            },
            { key: 'edit', label: '编辑', disabled: !isExcel && format !== 'DOCX' },
            { key: 'business', label: '业务数据', disabled: !isExcel },
            {
              key: 'versions',
              label: (
                <>
                  <HistoryOutlined /> 版本记录
                </>
              ),
            },
          ]}
        />
      </nav>
      <div className="workspace-main-stage">
        <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
          {view === 'recognition' ? (
            <section className="quality-version-panel">
              <Typography.Title level={4}>{isPhoto ? '图片识别结果' : 'XLSX 识别结果'}</Typography.Title>
              <Alert
                type={record.matchMode === 'USER_REVIEW' ? 'warning' : 'success'}
                showIcon
                message={record.matchMode === 'EXACT_MANIFEST' ? '已通过模板清单精确匹配'
                  : record.matchMode === 'SIMILAR_AUTO' ? '已自动匹配相似模板'
                    : '需要人工确认模板'}
                description={record.failureMessage ?? (isPhoto && record.matchMode === 'USER_REVIEW'
                  ? '图片识别需要先选择已发布模板，确认后才会调用图片识别模型。'
                  : `匹配分数：${Math.round((record.templateMatchScore ?? 0) * 100)}%`)}
              />
              <Card size="small" title="候选模板" style={{ marginTop: 16 }}>
                {(record.recognitionResult?.templateCandidates ?? []).length
                  ? record.recognitionResult?.templateCandidates?.map((item) => (
                    <div key={item.templateVersionId} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
                      <span><b>{item.templateName}</b> · {item.templateCode} · {Math.round(item.score * 100)}%</span>
                      {record.matchMode === 'USER_REVIEW' && (
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
                  : <Empty description="没有可匹配的已发布 XLSX 模板" />}
              </Card>
              <Card size="small" title="抽取数据" style={{ marginTop: 16 }}>
                <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                  {JSON.stringify(record.recognitionResult?.data ?? {}, null, 2)}
                </pre>
              </Card>
            </section>
          ) : view === 'versions' && !selectedVersion ? (
            <section className="quality-version-panel">
              <Typography.Title level={4}>版本记录</Typography.Title>
              <Typography.Text type="secondary">
                每次发布都会保留完整数据快照，可追溯发布人和发布时间。
              </Typography.Text>
              {versions.length ? (
                <div className="quality-version-list">
                  {versions.map((item) => (
                    <button
                      className="quality-version-card"
                      key={item.id}
                      onClick={() => setSelectedVersion(item)}
                    >
                      <span className="quality-version-no">V{item.versionNo}</span>
                      <span>
                        <b>发布版本</b>
                        <small>
                          {item.createdBy} · {new Date(item.createdAt).toLocaleString()}
                        </small>
                      </span>
                    </button>
                  ))}
                </div>
              ) : (
                <Empty description="暂无版本记录" />
              )}
            </section>
          ) : view === 'business' ? (
            <section className="quality-record-editor production-business-panel-shell">
              <div className="quality-record-editor-title">
                <Typography.Title level={4} style={{ margin: 0 }}>生产业务数据</Typography.Title>
                <Typography.Text type="secondary">生产任务、投料、M687 配方、巡检、签名、库存及研发联动</Typography.Text>
              </div>
              <ProductionBusinessPanel
                value={business}
                editable={editable}
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
                      onDirty={() => setDirty(true)}
                      onEditorValue={() => undefined}
                    />
                  </Suspense>
                </div>
              ) : isPhoto ? (
                photoUrl ? (
                  <div style={{ padding: 24, textAlign: 'center' }}>
                    <img src={photoUrl} alt={record.originalName} style={{ maxWidth: '100%', maxHeight: '72vh', objectFit: 'contain' }} />
                  </div>
                ) : <Empty description="图片预览加载中" />
              ) : (
                <WordNativePreview
                  versionId={record.id}
                  snapshot={displayedSnapshot ?? {}}
                  documentStructure={undefined}
                  editable={editable}
                  bindings={[]}
                  onDirty={() => setDirty(true)}
                  onEditorValue={() => undefined}
                  onPatch={(operation) => setSnapshot((current) => ({
                    ...(current ?? {}),
                    wordPatches: [...(((current ?? {}).wordPatches as unknown[]) ?? []), operation],
                  }))}
                  loadPreview={() => fetchFileBlob(record.fileId)}
                />
              )}
            </section>
          )}
        </main>
      </div>
    </section>
  );
}
