import { ArrowLeftOutlined, CheckOutlined, CloseOutlined, DownloadOutlined, EyeOutlined, HistoryOutlined, SaveOutlined, SendOutlined } from '@ant-design/icons';
import { Button, Empty, Input, Modal, Segmented, Space, Spin, Tabs, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { usePermission } from '@/components/auth/usePermission';
import { DataWorkbenchShell, DataWorkbookCanvas } from '@/features/data-workbench/DataWorkbench';
import { FilePreviewModal } from '@/components/file-preview';
import { downloadFile, fetchFilePreviewBlob, fileContentUrl } from '@/services/files/file-api';
import type { DataWorkbookSnapshot } from '@/services/data/data-api';
import type { EditorHandle, EditorSelection } from '@/features/template-workspace/types';
import { approveQuotationReview, getQuotation, getQuotationReview, getQuotationSourceWorkbook,
  listQuotationVersions, rejectQuotationReview, rollbackQuotationVersion, submitQuotationReview, updateQuotation,
  type QuotationDetail, type QuotationEdit, type QuotationReview,
  type QuotationVersion } from '@/services/quotations/quotation-api';
import { QuotationEditForm } from './QuotationEditForm';
import { changedQuotationCells, fieldKey, quotationLocations, type FieldLocation } from './quotation-locator';
import './quotation-workspace.css';
import { quotationReviewLabels } from './quotation-review-status';

const display = (value?: string | null) => value || '—';
const cellText = (value: unknown): string => {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return value == null ? '' : JSON.stringify(value);
};

async function sourceWorkbook(fileId: string, fileName: string): Promise<DataWorkbookSnapshot> {
  const XLSX = await import('xlsx');
  const blob = await fetchFilePreviewBlob(fileId);
  const source = XLSX.read(await blob.arrayBuffer(), { type: 'array', cellDates: false, cellStyles: true });
  const sheets: Record<string, Record<string, unknown>> = {};
  const sheetOrder: string[] = [];
  source.SheetNames.forEach((name, index) => {
    const worksheet = source.Sheets[name];
    if (!worksheet) return;
    const range = XLSX.utils.decode_range(worksheet['!ref'] || 'A1');
    const sheetId = `quotation-sheet-${index + 1}`;
    const cellData: Record<string, Record<string, { v: string }>> = {};
    for (let row = range.s.r; row <= range.e.r; row += 1) {
      for (let column = range.s.c; column <= range.e.c; column += 1) {
        const cell = worksheet[XLSX.utils.encode_cell({ r: row, c: column })] as { v?: unknown; w?: string } | undefined;
        if (cell?.v == null) continue;
        (cellData[String(row)] ??= {})[String(column)] = { v: cellText(cell.w ?? cell.v) };
      }
    }
    const mergeData = (worksheet['!merges'] || []).map((merge) => ({
      startRow: merge.s.r, endRow: merge.e.r, startColumn: merge.s.c, endColumn: merge.e.c,
    }));
    const columnData = Object.fromEntries((worksheet['!cols'] || []).flatMap((column, columnIndex) =>
      column?.wpx ? [[String(columnIndex), { w: column.wpx }]] : []));
    const rowData = Object.fromEntries((worksheet['!rows'] || []).flatMap((row, rowIndex) =>
      row?.hpx ? [[String(rowIndex), { h: row.hpx }]] : []));
    sheets[sheetId] = { id: sheetId, name, rowCount: Math.max(range.e.r + 1, 200),
      columnCount: Math.max(range.e.c + 1, 20), cellData, mergeData, columnData, rowData };
    sheetOrder.push(sheetId);
  });
  return { fileName, format: fileName.toLowerCase().endsWith('.csv') ? 'CSV' : 'XLSX',
    editable: false, sheets: [], regions: [], records: [], fields: [],
    snapshot: { id: `quotation-${fileId}`, snapshotFormatVersion: 3, name: fileName,
      sheetOrder, sheets, styles: {} } };
}

function restoreSourceStyles(saved: Record<string, unknown>, source: Record<string, unknown>) {
  const sourceSheets = source.sheets as Record<string, Record<string, unknown>>;
  const savedSheets = saved.sheets as Record<string, Record<string, unknown>>;
  if (!sourceSheets || !savedSheets) return saved;
  const sheets = { ...sourceSheets };
  const sourceOrder = source.sheetOrder as string[];
  const savedOrder = saved.sheetOrder as string[];
  for (const [index, sheetId] of (sourceOrder ?? Object.keys(sourceSheets)).entries()) {
    const sourceSheet = sourceSheets[sheetId];
    if (!sourceSheet) continue;
    const oldSheetId = savedOrder?.[index];
    const savedSheet = savedSheets[sheetId] ?? (oldSheetId ? savedSheets[oldSheetId] : undefined);
    if (!savedSheet) continue;
    const sourceCells = sourceSheet.cellData as Record<string, Record<string, Record<string, unknown>>>;
    const savedCells = savedSheet.cellData as Record<string, Record<string, Record<string, unknown>>>;
    const cellData = { ...sourceCells };
    for (const [row, columns] of Object.entries(savedCells ?? {})) {
      cellData[row] = { ...cellData[row] };
      for (const [column, cell] of Object.entries(columns)) {
        cellData[row][column] = { ...sourceCells?.[row]?.[column], ...cell };
        if (!cell.s && sourceCells?.[row]?.[column]?.s) cellData[row][column].s = sourceCells[row][column].s;
      }
    }
    sheets[sheetId] = { ...sourceSheet, cellData,
      columnData: sourceSheet.columnData, rowData: sourceSheet.rowData, mergeData: sourceSheet.mergeData };
  }
  return { ...source, sheets, styles: { ...(source.styles as object), ...(saved.styles as object) } };
}

export function QuotationWorkspacePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const canEdit = usePermission('quotation.edit');
  const canSubmit = usePermission('quotation.submit');
  const canReview = usePermission('quotation.review');
  const editorRef = useRef<EditorHandle>(null);
  const pendingCellWrites = useRef<Promise<void>[]>([]);
  const [detail, setDetail] = useState<QuotationDetail>();
  const [workbook, setWorkbook] = useState<DataWorkbookSnapshot>();
  const [originalWorkbook, setOriginalWorkbook] = useState<DataWorkbookSnapshot>();
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<'preview' | 'edit' | 'versions'>('preview');
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [cellChange, setCellChange] = useState<{ key: string; value: string; revision: number }>();
  const [fieldEdits, setFieldEdits] = useState<Record<string, string>>({});
  const [basicOpen, setBasicOpen] = useState(false);
  const [versions, setVersions] = useState<QuotationVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<QuotationVersion>();
  const [rollbackBusy, setRollbackBusy] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [error, setError] = useState<string>();
  const [review, setReview] = useState<QuotationReview>();
  const [reviewLoading, setReviewLoading] = useState(true);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [reviewHistoryOpen, setReviewHistoryOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [selectedItemIndex, setSelectedItemIndex] = useState(0);
  const [fieldSection, setFieldSection] = useState<'customer' | 'items'>('customer');
  const [selectedLocationKey, setSelectedLocationKey] = useState<string>();
  const returnTo = (location.state as { returnTo?: string } | null)?.returnTo;
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  const goBack = () => {
    const destination = returnTo?.startsWith('/quotations/list') || returnTo === '/quotations/upload'
      ? returnTo : '/quotations/list';
    if (!dirty) { navigate(destination); return; }
    Modal.confirm({ title: '离开报价工作空间？', content: '未保存的修改将丢失。',
      okText: '离开', cancelText: '继续编辑', onOk: () => navigate(destination) });
  };
  const locations = useMemo(() => detail && workbook
    ? quotationLocations(view === 'versions' && selectedVersion
      ? selectedVersion.detail.workbookSnapshot ?? originalWorkbook?.snapshot ?? workbook.snapshot
      : workbook.snapshot, selectedVersion?.detail ?? detail)
    : {}, [detail, workbook, originalWorkbook, selectedVersion, view]);

  useEffect(() => {
    if (!id) return;
    let active = true;
    void getQuotation(id).then(async (value) => {
      if (!active) return;
      setDetail(value);
      if (value.workbookSnapshot && !value.summary.sourceFileId) {
        setWorkbook({ fileName: value.summary.sourceFileName || '报价记录.xlsx', format: value.sourceFormat || 'XLSX',
          editable: true, sheets: [], regions: [], records: [], fields: [], snapshot: value.workbookSnapshot });
      }
      if (value.summary.sourceFileId) {
        try {
          const source = value.sourceFormat === 'XLSX'
            ? { fileName: value.summary.sourceFileName || '报价记录.xlsx', format: 'XLSX', editable: false,
              sheets: [], regions: [], records: [], fields: [], snapshot: await getQuotationSourceWorkbook(id) } as DataWorkbookSnapshot
            : await sourceWorkbook(value.summary.sourceFileId, value.summary.sourceFileName || '报价记录.xlsx');
          if (active) {
            setOriginalWorkbook(source);
            setWorkbook({ ...source, editable: true, snapshot: value.workbookSnapshot
              ? restoreSourceStyles(value.workbookSnapshot, source.snapshot) : source.snapshot });
          }
        } catch (cause) {
          if (active) message.warning(cause instanceof Error ? cause.message : '原文件预览加载失败');
        }
      }
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : '报价记录加载失败');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id]);

  useEffect(() => {
    if (!id) return;
    let active = true;
    setReviewLoading(true);
    setReview(undefined);
    setView('preview');
    void getQuotationReview(id).then((value) => {
      if (!active) return;
      setReview(value);
      setView(canEdit && ['NOT_SUBMITTED', 'REJECTED'].includes(value.status) ? 'edit' : 'preview');
    })
      .catch((cause: unknown) => {
        if (active) message.error(cause instanceof Error ? cause.message : '审核状态加载失败');
      })
      .finally(() => { if (active) setReviewLoading(false); });
    return () => { active = false; };
  }, [canEdit, id]);

  const changeReview = async (action: 'submit' | 'approve' | 'reject') => {
    if (!id || !detail || !review || reviewBusy) return;
    if (dirty || saving) { message.warning('请先保存当前内容再提交审核'); return; }
    if (action === 'reject' && !rejectReason.trim()) {
      message.warning('请填写驳回原因');
      return;
    }
    setReviewBusy(true);
    try {
      const updated = action === 'submit'
        ? await submitQuotationReview(id, detail.versionNo, review.lockVersion)
        : action === 'approve'
          ? await approveQuotationReview(id, detail.versionNo, review.lockVersion)
          : await rejectQuotationReview(id, detail.versionNo, review.lockVersion, rejectReason.trim());
      setReview(updated);
      setRejectOpen(false);
      setRejectReason('');
      if (action === 'submit' || action === 'approve') setView('preview');
      message.success(action === 'submit' ? '报价已提交审核'
        : action === 'approve' ? '报价审核已通过' : '报价已驳回');
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '审核操作失败');
      void getQuotationReview(id).then(setReview).catch(() => undefined);
    } finally { setReviewBusy(false); }
  };

  const save = async (value: QuotationEdit) => {
    if (!id || !detail) return;
    setSaving(true);
    try {
      await Promise.all(pendingCellWrites.current.splice(0));
      const snapshot = editorRef.current?.getSnapshot() ?? workbook?.snapshot;
      const merged: QuotationEdit = { ...value, items: value.items.map((item) => ({
        ...item, priceOptions: item.priceOptions.map((option) => ({ ...option })),
      })) };
      const customerKeys: Record<string, string> = {
        '客户名称': 'customerName', '关联公司': 'relatedCompany', '主联系人': 'contactName',
        '职位': 'contactTitle', '电话': 'phone', '传真': 'fax', '邮箱': 'email',
        '网站': 'website', '地址1': 'addressLine1', '地址2': 'addressLine2', '备注': 'note',
      };
      const itemKeys: Record<string, string> = {
        '产品': 'productName', '单价': 'unitPrice', '规格': 'specification',
        '报价日期': 'quoteDate', '客户编码': 'customerCode', '备注': 'note',
      };
      const applyFieldEdit = (key: string, edited: string) => {
        const [area, index, name] = key.split(':');
        if (!name) return;
        if (area === 'customer' && customerKeys[name]) {
          Object.assign(merged, { [customerKeys[name]]: edited });
        } else if (area === 'items') {
          const item = merged.items[Number(index)];
          if (!item || !itemKeys[name]) return;
          Object.assign(item, { [itemKeys[name]]: edited || null });
          if (name === '单价' && item.priceOptions.length === 1 && Number.isFinite(Number(edited)) && Number(edited) >= 0) {
            item.priceOptions[0]!.unitPrice = Number(edited);
          }
          if (name === '规格' && item.priceOptions.length === 1) item.priceOptions[0]!.specification = edited;
        }
      };
      if (snapshot && originalWorkbook?.snapshot) {
        const baselineCustomer: Record<string, unknown> = {
          customerName: detail.summary.customerName, relatedCompany: detail.summary.relatedCompany,
          contactName: detail.summary.contactName, contactTitle: detail.contactTitle,
          phone: detail.summary.phone, fax: detail.fax, email: detail.email,
          website: detail.website, addressLine1: detail.addressLine1,
          addressLine2: detail.addressLine2, note: detail.note,
        };
        for (const [key, edited] of Object.entries(changedQuotationCells(
          snapshot, originalWorkbook.snapshot, locations))) {
          if (Object.hasOwn(fieldEdits, key)) continue;
          const [area, index, name] = key.split(':');
          const field = area === 'customer' ? customerKeys[name ?? ''] : itemKeys[name ?? ''];
          if (!field) continue;
          const current = area === 'customer'
            ? (merged as unknown as Record<string, unknown>)[field]
            : (merged.items[Number(index)] as unknown as Record<string, unknown> | undefined)?.[field];
          const baseline = area === 'customer' ? baselineCustomer[field]
            : (detail.items[Number(index)] as unknown as Record<string, unknown> | undefined)?.[field];
          if (cellText(current) === cellText(baseline)) applyFieldEdit(key, edited);
        }
      }
      for (const [key, edited] of Object.entries(fieldEdits)) applyFieldEdit(key, edited);
      const saved = await updateQuotation(id, { ...merged,
        workbookSnapshot: snapshot });
      setDetail(saved);
      if (saved.workbookSnapshot) setWorkbook((current) => current ? { ...current, snapshot: saved.workbookSnapshot! } : current);
      setDirty(false);
      setFieldEdits({});
      setBasicOpen(false);
      setView('preview');
      message.success('报价记录已保存');
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '保存失败');
    } finally { setSaving(false); }
  };

  if (loading || reviewLoading) return <div className="quotation-workspace-loading"><Spin /></div>;
  if (error || !detail) return <Empty description={error || '报价记录不存在'} />;
  const fileId = detail.summary.sourceFileId;
  const saveDraft = () => {
    const draft: QuotationEdit = {
      customerName: detail.summary.customerName,
      relatedCompany: detail.summary.relatedCompany,
      contactName: detail.summary.contactName,
      contactTitle: detail.contactTitle,
      phone: detail.summary.phone,
      fax: detail.fax, email: detail.email,
      addressLine1: detail.addressLine1, addressLine2: detail.addressLine2,
      website: detail.website, note: detail.note,
      contacts: detail.contacts.map((contact) => ({ sourceColumn: contact.sourceColumn,
        name: contact.name, title: contact.title, phone: contact.phone })),
      items: detail.items.map((item) => ({ sourceRowNo: item.sourceRowNo || 1,
        productName: item.productName, unitPrice: item.unitPrice, specification: item.specification,
        quoteDate: item.quoteDate, customerCode: item.customerCode, note: item.note,
        priceOptions: item.priceOptions.map((option) => ({ unitPrice: option.unitPrice,
          specification: option.specification })) })),
      versionNo: detail.versionNo,
    };
    void save(draft);
  };
  const showVersions = async () => {
    if (!id) return;
    try { setVersions(await listQuotationVersions(id)); setSelectedVersion(undefined); setView('versions'); }
    catch (cause) { message.error(cause instanceof Error ? cause.message : '版本记录加载失败'); }
  };
  const rollbackVersion = async (version: QuotationVersion) => {
    if (!id || !detail || rollbackBusy) return;
    setRollbackBusy(true);
    try {
      const restored = await rollbackQuotationVersion(id, version.versionNo, detail.versionNo);
      setDetail(restored);
      setReview(await getQuotationReview(id));
      if (originalWorkbook) {
        setWorkbook({ ...originalWorkbook, editable: true,
          snapshot: restored.workbookSnapshot
            ? restoreSourceStyles(restored.workbookSnapshot, originalWorkbook.snapshot)
            : originalWorkbook.snapshot });
      }
      setSelectedVersion(undefined);
      setDirty(false);
      setFieldEdits({});
      setView('edit');
      message.success(`已从 V${version.versionNo} 创建 V${restored.versionNo} 草稿`);
    } catch (cause) {
      message.error(cause instanceof Error ? cause.message : '回退版本失败');
    } finally { setRollbackBusy(false); }
  };
  const focusItem = (index: number) => {
    setSelectedItemIndex(index);
    const firstSheet = (workbook?.snapshot.sheetOrder as string[] | undefined)?.[0];
    const sourceRow = (selectedVersion?.detail ?? detail).items[index]?.sourceRowNo;
    if (firstSheet && sourceRow) editorRef.current?.focusCell?.(firstSheet, `A${sourceRow}`);
  };
  const selectCell = (selection: EditorSelection) => {
    const found = Object.entries(locations).find(([, location]) =>
      location.sheetId === selection.sheetId && location.address === selection.address.split(':')[0]);
    if (found) {
      setSelectedLocationKey(found[0]);
      const [area, index] = found[0].split(':');
      if (area === 'customer' || area === 'items') setFieldSection(area);
      if (area === 'items') setSelectedItemIndex(Number(index));
      return;
    }
    const match = /\d+/.exec(selection.address);
    if (!match) return;
    const index = (selectedVersion?.detail ?? detail).items.findIndex((item) => item.sourceRowNo === Number(match[0]));
    if (index >= 0) {
      setSelectedItemIndex(index);
      setFieldSection('items');
    }
  };
  const focusField = (key: string, location?: FieldLocation) => {
    setSelectedLocationKey(key);
    if (location) editorRef.current?.focusCell?.(location.sheetId, location.address);
  };
  const commitField = (key: string, value: string) => {
    const location = locations[key];
    if (!location || !editorRef.current?.writeCell) return;
    const write = editorRef.current.writeCell(location.sheetId, location.address, value);
    pendingCellWrites.current.push(write);
    void write.catch((cause: unknown) => message.error(cause instanceof Error ? cause.message : '单元格写入失败'));
  };
  const changeCell = (change: { sheetId: string; address: string; value: unknown }) => {
    setDirty(true);
    const found = Object.entries(locations).find(([, location]) =>
      location.sheetId === change.sheetId && location.address === change.address.split(':')[0]);
    if (found) {
      const value = cellText(change.value);
      setCellChange({ key: found[0], value, revision: Date.now() });
      setFieldEdits((current) => ({ ...current, [found[0]]: value }));
    }
  };
  const historical = selectedVersion?.detail;
  const editableReview = review?.status === 'NOT_SUBMITTED' || review?.status === 'REJECTED';
  const canvasWorkbook = historical
    ? historical.workbookSnapshot && workbook
      ? { ...workbook, snapshot: originalWorkbook
        ? restoreSourceStyles(historical.workbookSnapshot, originalWorkbook.snapshot)
        : historical.workbookSnapshot, editable: false }
      : originalWorkbook
    : workbook;
  return <>
    <DataWorkbenchShell breadcrumb="报价记录 / 报价工作空间" title={detail.summary.quotationNo}
      leading={<Button type="text" icon={<ArrowLeftOutlined />}
        onClick={goBack}>返回</Button>}
      meta={<><span className="workspace-meta-item">V{selectedVersion?.versionNo ?? detail.versionNo}</span>
        <Tag>{historical?.summary.customerName ?? detail.summary.customerName}</Tag>
        {review && !historical && <Tag color={review.status === 'APPROVED' ? 'success'
          : review.status === 'SUBMITTED' ? 'processing'
            : review.status === 'REJECTED' ? 'error' : 'default'}>{quotationReviewLabels[review.status]}</Tag>}</>}
      actions={<>
        <Tag color={dirty ? 'warning' : 'success'}>{saving ? '保存中' : dirty ? '未保存' : '已保存'}</Tag>
        {review && <Button icon={<HistoryOutlined />} onClick={() => setReviewHistoryOpen(true)}>审核记录</Button>}
        {canSubmit && editableReview && <Button icon={<SendOutlined />} loading={reviewBusy}
          disabled={dirty || saving} onClick={() => void changeReview('submit')}>
          {review?.status === 'REJECTED' ? '再次提交审核' : '提交审核'}
        </Button>}
        {canReview && review?.status === 'SUBMITTED' && <>
          <Button icon={<CheckOutlined />} loading={reviewBusy}
            onClick={() => void changeReview('approve')}>审核通过</Button>
          <Button danger icon={<CloseOutlined />} disabled={reviewBusy}
            onClick={() => setRejectOpen(true)}>驳回</Button>
        </>}
        {fileId && <Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>预览原文件</Button>}
        {fileId && <Button icon={<DownloadOutlined />} onClick={() => void downloadFile(fileId, detail.summary.sourceFileName || '报价记录')
          .catch((cause: unknown) => message.error(cause instanceof Error ? cause.message : '下载失败'))}>原文下载</Button>}
        {canEdit && editableReview && !historical && <Button disabled={view !== 'edit' || dirty} onClick={() => setBasicOpen(true)}>编辑基本信息</Button>}
        {canEdit && editableReview && !historical && <Button type="primary" icon={<SaveOutlined />} disabled={view !== 'edit'}
          loading={saving} onClick={saveDraft}>保存草稿</Button>}
      </>}
      navigation={<nav className="workspace-view-tabs"><Tabs activeKey={view}
        onChange={(key) => { if (key === 'versions') void showVersions(); else {
          setSelectedVersion(undefined);
          setView(key as 'preview' | 'edit');
        } }}
        items={[{ key: 'preview', label: <><EyeOutlined /> 预览</>, disabled: dirty && view === 'edit' },
          { key: 'edit', label: '编辑', disabled: !canEdit || !editableReview },
          { key: 'versions', label: '版本记录', disabled: dirty && view === 'edit' }]} /></nav>}
      content={view === 'versions' && !selectedVersion ? <main className="version-history-page quotation-history-page">
        <div className="version-history-heading"><Typography.Title level={4}>版本记录</Typography.Title></div>
        <div className="version-history-list">{(versions.length ? versions : [{ versionNo: detail.versionNo,
          createdAt: detail.summary.createdAt, createdBy: '', detail }]).map((version) => {
          const current = version.versionNo === detail.versionNo;
          return <article className="version-history-item" key={version.versionNo}>
            <div className="version-marker">V{version.versionNo}</div>
            <div className="quotation-history-content"><Space wrap size={8}>
              <Typography.Text strong>{current ? '当前版本' : `版本 V${version.versionNo}`}</Typography.Text>
              <Tag color={current ? review?.status === 'APPROVED' ? 'green'
                : review?.status === 'SUBMITTED' ? 'blue'
                  : review?.status === 'REJECTED' ? 'red' : 'gold' : 'default'}>
                {current ? quotationReviewLabels[review?.status ?? 'NOT_SUBMITTED'] : '历史版本'}</Tag>
            </Space>
              <Typography.Paragraph type="secondary">最近更新：{new Date(version.createdAt).toLocaleString('zh-CN')}</Typography.Paragraph>
              <Space wrap><Button size="small" icon={<EyeOutlined />} onClick={() => setSelectedVersion(version)}>查看快照</Button>
                {canEdit && version.versionNo < detail.versionNo && <Button size="small" loading={rollbackBusy}
                  onClick={() => Modal.confirm({ title: `回退到 V${version.versionNo}？`,
                    content: '将从该快照创建新的报价草稿版本，并保留当前版本与审核记录。',
                    okText: '确认回退', cancelText: '取消', onOk: () => rollbackVersion(version) })}>回退到此版本</Button>}
                {current && <Tag color="blue">当前版本</Tag>}
                {version.createdByName && <Typography.Text type="secondary">创建人：{version.createdByName}</Typography.Text>}
              </Space>
            </div>
          </article>; })}</div>
      </main> : undefined}
      canvas={<DataWorkbookCanvas key={selectedVersion ? `version-${selectedVersion.versionNo}` : view === 'edit' ? 'editable' : 'readonly'} ref={editorRef}
        workbook={canvasWorkbook} editorLabel="报价原文件"
        editable={view === 'edit' && editableReview && !historical} onCellChange={view === 'edit' && editableReview && !historical ? changeCell : undefined}
        onDirty={view === 'edit' && !historical ? () => setDirty(true) : undefined}
        onSelectionChange={selectCell} />}
      panelTitle={historical ? `版本 V${selectedVersion.versionNo} 快照` : '本次报价数据'} panel={<>
          {selectedVersion && <Space wrap><Button type="link" onClick={() => setSelectedVersion(undefined)}>返回版本列表</Button>
            {canEdit && selectedVersion.versionNo < detail.versionNo && <Button size="small" loading={rollbackBusy}
              onClick={() => Modal.confirm({ title: `回退到 V${selectedVersion.versionNo}？`,
                content: '将从该快照创建新的报价草稿版本，并保留当前版本与审核记录。',
                okText: '确认回退', cancelText: '取消', onOk: () => rollbackVersion(selectedVersion) })}>回退到此版本</Button>}</Space>}
          <QuotationFieldPanel detail={historical ?? detail} section={fieldSection} onSectionChange={setFieldSection}
            selectedItemIndex={selectedItemIndex} onSelectItem={focusItem} locations={locations}
            selectedLocationKey={selectedLocationKey} onFocusField={focusField}
            editable={view === 'edit' && editableReview && !historical} edits={fieldEdits}
            onEdit={(key, value) => { setFieldEdits((current) => ({ ...current, [key]: value })); setDirty(true); }}
            onCommitField={commitField} /></>} />
    <Modal title="编辑报价基本信息与明细" open={basicOpen} onCancel={() => setBasicOpen(false)}
      footer={null} width={980} destroyOnHidden>
      <QuotationEditForm detail={detail} saving={saving} cellChange={cellChange}
        onCancel={() => setBasicOpen(false)} onSave={save} />
    </Modal>
    <Modal title="驳回报价审核" open={rejectOpen} okText="确认驳回" cancelText="取消"
      okButtonProps={{ danger: true, loading: reviewBusy }}
      onCancel={() => { setRejectOpen(false); setRejectReason(''); }}
      onOk={() => void changeReview('reject')}>
      <Input.TextArea value={rejectReason} onChange={(event) => setRejectReason(event.target.value)}
        placeholder="请输入驳回原因" rows={4} maxLength={2000} showCount />
    </Modal>
    <Modal title="报价审核记录" open={reviewHistoryOpen} footer={null} width={640}
      onCancel={() => setReviewHistoryOpen(false)} destroyOnHidden>
      {review?.events.length ? review.events.map((event) => <div key={event.id}
        className="quotation-review-event">
        <strong>{event.toStatus === 'SUBMITTED' ? '提交审核'
          : event.toStatus === 'APPROVED' ? '审核通过'
            : event.toStatus === 'NOT_SUBMITTED' ? '版本回退' : '审核驳回'}</strong>
        <span>V{event.quotationVersion} · {event.actorName} · {new Date(event.createdAt).toLocaleString('zh-CN')}</span>
        {event.comment && <p>{event.comment}</p>}
      </div>) : <Empty description="暂无审核记录" />}
    </Modal>
    {fileId && <FilePreviewModal open={previewOpen} onClose={() => setPreviewOpen(false)}
      file={{ fileName: detail.summary.sourceFileName || '报价记录',
        load: () => fetchFilePreviewBlob(fileId), downloadUrl: fileContentUrl(fileId) }} />}
  </>;
}

function QuotationFieldPanel({ detail, section, onSectionChange, selectedItemIndex, onSelectItem,
  locations, selectedLocationKey, onFocusField, editable, edits, onEdit, onCommitField }: {
  detail: QuotationDetail; section: 'customer' | 'items';
  onSectionChange: (section: 'customer' | 'items') => void;
  selectedItemIndex: number; onSelectItem: (index: number) => void;
  locations: Record<string, FieldLocation>; selectedLocationKey?: string;
  onFocusField: (key: string, location?: FieldLocation) => void;
  editable: boolean; edits: Record<string, string>; onEdit: (key: string, value: string) => void;
  onCommitField: (key: string, value: string) => void;
}) {
  const [activeTab, setActiveTab] = useState<'data' | 'structure' | 'properties'>('data');
  const [selectedField, setSelectedField] = useState('客户名称');
  useEffect(() => {
    if (!selectedLocationKey) return;
    const name = selectedLocationKey.split(':').slice(2).join(':');
    if (name) setSelectedField(name);
    setActiveTab('data');
  }, [selectedLocationKey]);
  const item = detail.items[selectedItemIndex];
  const customerFields: Array<[string, string | null | undefined]> = [
    ['客户名称', detail.summary.customerName], ['关联公司', detail.summary.relatedCompany],
    ['主联系人', detail.summary.contactName], ['职位', detail.contactTitle],
    ['电话', detail.summary.phone], ['传真', detail.fax], ['邮箱', detail.email],
    ['网站', detail.website], ['地址1', detail.addressLine1], ['地址2', detail.addressLine2],
    ['备注', detail.note],
  ];
  const itemFields: Array<[string, string | null | undefined]> = item ? [
    ['产品', item.productName], ['单价', item.unitPrice], ['规格', item.specification],
    ['报价日期', item.quoteDate], ['客户编码', item.customerCode], ['备注', item.note],
  ] : [];
  const fields = section === 'customer' ? customerFields : itemFields;
  const selectedKey = fieldKey(section, selectedField, section === 'items' ? selectedItemIndex : 0);
  const selectedValue = edits[selectedKey] ?? fields.find(([name]) => name === selectedField)?.[1];
  const selectedSource = locations[fieldKey(section, selectedField, section === 'items' ? selectedItemIndex : 0)];
  const selectField = (name: string, area: 'customer' | 'items') => {
    onSectionChange(area);
    setSelectedField(name);
    setActiveTab('properties');
    const key = fieldKey(area, name, area === 'items' ? selectedItemIndex : 0);
    onFocusField(key, locations[key]);
  };
  return <div className="quotation-field-panel">
    <Tabs className="quotation-field-tabs" activeKey={activeTab}
      onChange={(key) => setActiveTab(key as 'data' | 'structure' | 'properties')}
      items={[{ key: 'data', label: '字段数据' }, { key: 'structure', label: '字段结构' },
        { key: 'properties', label: '字段属性' }]} />
    <div className="quotation-field-scroll">
      {activeTab === 'data' && <div className="quotation-field-body">
        <Segmented block value={section} onChange={(value) => onSectionChange(value as 'customer' | 'items')}
          options={[{ value: 'customer', label: '客户信息' }, { value: 'items', label: `报价明细 (${detail.items.length})` }]} />
        <div className="quotation-field-heading"><strong>{section === 'customer' ? '客户信息' : '报价明细'}</strong>
          <small>{section === 'customer' ? `${customerFields.length} 个字段，1 条记录` : `${itemFields.length} 个字段，${detail.items.length} 条记录`}</small></div>
        {section === 'items' && detail.items.length > 0 && <div className="quotation-record-select">
          <label htmlFor="quotation-item-select">数据记录</label>
          <select id="quotation-item-select" value={selectedItemIndex} onChange={(event) => onSelectItem(Number(event.target.value))}>
            {detail.items.map((entry, index) => <option key={entry.id} value={index}>{index + 1}. {entry.productName}</option>)}
          </select>
        </div>}
        <div className="quotation-field-status">当前记录字段已解析</div>
        <div className="quotation-field-table"><div className="quotation-field-table-head"><span>字段名称</span><span>当前值</span></div>
          {fields.map(([label, value]) => {
            const key = fieldKey(section, label, section === 'items' ? selectedItemIndex : 0);
            const source = locations[key];
            return <div className="quotation-field-entry" key={key}>
              <button type="button" className={`quotation-field-row${selectedLocationKey === key ? ' is-selected' : ''}`}
                onClick={() => { setSelectedField(label); onFocusField(key, source); }}>
                <strong>{label}</strong><span>{display(edits[key] ?? value)}</span></button>
              {selectedLocationKey === key && <div className="quotation-field-source">
                <div><span>原始值</span><strong>{source ? display(source.rawValue) : '未定位'}</strong></div>
                <div><span>来源位置</span><strong>{source ? `${source.sheetName} · ${source.address}` : '原文件中未找到对应单元格'}</strong></div>
                {editable && <Input aria-label={`修改${label}`} value={edits[key] ?? value ?? ''}
                  onChange={(event) => onEdit(key, event.target.value)}
                  onBlur={(event) => onCommitField(key, event.target.value)} />}
              </div>}
            </div>;
          })}</div>
        {section === 'customer' && detail.contacts.length > 0 && <>
          <Typography.Title level={5}>其他联系人</Typography.Title>
          {detail.contacts.map((contact) => <div className="quotation-contact-row" key={contact.id}>
            {[contact.name, contact.title, contact.phone].filter(Boolean).join(' · ')}</div>)}
        </>}
        {section === 'items' && item?.priceOptions.length ? <>
          <Typography.Title level={5}>价格选项</Typography.Title>
          {item.priceOptions.map((price) => <div className="quotation-contact-row" key={price.id}>
            {price.unitPrice} / {display(price.specification)}</div>)}
        </> : null}
      </div>}
      {activeTab === 'structure' && <div className="quotation-field-body">
        <Typography.Title level={5}>客户信息</Typography.Title>
        <Typography.Text type="secondary">单条记录 · {customerFields.length} 个字段</Typography.Text>
        <div className="quotation-structure-list">{customerFields.map(([name]) =>
          <button type="button" key={name} onClick={() => selectField(name, 'customer')}>{name}</button>)}</div>
        <Typography.Title level={5}>报价明细</Typography.Title>
        <Typography.Text type="secondary">重复记录 · {detail.items.length} 条 · {itemFields.length} 个字段</Typography.Text>
        <div className="quotation-structure-list">{itemFields.map(([name]) =>
          <button type="button" key={name} onClick={() => selectField(name, 'items')}>{name}</button>)}</div>
        <Typography.Title level={5}>价格选项</Typography.Title>
        <Typography.Text type="secondary">每条明细可包含多个价格与规格</Typography.Text>
      </div>}
      {activeTab === 'properties' && <div className="quotation-field-body">
        <Typography.Title level={5}>{selectedField}</Typography.Title>
        <div className="quotation-property-row"><span>所属区域</span><strong>{section === 'customer' ? '客户信息' : '报价明细'}</strong></div>
        <div className="quotation-property-row"><span>当前值</span><strong>{display(selectedValue)}</strong></div>
        <div className="quotation-property-row"><span>记录</span><strong>{section === 'customer' ? '客户资料' : `${selectedItemIndex + 1}. ${item?.productName || ''}`}</strong></div>
        <div className="quotation-property-row"><span>来源位置</span><strong>{selectedSource
          ? `${selectedSource.sheetName} · ${selectedSource.address}` : '未定位'}</strong></div>
        <Typography.Text type="secondary">报价字段可在“编辑”页修改并保存。</Typography.Text>
      </div>}
    </div>
  </div>;
}
