import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileExcelOutlined,
  LoadingOutlined,
  ReloadOutlined,
  RobotOutlined,
  SaveOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { formatTime } from '@/utils/date';
import { SaveStateBadge, type SaveState } from '@/components/SaveStateBadge';
import { VersionHistoryPanel } from '@/components/version-history/VersionHistoryPanel';
import {
  App,
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Progress,
  Result,
  Skeleton,
  Space,
  Spin,
  Select,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';

import {
  buildExperimentSnapshot,
  parseExperimentSnapshot,
} from '@/features/experiment-workspace/experiment-workbook';
import type { EditorHandle, EditorSelection, TemplateBinding } from '@/features/template-workspace/types';
import { getAtPath, setAtPath } from '@/features/template-workspace/path-utils';
import {
  reviewInformation,
  reviewInformationMissing,
  withReviewInformation,
  type ReviewInformation,
} from './experiment-review-information';
import {
  actExperiment,
  createRevision,
  getExperiment,
  getExperimentImport,
  listExperimentImports,
  listVersions,
  retryExperimentImport,
  saveExperiment,
  type ExperimentDetail,
  type ExperimentModel,
  type ExperimentVersion,
  exportExperiment,
  getExperimentTemplateDataReview,
  confirmExperimentTemplateData,
  type ExperimentTemplateDataReview,
} from '@/services/experiments/experiment-api';
import { downloadBlob, fetchFilePreviewBlob, fileContentUrl } from '@/services/files/file-api';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { Can } from '@/components/auth/Can';
import { templateApi, type TemplateCategory } from '@/services/templates/template-api';
import { ExperimentFieldReviewPanel } from './ExperimentFieldReviewPanel';
import './experiments.css';
import { ExperimentTemplateDataPanel } from './ExperimentTemplateDataPanel';
import { ExperimentImportDataPanel } from './ExperimentImportDataPanel';

const SheetsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverSheetsEditor')).UniverSheetsEditor,
}));

const DocsEditor = lazy(async () => ({
  default: (await import('@/features/template-workspace/UniverDocsEditor')).UniverDocsEditor,
}));

type WorkspaceView = 'preview' | 'edit' | 'versions';
type PublishAction = 'submit-review' | 'approve';
/** 与模板中心一致：停止编辑 2 秒后自动保存草稿。 */
const AUTOSAVE_DELAY_MS = 2000;
const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待开始',
  IN_PROGRESS: '进行中',
  PENDING_REVIEW: '待审核',
  RETURNED: '已退回',
  COMPLETED: '已发布',
  VOIDED: '已作废',
};

function isWorkbookSnapshot(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const candidate = value as Record<string, unknown>;
  return Boolean(
    candidate.sheets && typeof candidate.sheets === 'object' && !Array.isArray(candidate.sheets),
  );
}

function documentFormatOf(
  model: ExperimentModel,
  snapshot?: Record<string, unknown>,
): 'word' | 'excel' {
  if (
    model.documentFormat === 'excel' ||
    isWorkbookSnapshot(model.documentSnapshot) ||
    isWorkbookSnapshot(snapshot)
  ) {
    return 'excel';
  }
  const sourceName = typeof model.sourceFileName === 'string' ? model.sourceFileName : '';
  return /\.(xlsx|xls|csv)$/i.test(sourceName) ? 'excel' : 'word';
}

export function ExperimentWorkspacePage({
  experimentId,
  returnTo: returnToOverride,
  onBack,
}: {
  experimentId?: string;
  returnTo?: string;
  onBack?: () => void;
} = {}) {
  const { id: routeId = '' } = useParams();
  const id = experimentId ?? routeId;
  const navigate = useNavigate();
  const location = useLocation();
  const returnTo = returnToOverride
    ?? (typeof location.state?.returnTo === 'string' ? location.state.returnTo : '/experiments/list');
  const goBack = () => onBack ? onBack() : navigate(returnTo);
  const { message, modal } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const detailRef = useRef<ExperimentDetail>();
  const [detail, setDetail] = useState<ExperimentDetail>();
  const [snapshot, setSnapshot] = useState<Record<string, unknown>>();
  const [editModel, setEditModel] = useState<ExperimentModel>({});
  const [templateReview, setTemplateReview] = useState<ExperimentTemplateDataReview>();
  const pendingTemplateWrites = useRef<Promise<void>[]>([]);
  const savePromiseRef = useRef<Promise<boolean>>();
  const saveRef = useRef<(silent?: boolean) => Promise<boolean>>(() => Promise.resolve(false));
  const autosaveTimerRef = useRef<number>();
  const editGenerationRef = useRef(0);
  const [versions, setVersions] = useState<ExperimentVersion[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<ExperimentVersion>();
  const [view, setView] = useState<WorkspaceView>('preview');
  const [busy, setBusy] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [previewSource, setPreviewSource] = useState<FilePreviewDescriptor>();
  const [saveState, setSaveState] = useState<SaveState>('SAVED');
  const [editRevision, setEditRevision] = useState(0);
  const [loadError, setLoadError] = useState<string>();
  const [reviewOpen, setReviewOpen] = useState(false);
  const [returnOpen, setReturnOpen] = useState(false);
  const [returnReason, setReturnReason] = useState('');
  const [reviewTrigger, setReviewTrigger] = useState(0);
  const [importPanelTab, setImportPanelTab] = useState<'data' | 'recognition'>('data');
  const [coordinatePicking, setCoordinatePicking] = useState<{ fieldId: string; target: 'labelAddress' | 'address' }>();
  const [recognitionBusy, setRecognitionBusy] = useState(false);
  const [recognitionProgress, setRecognitionProgress] = useState<number>();
  const [reviewForm] = Form.useForm<ReviewInformation>();
  const reviewFormValuesRef = useRef<ReviewInformation>();
  const [templateDraftOpen, setTemplateDraftOpen] = useState(false);
  const [templateDraftSaving, setTemplateDraftSaving] = useState(false);
  const [templateCategories, setTemplateCategories] = useState<TemplateCategory[]>([]);
  const [templateDraftForm] = Form.useForm<{ name: string; categoryId: string }>();
  const templateDraftNameRef = useRef('');
  const [metadataOpen, setMetadataOpen] = useState(false);
  const [metadataSaving, setMetadataSaving] = useState(false);
  const [metadataForm] = Form.useForm<{ ownerName: string; experimentDate: string }>();
  const metadataFormValuesRef = useRef<{ ownerName: string; experimentDate: string }>();
  const isDesktop = useDesktopEditing();
  // 只读状态与自动保存共用同一判定，避免两处条件漂移。
  const statusLocked = !detail
    || ['COMPLETED', 'VOIDED', 'PENDING_REVIEW'].includes(detail.summary.status);

  const load = useCallback(async () => {
    setSaveState('SAVED');
    try {
      const [d, v] = await Promise.all([
        getExperiment(id),
        listVersions(id),
      ]);
      const nextModel: ExperimentModel = { ...(d.editModel ?? {}) };
      // Keep legacy XLS/XLSX imports aligned with the production workspace:
      // workbook snapshots always use Sheets, even if an older record was
      // persisted with documentFormat=word.
      nextModel.documentFormat = documentFormatOf(nextModel, d.templateSnapshot);
      if (
        nextModel.documentFormat === 'word' &&
        d.templateSnapshot &&
        !nextModel.documentSnapshot
      ) {
        nextModel.documentSnapshot = d.templateSnapshot;
      }
      detailRef.current = d;
      setDetail(d);
      setEditModel(nextModel);
      setTemplateReview(nextModel.templateDataReview?.required
        || (d.summary.sourceType === 'TEMPLATE' && Boolean(d.templateVersionId))
        || (['EXCEL_IMPORT', 'OCR_IMPORT'].includes(d.summary.sourceType)
          && Boolean(nextModel.sourceImportJobId) && Boolean(d.templateVersionId))
        ? await getExperimentTemplateDataReview(id) : undefined);
      const initialSnapshot = buildExperimentSnapshot(
        nextModel,
        d.currentVersionId ?? id,
        d.templateSnapshot,
      );
      setSnapshot(initialSnapshot);
      setVersions(v);
      const editable = !['COMPLETED', 'VOIDED', 'PENDING_REVIEW'].includes(d.summary.status);
      setView(editable ? 'edit' : 'preview');
      setSaveState('SAVED');
      editGenerationRef.current = 0;
      setEditRevision(0);
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : '实验本加载失败');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const currentDraftModel = () => {
    if (!detail) return editModel;
    // Keep saving functional while the lazy Univer editor is starting by
    // using the last loaded snapshot as the document baseline; once the
    // editor is ready its live snapshot remains authoritative.
    const currentSnapshot =
      editorRef.current?.getSnapshot() ??
      snapshot ??
      buildExperimentSnapshot(editModel, detail.currentVersionId ?? id, detail.templateSnapshot);
    const model = parseExperimentSnapshot(currentSnapshot, editModel);
    if (!editorRef.current || !recognizedBindings.length) return model;
    let values = model.dynamicValues ?? {};
    for (const binding of recognizedBindings) {
      if (binding.role !== 'FIELD' || !binding.dataPath) continue;
      try {
        const value = editorRef.current.readBinding(binding);
        if (value !== undefined) values = setAtPath(values, binding.dataPath, value);
      } catch {
        // Keep the last saved value if this position is still under review.
      }
    }
    return { ...model, dynamicValues: values };
  };

  // 与模板中心一致：每次修改推进编辑代号并标记未保存，由自动保存接管落库。
  const markDirty = () => {
    editGenerationRef.current += 1;
    setEditRevision(editGenerationRef.current);
    setSaveState((current) => (current === 'SAVING' ? current : 'DIRTY'));
  };

  const updateRecognizedField = (fieldId: string, changes: Partial<NonNullable<ExperimentModel['recognizedFields']>[number]>) => {
    setEditModel((current) => ({
      ...current,
      recognizedFields: current.recognizedFields?.map((field) =>
        field.id === fieldId ? { ...field, ...changes } : field),
    }));
    markDirty();
  };
  const updateRecognizedRegion = (regionId: string, confirmed: boolean) => {
    setEditModel((current) => ({
      ...current,
      recognizedRegions: current.recognizedRegions?.map((region) =>
        region.id === regionId ? { ...region, confirmed } : region),
    }));
    markDirty();
  };
  const handleCoordinateSelection = (selection: EditorSelection) => {
    if (!coordinatePicking) return;
    const { fieldId, target } = coordinatePicking;
    setEditModel((current) => ({
      ...current,
      recognizedFields: current.recognizedFields?.map((field) => {
        if (field.id !== fieldId) return field;
        const locator: Record<string, unknown> = {
              ...field.locator,
              sheetId: selection.sheetId,
              sheetName: selection.sheetName,
              ...(target === 'labelAddress'
                ? { labelAddress: selection.address, labelRange: selection.address }
                : { address: selection.address, valueRange: selection.address }),
              source: 'MANUAL',
            };
        return {
          ...field,
          locator,
          confirmed: Boolean(field.name.trim() && field.fieldCode.trim() && field.dataPath.trim()
            && (locator.address || locator.anchorAddress || locator.valueRange
              || locator.dataRange || locator.range)),
        };
      }),
    }));
    markDirty();
    setCoordinatePicking(undefined);
  };
  const confirmAllRecognizedFields = () => {
    if (!editModel.recognizedFields?.some((field) => !field.confirmed)) return;
    setEditModel((current) => ({
      ...current,
      recognizedFields: current.recognizedFields?.map((field) =>
        field.fieldCode.trim() && field.dataPath.trim()
          && (field.locator.address || field.locator.anchorAddress || field.locator.range
            || field.locator.valueRange || field.locator.dataRange)
          ? { ...field, confirmed: true } : field),
    }));
    markDirty();
  };
  const restartRecognition = async () => {
    if (!detail || recognitionBusy) return;
    setRecognitionBusy(true);
    setReviewTrigger((current) => current + 1);
    try {
      const jobs = await listExperimentImports();
      const job = jobs.find((item) => item.experimentId === detail.summary.id);
      if (!job) throw new Error('当前实验没有可重新识别的上传任务');
      await retryExperimentImport(job.id);
      setRecognitionProgress(0);
      for (let attempt = 0; attempt < 120; attempt++) {
        await new Promise((resolve) => window.setTimeout(resolve, 1000));
        const latest = await getExperimentImport(job.id);
        setRecognitionProgress(latest.progress);
        if (latest.status === 'COMPLETED') {
          await load();
          void message.success('重新识别完成，请核对字段和填写位置');
          return;
        }
        if (['FAILED', 'CANCELLED'].includes(latest.status)) {
          throw new Error(latest.errorMessage || '重新识别失败');
        }
      }
      throw new Error('重新识别仍在进行，请稍后刷新工作区');
    } finally {
      setRecognitionBusy(false);
      setRecognitionProgress(undefined);
    }
  };
  const regionBindings: TemplateBinding[] = (editModel.recognizedRegions ?? [])
    .filter((region) => ['ROW_TABLE', 'COLUMN_TABLE'].includes(region.kind) && region.bindingId)
    .map((region) => ({
      bindingId: region.bindingId,
      dataPath: region.dataPath,
      role: 'REPEAT_REGION',
      mappingKind: 'REPEAT_REGION',
      locatorType: region.locatorType,
      locator: region.locator,
      syncDirection: 'DATA_TO_EDITOR',
      primaryBinding: true,
      bindingStatus: 'VALID',
    }));
  const recognizedBindings: TemplateBinding[] = [
    ...(templateReview?.bindings ?? []).map((binding): TemplateBinding => ({
      bindingId: binding.bindingId,
      fieldCode: binding.fieldCode,
      dataPath: binding.dataPath,
      role: binding.mappingKind === 'REPEAT_REGION' ? 'REPEAT_REGION' : 'FIELD',
      mappingKind: binding.mappingKind === 'REPEAT_FIELD' ? 'REPEAT_FIELD'
        : binding.mappingKind === 'REPEAT_REGION' ? 'REPEAT_REGION' : 'SCALAR',
      locatorType: 'CELL_RANGE',
      locator: binding.locator ?? {},
      syncDirection: 'TWO_WAY',
      primaryBinding: true,
      bindingStatus: 'VALID',
    })),
    ...regionBindings,
    ...(editModel.recognizedFields ?? [])
    .filter((field) => field.locator.address || field.locator.anchorAddress
      || field.locator.valueRange || field.locator.dataRange)
    .map((field): TemplateBinding => ({
      bindingId: field.bindingId || field.id,
      fieldId: field.id,
      parentBindingId: field.parentBindingId || undefined,
      fieldCode: field.fieldCode,
      dataPath: field.dataPath,
      role: 'FIELD',
      mappingKind: field.mappingKind === 'REPEAT_FIELD' ? 'REPEAT_FIELD' : 'SCALAR',
      repeatAxis: field.repeatAxis === 'ROW' || field.repeatAxis === 'COLUMN' ? field.repeatAxis : 'UNKNOWN',
      locatorType: field.locatorType || 'CELL_RANGE',
      locator: field.locator,
      syncDirection: 'TWO_WAY',
      primaryBinding: true,
      bindingStatus: 'VALID',
    })),
  ];
  const handleRecognizedValue = (binding: TemplateBinding, value: unknown) => {
    if (value === undefined || !binding.dataPath) return;
    const dataPath = binding.dataPath;
    const unchanged =
      JSON.stringify(getAtPath(editModel.dynamicValues ?? {}, dataPath)) === JSON.stringify(value);
    setEditModel((current) => {
      const values = current.dynamicValues ?? {};
      if (JSON.stringify(getAtPath(values, dataPath)) === JSON.stringify(value)) return current;
      return { ...current, dynamicValues: setAtPath(values, dataPath, value),
        templateDataReview: current.templateDataReview?.required
          ? { ...current.templateDataReview, confirmed: false } : current.templateDataReview };
    });
    if (!unchanged) markDirty();
  };

  const changeTemplateValue = (path: string, value: string) => {
    const binding = recognizedBindings.find((item) => item.dataPath === path);
    if (binding && editorRef.current) {
      const rawRange = String(binding.locator.anchorRange || binding.locator.logicalInputRange
        || binding.locator.anchorAddress || binding.locator.valueRange || binding.locator.address || '');
      const firstCell = rawRange.replaceAll('$', '').split(':')[0];
      // A repeated field can span several samples. Editing its summary writes
      // the first sample only; setValue on the whole range would overwrite all.
      const writeBinding = binding.mappingKind === 'REPEAT_FIELD' && firstCell
        ? { ...binding, mappingKind: 'SCALAR' as const,
          locator: { ...binding.locator, anchorAddress: firstCell, address: firstCell,
            valueMode: 'SCALAR' } }
        : binding;
      const write = editorRef.current.writeBinding(writeBinding, value);
      pendingTemplateWrites.current.push(write);
      void write.then(() => {
        pendingTemplateWrites.current = pendingTemplateWrites.current.filter((item) => item !== write);
      }, () => { /* Keep the rejected write for the confirmation error handler. */ });
    }
    setEditModel((current) => ({ ...current,
      dynamicValues: setAtPath(current.dynamicValues ?? {}, path, value),
      templateDataReview: current.templateDataReview?.required
        ? { ...current.templateDataReview, confirmed: false } : current.templateDataReview,
    }));
    markDirty();
  };

  const confirmTemplateData = async () => {
    if (!detail || (!templateReview && detail.summary.sourceType !== 'EXCEL_IMPORT')) return;
    setBusy(true);
    try {
      await Promise.all(pendingTemplateWrites.current);
      // 自动保存可能仍在进行，先等它结束，避免同一 revision 并发写入冲突。
      await savePromiseRef.current;
      const saved = await persistDraft(currentDraftModel());
      if (!saved) return;
      await confirmExperimentTemplateData(id, saved.summary.revision);
      void message.success('本次实验数据已确认');
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '数据确认失败');
    } finally {
      setBusy(false);
    }
  };

  const persistDraft = async (
    nextModel: ExperimentModel,
    successText?: string,
    silent = false,
    metadata?: { ownerName: string; experimentDate: string },
  ) => {
    const currentDetail = detailRef.current?.summary.id === id ? detailRef.current : detail;
    if (!currentDetail) return undefined;
    const title = nextModel.title ?? currentDetail.summary.title;
    if (!title.trim()) {
      if (!silent) void message.warning('实验标题不能为空');
      return undefined;
    }
    const savedDetail = await saveExperiment(id, {
      revision: currentDetail.summary.revision,
      experimentNo: currentDetail.summary.experimentNo,
      title,
      categoryId: currentDetail.summary.categoryId,
      categoryName: currentDetail.summary.categoryName,
      projectId: currentDetail.summary.projectId,
      stageId: currentDetail.summary.stageId,
      taskId: currentDetail.summary.taskId,
      ownerName: metadata?.ownerName ?? currentDetail.summary.ownerName ?? '',
      experimentDate: metadata?.experimentDate ?? currentDetail.summary.experimentDate ?? '',
      templateVersionId: currentDetail.templateVersionId,
      templateSnapshotHash: currentDetail.templateSnapshotHash,
      templateSnapshot: currentDetail.templateSnapshot,
      editModel: nextModel,
    });
    // Keep the live editor mounted after a draft save. Updating `snapshot`
    // here would trigger UniverSheetsEditor’s snapshot effect, destroy the
    // current workspace, and recreate it from a possibly stale response.
    detailRef.current = savedDetail;
    setDetail(savedDetail);
    setEditModel({ ...nextModel, templateDataReview: savedDetail.editModel.templateDataReview,
      importDataReview: savedDetail.editModel.importDataReview });
    if (successText) void message.success(successText);
    return savedDetail;
  };

  const openMetadataEditor = () => {
    if (!detail) return;
    metadataFormValuesRef.current = {
      ownerName: detail.summary.ownerName ?? '',
      experimentDate: detail.summary.experimentDate ?? '',
    };
    setMetadataOpen(true);
  };

  useEffect(() => {
    if (!metadataOpen || !metadataFormValuesRef.current) return;
    metadataForm.setFieldsValue(metadataFormValuesRef.current);
  }, [metadataForm, metadataOpen]);

  const saveMetadata = async () => {
    if (!detail || metadataSaving) return;
    let values: { ownerName: string; experimentDate: string };
    try {
      values = await metadataForm.validateFields();
    } catch {
      return;
    }
    const generation = editGenerationRef.current;
    window.clearTimeout(autosaveTimerRef.current);
    setMetadataSaving(true);
    setSaveState('SAVING');
    try {
      await Promise.all(pendingTemplateWrites.current);
      await savePromiseRef.current;
      const saved = await persistDraft(currentDraftModel(), '实验基本信息已保存', false, {
        ownerName: values.ownerName.trim(),
        experimentDate: values.experimentDate,
      });
      if (saved) {
        setSaveState(editGenerationRef.current === generation ? 'SAVED' : 'DIRTY');
        setMetadataOpen(false);
      } else {
        setSaveState('DIRTY');
      }
    } catch (error) {
      setSaveState('DIRTY');
      void message.error(error instanceof Error ? error.message : '实验基本信息保存失败');
    } finally {
      setMetadataSaving(false);
    }
  };

  const performSave = async (silent: boolean): Promise<boolean> => {
    if (!detail) return false;
    const generation = editGenerationRef.current;
    setSaveState('SAVING');
    try {
      const savedDetail = await persistDraft(
        currentDraftModel(),
        silent ? undefined : '草稿已保存',
        silent,
      );
      if (!savedDetail) {
        setSaveState('DIRTY');
        return false;
      }
      // 保存期间如果继续编辑，保持“未保存”，由自动保存继续落库，
      // 避免把保存前的旧内容当成最新版本。
      setSaveState(editGenerationRef.current === generation ? 'SAVED' : 'DIRTY');
      return true;
    } catch (error) {
      setSaveState('DIRTY');
      void message.error(error instanceof Error ? error.message : '保存失败，本地内容仍然保留');
      return false;
    }
  };

  const save = (silent = false): Promise<boolean> => {
    if (savePromiseRef.current) return savePromiseRef.current;
    const pending = performSave(silent).finally(() => {
      if (savePromiseRef.current === pending) savePromiseRef.current = undefined;
    });
    savePromiseRef.current = pending;
    return pending;
  };
  saveRef.current = save;

  // 参照模板中心：编辑态停止输入 2 秒后自动保存草稿。
  // 每次编辑都会推进 editRevision，从而重置防抖计时器。
  useEffect(() => {
    if (saveState !== 'DIRTY' || statusLocked || !isDesktop || view !== 'edit' || busy || metadataOpen || metadataSaving) return;
    autosaveTimerRef.current = window.setTimeout(() => {
      void saveRef.current(true);
    }, AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(autosaveTimerRef.current);
  }, [saveState, statusLocked, isDesktop, view, busy, editRevision, id, metadataOpen, metadataSaving]);

  const openReviewInformation = () => {
    reviewFormValuesRef.current = reviewInformation(editModel);
    setReviewOpen(true);
  };

  useEffect(() => {
    if (!reviewOpen || !reviewFormValuesRef.current) return;
    reviewForm.setFieldsValue(reviewFormValuesRef.current);
  }, [reviewForm, reviewOpen]);

  const submitReviewWithInformation = async () => {
    if (!detail) return;
    let values: ReviewInformation;
    try {
      values = await reviewForm.validateFields();
    } catch {
      return;
    }
    setBusy(true);
    setSaveState('SAVING');
    try {
      await savePromiseRef.current;
      const nextModel = withReviewInformation(currentDraftModel(), values);
      const savedDetail = await persistDraft(nextModel);
      if (!savedDetail) {
        setSaveState('DIRTY');
        return;
      }
      await actExperiment(id, 'submit-review', savedDetail.summary.revision);
      setReviewOpen(false);
      void message.success('已提交审核');
      await load();
    } catch (error) {
      setSaveState('DIRTY');
      void message.error(error instanceof Error ? error.message : '提交审核失败');
    } finally {
      setBusy(false);
    }
  };

  const publish = (action: PublishAction) => {
    if (action === 'submit-review' && Array.isArray(editModel.recognizedFields)
      && (!editModel.recognizedFields.length
        || editModel.recognizedFields.some((field) => !field.confirmed)
        || editModel.recognizedRegions?.some((region) => !region.confirmed))) {
      setReviewTrigger((current) => current + 1);
      void message.warning('请先确认 Excel 识别区域与字段绑定，再提交审核');
      return;
    }
    if (action === 'submit-review' && reviewInformationMissing(editModel)) {
      openReviewInformation();
      return;
    }
    void act(action);
  };

  const act = async (action: PublishAction) => {
    if (!detail) return;
    if (saveState !== 'SAVED') {
      void message.warning('当前内容尚未保存，请先保存后再执行流程操作');
      return;
    }
    setBusy(true);
    try {
      await actExperiment(id, action, detail.summary.revision);
      void message.success('状态已更新');
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验状态更新失败');
    } finally {
      setBusy(false);
    }
  };

  const returnForChanges = async () => {
    if (!detail) return;
    if (!returnReason.trim()) {
      void message.warning('请填写退回修改原因');
      return;
    }
    setBusy(true);
    try {
      await actExperiment(id, 'return', detail.summary.revision, returnReason.trim());
      setReturnOpen(false);
      setReturnReason('');
      void message.success('实验已退回修改');
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '退回修改失败');
    } finally {
      setBusy(false);
    }
  };

  const reviseCompletedExperiment = () => {
    if (!detail || detail.summary.status !== 'COMPLETED') return;
    modal.confirm({
      title: '创建修订版本？',
      content: '当前已完成版本会永久保留；系统将复制其内容，创建一个新的草稿版本供修改。',
      okText: '创建修订',
      cancelText: '取消',
      onOk: async () => {
        setBusy(true);
        try {
          await createRevision(id, detail.summary.revision, '修改已完成实验');
          void message.success('已创建修订草稿，原完成版本未改变');
          await load();
        } finally {
          setBusy(false);
        }
      },
    });
  };

  const exportRecord = async () => {
    if (!detail) return;
    setExporting(true);
    try {
      const blob = await exportExperiment(id);
      const extension = documentFormatOf(detail.editModel) === 'word' ? 'docx' : 'xlsx';
      // The export endpoint exports the current experiment revision, while
      // the version list only contains published versions. Use the current
      // summary version so draft revisions are named correctly as well.
      const versionSuffix = `-V${detail.summary.versionNo || 1}`;
      downloadBlob(
        blob,
        `${detail.summary.title || detail.summary.experimentNo}${versionSuffix}.${extension}`,
      );
      void message.success('实验已导出');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验导出失败');
    } finally {
      setExporting(false);
    }
  };

  const openTemplateDraft = async () => {
    if (!detail || view === 'versions' || statusIsVoided(detail.summary.status)) return;
    templateDraftNameRef.current = `${detail.summary.title}模板`.slice(0, 160);
    setTemplateDraftOpen(true);
    try {
      setTemplateCategories(await templateApi.listCategories());
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板分类加载失败');
    }
  };

  useEffect(() => {
    if (!templateDraftOpen) return;
    templateDraftForm.setFieldsValue({ name: templateDraftNameRef.current });
  }, [templateDraftForm, templateDraftOpen]);

  const createTemplateDraft = async () => {
    if (!detail || templateDraftSaving) return;
    let values: { name: string; categoryId: string };
    try {
      values = await templateDraftForm.validateFields();
    } catch {
      return;
    }
    const proceed = async () => {
      setTemplateDraftSaving(true);
      try {
        const draft = await templateApi.createFromExperiment(id, {
          name: values.name.trim(),
          categoryId: values.categoryId,
        });
        setTemplateDraftOpen(false);
        void message.success('模板草稿已生成');
        navigate(`/templates/${draft.versionId}/workspace`);
      } catch (error) {
        void message.error(error instanceof Error ? error.message : '生成模板草稿失败');
      } finally {
        setTemplateDraftSaving(false);
      }
    };
    if (saveState !== 'SAVED') {
      modal.confirm({
        title: '先保存当前实验？',
        content: '当前实验内容尚未保存。确认后系统会先保存，保存成功后再生成模板草稿。',
        okText: '保存并生成',
        cancelText: '取消',
        onOk: async () => {
          setTemplateDraftSaving(true);
          try {
            if (await save()) await proceed();
          } finally {
            setTemplateDraftSaving(false);
          }
        },
      });
      return;
    }
    await proceed();
  };

  const changeView = (key: WorkspaceView) => {
    if (saveState !== 'SAVED' && key === 'versions') {
      void message.warning('当前内容尚未保存，请先保存后再查看版本记录');
      return;
    }
    setSelectedVersion(undefined);
    setView(key);
  };

  if (loadError) {
    return (
      <Result
        status="error"
        title="实验本加载失败"
        subTitle={loadError}
        extra={<Button onClick={goBack}>返回</Button>}
      />
    );
  }
  if (!detail || !snapshot) return <Skeleton active paragraph={{ rows: 12 }} />;

  const status = detail.summary.status;
  // 与数据中心一致：预览走预览接口，下载走原文件地址（浏览器原生下载）。
  const sourceFile: FilePreviewDescriptor | undefined = detail.sourceFileId
    ? {
        fileName: detail.editModel.sourceFileName || detail.summary.title || '实验原文',
        load: () => fetchFilePreviewBlob(detail.sourceFileId!),
        downloadUrl: fileContentUrl(detail.sourceFileId),
      }
    : undefined;
  const sourceIsImage = /\.(png|jpe?g|gif|webp|bmp|tiff?)$/i.test(sourceFile?.fileName ?? '');
  const readonly = statusLocked;
  const editable = !readonly && isDesktop && view === 'edit';
  // 无模板导入（CONTENT_ONLY）不涉及识别区域与字段绑定，隐藏 Excel 识别审核面板。
  const showExcelRecognition = editModel.importProcessingMode !== 'CONTENT_ONLY'
    && !templateReview && documentFormatOf(editModel, snapshot) === 'excel'
    && Array.isArray(editModel.recognizedFields);
  const templateDataConfirmed = Boolean(templateReview?.confirmed && saveState === 'SAVED'
    && editModel.templateDataReview?.confirmed
    && editModel.templateDataReview.confirmedRevision === detail.summary.revision);
  const importedData = detail.summary.sourceType === 'EXCEL_IMPORT'
    || (detail.summary.sourceType === 'OCR_IMPORT' && Boolean(editModel.sourceImportJobId));
  const importedFromTemplate = importedData && Boolean(editModel.sourceImportJobId) && Boolean(templateReview);
  const importDataConfirmed = Boolean(importedData && saveState === 'SAVED'
    && editModel.importDataReview?.confirmed
    && editModel.importDataReview.confirmedRevision === detail.summary.revision);
  const pendingFieldCount = editModel.recognizedFields?.filter((field) => !field.confirmed).length ?? 0;
  const pendingRegionCount = editModel.recognizedRegions?.filter((region) => !region.confirmed).length ?? 0;
  const recognitionFailed = editModel.recognitionSummary?.modelStatus === 'FAILED'
    && !editModel.recognizedFields?.length;
  const needsRecognitionReview = recognitionFailed || !(editModel.recognizedFields?.length)
    || pendingFieldCount > 0 || pendingRegionCount > 0;
  const publishAction: PublishAction | undefined =
    status === 'DRAFT' || status === 'PENDING'
      ? 'submit-review'
      : status === 'IN_PROGRESS' || status === 'RETURNED'
        ? 'submit-review'
        : status === 'PENDING_REVIEW'
          ? 'approve'
          : undefined;
  return (
    <section
      className="workspace-shell template-business-workspace experiment-workspace-shell"
      aria-label={`${detail.summary.title}实验工作台`}
    >
      <header className="workspace-header">
        <div className="workspace-identity">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={goBack}>
            {onBack ? '返回导入数据' : '返回'}
          </Button>
          <span className="workspace-title-block">
            <Typography.Text type="secondary" className="workspace-breadcrumb">
              实验中心 / 实验记录
            </Typography.Text>
            <Typography.Text strong>{detail.summary.title}</Typography.Text>
          </span>
          <span className="workspace-meta-item">V{detail.summary.versionNo || 1}</span>
          <span className="workspace-meta-item">负责人：{detail.summary.ownerName || '未设置'} · 日期：{detail.summary.experimentDate || '未设置'}</span>
          {showExcelRecognition ? <span className="workspace-meta-item"><FileExcelOutlined /> Excel</span> : null}
          <Tag color={status === 'COMPLETED' ? 'green' : status === 'VOIDED' ? 'default' : 'blue'}>
            {STATUS_TEXT[status] ?? status}
          </Tag>
          {showExcelRecognition ? <Tag color="blue">审核：{pendingFieldCount || pendingRegionCount ? '待确认' : '已确认'}</Tag> : null}
          {templateReview && !importedData ? <Tag color={templateDataConfirmed ? 'success' : 'warning'}>数据：{templateDataConfirmed ? '已确认' : '待确认'}</Tag> : null}
          {importedData ? <Tag color={importDataConfirmed ? 'success' : 'warning'}>数据：{importDataConfirmed ? '已确认' : '待确认'}</Tag> : null}
        </div>
        <Space wrap>
          <SaveStateBadge state={saveState} />
          <Button
            className="workspace-export-button"
            icon={<DownloadOutlined />}
            loading={exporting}
            onClick={() => void exportRecord()}
          >
            导出
          </Button>
          {sourceFile ? (
            <Button
              type="primary"
              ghost
              icon={<EyeOutlined />}
              onClick={() => setPreviewSource(sourceFile)}
            >
              预览原文件
            </Button>
          ) : null}
          {sourceFile ? (
            <Button
              icon={<DownloadOutlined />}
              onClick={() => void downloadPreviewFile(sourceFile)
                .catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}
            >
              {sourceIsImage ? '原图下载' : '原文下载'}
            </Button>
          ) : null}
          {status === 'COMPLETED' && <Can permission="experiment.update">
            <Button loading={busy} onClick={reviseCompletedExperiment}>创建修订</Button>
          </Can>}
          {editable && <Button onClick={openMetadataEditor}>编辑基本信息</Button>}
          <Can permission="template.create">
            <Button
              disabled={view === 'versions' || status === 'VOIDED'}
              onClick={() => void openTemplateDraft()}
            >
              另存为模板
            </Button>
          </Can>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            disabled={!editable}
            loading={saveState === 'SAVING'}
            onClick={() => void save()}
          >
            保存草稿
          </Button>
          {publishAction === 'submit-review' ? <Can permission="experiment.submit">
            <Button type="primary" className="workspace-publish-button" loading={busy}
              disabled={!editable || saveState !== 'SAVED'} onClick={() => publish('submit-review')}>提交审核</Button>
          </Can> : null}
          {publishAction === 'approve' ? <>
            <Can permission="experiment.review">
              <Button loading={busy} onClick={() => { setReturnReason(''); setReturnOpen(true); }}>退回修改</Button>
            </Can>
            <Can permission="experiment.approve">
              <Button type="primary" className="workspace-publish-button" loading={busy}
                disabled={saveState !== 'SAVED'} onClick={() => publish('approve')}>审核通过并发布</Button>
            </Can>
          </> : null}
        </Space>
      </header>

      <FilePreviewModal
        open={Boolean(previewSource)}
        file={previewSource}
        onClose={() => setPreviewSource(undefined)}
      />

      <Modal
        title="另存为模板"
        open={templateDraftOpen}
        okText="生成模板草稿"
        cancelText="取消"
        confirmLoading={templateDraftSaving}
        onCancel={() => setTemplateDraftOpen(false)}
        onOk={() => void createTemplateDraft()}
        destroyOnHidden
      >
        <Form form={templateDraftForm} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label="模板名称"
            rules={[{ required: true, whitespace: true, message: '请输入模板名称' }]}
          >
            <Input maxLength={160} showCount placeholder="请输入模板名称" />
          </Form.Item>
          <Form.Item
            name="categoryId"
            label="模板分类"
            rules={[{ required: true, message: '请选择模板分类' }]}
          >
            <Select
              placeholder="请选择现有模板分类"
              options={templateCategories.map((category) => ({ value: category.id, label: category.name }))}
              notFoundContent="暂无可用模板分类"
            />
          </Form.Item>
          <Alert
            type="warning"
            showIcon
            message="将复制当前实验的版式和正文。系统仅不带入实验编号、日期、负责人及项目关联等平台信息；文档中的实验值会原样保留，请在模板编辑页清理后再提交审核。"
          />
        </Form>
      </Modal>

      <Modal
        title="编辑实验基本信息"
        open={metadataOpen}
        okText="保存"
        cancelText="取消"
        confirmLoading={metadataSaving}
        onCancel={() => setMetadataOpen(false)}
        onOk={() => void saveMetadata()}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          无模板导入默认使用当前登录人和上传当天，可在这里确认或调整。
        </Typography.Paragraph>
        <Form form={metadataForm} layout="vertical">
          <Form.Item
            name="ownerName"
            label="负责人"
            rules={[{ required: true, whitespace: true, message: '请输入负责人' }]}
          >
            <Input maxLength={100} placeholder="请输入负责人" />
          </Form.Item>
          <Form.Item
            name="experimentDate"
            label="实验日期"
            rules={[{ required: true, message: '请选择实验日期' }]}
          >
            <Input type="date" />
          </Form.Item>
        </Form>
      </Modal>

      <nav className="workspace-view-tabs" aria-label="实验页面">
        <Tabs
          activeKey={view}
          onChange={(key) => changeView(key as WorkspaceView)}
          items={[
            {
              key: 'preview',
              label: (
                <>
                  <EyeOutlined /> 预览
                </>
              ),
            },
            { key: 'edit', label: '编辑', disabled: readonly },
            { key: 'versions', label: '版本记录' },
          ]}
        />
      </nav>

      {view === 'versions' ? (
        selectedVersion ? (
          <ExperimentHistoricalVersion
            version={selectedVersion}
            onBack={() => setSelectedVersion(undefined)}
          />
        ) : (
          <VersionView versions={versions} onChangeVersion={setSelectedVersion} />
        )
      ) : (
        <div className="workspace-main-stage">
          {showExcelRecognition ? <div className="experiment-recognition-status recognition-status-bar" data-tone={needsRecognitionReview ? 'pending' : 'complete'} role="status">
            <div className="recognition-status-content"><span className="recognition-status-icon">{needsRecognitionReview ? <RobotOutlined /> : <CheckCircleOutlined />}</span><strong>{recognitionBusy ? '正在重新识别实验工作簿' : recognitionFailed ? '智能识别未完成' : needsRecognitionReview ? 'AI 识别待确认' : 'AI 识别已完成'}</strong><span>{recognitionBusy ? `识别进度 ${recognitionProgress ?? 0}%` : needsRecognitionReview ? `区域 ${editModel.recognizedRegions?.length ?? 0} ｜ 字段 ${editModel.recognizedFields?.length ?? 0} ｜ 待确认字段 ${pendingFieldCount} ｜ 结构冲突 0` : `区域 ${editModel.recognizedRegions?.length ?? 0} ｜ 字段 ${editModel.recognizedFields?.length ?? 0}`}</span></div>
            {recognitionBusy ? <Progress className="recognition-inline-progress" percent={recognitionProgress ?? 0} showInfo={false} /> : null}
            <Space size={6}>
              <Button size="small" onClick={() => { setImportPanelTab('recognition'); setReviewTrigger((current) => current + 1); }}>开始确认</Button>
              <Button size="small" icon={<ThunderboltOutlined />} disabled={!editable || recognitionBusy || pendingFieldCount === 0} onClick={confirmAllRecognizedFields}>一键确认全部字段</Button>
              <Button size="small" icon={<ReloadOutlined />} disabled={!editable} loading={recognitionBusy} onClick={() => void restartRecognition().catch((error) => void message.error(error instanceof Error ? error.message : '重新识别失败'))}>重新识别</Button>
            </Space>
          </div> : null}
          <div className={`template-workspace-grid prototype-workspace-grid ${showExcelRecognition || templateReview || importedData ? 'has-field-bindings' : ''}`}>
            <main className={`workspace-canvas ${view === 'preview' ? 'is-preview' : ''}`}>
              <Suspense
                fallback={
                  <Spin indicator={<LoadingOutlined spin />} tip="正在加载实验记录" fullscreen />
                }
              >
                {documentFormatOf(editModel, snapshot) === 'word' ? (
                  <div className={view === 'preview' ? 'document-readonly' : undefined}>
                    <DocsEditor
                      ref={editorRef}
                      snapshot={snapshot}
                      editable={editable}
                      onDirty={() => markDirty()}
                    />
                  </div>
                ) : (
                  <div className={view === 'preview' ? 'document-readonly' : undefined}>
                    <SheetsEditor
                      ref={editorRef}
                      snapshot={snapshot}
                      bindings={recognizedBindings}
                      editable={editable}
                      onEditorValue={handleRecognizedValue}
                      onSelectionChange={handleCoordinateSelection}
                      onDirty={() => markDirty()}
                    />
                  </div>
                )}
              </Suspense>
            </main>
            {importedData || showExcelRecognition ? <div className="experiment-import-review-column">
              {importedData && showExcelRecognition ? <div className="experiment-import-review-tabs" role="tablist" aria-label="导入数据面板">
                <button type="button" role="tab" aria-selected={importPanelTab === 'data'}
                  onClick={() => setImportPanelTab('data')}>数据确认</button>
                <button type="button" role="tab" aria-selected={importPanelTab === 'recognition'}
                  onClick={() => setImportPanelTab('recognition')}>识别字段</button>
              </div> : null}
              {importedData && !importedFromTemplate && (importPanelTab === 'data' || !showExcelRecognition) ? <ExperimentImportDataPanel
                snapshot={editModel.documentSnapshot ?? snapshot}
                confirmed={importDataConfirmed}
                editable={editable}
                busy={busy}
                onConfirm={() => void confirmTemplateData()}
              /> : null}
              {importedFromTemplate && templateReview ? <ExperimentTemplateDataPanel
                review={templateReview}
                values={editModel.dynamicValues ?? {}}
                importedSnapshot={editModel.documentSnapshot ?? snapshot}
                editable={editable}
                confirmed={importDataConfirmed}
                busy={busy}
                onChange={changeTemplateValue}
                onFocus={(bindingId) => {
                  const binding = recognizedBindings.find((item) => item.bindingId === bindingId);
                  if (binding) editorRef.current?.focusBinding(binding);
                }}
                onConfirm={() => void confirmTemplateData()}
              /> : null}
            {showExcelRecognition && (!importedData || importPanelTab === 'recognition') ? <ExperimentFieldReviewPanel
              fields={editModel.recognizedFields ?? []}
              regions={editModel.recognizedRegions ?? []}
              editable={editable}
              onUpdateField={updateRecognizedField}
              onConfirmRegion={updateRecognizedRegion}
              onFocusField={(fieldId) => {
                const binding = recognizedBindings.find((item) => item.fieldId === fieldId);
                if (binding) editorRef.current?.focusBinding(binding);
              }}
              onFocusRegion={(regionId) => {
                const region = editModel.recognizedRegions?.find((item) => item.id === regionId);
                if (!region) return;
                editorRef.current?.focusBinding({
                  bindingId: region.bindingId || region.id,
                  dataPath: region.dataPath,
                  role: 'REPEAT_REGION',
                  mappingKind: 'REPEAT_REGION',
                  locatorType: region.locatorType || 'TABLE_REGION',
                  locator: region.locator,
                  syncDirection: 'DATA_TO_EDITOR',
                  primaryBinding: true,
                  bindingStatus: 'VALID',
                });
              }}
              onAddField={(regionId) => {
                const fieldId = crypto.randomUUID();
                setEditModel((current) => ({
                  ...current,
                  recognizedFields: [...(current.recognizedFields ?? []), {
                    id: fieldId, bindingId: fieldId, parentBindingId: '',
                    name: '新字段', groupName: '基本信息', regionId: regionId ?? '',
                    fieldCode: '', dataPath: '',
                    mappingKind: regionId ? 'REPEAT_FIELD' : 'SCALAR',
                    repeatAxis: 'UNKNOWN', locatorType: 'CELL_RANGE',
                    standardMatchStatus: 'UNMATCHED', confirmed: false,
                    confidence: 1, locator: {},
                  }],
                }));
                markDirty();
              }}
              onDeleteField={(fieldId) => {
                setEditModel((current) => ({
                  ...current,
                  recognizedFields: current.recognizedFields?.filter((field) => field.id !== fieldId),
                }));
                markDirty();
              }}
              picking={coordinatePicking}
              onPickCoordinate={(fieldId, target) => setCoordinatePicking({ fieldId, target })}
              reviewTrigger={reviewTrigger}
            /> : null}
            </div> : null}
            {templateReview && !importedFromTemplate ? <ExperimentTemplateDataPanel
              review={templateReview}
              values={editModel.dynamicValues ?? {}}
              editable={editable}
              confirmed={templateDataConfirmed}
              busy={busy}
              onChange={changeTemplateValue}
              onFocus={(bindingId) => {
                const binding = recognizedBindings.find((item) => item.bindingId === bindingId);
                if (binding) editorRef.current?.focusBinding(binding);
              }}
              onConfirm={() => void confirmTemplateData()}
            /> : null}
          </div>
        </div>
      )}

      <Modal
        title="审核信息"
        open={reviewOpen}
        okText="保存并提交审核"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => setReviewOpen(false)}
        onOk={() => void submitReviewWithInformation()}
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          这两项属于实验审核信息，不会改变已冻结的实验模板版式。
        </Typography.Paragraph>
        <Form form={reviewForm} layout="vertical" preserve={false}>
          <Form.Item
            name="purpose"
            label="实验目的"
            rules={[{ required: true, whitespace: true, message: '请填写实验目的' }]}
          >
            <Input.TextArea rows={3} placeholder="说明本次实验需要验证的目标" />
          </Form.Item>
          <Form.Item
            name="mainConclusion"
            label="主要结论"
            rules={[{ required: true, whitespace: true, message: '请填写主要结论' }]}
          >
            <Input.TextArea rows={4} placeholder="填写真实实验完成后得到的主要结论" />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="退回修改"
        open={returnOpen}
        okText="确认退回"
        cancelText="取消"
        confirmLoading={busy}
        onCancel={() => setReturnOpen(false)}
        onOk={() => void returnForChanges()}
        destroyOnHidden
      >
        <Form layout="vertical">
          <Form.Item label="退回原因" required>
            <Input.TextArea rows={4} maxLength={500} showCount value={returnReason}
              onChange={(event) => setReturnReason(event.target.value)}
              placeholder="请说明需要补充或修改的内容" />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}

function statusIsVoided(status: string) {
  return status === 'VOIDED';
}

function VersionView({
  versions,
  onChangeVersion,
}: {
  versions: ExperimentVersion[];
  onChangeVersion: (version: ExperimentVersion) => void;
}) {
  return (
    <main className="workspace-canvas project-versions-panel">
      <div className="project-versions-content">
        <VersionHistoryPanel
          versions={versions}
          onSelect={onChangeVersion}
          description="每次发布都会保留完整数据快照，可追溯发布人和发布时间。"
          getLabel={(item) => STATUS_TEXT[item.status] ?? item.status}
        />
      </div>
    </main>
  );
}

function ExperimentHistoricalVersion({
  version,
  onBack,
}: {
  version: ExperimentVersion;
  onBack: () => void;
}) {
  const historicalSnapshot = buildExperimentSnapshot(
    version.editModel ?? {},
    version.id,
    version.templateSnapshot,
  );
  const word = documentFormatOf(version.editModel ?? {}, historicalSnapshot) === 'word';
  return (
    <div className="workspace-main-stage">
      <div className="project-version-preview">
        <header className="project-versions-header">
          <Button onClick={onBack}>返回版本列表</Button>
          <Typography.Title level={4}>版本 V{version.versionNo}</Typography.Title>
          <Typography.Text type="secondary">
            {formatTime(version.createdAt)} · {version.createdBy ?? '未知'}
          </Typography.Text>
        </header>
        <main className="workspace-canvas document-readonly">
          <Suspense fallback={<Spin indicator={<LoadingOutlined spin />} />}>
            {word ? (
              <DocsEditor
                snapshot={historicalSnapshot}
                editable={false}
                onDirty={() => undefined}
              />
            ) : (
              <SheetsEditor
                snapshot={historicalSnapshot}
                bindings={[]}
                editable={false}
                onEditorValue={() => undefined}
                onDirty={() => undefined}
              />
            )}
          </Suspense>
        </main>
      </div>
    </div>
  );
}

function useDesktopEditing() {
  // The experiment editor remains usable in the compact single-column
  // workspace.  The action guard must follow the actual editor breakpoint;
  // using 1100px disabled save/review actions in the 1008px browser viewport
  // even though Univer was fully loaded and editable.
  const [matches, setMatches] = useState(() => window.matchMedia('(min-width: 768px)').matches);
  useEffect(() => {
    const media = window.matchMedia('(min-width: 768px)');
    const update = () => setMatches(media.matches);
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  return matches;
}
