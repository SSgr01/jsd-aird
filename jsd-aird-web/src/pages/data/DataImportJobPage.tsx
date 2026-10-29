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
  Modal,
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
import { confirmationBlockReason, isFieldDefinitionIssue, requiresTemplateChange } from '@/services/data/import-guidance';
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
  type DataWorkbookFieldDefinition,
  type DataPreview,
  type DataWorkbookSnapshot,
  type ExperimentAssemblyPlan,
  type ExperimentAssemblyLink,
} from '@/services/data/data-api';
import { projectResourceApi, type ProjectRelationTarget, type RelatedProjectView } from '@/services/project/project-resource-api';
import { dataParseProgress } from '@/services/data/data-progress';
import { InstanceFieldEditor } from '@/features/template-workspace/InstanceFieldEditor';
import { writeWorkbookInstance, type WorkbookTemplateInstance } from '@/features/template-workspace/workbook-instance';
import { resolveInstanceDefinition } from '@/features/template-workspace/instance-definition';
import { labWorkbookInstance } from '@/features/experiment-workspace/lab-structure-adapter';
import { presentWorkbookFields } from '@/features/data-workbench/field-presentation';
import { instanceWorkbookView } from '@/features/data-workbench/instance-workbook-view';
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
  const [publication, setPublication] = useState<DataPublication>();
  const [workspaceView, setWorkspaceView] = useState('edit');
  const [instance, setInstance] = useState<WorkbookTemplateInstance>();
  const [structureDirty, setStructureDirty] = useState(false);
  const structureDirtyRef = useRef(false);
  const structureOperations = useRef<unknown[]>([]);
  const markStructureDirty = () => {
    structureDirtyRef.current = true; setStructureDirty(true);
    window.requestAnimationFrame(() => {
      const live = editorRef.current?.getSnapshot(), current = editorRef.current?.getTemplateInstance?.();
      if (live && current) {
        const view = instanceWorkbookView(live, current, true);
        setWorkbook(previous => previous ? { ...previous, fields: view.fields, records: view.records, regions: view.regions, fieldDefinitions: view.fieldDefinitions } : previous);
      }
    });
  };
  const [versionContainer, setVersionContainer] = useState<HTMLDivElement | null>(null);
  const hasUpdatePermission = usePermission(workspaceMode === 'EXPERIMENT' ? 'experiment.create' : 'data.update');
  const canUpdate = hasUpdatePermission && !['PENDING_REVIEW', 'APPROVED', 'PUBLISHED', 'DISABLED'].includes(publication?.status || '');
  const canAssign = usePermission('project.assign');
  const canCreate = usePermission(workspaceMode === 'EXPERIMENT' ? 'experiment.create' : 'data.create');

  const load = useCallback(async (options: { silent?: boolean; preserveSnapshot?: boolean; skipWorkbook?: boolean } = {}) => {
    if (!options.silent) {
      setLoading(true);
      setLoadError(undefined);
    }
    try {
      const nextPreview = await dataApi.preview(id);
      const stillProcessing = ['PARSING', 'VALIDATING', 'COMMITTING'].includes(nextPreview.job.status);
      const nextWorkbook = options.skipWorkbook && stillProcessing
        ? undefined
        : await dataApi.getImportWorkbookSnapshot(id).catch((error: unknown) => {
          if (stillProcessing || ['DOC', 'DOCX', 'IMAGE'].includes(nextPreview.job.sourceFormat)) return undefined;
          throw error;
        });
      setPreview(nextPreview);
      setLoadError(undefined);
      void projectResourceApi.links('DATA_IMPORT_JOB', id).then((relations) => {
        setRelatedProjects(relations);
        setProjectRelations(relations.map((item) => ({ projectId: item.projectId, stageId: item.stageId, taskId: item.taskId })));
      }).catch(() => { setRelatedProjects([]); setProjectRelations([]); });
      if (nextWorkbook) {
        const nextInstance = resolveInstanceDefinition(nextWorkbook.snapshot, nextWorkbook.templateInstance) ?? labWorkbookInstance(nextWorkbook.snapshot);
        if (nextInstance) writeWorkbookInstance(nextWorkbook.snapshot, nextInstance);
        const displayedWorkbook = presentWorkbookFields(nextWorkbook, nextInstance);
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
      setLoadError(error instanceof Error ? error.message : '导入任务加载失败');
    } finally {
      if (!options.silent) setLoading(false);
    }
  }, [id, message]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!preview?.job.categoryId) { setCategoryName(undefined); return; }
    void dataApi.listCategories().then((items) => setCategoryName(items.find((item) => item.id === preview.job.categoryId)?.name)).catch(() => setCategoryName(undefined));
  }, [preview?.job.categoryId]);

  const loadAssembly = useCallback(async () => {
    if (preview?.job.status !== 'COMPLETED' || preview.templateContract?.importContractVersion !== 9) return;
    try {
      const sync = await dataApi.experimentSyncStatus(id);
      setAssemblyPlan(sync.plan); setAssemblyLinks(sync.links);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验草稿组装计划加载失败');
    }
  }, [id, message, preview]);
  useEffect(() => { void loadAssembly(); }, [loadAssembly]);
  useEffect(() => {
    if (workspaceMode !== 'EXPERIMENT' || !assemblyPlan) return;
    const linked = assemblyLinks.filter((item) => Boolean(item.experimentId));
    const onlyLinked = linked.length === 1 ? linked[0] : undefined;
    onExperimentLinksReady?.(linked);
    if (assemblyPlan.candidates.length === 1 && onlyLinked?.experimentId) {
      onExperimentReady?.(onlyLinked.experimentId);
    }
  }, [assemblyLinks, assemblyPlan, onExperimentLinksReady, onExperimentReady, workspaceMode]);
  useEffect(() => {
    if (!assemblyLinks.some((item) => ['READY', 'RUNNING'].includes(item.status))) return undefined;
    const timer = window.setInterval(() => void loadAssembly(), 1600);
    return () => window.clearInterval(timer);
  }, [assemblyLinks, loadAssembly]);

  const processing = Boolean(preview && ['PARSING', 'VALIDATING', 'COMMITTING'].includes(preview.job.status));
  useEffect(() => {
    if (!processing) return undefined;
    const timer = window.setInterval(() => void load({ silent: true, skipWorkbook: true }), 1600);
    return () => window.clearInterval(timer);
  }, [load, processing]);

  const fields = workbook?.fields || [];
  const blockers = preview?.issues.filter((issue) => issue.severity === 'BLOCKER'
    && !['RESOLVED', 'IGNORED'].includes(issue.status)) || [];

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
      const savedJob = await dataApi.correctValue(id, field.recordId, {
        revision: correctionRevision.current ?? preview.job.revision,
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: displayedValue,
        reason: '导入确认页直接修正',
      });
      correctionRevision.current = Object.keys(documentCorrections).length ? savedJob.revision : undefined;
      setWorkbook((current) => current ? {
        ...current,
        fields: current.fields.map((item) => fieldKey(item) === fieldKey(field)
          ? { ...item, correctedValue: displayedValue, effectiveValue: displayedValue }
          : item),
      } : current);
      await load({ silent: true, preserveSnapshot: true });
      void message.success('修正值已保存并重新校验');
    } catch (error) {
      correctionRevision.current ??= preview.job.revision;
      setDocumentCorrections(current => ({ ...current, [fieldKey(field) || '']: formatImportValue(displayedValue) }));
      void message.error(error instanceof Error ? error.message : '修正值保存失败，当前修改仍保留');
    } finally {
      setSavingCell(undefined);
    }
  };

  const commit = async () => {
    setSaving(true);
    try {
      if (structureDirtyRef.current) throw new Error('请先保存工作簿草稿，再完成数据核对');
      await dataApi.commit(id);
      await load();
      if (workspaceMode === 'EXPERIMENT') await loadAssembly();
      void message.success(workspaceMode === 'EXPERIMENT'
        ? '导入数据已完成核对，实验草稿生成状态已更新'
        : '数据已完成核对，可以提交审核');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '正式数据提交失败');
    } finally { setSaving(false); }
  };

  const reExtract = async () => {
    setSaving(true);
    try {
      if (structureDirtyRef.current) throw new Error('请先保存工作簿草稿，再重新解析');
      await dataApi.reExtract(id,preview.job.revision);
      await load();
      void message.success('数据已重新读取并校验');
    } catch (error) { void message.error(error instanceof Error ? error.message : '重新读取失败'); }
    finally { setSaving(false); }
  };

  const saveDraft = async (silent = false) => {
    setSaving(true);
    setLoadError(undefined);
    try {
      let savedJob = { ...preview.job, revision: correctionRevision.current ?? preview.job.revision };
      if (instance && structureDirtyRef.current) {
        for (const [key,value] of Object.entries(documentCorrections)) {
          const field=fields.find(f=>fieldKey(f)===key);
          if (!field?.sheetId || !field.address) throw new Error('字段位置已变化，请核对后保存');
          await editorRef.current?.writeCell?.(field.sheetId,field.address,value);
        }
        const live = editorRef.current?.getSnapshot();
        if (!live) throw new Error('工作簿尚未准备好');
        const saved = await dataApi.saveWorkbookDraft(id, { revision: savedJob.revision, snapshot: live, templateInstance: editorRef.current?.getTemplateInstance?.() ?? instance, structureOperations: structureOperations.current }, workspaceMode === 'EXPERIMENT');
        structureOperations.current = [];
        structureDirtyRef.current = false; setStructureDirty(false); correctionRevision.current = undefined;
        setDocumentCorrections({});
        setPreview(current => current ? { ...current, job: saved.job } : current);
        // Refresh extraction results without replaying normalized metadata as a new user edit.
        setInstance(editorRef.current?.getTemplateInstance?.() ?? saved.workbook.templateInstance ?? instance);
        setWorkbook(current => ({ ...saved.workbook, snapshot: current?.snapshot ?? saved.workbook.snapshot }));
        await load({ silent: true, preserveSnapshot: true });
        if (!silent) void message.success('工作簿草稿已保存并重新校验');
        return true;
      }
      for (const [key, value] of Object.entries(documentCorrections)) {
        const field = fields.find((item) => fieldKey(item) === key);
        if (!field?.editable || !field.recordId) throw new Error('字段位置已变化，请重新核对后保存');
        savedJob = await dataApi.correctValue(id, field.recordId, { revision: savedJob.revision, bindingId: field.bindingId, valuePath: field.valuePath, correctedValue: value, reason: '保存导入草稿' });
        const ownedJob = savedJob;
        correctionRevision.current = savedJob.revision;
        setPreview(current => current ? { ...current, job: ownedJob } : current);
        setDocumentCorrections((current) => { const next = { ...current }; delete next[key]; return next; });
      }
      savedJob = await dataApi.saveMappings(id, savedJob.revision, preview.mappings);
      correctionRevision.current = undefined;
      setPreview(current => current ? { ...current, job: savedJob } : current);
      await load();
      if (!silent) void message.success('导入草稿已保存');
      return true;
    } catch (error) { const reason=error instanceof Error ? error.message : '未保存内容仍保留'; setLoadError(`草稿保存失败：${reason}`); void message.error(reason); return false; }
    finally { setSaving(false); }
  };

  const changeFieldCorrection = (field: DataFieldValueView, value: string) => {
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
      const savedJob = await dataApi.correctValue(id, field.recordId, {
        revision: correctionRevision.current ?? preview.job.revision,
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: value,
        reason: '导入核对页自动保存',
      });
      correctionRevision.current = savedJob.revision;
      setPreview((current) => current ? { ...current, job: savedJob } : current);
      setDocumentCorrections((current) => {
        if (current[key] !== value) return current;
        const next = { ...current };
        delete next[key];
        if (!Object.keys(next).length) correctionRevision.current = undefined;
        return next;
      });
      await load({ silent: true });
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '修改保存失败，当前内容仍保留');
      throw error;
    } finally {
      setSavingCell(undefined);
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
      setRelatedProjects(relations); setRelationOpen(false); void message.success('关联项目已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '关联项目保存失败'); }
    finally { setSaving(false); }
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
      breadcrumb={workspaceMode === 'EXPERIMENT' ? '实验中心 / 导入确认' : '数据中心 / 导入确认'}
      title={preview.job.sourceFileName}
      leading={<Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : (canCreate ? '/data/upload' : '/data/view'))}>{workspaceMode === 'EXPERIMENT' ? '返回实验上传' : (canCreate ? '返回上传' : '返回数据查看')}</Button>}
      meta={meta}
      navigation={<nav className="workspace-view-tabs data-workspace-view-tabs" aria-label="数据工作台视图"><Tabs activeKey={workspaceView === 'edit' && !canUpdate ? 'preview' : workspaceView} onChange={setWorkspaceView} items={[{ key: 'preview', label: <span><EyeOutlined /> 预览</span> }, { key: 'edit', label: '编辑', disabled: !canUpdate }, ...(workspaceMode === 'DATA' ? [{ key: 'versions', label: '版本记录' }] : [])]} /></nav>}
      content={workspaceMode === 'DATA' && workspaceView === 'versions' ? <div ref={setVersionContainer} /> : undefined}
      actions={<>
        {canUpdate && workbook?.editable && <Button loading={saving} disabled={Boolean(savingCell) || processing} onClick={() => void saveDraft()}>保存草稿</Button>}
        {workspaceMode === 'DATA' && <DataPublicationPanel jobId={id} refreshKey={preview.job.updatedAt}
          versionContainer={versionContainer}
          canConfirm={preview.job.status === 'WAITING_CONFIRM' && !blockers.length && !['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
          disabled={saving || Boolean(savingCell) || structureDirty || Object.keys(documentCorrections).length > 0}
          onStateChange={setPublication} onChanged={() => void load()} />}
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {canUpdate && !processing && preview.job.status !== 'COMPLETED' && <Button loading={saving} onClick={() => void reExtract()}>重新解析</Button>}
        <Button icon={<EyeOutlined />} onClick={() => setPreviewFile(sourceFile)}>预览原文件</Button>
        <Button icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(sourceFile)}>下载原文件</Button>
        {canAssign && canUpdate ? <Button icon={<LinkOutlined />} onClick={() => setRelationOpen(true)}>关联项目</Button> : null}
      </>}
      notice={<>
        {loadError ? <Alert
          showIcon
          type="warning"
          message={loadError.startsWith('草稿保存失败：') ? '草稿保存失败，当前未保存修改仍保留' : '任务数据刷新失败，当前显示的是上一次成功加载的数据'}
          description={loadError}
          action={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>重试</Button>}
          closable
          onClose={() => setLoadError(undefined)}
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
        {!processing && preview.job.status !== 'COMPLETED' && confirmationBlockReason(preview) && <Alert type="warning" showIcon
          message={confirmationBlockReason(preview)} action={<Button onClick={() => navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload')}>重新选择模板或文件</Button>} />}
        {structureDirty && <Alert type="info" showIcon message="工作簿还有未保存的修改，可继续编辑或点击“保存草稿”。" />}
        {Object.keys(documentCorrections).length > 0 && <Alert type="info" showIcon message="字段修改正在自动保存，也可点击“保存草稿”立即保存。" />}
      </>}
      canvas={['DOC', 'DOCX', 'IMAGE'].includes(preview.job.sourceFormat) ? <OriginalDocumentCanvas file={sourceFile} /> : <DataWorkbookCanvas
        ref={editorRef}
        workbook={workbook}
        loading={!workbook && processing}
        editable={Boolean(workspaceView === 'edit' && canUpdate && workbook?.editable && workbook.fields.some((field) => field.editable) && !savingCell && !saving)}
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
        {instance ? <InstanceFieldEditor view={activeTab} onViewChange={setActiveTab} getSelection={() => editorRef.current?.getSelection?.()} getSnapshot={() => editorRef.current?.getSnapshot()} instance={instance} ownerId={id} editable={workspaceView === 'edit' && canUpdate && !saving && Boolean(workbook?.editable)} selection={selectedCell}
            onFocusBinding={binding => editorRef.current?.focusBinding(binding)}
            onChange={next => { setInstance(next); void editorRef.current?.setTemplateInstance?.(next); markStructureDirty(); }}
            onFocus={field => { const b = instance.mapping.find(b => b.bindingId === field.bindingId); if(b) editorRef.current?.focusBinding(b); }}
            /> : activeTab === 'structure' ? <DataFieldStructureBrowser workbook={workbook} selectedBindingId={selectedBindingId} onSelectField={selectDefinition} /> : null}
        {workspaceMode === 'EXPERIMENT' && preview.job.status !== 'COMPLETED' ? (
          <DataReviewFooter
            editable={canUpdate}
            confirmed={false}
            busy={saving}
            saving={Boolean(savingCell) || Object.keys(documentCorrections).length > 0}
            blocked={structureDirty || definitionReviews.length > 0 || blockers.length > 0
              || preview.job.status !== 'WAITING_CONFIRM'
              || ['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
            blockedHint={structureDirty
              ? '左侧工作簿有未保存修改，请先点击顶部“保存草稿”'
              : definitionReviews.length
                ? '请先确认上方新增字段的类型和单位'
                : '请先处理上方待核对内容'}
            onConfirm={() => void commit()}
          />
        ) : null}
      </>}
      footer={preview.job.status === 'COMPLETED' ? (
        workspaceMode === 'EXPERIMENT' ? (
          preview.templateContract?.importContractVersion === 9 && assemblyPlan ? <div style={{ width: '100%' }}>
            <Alert style={{ marginTop: 8 }} type="info" showIcon message="导入数据已完成核对；实验草稿尚未发布" />
            {assemblyPlan.candidates.filter((item) => item.status === 'BLOCKED').map((item) => <Alert key={item.assemblyKey} style={{ marginTop: 8 }} type="error" showIcon message="待处理：该记录尚未生成实验草稿" description={<Space direction="vertical">{item.conflicts.map((issue) => <span key={issue.code}>{issue.message}</span>)}</Space>} />)}
            {assemblyPlan.candidates.filter((item) => item.status === 'NEEDS_REVIEW').map((item) => <Alert key={item.assemblyKey} style={{ marginTop: 8 }} type="warning" showIcon message="待复核：该记录尚未生成实验草稿" description={<Space direction="vertical">{[...item.conflicts, ...item.warnings].map((issue) => <span key={issue.code}>{issue.message}</span>)}</Space>} />)}
            {assemblyLinks.filter((item) => item.status === 'FAILED').map((item) => <Alert key={item.assemblyKey} style={{ marginTop: 8 }} type="error" showIcon message="实验草稿生成失败" description={item.errorMessage || '请重新导入；若问题仍存在，请联系管理员。'} />)}
            <Space wrap style={{ marginTop: 8 }}>
              {assemblyLinks.filter((item) => item.experimentId).map((item) => <Button key={item.assemblyKey} type="link" onClick={() => {
                if (!item.experimentId) return;
                if (onExperimentReady) onExperimentReady(item.experimentId);
                else navigate(`/experiments/${item.experimentId}`);
              }}>{item.experimentNo} · 进入实验记录</Button>)}
              {assemblyPlan.readyCount > 0 && !assemblyLinks.some((item) => item.experimentId) && !assemblyLinks.some((item) => item.status === 'FAILED') ? <Typography.Text type="secondary">实验草稿创建中</Typography.Text> : null}
              {assemblyPlan.readyCount === 0 && !assemblyLinks.some((item) => item.experimentId) && !assemblyLinks.some((item) => item.status === 'FAILED') && !assemblyPlan.reviewCount && !assemblyPlan.blockedCount ? <Typography.Text type="secondary">已完成导入，但当前没有可生成的实验草稿记录。</Typography.Text> : null}
              {assemblyPlan.blockedCount > 0 ? <Tag color="red">需处理 {assemblyPlan.blockedCount}</Tag> : null}
              {assemblyPlan.reviewCount > 0 ? <Tag color="warning">待复核 {assemblyPlan.reviewCount}</Tag> : null}
            </Space>
          </div> : null
        ) : <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Space><CheckCircleOutlined className="data-success-icon" /><Typography.Text strong>导入已完成，来源文件已归档</Typography.Text><Button onClick={() => navigate('/data/view')}>查看来源文件</Button></Space>
        </Space>
      ) : undefined}
    >
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} showSpreadsheetMerges={false} />
      <Modal open={relationOpen} title="维护数据任务关联项目" width={760} okText="保存" confirmLoading={saving} onCancel={() => setRelationOpen(false)} onOk={() => void saveProjectRelations()}><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} /></Modal>
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
    <DataImportQualityNotice issues={issues} sheets={preview.sheets} onLocate={onIssue} />
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
