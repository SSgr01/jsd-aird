import { ArrowLeftOutlined, DownloadOutlined, SaveOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Result,
  Select,
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
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import { editorUnchanged, flushEditorChanges, pauseWorkspaceInputs, useSaveSession } from '@/features/template-workspace/editor-save';
import { usePermission } from '@/components/auth/usePermission';
import {
  downloadBlob,
  fetchFileBlob,
  fetchFilePreviewBlob,
  stageFile,
} from '@/services/files/file-api';
import { templateApi } from '@/services/templates/template-api';
import type { EditorHandle, TemplateBinding } from '@/features/template-workspace/types';
import type { RecognitionReviewItem } from '@/services/templates/template-api';
import { WordNativePreview } from '@/features/template-workspace/WordNativePreview';
import { ProductionUploadFieldManagerPanel } from './ProductionUploadFieldManagerPanel';
import { ProductionUploadRecognitionStatusBar } from './ProductionUploadRecognitionStatusBar';
import type { FieldManagerTab } from '@/pages/template-workspace/TemplateFieldManager';
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
  productionStageLabel,
  type ProductionUpload,
  type ProductionUploadVersion,
} from '@/services/production-orders/production-upload-api';
import '../quality/quality-pages.css';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

type WorkspaceView = 'recognition' | 'preview' | 'edit' | 'material' | 'attachments' | 'versions';

/** Statuses the records endpoint answers while recognition is still running. */
const IN_PROGRESS_STATUSES = ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'];

/** The slice of the review contract the status strip and the field panel read. */
type ProductionRecognitionReview = {
  summary?: { total?: number; pending?: number; conflict?: number; regionCount?: number };
  recognitionCoverage?: { unresolvedRegionCount?: number };
};

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

/** How the record is called in confirmations, so an image or Word order is not
 *  described as an Excel one. */
function productionOrderKindLabel(fileName: string) {
  switch (formatOf(fileName)) {
    case 'XLSX':
      return 'Excel 生产单';
    case 'PHOTO':
      return '图片生产单';
    default:
      return 'Word 生产单';
  }
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
  const canCreateTemplate = usePermission('template.create');
  const editorRef = useRef<EditorHandle>(null);
  const saveSession = useSaveSession(uploadId);
  const workspaceRootRef = useRef<HTMLElement>(null);
  const savedGenerationRef = useRef(0);
  const savedEditorRef = useRef<{ editor: EditorHandle | null; revision: number | undefined }>();
  const editGenerationRef = useRef(0);
  const savePromiseRef = useRef<Promise<false | { lockVersion: number }>>();
  const saveOwnerRef = useRef<object>();
  const saveDraftRef = useRef<(business?: ProductionBusinessSnapshot, silent?: boolean) => Promise<false | { lockVersion: number }>>(() => Promise.resolve(false));
  const [record, setRecord] = useState<ProductionUpload>();
  const [versions, setVersions] = useState<ProductionUploadVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<ProductionUploadVersion>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [business, setBusiness] = useState<ProductionBusinessSnapshot>(createEmptyBusinessSnapshot);
  const latestSaveRef = useRef({ record, snapshot, business });
  latestSaveRef.current = { record, snapshot, business };
  const [view, setView] = useState<WorkspaceView>('edit');
  const [fieldTab, setFieldTab] = useState<FieldManagerTab>('recognition');
  const [reviewBusy, setReviewBusy] = useState(false);
  const [reviewRefreshToken, setReviewRefreshToken] = useState(0);
  const [editorReady, setEditorReady] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const markDirty = () => { editGenerationRef.current += 1; setDirty(true); };
  useEffect(() => { setSaving(false); }, [uploadId]);
  const [publishing, setPublishing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [downloadingSource, setDownloadingSource] = useState(false);
  const [templateDraftOpen, setTemplateDraftOpen] = useState(false);
  const [templateDraftSaving, setTemplateDraftSaving] = useState(false);
  const [templateCategories, setTemplateCategories] = useState<Array<{ id: string; name: string }>>([]);
  const [templateDraftForm] = Form.useForm<{ name: string; categoryId: string }>();
  const [selectingTemplate, setSelectingTemplate] = useState<string>();
  // Recognition runs as an async job; these drive the in-place progress strip so a
  // restart never has to send the reviewer back to the upload list.
  const [restarting, setRestarting] = useState(false);
  const [recognitionProgress, setRecognitionProgress] = useState<number>();
  const [recognitionStage, setRecognitionStage] = useState<string>();
  // Re-parsing blanks the stored recognition result on purpose, so the last usable
  // one is kept here to hold the field panel open until the new result arrives.
  const [retainedResult, setRetainedResult] = useState<ProductionUpload['recognitionResult']>();
  const [error, setError] = useState<string>();
  const [photoUrl, setPhotoUrl] = useState<string>();
  const [msg, holder] = message.useMessage();

  const load = useCallback(async () => {
    const session = saveSession.current;
    const isCurrent = () => session.active && saveSession.current === session;
    setLoading(true);
    setDirty(false);
    setSelectedVersion(undefined);
    setEditorReady(false);
    try {
      const [loadedDetail, history] = await Promise.all([
        productionOrderRecordApi.get(uploadId),
        productionOrderRecordApi.versions(uploadId).catch(() => []),
      ]);
      const detail = (formatOf(loadedDetail.originalName) === 'XLSX'
        || (formatOf(loadedDetail.originalName) === 'PHOTO' && !!loadedDetail.selectedTemplateVersionId))
        && !loadedDetail.recognitionResult?.recognitionReview
        ? await productionUploadApi.ensureFieldReview(uploadId,loadedDetail.lockVersion).catch(() => loadedDetail)
        : loadedDetail;
      if (!isCurrent()) return;
      setRecord(detail);
      setVersions(history);
      const detailFormat = formatOf(detail.originalName);
      let loadedSnapshot = detail.workbookSnapshot;
      const storedBusiness = loadedSnapshot?.productionBusiness;
      if (detailFormat === 'XLSX' || detailFormat === 'PHOTO') {
        if (!isWorkbookSnapshot(loadedSnapshot)) {
          loadedSnapshot = history.find((item) =>
            isWorkbookSnapshot(item.workbookSnapshot),
          )?.workbookSnapshot;
        }
        if (detailFormat === 'XLSX' && !isWorkbookSnapshot(loadedSnapshot)) {
          loadedSnapshot = await parseExcelSnapshot(
            await fetchFileBlob(detail.fileId),
            detail.originalName,
          );
        }
        if (!isCurrent()) return;
        if (isWorkbookSnapshot(loadedSnapshot)) {
          const recoveredSnapshot = {
            ...loadedSnapshot,
            productionBusiness: storedBusiness ?? loadedSnapshot.productionBusiness,
          };
          setSnapshot(recoveredSnapshot);
          setBusiness(normalizeBusinessSnapshot(recoveredSnapshot.productionBusiness));
        }
      }
      // 图片使用所选模板生成工作簿后，也要进入 Excel 编辑页；只有尚未生成
      // 工作簿的图片才停在原图预览页。
      setView(detailFormat === 'PHOTO' && !isWorkbookSnapshot(loadedSnapshot) ? 'preview' : 'edit');
      if (detailFormat === 'PHOTO') {
        const blob = await fetchFileBlob(detail.fileId);
        if (!isCurrent()) return;
        const url = URL.createObjectURL(blob);
        setPhotoUrl((current) => {
          if (current) URL.revokeObjectURL(current);
          return url;
        });
      }
      if (detailFormat === 'DOCX') {
        setSnapshot(detail.workbookSnapshot ?? { wordPatches: [] });
      }
      setEditorReady(false);
      savedGenerationRef.current = editGenerationRef.current = 0; savedEditorRef.current = undefined;
      setError(undefined);
    } catch (reason) {
      if (!isCurrent()) return;
      // A reload while recognition is running makes the records endpoint refuse the
      // upload ("尚未解析完成").  Fall back to the permissive upload endpoint so the
      // workspace can show the running job instead of an error screen.
      const running = await productionUploadApi.get(uploadId).catch(() => undefined);
      if (!isCurrent()) return;
      if (running && IN_PROGRESS_STATUSES.includes(running.status)) {
        setRecord(running);
        setRecognitionProgress(running.recognitionProgress ?? 0);
        setRecognitionStage(running.currentStage);
        setError(undefined);
        return;
      }
      setError(reason instanceof Error ? reason.message : '生产单文件加载失败');
    } finally {
      if (isCurrent()) setLoading(false);
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

  const performSaveDraft = async (
    override?: ProductionBusinessSnapshot,
    silent = false,
  ): Promise<false | {lockVersion: number}> => {
    const session = saveSession.current, editor = editorRef.current;
    const isCurrent = () => session.active && saveSession.current === session && editorRef.current === editor;
    flushEditorChanges(editor);
    const { record, snapshot, business } = latestSaveRef.current;
    const businessOverride = override ?? business;
    if (!record || !snapshot) return false;
    const sourceFormat = formatOf(record.originalName);
    const usesWorkbookEditor =
      sourceFormat === 'XLSX' || (sourceFormat === 'PHOTO' && isWorkbookSnapshot(snapshot));
    if (!usesWorkbookEditor && sourceFormat !== 'DOCX') return false;
    const validationError = validateBusinessSnapshot(businessOverride);
    if (validationError) {
      msg.error(validationError);
      return false;
    }
    setSaving(true);
    try {
      const liveSnapshot = editor?.getSnapshot();
      const generation = editGenerationRef.current, revision = editor?.getContentRevision?.();
      const current = usesWorkbookEditor
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
      if (!isCurrent()) return false;
      // A later writer may commit between our batch acknowledgement and this GET.
      // Never adopt their newer version as the base of our still-local workbook.
      if (saved.lockVersion !== record.lockVersion + 1) throw new Error('生产单已在其他位置更新，本地内容仍然保留');
      savedGenerationRef.current = generation; savedEditorRef.current = { editor, revision };
      setRecord(saved);
      setBusiness(current => current === business ? businessOverride : current);
      setDirty(editGenerationRef.current !== generation || !editorUnchanged(editorRef.current, editor, revision));
      if (!silent) msg.success('生产单草稿已保存');
      return {lockVersion: saved.lockVersion};
    } catch (reason) {
      if (isCurrent()) { setDirty(true); msg.error(reason instanceof Error ? reason.message : '保存失败'); }
      return false;
    } finally {
      if (session.active && saveSession.current === session) {
        setSaving(false);
        if (editorRef.current !== editor) setDirty(true);
      }
    }
  };
  const saveDraft = (override?: ProductionBusinessSnapshot, silent = false): Promise<false | { lockVersion: number }> => {
    const owner = saveSession.current;
    if (savePromiseRef.current && saveOwnerRef.current === owner) {
      return override ? savePromiseRef.current.then(() => owner.active && saveSession.current === owner ? saveDraftRef.current(override, silent) : false) : savePromiseRef.current;
    }
    const pending = performSaveDraft(override, silent).finally(() => { if (savePromiseRef.current === pending) savePromiseRef.current = undefined; });
    savePromiseRef.current = pending; saveOwnerRef.current = owner; return pending;
  };
  saveDraftRef.current = saveDraft;

  const publish = async () => {
    if (!record || selectedVersion) return;
    if (!record.productionName?.trim() || !record.orderNo?.trim()) {
      msg.error('发布前请先填写生产单名称和订单号');
      return;
    }
    setPublishing(true);
    const session = saveSession.current, editor = editorRef.current;
    const isCurrent = () => session.active && saveSession.current === session && editorRef.current === editor;
    const resume = pauseWorkspaceInputs(workspaceRootRef.current);
    flushEditorChanges(editor);
    const generation = editGenerationRef.current, revision = editor?.getContentRevision?.();
    try {
      if (formatOf(record.originalName) === 'XLSX'
        || (formatOf(record.originalName) === 'PHOTO' && !!record.selectedTemplateVersionId)) {
        const fields = await productionUploadApi.fields(record.id);
        if (!isCurrent()) return;
        if (fields.some((field) => !['CONFIRMED', 'IGNORED'].includes(field.reviewStatus))) {
          msg.warning('请先确认生产单的全部识别字段');
          setView('edit');
          setFieldTab('recognition');
          return;
        }
      }
      // 编辑中的内容随发布一起落库，不需要先手动保存草稿。
      let version=record.lockVersion;
      const baseline = savedEditorRef.current?.editor === editor ? savedEditorRef.current.revision : 0;
      if (generation !== savedGenerationRef.current || (editor?.getContentRevision && revision !== baseline) || savePromiseRef.current || !record.workbookSnapshot) {
        const saved = await saveDraft(undefined, true);
        if (!saved) return;
        version=saved.lockVersion;
      }
      if (!isCurrent()) return;
      if (generation !== editGenerationRef.current || !editorUnchanged(editorRef.current, editor, revision)) { setDirty(true); return; }
      await productionOrderRecordApi.publish(record.id,version);
      if (!isCurrent()) return;
      msg.success('生产单已发布，并生成新版本');
      const [detail, history] = await Promise.all([
        productionOrderRecordApi.get(record.id),
        productionOrderRecordApi.versions(record.id),
      ]);
      if (!isCurrent()) return;
      setRecord(detail);
      setVersions(history);
    } catch (reason) {
      if (isCurrent()) msg.error(reason instanceof Error ? reason.message : '发布失败');
    } finally {
      resume();
      if (isCurrent()) setPublishing(false);
    }
  };

  // Recognition runs as an async job and the records endpoint only serves finished
  // uploads, so poll the permissive upload endpoint until the job settles instead
  // of reloading into an error page.  `onProgress` reports every poll so the caller
  // can show the stage and percentage in place, and the settled record comes back
  // so the caller can tell success, failure and timeout apart.
  const waitForRecognition = useCallback(async (
    attempts = 160,
    onProgress?: (current: ProductionUpload) => void,
  ): Promise<ProductionUpload | undefined> => {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const current = await productionUploadApi.get(uploadId).catch(() => undefined);
      if (!current) return undefined;
      onProgress?.(current);
      if (!IN_PROGRESS_STATUSES.includes(current.status)) return current;
      await new Promise((resolve) => window.setTimeout(resolve, 1500));
    }
    return undefined;
  }, [uploadId]);

  // A record is recognising either because this page started the job or because
  // the page was reloaded while that job was still running.  Both keep the
  // progress strip alive and the conflicting actions locked.
  const recognizing = restarting || (record ? IN_PROGRESS_STATUSES.includes(record.status) : false);

  const chooseTemplate = async (templateVersionId: string) => {
    setSelectingTemplate(templateVersionId);
    try {
      await productionUploadApi.selectTemplate(uploadId, templateVersionId,record!.lockVersion);
      msg.loading({ content: '正在识别图片字段，请稍候…', duration: 0, key: 'photo-recognition' });
      const finished = await waitForRecognition();
      msg.destroy('photo-recognition');
      if (!finished) {
        msg.warning('字段识别仍在进行，可稍后刷新本页查看识别结果');
        return;
      }
      if (finished.status === 'FAILED') {
        msg.error(finished.failureMessage || '字段识别失败，请重新选择模板后再试');
        return;
      }
      msg.success('模板已选择，字段识别完成');
      await load();
    } catch (reason) {
      msg.destroy('photo-recognition');
      msg.error(reason instanceof Error ? reason.message : '模板选择失败');
    } finally {
      setSelectingTemplate(undefined);
    }
  };

  const confirmAllFields = async () => {
    if (!record) return;
    setReviewBusy(true);
    try {
      const fields = await productionUploadApi.fields(record.id);
      const pending = fields.filter((field) => !['ROW_TABLE', 'COLUMN_TABLE', 'FORM_REGION'].includes(field.itemKind)
        && !['CONFIRMED', 'IGNORED'].includes(field.reviewStatus));
      if (pending.length) {
        await productionUploadApi.confirmFields(record.id, pending.map((field) => ({
          fieldCode: field.fieldCode,
          dataPath: field.dataPath,
          recordIndex: field.recordIndex,
          reviewStatus: 'CONFIRMED',
        })),record.lockVersion);
      }
      msg.success(`已确认 ${pending.length} 个字段`);
      setRecord(await productionOrderRecordApi.get(record.id));
      setReviewRefreshToken((current) => current + 1);
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '字段确认失败');
    } finally {
      setReviewBusy(false);
    }
  };

  const restartRecognition = async () => {
    if (!record || record.status === 'PUBLISHED' || dirty || restarting) return;
    setRestarting(true);
    setRecognitionProgress(0);
    setRecognitionStage(undefined);
    setFieldTab('recognition');
    try {
      const started = await productionUploadApi.retry(record.id,record.lockVersion);
      setRecognitionProgress(started.recognitionProgress ?? 0);
      setRecognitionStage(started.currentStage);
      const settled = await waitForRecognition(160, (current) => {
        setRecognitionProgress(current.recognitionProgress ?? 0);
        setRecognitionStage(current.currentStage);
      });
      if (!settled) {
        msg.warning('重新识别仍在后台进行，可稍后刷新本页查看识别结果');
        return;
      }
      if (settled.status === 'FAILED') {
        msg.error(settled.failureMessage || '重新识别失败，请稍后重试');
        return;
      }
      msg.success('重新识别完成，请核对新的字段识别结果');
      await load();
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '重新识别失败');
    } finally {
      setRestarting(false);
      setRecognitionProgress(undefined);
      setRecognitionStage(undefined);
    }
  };

  const requestRestartRecognition = () => {
    if (!record) return;
    Modal.confirm({
      title: `重新识别这份${productionOrderKindLabel(record.originalName)}？`,
      content: '系统会重新读取上传的原文件并生成字段候选，当前草稿中的编辑内容会被识别结果替换。识别期间本页会显示进度，无需离开。',
      okText: '重新识别',
      cancelText: '取消',
      // Return nothing on purpose: antd keeps a dialog open while `onOk` returns a
      // promise, and the restart now waits for the whole recognition job.  Closing
      // at once hands the progress to the status strip instead of a modal that
      // blocks the workspace for minutes.
      onOk: () => {
        void restartRecognition();
      },
    });
  };

  const downloadSource = async () => {
    if (!record) return;
    setDownloadingSource(true);
    try {
      downloadBlob(await fetchFileBlob(record.fileId), record.originalName);
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '原文下载失败');
    } finally {
      setDownloadingSource(false);
    }
  };

  const openTemplateDraft = async () => {
    if (!record) return;
    templateDraftForm.setFieldsValue({ name: `${record.productionName || record.originalName.replace(/\.xlsx?$/i, '')}模板`.slice(0, 160) });
    setTemplateDraftOpen(true);
    try {
      setTemplateCategories(await templateApi.listCategories());
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '模板分类加载失败');
    }
  };

  const createTemplateDraft = async () => {
    if (!record || templateDraftSaving) return;
    let values: { name: string; categoryId: string };
    try { values = await templateDraftForm.validateFields(); } catch { return; }
    setTemplateDraftSaving(true);
    try {
      if (dirty && !await saveDraft(business, true)) return;
      const blob = await productionOrderRecordApi.export(record.id);
      const file = new File([blob], `${values.name.trim()}.xlsx`, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
      const staged = await stageFile(file, 'IMPORT');
      await templateApi.createImport(staged.fileId, 'XLSX', {
        categoryId: values.categoryId, duplicateOverride: true, operationSource: 'PRODUCTION_ORDER',
      });
      setTemplateDraftOpen(false);
      msg.success('已提交模板草稿生成任务');
      navigate('/templates/upload');
    } catch (reason) {
      msg.error(reason instanceof Error ? reason.message : '生成模板草稿失败');
    } finally {
      setTemplateDraftSaving(false);
    }
  };

  const restoreVersion = (version: ProductionUploadVersion) => {
    if (!isWorkbook) return;
    setSnapshot(version.workbookSnapshot);
    setBusiness(normalizeBusinessSnapshot(version.workbookSnapshot.productionBusiness));
    setSelectedVersion(undefined);
    setView('edit');
    setEditorReady(false);
    markDirty();
    msg.info(`已载入版本 V${version.versionNo}，保存草稿后生效`);
  };

  const switchView = (next: WorkspaceView) => {
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
      const extension = isWorkbookSnapshot(record.workbookSnapshot)
        ? '.xlsx'
        : record.originalName.includes('.')
          ? record.originalName.slice(record.originalName.lastIndexOf('.'))
          : '';
      const baseName = record.productionName || record.originalName || '生产单';
      const fileName =
        extension && !baseName.toLowerCase().endsWith(extension.toLowerCase())
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

  // A page reloaded while recognition was still running has no local restart to
  // await, so follow the job here and refresh once it settles.  `restarting`
  // already owns its own polling, which keeps the two from doubling up.
  useEffect(() => {
    if (!recognizing || restarting || !record) return;
    let active = true;
    void (async () => {
      const settled = await waitForRecognition(160, (current) => {
        if (!active) return;
        setRecognitionProgress(current.recognitionProgress ?? 0);
        setRecognitionStage(current.currentStage);
      });
      if (!active || !settled) return;
      await load();
    })();
    return () => {
      active = false;
    };
  }, [load, recognizing, record, restarting, waitForRecognition]);

  // Re-parsing blanks the stored recognition result on purpose, so remember the last
  // usable one to keep the field panel mounted while recognition runs.
  useEffect(() => {
    const current = record?.recognitionResult;
    if (current?.recognitionReview) setRetainedResult(current);
  }, [record]);

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
  const isWorkbook = isExcel || (isPhoto && isWorkbookSnapshot(displayedSnapshot));
  const storedRecognitionResult = (isExcel || (isPhoto && !!record.selectedTemplateVersionId))
    ? record.recognitionResult
    : undefined;
  // Recognition clears the stored result before re-parsing, and a reload during the
  // job reads the same cleared record.  Fall back to the previous run so the panel —
  // review, structure and mapping alike — stays on screen mid-restart, exactly like
  // the template center keeps showing the fields it already recognised.
  const effectiveRecognitionResult = storedRecognitionResult
    ?? (recognizing ? retainedResult : undefined);
  const review = effectiveRecognitionResult?.recognitionReview as
    ProductionRecognitionReview | undefined;
  // Field review only needs the selected template the values were extracted
  // against; editing additionally needs the generated workbook.  A photo whose
  // workbook generation failed can therefore still confirm its fields.
  const hasFieldReview = isExcel || (isPhoto && !!record.selectedTemplateVersionId);
  // The preview is what a reviewer checks the recognized values against, so the
  // field review belongs next to it as well: on a photo upload the workbook only
  // exists on the edit view, which used to hide the panel exactly where the
  // original document is still on screen.
  const showFieldReview = hasFieldReview && !selectedVersion
    && (view === 'preview' || view === 'edit' || (isPhoto && !isWorkbook));
  const focusRecognitionItem = (item: RecognitionReviewItem) => {
    const source = item.payload.locator ?? {};
    const locator: Record<string, unknown> = {
      ...source,
      sheetId: source.sheetId || item.sheetId,
      sheetName: source.sheetName || item.sheetName,
      address: source.address || item.address,
      labelAddress: source.labelAddress || item.labelAddress,
    };
    const binding: TemplateBinding = {
      bindingId: item.payload.bindingId || item.id,
      dataPath: item.payload.dataPath || '',
      role: item.kind === 'SCALAR' ? 'FIELD' : 'REPEAT_REGION',
      locatorType: item.payload.locatorType || (item.kind === 'SCALAR' ? 'CELL_RANGE' : 'TABLE_REGION'),
      locator,
      syncDirection: 'DATA_TO_EDITOR',
      primaryBinding: true,
      bindingStatus: 'VALID',
    };
    editorRef.current?.focusBinding(binding);
  };
  const latestVersion = versions[0];
  const readonly = !['edit', 'material', 'attachments'].includes(view) || Boolean(selectedVersion);
  const editable = (isWorkbook || format === 'DOCX') && !readonly && canUpdate;
  const waitingForEditor = isWorkbook && view === 'edit' && !editorReady;
  const saveState: SaveState = saving ? 'SAVING' : dirty ? 'DIRTY' : 'SAVED';
  const workspaceActions = () => (
    <>
      <SaveStateBadge state={saveState} />
      <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void exportRecord()}>
        导出
      </Button>
      <Button icon={<DownloadOutlined />} loading={downloadingSource} onClick={() => void downloadSource()}>
        原文下载
      </Button>
      {isExcel && canCreateTemplate && (
        <Button disabled={Boolean(selectedVersion)} onClick={() => void openTemplateDraft()}>
          另存为模板
        </Button>
      )}
      {selectedVersion && <Button onClick={() => setSelectedVersion(undefined)}>返回</Button>}
      {!selectedVersion && (
        <>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={saving}
            disabled={!editable || waitingForEditor || recognizing}
            onClick={() => void saveDraft()}
          >
            保存草稿
          </Button>
          <Button
            type="primary"
            loading={publishing}
            disabled={!canSubmit || saving || recognizing}
            onClick={() => void publish()}
          >
            发布
          </Button>
        </>
      )}
    </>
  );
  return (
    <section ref={workspaceRootRef} className="workspace-shell template-business-workspace quality-excel-workspace production-upload-workspace">
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
            <Typography.Text strong>{record.originalName}</Typography.Text>
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
            { key: 'edit', label: '编辑', disabled: !isWorkbook && format !== 'DOCX' },
            { key: 'versions', label: '版本记录' },
            { key: 'material', label: '实际投料单', disabled: !isWorkbook },
            { key: 'attachments', label: '工艺附件', disabled: !isWorkbook },
          ]}
        />
      </nav>
      <div
        className={`workspace-main-stage ${['material', 'attachments'].includes(view) ? 'is-business-tab' : ''}`}
      >
        {isPhoto && !record.selectedTemplateVersionId && !selectedVersion && (
          <section style={{ padding: 16, borderBottom: '1px solid #f0f0f0' }}>
            <Alert
              type="info"
              showIcon
              message="图片字段解析需要先选择识别模板"
              description="字段解析按模板的字段清单从图片中抽取值，并把结果生成为可编辑的工作簿；未选模板时只保存原图。"
            />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
              {(record.recognitionResult?.templateCandidates ?? []).map((item) => (
                <div
                  key={item.templateVersionId}
                  style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}
                >
                  <span>
                    <b>{item.templateName}</b> · {item.templateCode} · {Math.round(item.score * 100)}%
                  </span>
                  <Button
                    size="small"
                    type="primary"
                    loading={selectingTemplate === item.templateVersionId}
                    disabled={Boolean(selectingTemplate) || !canUpdate}
                    onClick={() => void chooseTemplate(item.templateVersionId)}
                  >
                    选择此模板
                  </Button>
                </div>
              ))}
              {!(record.recognitionResult?.templateCandidates ?? []).length && (
                <Empty description="没有可匹配的已发布 XLSX 模板，请先在模板中心发布生产单模板" />
              )}
            </div>
          </section>
        )}
        {hasFieldReview && showFieldReview && record.recognitionResult?.recognitionWarning && (
          <Alert type="warning" showIcon message="完整字段识别未完成"
            description={record.recognitionResult.recognitionWarning} />
        )}
        {showFieldReview && !review && !recognizing && (
          <Alert type="warning" showIcon message="这条生产单尚未生成字段确认结果"
            description="可重新生成字段确认；原工作簿和已发布版本会保留。"
            action={<Button size="small" loading={reviewBusy} onClick={() => {
              setReviewBusy(true);
              void productionUploadApi.ensureFieldReview(record.id,record.lockVersion).then(setRecord)
                .catch((reason) => msg.error(reason instanceof Error ? reason.message : '生成字段确认失败'))
                .finally(() => setReviewBusy(false));
            }}>生成字段确认</Button>} />
        )}
        {((showFieldReview && review) || recognizing) && (
          <ProductionUploadRecognitionStatusBar
            fieldCount={review?.summary?.total ?? 0}
            pendingCount={review?.summary?.pending ?? 0}
            pendingRegionCount={review?.recognitionCoverage?.unresolvedRegionCount ?? 0}
            conflictCount={review?.summary?.conflict ?? 0}
            regionCount={review?.summary?.regionCount ?? 0}
            busy={reviewBusy}
            running={recognizing}
            progress={recognitionProgress ?? record.recognitionProgress}
            stageLabel={productionStageLabel(recognitionStage ?? record.currentStage)}
            canUpdate={canUpdate}
            canRestart={canUpdate && record.status !== 'PUBLISHED' && !dirty}
            onStart={() => setFieldTab('recognition')}
            onConfirmAll={() => void confirmAllFields()}
            onRestart={requestRestartRecognition}
          />
        )}
        <div className={showFieldReview && review ? 'template-workspace-grid production-review-grid' : 'production-workspace-content'}>
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
                onAttachmentsChange={async (next) => Boolean(await saveDraft(next, true))}
                onChange={(next) => {
                  setBusiness(next);
                  markDirty();
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
                    {canUpdate && isWorkbook && (
                      <Button size="small" onClick={() => restoreVersion(selectedVersion)}>
                        以此版本恢复草稿
                      </Button>
                    )}
                  </Space>
                )}
              </div>
              {isWorkbook && displayedSnapshot ? (
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
                        if (editorReady) markDirty();
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
                  onDirty={markDirty}
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
                  loadPreview={() =>
                    // A legacy .doc is an OLE2 binary the renderer cannot read, so the
                    // preview uses the DOCX copy the server staged during recognition.
                    fetchFilePreviewBlob(
                      record.recognitionResult?.wordDocument?.workingDocxFileId
                        ?? record.recognitionResult?.normalizedFileId
                        ?? record.fileId,
                    )
                  }
                />
              )}
            </section>
          )}
        </main>
        {showFieldReview && review && (
          <aside className="business-field-panel">
            <ProductionUploadFieldManagerPanel
              uploadId={record.id}
              lockVersion={record.lockVersion}
              format="XLSX"
              recognitionResult={effectiveRecognitionResult}
              canUpdate={canUpdate && !recognizing}
              activeTab={fieldTab}
              onActiveTabChange={setFieldTab}
              onFocusItem={focusRecognitionItem}
              refreshToken={reviewRefreshToken}
              onSaved={() => void productionOrderRecordApi.get(record.id).then(setRecord)}
            />
          </aside>
        )}
        </div>
      </div>
      <Modal title="另存为模板" open={templateDraftOpen} okText="生成模板草稿"
        confirmLoading={templateDraftSaving} onCancel={() => setTemplateDraftOpen(false)}
        onOk={() => void createTemplateDraft()} destroyOnClose>
        <Form form={templateDraftForm} layout="vertical" preserve={false}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true, whitespace: true, message: '请输入模板名称' }]}>
            <Input maxLength={160} showCount />
          </Form.Item>
          <Form.Item name="categoryId" label="模板分类" rules={[{ required: true, message: '请选择模板分类' }]}>
            <Select options={templateCategories.map((item) => ({ value: item.id, label: item.name }))} placeholder="请选择模板分类" />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}
