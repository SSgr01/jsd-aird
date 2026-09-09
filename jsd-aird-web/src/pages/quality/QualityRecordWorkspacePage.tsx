import {
  ArrowLeftOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileOutlined,
  HistoryOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Input,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { downloadBlob, fetchFileBlob } from '@/services/files';
import type { EditorHandle } from '@/features/template-workspace/types';
import {
  qualityApi,
  type QualityRecord,
  type QualityRecordVersion,
  type QualityType,
} from '@/services/quality/quality-api';
import './quality-pages.css';

type ViewMode = 'preview' | 'edit' | 'versions';
const textValue = (value: unknown) =>
  typeof value === 'string' ? value : value == null ? '' : JSON.stringify(value);

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

async function parseExcelSnapshot(blob: Blob, fileName: string): Promise<Record<string, unknown>> {
  const XLSX = await import('xlsx');
  const workbook = XLSX.read(await blob.arrayBuffer(), { type: 'array', cellDates: false });
  const sheets: Record<string, Record<string, unknown>> = {};
  const sheetOrder: string[] = [];
  workbook.SheetNames.forEach((name, index) => {
    const source = workbook.Sheets[name];
    if (!source) return;
    const range = XLSX.utils.decode_range(source['!ref'] || 'A1');
    const id = `quality-sheet-${index + 1}`;
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
    id: `quality-${Date.now()}`,
    snapshotFormatVersion: 3,
    name: fileName,
    sheetOrder,
    sheets,
    styles: {},
  };
}

function setWorkbookCell(
  cellData: Record<string, Record<string, { v: unknown }>>,
  row: number,
  column: number,
  value: unknown,
) {
  const rowData = (cellData[String(row)] ??= {});
  rowData[String(column)] = { v: value ?? '' };
}

function buildQualityWorkbookSnapshot(
  record: QualityRecord,
  type: QualityType,
): Record<string, unknown> {
  const cellData: Record<string, Record<string, { v: unknown }>> = {};
  setWorkbookCell(cellData, 0, 0, '字段');
  setWorkbookCell(cellData, 0, 1, '内容');
  type.fields.forEach((field, index) => {
    setWorkbookCell(cellData, index + 1, 0, field.label);
    setWorkbookCell(cellData, index + 1, 1, record.data[field.key] ?? '');
  });
  const sheetId = 'quality-record';
  return {
    id: `quality-${record.id}`,
    snapshotFormatVersion: 3,
    name: record.categoryName || type.name,
    sheetOrder: [sheetId],
    sheets: {
      [sheetId]: {
        id: sheetId,
        name: type.name,
        rowCount: Math.max(200, type.fields.length + 2),
        columnCount: 8,
        cellData,
      },
    },
    styles: {},
  };
}

function parseQualityWorkbookData(
  snapshot: Record<string, unknown>,
  type: QualityType,
  current: Record<string, unknown>,
): Record<string, unknown> {
  const sheets = snapshot.sheets as Record<string, Record<string, unknown>> | undefined;
  const next = { ...current };
  const normalize = (value: unknown) => textValue(value).trim().toLowerCase()
    .replace(/[\s:_\-（）()[\]]/g, '').replace(/\//g, '');
  const orderedIds = Array.isArray(snapshot.sheetOrder)
    ? (snapshot.sheetOrder as unknown[]).map(String)
    : Object.keys(sheets ?? {});
  const orderedSheets = orderedIds.map((id) => sheets?.[id]).filter(Boolean) as Record<string, unknown>[];
  // Generated quality templates use a vertical label/value layout.
  const generated = sheets?.['quality-record'];
  if (generated) {
    const cellData = (generated.cellData as Record<string, Record<string, { v?: unknown }>>) ?? {};
    type.fields.forEach((field, index) => {
      const value = cellData[String(index + 1)]?.['1']?.v;
      if (value !== undefined) next[field.key] = textValue(value);
    });
  }
  // Uploaded workbooks keep their original sheet ids and layout. Resolve
  // field labels against both a horizontal header row and label/value pairs so
  // editing an imported workbook also updates the structured record fields.
  for (const field of type.fields) {
    const labels = new Set([normalize(field.label), normalize(field.key)]);
    let resolved: string | undefined;
    for (const sheet of orderedSheets) {
      const rows = (sheet.cellData as Record<string, Record<string, { v?: unknown }>>) ?? {};
      const rowEntries = Object.entries(rows).sort(([left], [right]) => Number(left) - Number(right));
      for (let rowIndex = 0; rowIndex < rowEntries.length; rowIndex += 1) {
        const rowEntry = rowEntries[rowIndex];
        if (!rowEntry) continue;
        const row = rowEntry[1];
        const cells = Object.entries(row).sort(([left], [right]) => Number(left) - Number(right));
        for (let index = 0; index < cells.length; index += 1) {
          const cellEntry = cells[index];
          if (!cellEntry) continue;
          const [column, cell] = cellEntry;
          if (!labels.has(normalize(cell?.v))) continue;
          const right = cells[index + 1]?.[1]?.v;
          if (right !== undefined && textValue(right).trim()) {
            resolved = textValue(right).trim();
            break;
          }
          const below = rowEntries[rowIndex + 1]?.[1]?.[column]?.v;
          if (below !== undefined && textValue(below).trim()) resolved = textValue(below).trim();
          if (resolved) break;
        }
        if (resolved) break;
      }
      if (resolved) break;
    }
    if (resolved) next[field.key] = resolved;
  }
  return next;
}

export function QualityRecordWorkspacePage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const [types, setTypes] = useState<QualityType[]>([]);
  const [record, setRecord] = useState<QualityRecord>();
  const [draft, setDraft] = useState<Record<string, unknown>>({});
  const [versions, setVersions] = useState<QualityRecordVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<QualityRecordVersion>();
  const [view, setView] = useState<ViewMode>('edit');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [downloadingSource, setDownloadingSource] = useState(false);
  const [error, setError] = useState<string>();
  const [workbook, setWorkbook] = useState<Record<string, unknown>>();
  const editorRef = useRef<EditorHandle>(null);
  const [msg, holder] = message.useMessage();
  const isDesktop = useDesktopEditing();

  const load = useCallback(async () => {
    try {
      const [detail, history, definitions] = await Promise.all([
        qualityApi.record(id),
        qualityApi.versions(id),
        qualityApi.types(),
      ]);
      setRecord(detail);
      setDraft({ ...detail.data });
      let initialWorkbook = detail.workbookSnapshot && Object.keys(detail.workbookSnapshot).length
        ? detail.workbookSnapshot
        : undefined;
      let sourceWorkbook: Record<string, unknown> | undefined;
      // XLSX uploads are parsed server-side into a Univer snapshot that keeps
      // sheets, merged cells, dimensions and styles. Only fall back to the
      // lightweight browser parser when an older record has no stored snapshot.
      if (detail.sourceFileId && !initialWorkbook && /\.(xls|xlsx)$/i.test(detail.sourceFileName || '')) {
        try {
          sourceWorkbook = await parseExcelSnapshot(
            await fetchFileBlob(detail.sourceFileId),
            detail.sourceFileName || detail.businessNo,
          );
          initialWorkbook ??= sourceWorkbook;
        } catch {
          /* structured fallback remains available */
        }
      }
      if (!initialWorkbook && !sourceWorkbook) {
        initialWorkbook = buildQualityWorkbookSnapshot(
          detail,
          definitions.find((item) => item.id === detail.businessType) ?? {
            id: detail.businessType,
            name: detail.categoryName,
            prefix: '',
            fields: [],
          },
        );
      }
      setWorkbook(initialWorkbook);
      setVersions(history);
      setTypes(definitions);
      setError(undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : '品管数据加载失败');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const exportRecord = async () => {
    if (!record) return;
    try {
      const blob = await qualityApi.exportRecord(record.id);
      const fileName = record.businessNo.toLowerCase().endsWith('.xlsx')
        ? record.businessNo
        : `${record.businessNo}.xlsx`;
      downloadBlob(blob, fileName);
      msg.success('品管数据已导出');
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '品管数据导出失败');
    }
  };

  const downloadSource = async () => {
    if (!record?.sourceFileId) return;
    setDownloadingSource(true);
    try {
      const blob = await fetchFileBlob(record.sourceFileId);
      downloadBlob(blob, record.sourceFileName || record.displayName || record.businessNo || '品管原文');
      msg.success('原文已下载');
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '原文下载失败');
    } finally {
      setDownloadingSource(false);
    }
  };

  const type = useMemo(
    () => types.find((item) => item.id === record?.businessType),
    [types, record?.businessType],
  );
  const fields = type?.fields ?? [];
  const update = (key: string, value: string) => {
    setDraft((current) => ({ ...current, [key]: value }));
    setDirty(true);
  };
  const save = async (): Promise<boolean> => {
    if (!record || !type) return false;
    setSaving(true);
    try {
      const currentWorkbook = workbook ? editorRef.current?.getSnapshot() || workbook : undefined;
      const nextDraft = currentWorkbook
        ? parseQualityWorkbookData(currentWorkbook, type, draft)
        : draft;
      for (const field of fields) {
        const value = textValue(nextDraft[field.key]).trim();
        if (field.kind === 'number' && value && Number(value) < 0) {
          msg.warning(`${field.label}不能为负数`);
          setSaving(false);
          return false;
        }
      }
      if (record.businessType === 'defect' && textValue(nextDraft.status) === '已关闭') {
        if (!textValue(nextDraft.cause).trim()) {
          msg.warning('关闭不良单前必须填写原因分析');
          setSaving(false);
          return false;
        }
        if (!textValue(nextDraft.correctiveAction).trim()) {
          msg.warning('关闭不良单前必须填写纠正措施');
          setSaving(false);
          return false;
        }
      }
      const businessNo = textValue(
        nextDraft[type.fields.find((field) => field.key.endsWith('No'))?.key ?? ''] ??
          record.businessNo,
      );
      await qualityApi.saveBatch({
        type: record.businessType,
        categoryId: record.categoryId,
        records: [
          {
            id: record.id,
            businessNo,
            data: nextDraft,
            workbookSnapshot: currentWorkbook,
            lockVersion: record.lockVersion,
          },
        ],
        deleteIds: [],
      });
      msg.success('品管数据草稿已保存');
      setDirty(false);
      await load();
      return true;
    } catch (e) {
      msg.error(e instanceof Error ? e.message : '保存失败');
      return false;
    } finally {
      setSaving(false);
    }
  };
  const publish = async () => {
    if (!record || selectedVersion) return;
    if (dirty) {
      msg.warning('当前内容尚未保存，请先保存草稿后再发布');
      return;
    }
    setPublishing(true);
    try {
      // Uploaded/new records may not have a stored workbook snapshot yet. Persist
      // the current workbook before creating the publish version so the version
      // always contains the exact Excel snapshot that was published.
      if (!record.workbookSnapshot && workbook) {
        const saved = await save();
        if (!saved) return;
      }
      await qualityApi.publishRecord(record.id);
      msg.success('品管数据已发布，并生成新版本');
      await load();
    } catch (e) {
      msg.error(e instanceof Error ? e.message : '发布失败');
    } finally {
      setPublishing(false);
    }
  };
  const switchView = (next: ViewMode) => {
    if (next === 'versions' && versions.length === 0) {
      msg.info('发布后才会生成版本记录');
      return;
    }
    if (next === 'versions' && dirty) {
      msg.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    setSelectedVersion(undefined);
    setView(next);
  };

  if (error)
    return (
      <div className="quality-page">
        <Alert type="error" message="品管数据加载失败" description={error} showIcon />
        <Button style={{ marginTop: 16 }} onClick={() => navigate('/quality/view')}>
          返回品管数据
        </Button>
      </div>
    );
  if (!record || !type)
    return (
      <div className="quality-page">
        <Spin tip="正在加载品管数据" />
      </div>
    );
  const displayed = selectedVersion?.data ?? (view === 'preview' ? record.data : draft);
  const displayedWorkbook =
    selectedVersion?.workbookSnapshot ?? (selectedVersion ? undefined : workbook);
  const readonly = view !== 'edit' || Boolean(selectedVersion);
  const editable = isDesktop && !readonly;
  const saveState: SaveState = saving ? 'SAVING' : dirty ? 'DIRTY' : 'SAVED';

  return (
    <section className="workspace-shell template-business-workspace quality-excel-workspace">
      {holder}
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/quality/view')}
          >
            返回
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              品管部数据 / {record.categoryName || '数据查看'}
            </Typography.Text>
            <Typography.Text strong>
              {record.displayName || record.sourceFileName || record.businessNo}
            </Typography.Text>
          </span>
          <Tag color={versions.length ? 'blue' : 'default'}>
            {versions.length ? `V${versions[0]?.versionNo ?? ''}` : '未发布'}
          </Tag>
        </div>
        <Space wrap>
          <SaveStateBadge state={saveState} />
          <Button icon={<DownloadOutlined />} onClick={() => void exportRecord()}>
            导出
          </Button>
          {record.sourceFileId && (
            <Button icon={<FileOutlined />} loading={downloadingSource} onClick={() => void downloadSource()}>
              原文
            </Button>
          )}
          {selectedVersion && (
            <Button onClick={() => setSelectedVersion(undefined)}>
              返回
            </Button>
          )}
          {!selectedVersion && (
            <>
              <Button
                type="primary"
                icon={<SaveOutlined />}
                loading={saving}
                disabled={!editable}
                onClick={() => void save()}
              >
                保存草稿
              </Button>
              <Button
                type="primary"
                loading={publishing}
                disabled={!editable || dirty || saving}
                onClick={() => void publish()}
              >
                发布
              </Button>
            </>
          )}
        </Space>
      </header>
      <nav className="workspace-view-tabs" aria-label="品管数据页面">
        <Tabs
          activeKey={view}
          onChange={(key) => switchView(key as ViewMode)}
          items={[
            {
              key: 'preview',
              label: (
                <>
                  <EyeOutlined /> 预览
                </>
              ),
            },
            { key: 'edit', label: '编辑', disabled: !isDesktop },
            ...(versions.length > 0
              ? [{
                key: 'versions',
                label: (
                  <>
                    <HistoryOutlined /> 版本记录
                  </>
                ),
              }]
              : []),
          ]}
        />
      </nav>
      <div className="workspace-main-stage">
        <div className="template-workspace-grid quality-single-workspace-grid">
          <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
            {view === 'versions' && !selectedVersion ? (
              <VersionHistoryPanel
                versions={versions}
                onSelect={setSelectedVersion}
                getLabel={(item) => item.changeType === 'INITIAL'
                  ? '初始版本'
                  : item.changeType === 'PUBLISH'
                    ? '发布版本'
                    : item.changeType === 'UPLOAD'
                      ? '上传生成'
                      : '编辑保存'}
              />
            ) : (
              <section className="quality-record-editor">
                <div className="quality-record-editor-title">
                  <Space>
                    <Typography.Title level={4} style={{ margin: 0 }}>
                      {selectedVersion
                        ? `版本 V${selectedVersion.versionNo}`
                        : view === 'edit'
                          ? '编辑数据'
                          : '数据预览'}
                    </Typography.Title>
                  </Space>
                  {selectedVersion && (
                    <Typography.Text type="secondary">
                      {selectedVersion.createdBy} ·{' '}
                      {new Date(selectedVersion.createdAt).toLocaleString()}
                    </Typography.Text>
                  )}
                  {view === 'preview' && !selectedVersion && (
                    <Typography.Text type="secondary">当前为预览状态，不可编辑</Typography.Text>
                  )}
                </div>
                {displayedWorkbook ? (
                  <div
                    className="quality-excel-canvas"
                    key={selectedVersion?.id ?? 'current'}
                  >
                    <Suspense
                      fallback={
                        <div className="quality-excel-loading">
                          <Spin tip="正在加载 Excel" />
                        </div>
                      }
                    >
                      <SheetsEditor
                        ref={editorRef}
                        snapshot={displayedWorkbook}
                        bindings={[]}
                        editable={editable}
                        permissionDialogVisible={editable}
                        onDirty={() => setDirty(true)}
                        onEditorValue={() => undefined}
                      />
                    </Suspense>
                  </div>
                ) : null}
                {!displayedWorkbook ? (
                  <div className="quality-record-fields">
                    {fields.map((field) => (
                      <label key={field.key}>
                        <span>
                          {field.label}
                          {field.required && ' *'}
                        </span>
                        {field.kind === 'select' ? (
                          <Select
                            disabled={readonly}
                            value={textValue(displayed[field.key])}
                            options={field.options.map((option) => ({
                              value: option,
                              label: option,
                            }))}
                            onChange={(value) => update(field.key, value)}
                          />
                        ) : field.kind === 'long' ? (
                          <Input.TextArea
                            disabled={readonly}
                            rows={4}
                            value={textValue(displayed[field.key])}
                            onChange={(event) => update(field.key, event.target.value)}
                          />
                        ) : (
                          <Input
                            disabled={
                              readonly ||
                              (!record.id.startsWith('new-') && field.key.endsWith('No'))
                            }
                            type={
                              field.kind === 'number'
                                ? 'number'
                                : field.kind === 'date'
                                  ? 'date'
                                  : 'text'
                            }
                            value={textValue(displayed[field.key])}
                            onChange={(event) => update(field.key, event.target.value)}
                          />
                        )}
                      </label>
                    ))}
                  </div>
                ) : null}
              </section>
            )}
          </main>
        </div>
      </div>
    </section>
  );
}

function useDesktopEditing() {
  const [matches, setMatches] = useState(() => window.matchMedia('(min-width: 1100px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(min-width: 1100px)');
    const update = () => setMatches(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  return matches;
}
