import { FormModal } from '@/components/form-dialog/FormModal';
import { useLatestRequest } from '@/services/query/use-request-guard';
import { usePolling } from '@/hooks/use-polling';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Space,
  Spin,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { usePermission } from '@/components/auth/usePermission';
import { DataPublicationPanel } from '@/features/data-workbench/DataPublicationPanel';
import { confirmationBlockReason, isFieldDefinitionIssue, isImportBlocker, requiresTemplateChange } from '@/services/data/import-guidance';
import type { DataPublication } from '@/services/data/data-api';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import {
  DataFieldDataBrowser,
  DataFieldStructureBrowser,
  DataWorkbenchShell,
  DataWorkbookCanvas,
} from '@/features/data-workbench/DataWorkbench';
import { DataImportQualityNotice } from '@/features/data-workbench/DataImportQualityNotice';
import { OriginalDocumentCanvas } from '@/features/data-workbench/OriginalDocumentCanvas';
import type { EditorCellChange, EditorHandle, EditorSelection } from '@/features/template-workspace/types';
import { importFieldForCell, importFieldInSheetRow } from './data-import-workbench-utils';
import {
  dataApi,
  type DataFieldValueView,
  type DataJob,
  type DataWorkbookFieldDefinition,
  type DataPreview,
  type DataWorkbookSnapshot,
  type ExperimentAssemblyPlan,
  type ExperimentAssemblyLink,
} from '@/services/data/data-api';
import { projectResourceApi, type ProjectRelationTarget, type RelatedProjectView } from '@/services/project/project-resource-api';
import { dataParseProgress } from '@/services/data/data-progress';
import { InstanceFieldEditor } from '@/features/template-workspace/InstanceFieldEditor';
import { type WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { presentWorkbookFields } from '@/features/data-workbench/field-presentation';
import { buildImportWorkbookView } from '@/features/data-workbench/import-workbook-loader';
import { workbookCellValue } from '@/features/data-workbench/cell-format';
import { AutoSaveFieldInput } from '@/features/data-workbench/AutoSaveFieldInput';
import { DataReviewFooter } from '@/features/data-workbench/DataReviewFooter';
import { FieldDefinitionReview } from '@/features/data-workbench/FieldDefinitionReview';
import {
  deduplicateFieldDefinitionIssues,
  definitionReviewItems,
  fieldDefinitionIssueLocation,
  issueRepresentedByDefinition,
  type FieldDefinitionReviewItem,
} from '@/features/data-workbench/field-definition-review';
import { updateWorkbookField } from '@/features/template-workspace/workbook-instance';
import { markPerformance, startOperationMetric } from '@/services/performance/metrics';

type PanelTab = 'data' | 'structure' | 'properties';

const jobStatusLabels: Record<string, string> = {
  PARSING: '解析中', VALIDATING: '校验中', WAITING_MAPPING: '待核对数据', WAITING_CONFIRM: '待核对数据',
  COMMITTING: '提交中', COMPLETED: '导入完成', FAILED: '处理失败',
};
const compatibilityLabels: Record<string, string> = {
  EXACT: '模板匹配', COMPATIBLE: '模板可用', REVIEW_REQUIRED: '需要确认', INCOMPATIBLE: '模板不匹配', LEGACY: '已兼容',
};

export function DataImportJobPage({
  workspaceMode = 'DATA',
  onExperimentReady,
  onExperimentLinksReady,
}: {
  workspaceMode?: 'DATA' | 'EXPERIMENT';
  onExperimentReady?: (experimentId: string) => void;
  onExperimentLinksReady?: (links: ExperimentAssemblyLink[]) => void;
}) {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const editorRef = useRef<EditorHandle>(null);
  const [preview, setPreview] = useState<DataPreview>();
  const [workbook, setWorkbook] = useState<DataWorkbookSnapshot>();
  const [loading, setLoading] = useState(true);
  const [workbookLoading, setWorkbookLoading] = useState(false);
  const [workbookError, setWorkbookError] = useState<string>();
  const [loadError, setLoadError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [savingCell, setSavingCell] = useState<string>();
  const [documentCorrections, setDocumentCorrections] = useState<Record<string, string>>({});
  const correctionRevision = useRef<string | undefined>(undefined);
  const [activeTab, setActiveTab] = useState<PanelTab>('data');
  const [selectedFieldKey, setSelectedFieldKey] = useState<string>();
  const [selectedBindingId, setSelectedBindingId] = useState<string>();
  const [selectedCell, setSelectedCell] = useState<EditorSelection>();
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();
  const [categoryName, setCategoryName] = useState<string>();
  const [relatedProjects, setRelatedProjects] = useState<RelatedProjectView[]>([]);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [relationOpen, setRelationOpen] = useState(false);
  const [assemblyPlan, setAssemblyPlan] = useState<ExperimentAssemblyPlan>();
  const [assemblyLinks, setAssemblyLinks] = useState<ExperimentAssemblyLink[]>([]);
  const [preparationStatus, setPreparationStatus] = useState<string>();
  const [publication, setPublication] = useState<DataPublication>();
  const [workspaceView, setWorkspaceView] = useState('edit');
  const [instance, setInstance] = useState<WorkbookTemplateInstance>();
  const [structureDirty, setStructureDirty] = useState(false);
  const structureDirtyRef = useRef(false);
  const editGeneration = useRef(0);
  const structureOperations = useRef<unknown[]>([]);
  const editedViewAbort = useRef<AbortController>();
  const editedViewFrame = useRef<number>();
  useEffect(() => () => { editedViewAbort.current?.abort(); if (editedViewFrame.current) cancelAnimationFrame(editedViewFrame.current); }, [id, workspaceMode]);
  const markStructureDirty = () => {
    if (!loads.isActive()) return;
    editGeneration.current++;
    structureDirtyRef.current = true; setStructureDirty(true);
    editedViewAbort.current?.abort();
    if (editedViewFrame.current) cancelAnimationFrame(editedViewFrame.current);
    const generation = editGeneration.current;
    editedViewFrame.current = window.requestAnimationFrame(() => {
      if (!loads.isActive()) return;
      const live = editorRef.current?.getSnapshot(), current = editorRef.current?.getTemplateInstance?.();
      if (live && current) {
        const abort = new AbortController(); editedViewAbort.current = abort;
        void buildImportWorkbookView(live, current, abort.signal).then(view => {
          if (!abort.signal.aborted && loads.isActive() && generation === editGeneration.current)
            setWorkbook(previous => previous ? { ...previous, fields: view.fields, records: view.records, regions: view.regions, fieldDefinitions: view.fieldDefinitions } : previous);
        }).catch(error => { if (!abort.signal.aborted && loads.isActive()) setLoadError(error instanceof Error ? error.message : '字段数据加载失败'); });
      }
    });
  };
  const [versionContainer, setVersionContainer] = useState<HTMLDivElement | null>(null);
  const hasUpdatePermission = usePermission(workspaceMode === 'EXPERIMENT' ? 'eln.upload.create' : 'data.upload.create');
  const canAssign = usePermission('project.assign');
  const canCreate = usePermission(workspaceMode === 'EXPERIMENT' ? 'eln.upload.create' : 'data.upload.create');
  const canUpdate = (hasUpdatePermission || (workspaceMode === 'EXPERIMENT' && canCreate))
    && !['PENDING_REVIEW', 'APPROVED', 'PUBLISHED', 'DISABLED'].includes(publication?.status || '');
  const canDownload = usePermission(workspaceMode === 'EXPERIMENT' ? 'eln.upload.download' : 'data.upload.download');

  const loads = useLatestRequest(`${workspaceMode}:${id}`);
  const writeQueue = useRef<Promise<unknown>>(Promise.resolve());
  const draftWrite = useRef<Promise<boolean>>();
  const queueWrite = <T,>(action: (revision: string) => Promise<T>, jobOf: (result: T) => DataJob): Promise<T> => {
    const pending = writeQueue.current.catch(() => undefined).then(async () => {
      if (!loads.isActive()) throw new DOMException('页面已切换', 'AbortError');
      loads.invalidate();
      const result = await action(correctionRevision.current ?? preview!.job.revision);
      if (loads.isActive()) {
        const job = jobOf(result);
        correctionRevision.current = job.revision;
        setPreview(current => current ? { ...current, job } : current);
      }
      return result;
    });
    writeQueue.current = pending;
    return pending;
  };
  const workbookRef = useRef(workbook);
  workbookRef.current = workbook;
  const assemblyLoads = useLatestRequest(`${workspaceMode}:${id}`);
  useEffect(() => {
    setPreview(undefined); setWorkbook(undefined); setInstance(undefined); setPublication(undefined);
    setAssemblyPlan(undefined); setAssemblyLinks([]); setPreparationStatus(undefined); setLoading(true); structureDirtyRef.current = false; setStructureDirty(false);
    editGeneration.current = 0; structureOperations.current = []; correctionRevision.current = undefined;
    setDocumentCorrections({}); setSaving(false); setSavingCell(undefined);
    writeQueue.current = Promise.resolve(); draftWrite.current = undefined;
    setWorkbookLoading(false); setWorkbookError(undefined);
    setSelectedFieldKey(undefined); setSelectedBindingId(undefined);
  }, [id, workspaceMode]);
  const loadAbort = useRef<AbortController>();
  useEffect(() => () => loadAbort.current?.abort(), [id, workspaceMode]);
  const scopeLoads = useRef(new Map<string, AbortController>());
  const loadedScopes = useRef(new Set<string>());
  const currentScope = useRef<{ sheetId?: string; recordId: string }>();
  const scopeVersion = useRef(preview?.job.revision);
  scopeVersion.current = preview?.job.revision;
  useEffect(() => () => { for (const controller of scopeLoads.current.values()) controller.abort(); scopeLoads.current.clear(); loadedScopes.current.clear(); }, [id, workspaceMode]);
  const loadFieldScope = useCallback(async (scope: { sheetId?: string; recordId: string }) => {
    currentScope.current = scope;
    const revision = scopeVersion.current;
    if (!revision || structureDirtyRef.current || workbookRef.current?.fieldResultsSource === 'SNAPSHOT') return;
    const key = `${revision}:${scope.sheetId ?? ''}:${scope.recordId}`;
    if (loadedScopes.current.has(key) || scopeLoads.current.has(key)) return;
    const controller = new AbortController();
    scopeLoads.current.set(key, controller);
    try {
      const result = await dataApi.importFieldResults(id, { revision, ...scope }, workspaceMode === 'EXPERIMENT', controller.signal);
      if (controller.signal.aborted || revision !== scopeVersion.current || structureDirtyRef.current) return;
      loadedScopes.current.add(key);
      setWorkbook(current => {
        if (!current) return current;
        const keys = new Set(result.fields.map(next => `${next.recordId}|${next.bindingId}|${next.valuePath}`));
        const previous = current.fields.filter(field => !keys.has(`${field.recordId}|${field.bindingId}|${field.valuePath}`));
        return presentWorkbookFields({ ...current, fields: [...previous, ...result.fields] }, current.templateInstance);
      });
    } catch (error) {
      if (!controller.signal.aborted && revision === scopeVersion.current) setLoadError(error instanceof Error ? error.message : '字段数据加载失败');
    } finally { scopeLoads.current.delete(key); }
  }, [id, workspaceMode]);
  useEffect(() => {
    if (preview?.job.revision && currentScope.current) void loadFieldScope(currentScope.current);
  }, [preview?.job.revision, loadFieldScope]);
  const load = useCallback(async (options: { silent?: boolean; preserveSnapshot?: boolean; skipWorkbook?: boolean } = {}) => {
    if (!loads.isActive()) return;
    if (!options.skipWorkbook) markPerformance('import-workbook-load-start');
    const isCurrent = loads.begin();
    loadAbort.current?.abort();
    const abort = new AbortController();
    loadAbort.current = abort;
    if (!options.silent) {
      setLoading(true);
      setLoadError(undefined);
    }
    try {
      // Start both transfers together; metadata must never block the workbook.
      const workbookRequest = options.skipWorkbook ? undefined
        : dataApi.getImportWorkbookSnapshot(id, workspaceMode === 'EXPERIMENT', abort.signal)
          .then(value => ({ value }), error => ({ error }));
      const nextPreview = await dataApi.preview(id, workspaceMode === 'EXPERIMENT', 'summary', abort.signal);
      if (!isCurrent()) return;
      const stillProcessing = ['CREATED', 'QUEUED', 'PARSING', 'VALIDATING', 'COMMITTING'].includes(nextPreview.job.status);
      if (stillProcessing) abort.abort();
      setPreview(nextPreview);
      setLoadError(undefined);
      setLoading(false);
      if (options.skipWorkbook && (stillProcessing || workbookRef.current)) return;
      setWorkbookError(undefined);
      setWorkbookLoading(!stillProcessing);
      const result = stillProcessing ? undefined : await (workbookRequest
        ?? dataApi.getImportWorkbookSnapshot(id, workspaceMode === 'EXPERIMENT', abort.signal)
          .then(value => ({ value }), error => ({ error })));
      const nextWorkbook = result && 'value' in result ? result.value : undefined;
      if (result && 'error' in result && isCurrent() && !abort.signal.aborted && !['DOC', 'DOCX', 'IMAGE'].includes(nextPreview.job.sourceFormat)) {
        if (import.meta.env.DEV) console.warn('[aird:workbook-load]', result.error);
        setWorkbookError(result.error instanceof Error ? result.error.message : '工作簿加载失败');
      }
      if (!isCurrent()) return;
      setPreview(nextPreview);
      setLoadError(undefined);
      void projectResourceApi.links('DATA_IMPORT_JOB', id).then((relations) => {
        if (!isCurrent()) return;
        setRelatedProjects(relations);
        setProjectRelations(relations.map((item) => ({ projectId: item.projectId, stageId: item.stageId, taskId: item.taskId })));
      }).catch(() => { if (isCurrent()) { setRelatedProjects([]); setProjectRelations([]); } });
      if (nextWorkbook) {
        const nextInstance = nextWorkbook.templateInstance;
        const displayedWorkbook = nextWorkbook;
        if (!structureDirtyRef.current && !options.preserveSnapshot) setInstance(nextInstance);
        setWorkbook((current) => (options.preserveSnapshot || structureDirtyRef.current) && current
          ? structureDirtyRef.current ? current : { ...displayedWorkbook, snapshot: current.snapshot }
          : displayedWorkbook);
        setSelectedFieldKey((current) => current || fieldKey(nextWorkbook.fields[0]));
        const firstField = nextWorkbook.fields[0];
        const firstDefinition = firstField ? definitionForValue(nextWorkbook, firstField)
          : nextWorkbook.fieldDefinitions?.[0];
        setSelectedBindingId((current) => current || firstDefinition?.bindingId || firstField?.bindingId);
      }
    } catch (error) {
      if (isCurrent()) setLoadError(error instanceof Error ? error.message : '导入任务加载失败');
    } finally {
      if (isCurrent()) { setLoading(false); setWorkbookLoading(false); }
    }
  }, [id, loads, workspaceMode]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!preview?.job.categoryId) { setCategoryName(undefined); return; }
    void dataApi.listCategories().then((items) => setCategoryName(items.find((item) => item.id === preview.job.categoryId)?.name)).catch(() => setCategoryName(undefined));
  }, [preview?.job.categoryId]);

  const loadAssembly = useCallback(async () => {
    if (preview?.job.status !== 'COMPLETED' || (preview.job.importContractVersion ?? preview.templateContract?.importContractVersion) !== 9) return;
    const isCurrent = assemblyLoads.begin();
    try {
      const sync = await dataApi.experimentSyncStatus(id, 'summary');
      if (!isCurrent()) return;
      setAssemblyPlan(sync.plan ?? undefined); setAssemblyLinks(sync.links); setPreparationStatus(sync.preparationStatus);
    } catch {
    }
  }, [id, assemblyLoads, preview?.job.status, preview?.job.importContractVersion, preview?.templateContract?.importContractVersion]);
  useEffect(() => { void loadAssembly(); }, [loadAssembly]);
  useEffect(() => {
    const refresh = () => { if (document.visibilityState !== 'hidden') void loadAssembly(); };
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => { window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh); };
  }, [loadAssembly]);
  useEffect(() => {
    if (workspaceMode !== 'EXPERIMENT') return;
    const linked = assemblyLinks.filter((item) => Boolean(item.experimentId));
    const onlyLinked = linked.length === 1 ? linked[0] : undefined;
    onExperimentLinksReady?.(linked);
    const activeCount = assemblyPlan?.candidates.length ?? assemblyLinks.filter(item => item.status !== 'SKIPPED').length;
    if (activeCount === 1 && onlyLinked?.experimentId) {
      onExperimentReady?.(onlyLinked.experimentId);
    }
  }, [assemblyLinks, assemblyPlan, onExperimentLinksReady, onExperimentReady, workspaceMode]);
  usePolling(loadAssembly, ['READY', 'RUNNING'].includes(preparationStatus ?? '') || assemblyLinks.some((item) => ['READY', 'RUNNING'].includes(item.status)), 1600, id);

  const processing = Boolean(preview && ['CREATED', 'QUEUED', 'PARSING', 'VALIDATING', 'COMMITTING'].includes(preview.job.status));
  usePolling(() => load({ silent: true, skipWorkbook: true }), processing, 1600, id);

  const fields = workbook?.fields || [];
  const workbookUnavailable = !['DOC', 'DOCX', 'IMAGE'].includes(preview?.job.sourceFormat ?? '')
    && (workbookLoading || Boolean(workbookError) || !workbook);

  if (loading && !preview) return <div className="data-workbench-loading"><Spin /></div>;
  if (!preview) return (
    <div className="data-workbench-loading">
      <Alert
        showIcon
        type="error"
        message="导入任务加载失败"
        description={loadError || '无法加载该任务，请检查任务 ID、后端接口和服务连接后重试。'}
        action={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>重试</Button>}
      />
    </div>
  );

  const displayedIssues = deduplicateFieldDefinitionIssues(preview.issues, instance, preview.sheets);
  const blockers = displayedIssues.filter(isImportBlocker);
  const definitionReviews = definitionReviewItems(instance, displayedIssues);
  const issuesOutsideDefinition = displayedIssues.filter((issue) =>
    !issueRepresentedByDefinition(issue, definitionReviews));
  const definitionIssueCount = definitionReviews.length;

  const sourceFile: FilePreviewDescriptor = {
    fileName: preview.job.sourceFileName,
    load: () => dataApi.sourcePreviewBlob(preview.job.sourceFileId),
    downloadUrl: `/api/v1/files/${encodeURIComponent(preview.job.sourceFileId)}/content`,
  };

  const selectField = (field: DataFieldValueView) => {
    setSelectedFieldKey(fieldKey(field));
    setSelectedBindingId(definitionForValue(workbook, field)?.bindingId || field.bindingId);
    if (field.sheetId && field.address) editorRef.current?.focusCell?.(field.sheetId, field.address);
  };

  const selectDefinition = (field: DataWorkbookFieldDefinition) => {
    setSelectedBindingId(field.bindingId);
    const value = fields.find((item) => item.componentId === field.componentId
      && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
    if (value) setSelectedFieldKey(fieldKey(value));
    if (value?.sheetId && value.address) editorRef.current?.focusCell?.(value.sheetId, value.address);
    else if (field.sheetId && field.sourceRange) editorRef.current?.focusRange?.(field.sheetId, field.sourceRange);
  };

  const handleSelection = (selection: EditorSelection) => {
    setSelectedCell(selection);
    const exact = importFieldForCell(fields, selection);
    const row = cellRow(selection.address);
    const fallback = row === undefined ? undefined : importFieldInSheetRow(fields, selection, row);
    const next = exact || fallback;
    if (next) {
      setSelectedFieldKey(fieldKey(next));
      setSelectedBindingId(definitionForValue(workbook, next)?.bindingId || next.bindingId);
    }
  };

  const handleCellChange = async (change: EditorCellChange) => {
    const field = importFieldForCell(fields, change);
    const displayedValue = workbookCellValue(change.value, field?.valueType ?? '', change.numberFormat ?? '');
    if (instance) {
      if (field && !field.editable) {
        await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue, { raw: true });
        void message.warning('公式或只读字段不能直接修改'); return;
      }
      markStructureDirty();
      setWorkbook(current => current ? { ...current, fields: current.fields.map(item => item.sheetId === change.sheetId && item.address === change.address ? { ...item, effectiveValue: displayedValue, correctedValue: displayedValue } : item) } : current);
      return;
    }
    if (!field?.editable || !field.recordId || !field.bindingId || !field.valuePath) {
      await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue, { raw: true }).catch(() => undefined);
      void message.warning({
        key: 'data-workbench-readonly-cell',
        content: field ? '该单元格是公式、派生值或只读内容，不能直接修改' : '该单元格尚未绑定可修正字段',
      });
      return;
    }
    const key = `${change.sheetId}:${change.address}`;
    setSavingCell(key);
    setSelectedFieldKey(fieldKey(field));
    try {
      const savedJob = await queueWrite(revision => dataApi.correctValue(id, field.recordId!, {
        revision,
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: displayedValue,
        reason: '导入确认页直接修正',
      }), job => job);
      if (!loads.isActive()) return;
      correctionRevision.current = savedJob.revision;
      setWorkbook((current) => current ? {
        ...current,
        fields: current.fields.map((item) => fieldKey(item) === fieldKey(field)
          ? { ...item, correctedValue: displayedValue, effectiveValue: displayedValue }
          : item),
      } : current);
      await load({ silent: true, preserveSnapshot: true, skipWorkbook: true });
      void message.success('修正值已保存并重新校验');
    } catch (error) {
      if (!loads.isActive()) return;
      correctionRevision.current ??= preview.job.revision;
      setDocumentCorrections(current => ({ ...current, [fieldKey(field) || '']: formatImportValue(displayedValue) }));
      void message.error(error instanceof Error ? error.message : '修正值保存失败，当前修改仍保留');
    } finally {
      if (loads.isActive()) setSavingCell(undefined);
    }
  };

  const commit = async () => {
    const finishMetric = startOperationMetric('import-confirm');
    setSaving(true);
    try {
      if (structureDirtyRef.current) throw new Error('请先保存工作簿草稿，再完成数据核对');
      loads.invalidate();
      const job = await queueWrite(revision => dataApi.commit(id, workspaceMode === 'EXPERIMENT', revision), result => result);
      if (!loads.isActive()) return;
      setPreview(current => current ? { ...current, job } : current);
      if (workspaceMode === 'EXPERIMENT') setPreparationStatus('READY');
      void load({ silent: true, skipWorkbook: true });
      void message.success(workspaceMode === 'EXPERIMENT'
        ? '导入数据已完成核对，实验草稿生成状态已更新'
        : '数据已完成核对，可以提交审核');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '正式数据提交失败');
    } finally { if (loads.isActive()) setSaving(false); finishMetric(); }
  };

  const reExtract = async () => {
    setSaving(true);
    try {
      if (structureDirtyRef.current) throw new Error('请先保存工作簿草稿，再重新解析');
      loads.invalidate();
      await dataApi.reExtract(id,preview.job.revision);
      if (!loads.isActive()) return;
      await load();
      void message.success('数据已重新读取并校验');
    } catch (error) { void message.error(error instanceof Error ? error.message : '重新读取失败'); }
    finally { if (loads.isActive()) setSaving(false); }
  };

  const persistDraft = async (silent = false) => {
    if (!loads.isActive()) return false;
    if (!structureDirtyRef.current && !Object.keys(documentCorrections).length) return true;
    loads.invalidate();
    const generation = editGeneration.current;
    const sentOperations = [...structureOperations.current];
    const editor = editorRef.current;
    const finishMetric = startOperationMetric('import-save');
    setSaving(true);
    setLoadError(undefined);
    try {
      let savedJob = { ...preview.job, revision: correctionRevision.current ?? preview.job.revision };
      if (instance && structureDirtyRef.current) {
        for (const [key,value] of Object.entries(documentCorrections)) {
          const field=fields.find(f=>fieldKey(f)===key);
          if (!field?.sheetId || !field.address) throw new Error('字段位置已变化，请核对后保存');
          await editor?.writeCell?.(field.sheetId,field.address,value);
        }
        const live = editor?.getSnapshot();
        if (!live) throw new Error('工作簿尚未准备好');
        const saved = await queueWrite(revision => dataApi.saveWorkbookDraft(id, { revision, snapshot: live, templateInstance: editor?.getTemplateInstance?.() ?? instance, structureOperations: sentOperations }, workspaceMode === 'EXPERIMENT'), result => result.job);
        if (!loads.isActive()) return false;
        structureOperations.current = structureOperations.current.slice(sentOperations.length);
        const unchanged = generation === editGeneration.current;
        correctionRevision.current = saved.job.revision;
        if (unchanged) { structureDirtyRef.current = false; setStructureDirty(false); }
        setDocumentCorrections(current => Object.fromEntries(Object.entries(current).filter(([key, value]) => documentCorrections[key] !== value)));
        setPreview(current => current ? { ...current, job: saved.job } : current);
        // Refresh extraction results without replaying normalized metadata as a new user edit.
        if (unchanged) setInstance(editor?.getTemplateInstance?.() ?? saved.workbook?.templateInstance ?? instance);
        if (saved.workbook) {
          const returned = saved.workbook;
          setWorkbook(current => unchanged || !current ? { ...returned, snapshot: current?.snapshot ?? returned.snapshot } : current);
        }
        void load({ silent: true, preserveSnapshot: true, skipWorkbook: true });
        if (!silent) void message.success('工作簿草稿已保存并重新校验');
        return true;
      }
      for (const [key, value] of Object.entries(documentCorrections)) {
        const field = fields.find((item) => fieldKey(item) === key);
        if (!field?.editable || !field.recordId) throw new Error('字段位置已变化，请重新核对后保存');
        savedJob = await queueWrite(revision => dataApi.correctValue(id, field.recordId!, { revision, bindingId: field.bindingId, valuePath: field.valuePath, correctedValue: value, reason: '保存导入草稿' }), job => job);
        if (!loads.isActive()) return false;
        const ownedJob = savedJob;
        correctionRevision.current = savedJob.revision;
        setPreview(current => current ? { ...current, job: ownedJob } : current);
        setDocumentCorrections((current) => { if (current[key] !== value) return current; const next = { ...current }; delete next[key]; return next; });
      }
      const mappingPreview = await dataApi.preview(id, workspaceMode === 'EXPERIMENT');
      savedJob = await queueWrite(revision => dataApi.saveMappings(id, revision, mappingPreview.mappings), job => job);
      if (!loads.isActive()) return false;
      correctionRevision.current = savedJob.revision;
      setPreview(current => current ? { ...current, job: savedJob } : current);
      void load();
      if (!silent) void message.success('导入草稿已保存');
      return true;
    } catch (error) { if (!loads.isActive()) return false; const reason=error instanceof Error ? error.message : '未保存内容仍保留'; setLoadError(`草稿保存失败：${reason}`); void message.error(reason); return false; }
    finally { if (loads.isActive()) setSaving(false); finishMetric(); }
  };

  const saveDraft = (silent = false): Promise<boolean> => {
    if (draftWrite.current) return draftWrite.current;
    const pending = persistDraft(silent).finally(() => { if (draftWrite.current === pending) draftWrite.current = undefined; });
    draftWrite.current = pending;
    return pending;
  };

  const changeFieldCorrection = (field: DataFieldValueView, value: string) => {
    editGeneration.current++;
    correctionRevision.current ??= preview.job.revision;
    setDocumentCorrections((current) => {
      const key = fieldKey(field) || '';
      const next = { ...current };
      if (value === formatImportValue(field.effectiveValue)) delete next[key];
      else next[key] = value;
      return next;
    });
  };

  const saveFieldCorrection = async (field: DataFieldValueView, value: string) => {
    const key = fieldKey(field) || '';
    setSavingCell(key);
    try {
      if (instance && field.sheetId && field.address) {
        await editorRef.current?.writeCell?.(field.sheetId, field.address, value);
        markStructureDirty();
        if (!(await saveDraft(true))) throw new Error('修改尚未保存，请重试');
        return;
      }
      if (!field.recordId) throw new Error('字段位置已变化，请重新核对');
      const savedJob = await queueWrite(revision => dataApi.correctValue(id, field.recordId!, {
        revision,
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: value,
        reason: '导入核对页自动保存',
      }), job => job);
      if (!loads.isActive()) return;
      correctionRevision.current = savedJob.revision;
      setPreview((current) => current ? { ...current, job: savedJob } : current);
      setDocumentCorrections((current) => {
        if (current[key] !== value) return current;
        const next = { ...current };
        delete next[key];
        return next;
      });
      await load({ silent: true, preserveSnapshot: true, skipWorkbook: true });
    } catch (error) {
      if (!loads.isActive()) return;
      void message.error(error instanceof Error ? error.message : '修改保存失败，当前内容仍保留');
      throw error;
    } finally {
      if (loads.isActive()) setSavingCell(undefined);
    }
  };

  const editFieldDefinition = (item: FieldDefinitionReviewItem) => {
    const field = instance?.model.fields.find((candidate) => candidate.id === item.fieldId);
    const binding = field && instance?.mapping.find((candidate) => candidate.bindingId === field.bindingId);
    setActiveTab('properties');
    setSelectedBindingId(item.bindingId);
    if (binding) editorRef.current?.focusBinding(binding);
  };

  const confirmFieldDefinition = async (item: FieldDefinitionReviewItem) => {
    const current = editorRef.current?.getTemplateInstance?.() ?? instance;
    if (!current) throw new Error('字段定义尚未加载');
    const field = current.model.fields.find((candidate) => candidate.id === item.fieldId);
    if (!field) throw new Error('未找到需要确认的字段');
    const next = updateWorkbookField(current, item.fieldId, {
      ...(field.experimentSemanticStatus === 'NEEDS_REVIEW'
        ? { experimentSemanticStatus: 'CONFIRMED' as const }
        : {}),
    });
    await editorRef.current?.setTemplateInstance?.(next);
    if (!loads.isActive()) return;
    setInstance(next);
    markStructureDirty();
    if (!(await saveDraft(true))) throw new Error('字段确认尚未保存，请重试');
    void message.success(`字段“${field.name}”已确认`);
  };

  const compatibility = preview.job.compatibilityStatus || 'LEGACY';
  const saveProjectRelations = async () => {
    setSaving(true);
    try {
      const relations = await projectResourceApi.replaceLinks('DATA_IMPORT_JOB', id,
        projectRelations.filter((item) => item.projectId), relatedProjects);
      if (!loads.isActive()) return;
      setRelatedProjects(relations); setRelationOpen(false); void message.success('关联项目已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '关联项目保存失败'); }
    finally { if (loads.isActive()) setSaving(false); }
  };
  const meta = (
    <Space wrap size={8}>
      {categoryName ? <Tag color="blue">归档分类：{categoryName}</Tag> : null}
      {(workspaceMode === 'EXPERIMENT' || processing || preview.job.status === 'FAILED') && <Tag color={preview.job.status === 'COMPLETED' ? 'success' : preview.job.status === 'FAILED' ? 'error' : 'processing'}>
        {jobStatusLabels[preview.job.status] || '处理中'}
      </Tag>}
      {workspaceMode === 'DATA' ? <Tag color={compatibility === 'EXACT' ? 'success' : compatibility === 'COMPATIBLE' ? 'blue' : 'warning'}>
        {compatibilityLabels[compatibility] || '需要确认'}
      </Tag> : null}
      <Typography.Text type="secondary">解析进度 {dataParseProgress(preview.job)}%</Typography.Text>
      {relatedProjects.map((relation) => <Tag color="blue" key={`${relation.projectId}-${relation.stageId || ''}-${relation.taskId || ''}`}>{relation.projectName}{relation.stageName ? ` / ${relation.stageName}` : ''}{relation.taskName ? ` / ${relation.taskName}` : ''}</Tag>)}
    </Space>
  );

  return (
    <DataWorkbenchShell
      maximizable={workspaceMode === 'EXPERIMENT'}
      breadcrumb={workspaceMode === 'EXPERIMENT' ? '实验中心 / 导入确认' : '数据中心 / 导入确认'}
      title={preview.job.sourceFileName}
      leading={<Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : (canCreate ? '/data/upload' : '/data/view'))}>{workspaceMode === 'EXPERIMENT' ? '返回实验上传' : (canCreate ? '返回上传' : '返回数据查看')}</Button>}
      meta={meta}
      navigation={<nav className="workspace-view-tabs data-workspace-view-tabs" aria-label="数据工作台视图"><Tabs activeKey={workspaceView === 'edit' && !canUpdate ? 'preview' : workspaceView} onChange={setWorkspaceView} items={[{ key: 'preview', label: <span><EyeOutlined /> 预览</span> }, ...(canUpdate ? [{ key: 'edit', label: '编辑' }] : []), ...(workspaceMode === 'DATA' ? [{ key: 'versions', label: '版本记录' }] : [])]} /></nav>}
      content={workspaceMode === 'DATA' && workspaceView === 'versions' ? <div ref={setVersionContainer} /> : undefined}
      actions={<>
        {canUpdate && workbook?.editable && <Button loading={saving} disabled={Boolean(savingCell) || processing || workbookUnavailable} onClick={() => void saveDraft()}>保存草稿</Button>}
        {workspaceMode === 'DATA' && <DataPublicationPanel jobId={id} refreshKey={preview.job.updatedAt}
          versionContainer={versionContainer}
          canConfirm={!confirmationBlockReason(preview, displayedIssues)}
          disabled={saving || Boolean(savingCell) || workbookUnavailable || structureDirty || Object.keys(documentCorrections).length > 0}
          onStateChange={setPublication} onChanged={() => void load()} />}
        <Button icon={<ReloadOutlined />} disabled={workbookLoading} onClick={() => void load()}>刷新</Button>
        {canUpdate && !processing && preview.job.status !== 'COMPLETED' && <Button loading={saving} onClick={() => void reExtract()}>重新解析</Button>}
        <Button icon={<EyeOutlined />} onClick={() => setPreviewFile(sourceFile)}>预览原文件</Button>
        {canDownload && <Button icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(sourceFile)}>下载原文件</Button>}
        {canAssign && canUpdate ? <Button icon={<LinkOutlined />} onClick={() => setRelationOpen(true)}>关联项目</Button> : null}
      </>}
      notice={<>
        {workbookError || loadError ? <Alert
          showIcon
          type={workbookError ? 'error' : 'warning'}
          message={loadError?.startsWith('草稿保存失败：') ? '草稿保存失败，当前修改仍保留' : '加载失败，请重试'}
          action={<Button icon={<ReloadOutlined />} loading={loading || workbookLoading} onClick={() => void load()}>重试</Button>}
        /> : null}
        <FieldDefinitionReview
          items={definitionReviews}
          busy={saving || Boolean(savingCell)}
          onEdit={editFieldDefinition}
          onConfirm={confirmFieldDefinition}
        />
        <WorkbenchNotice preview={preview} issues={issuesOutsideDefinition} processing={processing} onIssue={(issue) => {
          if (isFieldDefinitionIssue(issue) && instance) {
            const location = fieldDefinitionIssueLocation(issue, instance, preview.sheets);
            if (location) {
              setSelectedCell(location);
              setActiveTab('properties');
              editorRef.current?.focusCell?.(location.sheetId, location.address);
            } else setActiveTab('structure');
            return;
          }
          if(issue.issueType === 'INSTANCE_STRUCTURE_UNCONFIRMED') { setActiveTab('structure'); return; }
          if (issue.sheetId && issue.address && !requiresTemplateChange(issue.issueType)) { editorRef.current?.focusCell?.(issue.sheetId, issue.address); setActiveTab('data'); }
          else navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload');
        }} />
        {!processing && preview.job.status !== 'COMPLETED' && confirmationBlockReason(preview, displayedIssues) && <Alert type="warning" showIcon
          message={confirmationBlockReason(preview, displayedIssues)} action={<Button onClick={() => navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload')}>重新选择模板或文件</Button>} />}
        {structureDirty && <Alert type="info" showIcon message="工作簿还有未保存的修改，可继续编辑或点击“保存草稿”。" />}
        {Object.keys(documentCorrections).length > 0 && <Alert type="info" showIcon message="字段修改正在自动保存，也可点击“保存草稿”立即保存。" />}
      </>}
      canvas={['DOC', 'DOCX', 'IMAGE'].includes(preview.job.sourceFormat) ? <OriginalDocumentCanvas file={sourceFile} /> : workbookError && !workbook ? null : <DataWorkbookCanvas
        ref={editorRef}
        workbook={workbook}
        loading={!workbook && (processing || workbookLoading)}
        editable={Boolean(workspaceView === 'edit' && canUpdate && !workbookUnavailable && workbook?.editable && (instance || workbook.fields.some((field) => field.editable)) && !savingCell && !saving)}
        instance={instance}
        onInstanceChange={next => { setInstance(next); markStructureDirty(); }}
        onStructureChange={operation => { structureOperations.current.push(operation); markStructureDirty(); }}
        onDirty={markStructureDirty}
        onSelectionChange={handleSelection}
        onCellChange={(change) => void handleCellChange(change)}
      />}
      panelTitle={workspaceMode === 'EXPERIMENT' ? '本次实验数据' : '本次导入数据'}
      panelExtra={<Tag color={preview.job.status === 'COMPLETED' ? 'success' : 'warning'}>{preview.job.status === 'COMPLETED' ? '已核对' : '待核对'}</Tag>}
      panel={<>
        <Tabs
          className="data-workbench-tabs"
          activeKey={activeTab}
          onChange={(value) => setActiveTab(value as PanelTab)}
          items={[
            { key: 'data', label: '字段数据' },
            { key: 'structure', label: '字段结构' },
            ...(instance ? [{ key: 'properties', label: '字段属性' }] : []),
          ]}
        />
        {activeTab === 'data' ? (
          <DataFieldDataBrowser
            workbook={workbook}
            definitionIssueCount={definitionIssueCount}
            selectedFieldKey={selectedFieldKey}
            selectedCell={selectedCell}
            onFieldScopeChange={loadFieldScope}
            headerExtra={<Space size={8}>
              {savingCell ? <Tag color="processing">正在保存</Tag> : null}
            </Space>}
            onSelectField={selectField}
            renderFieldExtra={(field) => <Space size={8}>
              {workspaceView === 'edit' && canUpdate && field.editable && field.recordId ? (
                <AutoSaveFieldInput
                  label={field.fieldName}
                  disabled={saving || Boolean(savingCell && savingCell !== fieldKey(field))}
                  saving={savingCell === fieldKey(field)}
                  value={documentCorrections[fieldKey(field) || ''] ?? formatImportValue(field.effectiveValue)}
                  savedValue={formatImportValue(field.effectiveValue)}
                  onChange={(value) => changeFieldCorrection(field, value)}
                  onSave={(value) => { void saveFieldCorrection(field, value).catch(() => undefined); }}
                />
              ) : null}
              {!field.editable && <Typography.Text type="secondary">{field.excluded ? '已排除的记录不可修正' : ['FORMULA', 'DERIVED', 'STATIC'].includes(field.valueSource) ? '公式、派生值和静态内容由源文件计算，不能直接修改' : '此字段当前只读'}</Typography.Text>}
              {!field.excluded && field.valueStatus !== 'VALID' && field.editable && field.sheetId && field.address ? (
                !['DOC', 'DOCX', 'IMAGE'].includes(preview.job.sourceFormat) ? <Button size="small" onClick={() => selectField(field)}>定位并修正</Button> : null
              ) : null}
            </Space>}
            renderFieldMeta={(field) => field.valueSource === 'FORMULA' ? <Typography.Text type="secondary">公式结果由系统计算</Typography.Text> : null}
          />
        ) : null}
        {instance && activeTab !== 'data' ? <InstanceFieldEditor view={activeTab} onViewChange={setActiveTab} getSelection={() => editorRef.current?.getSelection?.()} getSnapshot={() => editorRef.current?.getSnapshot()} instance={instance} ownerId={id} editable={workspaceView === 'edit' && canUpdate && !saving && Boolean(workbook?.editable)} selection={selectedCell}
            onFocusBinding={binding => editorRef.current?.focusBinding(binding)}
            onChange={next => { setInstance(next); void editorRef.current?.setTemplateInstance?.(next); markStructureDirty(); }}
            onFocus={field => { const b = instance.mapping.find(b => b.bindingId === field.bindingId); if(b) editorRef.current?.focusBinding(b); }}
            /> : activeTab === 'structure' ? <DataFieldStructureBrowser workbook={workbook} selectedBindingId={selectedBindingId} onSelectField={selectDefinition} /> : null}
        {workspaceMode === 'EXPERIMENT' ? (
          <DataReviewFooter
            editable={canUpdate}
            confirmed={preview.job.status === 'COMPLETED'
              && !['FAILED', 'CANCELLED'].includes(preparationStatus ?? '')
              && !(preparationStatus == null && (assemblyPlan?.readyCount ?? 0) > 0)}
            busy={saving}
            saving={Boolean(savingCell) || Object.keys(documentCorrections).length > 0}
            blocked={workbookUnavailable || processing || structureDirty || definitionReviews.length > 0 || blockers.length > 0
              || preview.job.status !== 'COMPLETED' && Boolean(confirmationBlockReason(preview, displayedIssues))
              || ['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
            blockedHint={workbookUnavailable ? '请等待工作簿加载成功后再完成核对' : structureDirty
              ? '左侧工作簿有未保存修改，请先点击顶部“保存草稿”'
              : definitionReviews.length
                ? '请先确认上方新增字段的类型和单位'
                : '请先处理上方待核对内容'}
            onConfirm={() => void commit()}
          />
        ) : null}
      </>}
      footer={preview.job.status === 'COMPLETED' ? (
        workspaceMode === 'EXPERIMENT' ? null : <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Space><CheckCircleOutlined className="data-success-icon" /><Typography.Text strong>导入已完成，来源文件已归档</Typography.Text><Button onClick={() => navigate('/data/view')}>查看来源文件</Button></Space>
        </Space>
      ) : undefined}
    >
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} showSpreadsheetMerges={false} />
      <FormModal draft={projectRelations} open={relationOpen} title="维护数据任务关联项目" width={760} okText="保存" confirmLoading={saving} onCancel={() => setRelationOpen(false)} onOk={() => saveProjectRelations()}><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} /></FormModal>
    </DataWorkbenchShell>
  );
}
function formatImportValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'object') {
    try { return JSON.stringify(value) ?? ''; } catch { return ''; }
  }
  if (typeof value === 'number' || typeof value === 'boolean' || typeof value === 'bigint') return `${value}`;
  return '';
}

function WorkbenchNotice({ preview, issues, processing, onIssue }: { preview: DataPreview; issues: DataPreview['issues']; processing: boolean; onIssue: (issue: DataPreview['issues'][number]) => void }) {
  if (processing) return <Alert banner showIcon type="info" message="系统正在读取工作簿并校验字段，完成后自动更新。" />;
  const statusNotice = preview.job.status === 'FAILED'
    ? <Alert banner showIcon type="error" message={preview.job.errorMessage || '文件处理失败，请检查原文件。'} />
    : preview.job.compatibilityStatus === 'INCOMPATIBLE'
      ? <Alert banner showIcon type="error" message="当前文件与所选模板不匹配，请选择正确模板或检查表头。" />
      : preview.job.compatibilityStatus === 'REVIEW_REQUIRED'
        ? <Alert banner showIcon type="warning" message="文件与所选模板的结构不一致，无法可靠读取，请重新选择模板或文件。" />
        : undefined;
  if (!statusNotice && !issues.some(issue => !['RESOLVED', 'IGNORED'].includes(issue.status))) return null;
  return <Space direction="vertical" size={8} style={{ width: '100%' }}>
    {statusNotice}
    <DataImportQualityNotice issues={issues} sheets={preview.sheets} onLocate={onIssue} experimentDraft={preview.job.importPurpose === 'EXPERIMENT_DRAFT'} />
  </Space>;
}

function fieldKey(field?: DataFieldValueView) {
  return field ? [field.recordId, field.bindingId, field.valuePath, field.sheetId, field.address].join('|') : undefined;
}

function definitionForValue(workbook: DataWorkbookSnapshot | undefined, field: DataFieldValueView) {
  return workbook?.fieldDefinitions?.find((item) => item.componentId === field.componentId
    && (item.bindingId === field.bindingId || item.fieldCode === field.fieldCode));
}

function cellRow(address: string) {
  const match = /([1-9][0-9]*)$/.exec(address.replace(/\$/g, ''));
  return match ? Number(match[1]) : undefined;
}
