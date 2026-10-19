import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CheckOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
  LinkOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Input,
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
import { confirmationBlockReason, requiresTemplateChange } from '@/services/data/import-guidance';
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

type PanelTab = 'data' | 'structure';

const jobStatusLabels: Record<string, string> = {
  PARSING: '解析中', VALIDATING: '校验中', WAITING_MAPPING: '待确认数据', WAITING_CONFIRM: '待确认数据',
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
        : await dataApi.getImportWorkbookSnapshot(id).catch(() => undefined);
      setPreview(nextPreview);
      setLoadError(undefined);
      void projectResourceApi.links('DATA_IMPORT_JOB', id).then((relations) => {
        setRelatedProjects(relations);
        setProjectRelations(relations.map((item) => ({ projectId: item.projectId, stageId: item.stageId, taskId: item.taskId })));
      }).catch(() => { setRelatedProjects([]); setProjectRelations([]); });
      if (nextWorkbook) {
        setWorkbook((current) => options.preserveSnapshot && current
          ? { ...nextWorkbook, snapshot: current.snapshot }
          : nextWorkbook);
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
    if (!field?.editable || !field.recordId || !field.bindingId || !field.valuePath) {
      await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue).catch(() => undefined);
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
      await dataApi.correctValue(id, field.recordId, {
        bindingId: field.bindingId,
        valuePath: field.valuePath,
        correctedValue: change.value,
        reason: '导入确认页直接修正',
      });
      setWorkbook((current) => current ? {
        ...current,
        fields: current.fields.map((item) => fieldKey(item) === fieldKey(field)
          ? { ...item, correctedValue: change.value, effectiveValue: change.value }
          : item),
      } : current);
      await load({ silent: true, preserveSnapshot: true });
      void message.success('修正值已保存并重新校验');
    } catch (error) {
      await editorRef.current?.writeCell?.(change.sheetId, change.address, change.previousValue).catch(() => undefined);
      void message.error(error instanceof Error ? error.message : '修正值保存失败，已恢复原值');
    } finally {
      setSavingCell(undefined);
    }
  };

  const commit = async () => {
    setSaving(true);
    try {
      await dataApi.commit(id);
      await load();
      if (workspaceMode === 'EXPERIMENT') await loadAssembly();
      void message.success(workspaceMode === 'EXPERIMENT'
        ? '导入数据已确认，实验草稿生成状态已更新'
        : '数据已确认，可以提交审核');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '正式数据提交失败');
    } finally { setSaving(false); }
  };

  const reExtract = async () => {
    setSaving(true);
    try {
      await dataApi.reExtract(id);
      await load();
      void message.success('数据已重新读取并校验');
    } catch (error) { void message.error(error instanceof Error ? error.message : '重新读取失败'); }
    finally { setSaving(false); }
  };

  const saveDraft = async () => {
    setSaving(true);
    try {
      for (const [key, value] of Object.entries(documentCorrections)) {
        const field = fields.find((item) => fieldKey(item) === key);
        if (!field?.editable || !field.recordId) throw new Error('字段位置已变化，请重新核对后保存');
        await dataApi.correctValue(id, field.recordId, { bindingId: field.bindingId, valuePath: field.valuePath, correctedValue: value, reason: '保存导入草稿' });
        setDocumentCorrections((current) => { const next = { ...current }; delete next[key]; return next; });
      }
      await dataApi.saveMappings(id, preview.mappings);
      await load();
      void message.success('导入草稿已保存');
    } catch (error) { void message.error(error instanceof Error ? error.message : '草稿保存失败，未保存内容仍保留'); }
    finally { setSaving(false); }
  };

  const compatibility = preview.job.compatibilityStatus || 'LEGACY';
  const saveProjectRelations = async () => {
    setSaving(true);
    try {
      const relations = await projectResourceApi.replaceLinks('DATA_IMPORT_JOB', id,
        projectRelations.filter((item) => item.projectId));
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
      navigation={workspaceMode === 'DATA' && <nav className="workspace-view-tabs data-workspace-view-tabs" aria-label="数据工作台视图"><Tabs activeKey={workspaceView === 'edit' && !canUpdate ? 'preview' : workspaceView} onChange={setWorkspaceView} items={[{ key: 'preview', label: <span><EyeOutlined /> 预览</span> }, { key: 'edit', label: '编辑', disabled: !canUpdate }, { key: 'versions', label: '版本记录' }]} /></nav>}
      content={workspaceMode === 'DATA' && workspaceView === 'versions' ? <div ref={setVersionContainer} /> : undefined}
      actions={<>
        {canUpdate && workbook?.editable && <Button loading={saving} disabled={Boolean(savingCell) || processing} onClick={() => void saveDraft()}>保存草稿</Button>}
        {workspaceMode === 'DATA' && <DataPublicationPanel jobId={id} refreshKey={preview.job.updatedAt}
          versionContainer={versionContainer}
          canConfirm={preview.job.status === 'WAITING_CONFIRM' && !blockers.length && !['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
          disabled={saving || Boolean(savingCell) || Object.keys(documentCorrections).length > 0}
          onStateChange={setPublication} onChanged={() => void load()} />}
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        {canUpdate && !processing && preview.job.status !== 'COMPLETED' && <Button loading={saving} onClick={() => void reExtract()}>重新解析</Button>}
        <Button icon={<EyeOutlined />} onClick={() => setPreviewFile(sourceFile)}>预览原文件</Button>
        <Button icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(sourceFile)}>下载原文件</Button>
        {canAssign && canUpdate ? <Button icon={<LinkOutlined />} onClick={() => setRelationOpen(true)}>关联项目</Button> : null}
        {workspaceMode === 'EXPERIMENT' && canUpdate && preview.job.status !== 'COMPLETED' ? <Button
          type="primary"
          icon={<CheckOutlined />}
          disabled={blockers.length > 0 || preview.job.status !== 'WAITING_CONFIRM' || ['INCOMPATIBLE', 'REVIEW_REQUIRED'].includes(compatibility)}
          loading={saving}
          onClick={() => void commit()}
        >生成实验草稿</Button> : null}
      </>}
      notice={<>
        {loadError ? <Alert
          showIcon
          type="warning"
          message="任务数据刷新失败，当前显示的是上一次成功加载的数据"
          description={loadError}
          action={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>重试</Button>}
          closable
          onClose={() => setLoadError(undefined)}
        /> : null}
        <WorkbenchNotice preview={preview} processing={processing} onIssue={(issue) => {
          if (issue.sheetId && issue.address && !requiresTemplateChange(issue.issueType)) { editorRef.current?.focusCell?.(issue.sheetId, issue.address); setActiveTab('data'); }
          else navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload');
        }} />
        {!processing && preview.job.status !== 'COMPLETED' && confirmationBlockReason(preview) && <Alert type="warning" showIcon
          message={confirmationBlockReason(preview)} action={<Button onClick={() => navigate(workspaceMode === 'EXPERIMENT' ? '/experiments/upload' : '/data/upload')}>重新选择模板或文件</Button>} />}
        {Object.keys(documentCorrections).length > 0 && <Alert type="info" showIcon message="有尚未保存的字段修改，请保存草稿后提交审核。" />}
      </>}
      canvas={['DOCX', 'IMAGE'].includes(preview.job.sourceFormat) ? <OriginalDocumentCanvas file={sourceFile} /> : <DataWorkbookCanvas
        ref={editorRef}
        workbook={workbook}
        loading={!workbook && processing}
        editable={Boolean(workspaceView === 'edit' && canUpdate && workbook?.editable && workbook.fields.some((field) => field.editable) && !savingCell && !saving)}
        onSelectionChange={handleSelection}
        onCellChange={(change) => void handleCellChange(change)}
      />}
      panel={<>
        <Tabs
          className="data-workbench-tabs"
          activeKey={activeTab}
          onChange={(value) => setActiveTab(value as PanelTab)}
          items={[
            { key: 'data', label: '字段数据' },
            { key: 'structure', label: '字段结构' },
          ]}
        />
        {activeTab === 'data' ? (
          <DataFieldDataBrowser
            workbook={workbook}
            selectedFieldKey={selectedFieldKey}
            selectedCell={selectedCell}
            headerExtra={<Space size={8}>
              {savingCell ? <Tag color="processing">正在保存</Tag> : null}
            </Space>}
            onSelectField={selectField}
            renderFieldExtra={(field) => <Space size={8}>
              {workspaceView === 'edit' && canUpdate && field.editable && field.recordId ? <Space.Compact>
                <Input disabled={saving || Boolean(savingCell)} aria-label={`修正${field.fieldName}`} placeholder="核对原文后输入实际值"
                  value={documentCorrections[fieldKey(field) || ''] ?? formatImportValue(field.effectiveValue)}
                  onChange={(event) => setDocumentCorrections((current) => ({ ...current, [fieldKey(field) || '']: event.target.value }))} />
                <Button disabled={saving || Boolean(savingCell)} loading={savingCell === fieldKey(field)} onClick={() => {
                  setSavingCell(fieldKey(field));
                  void dataApi.correctValue(id, field.recordId!, {
                    bindingId: field.bindingId, valuePath: field.valuePath,
                    correctedValue: documentCorrections[fieldKey(field) || ''] ?? formatImportValue(field.effectiveValue),
                    reason: '人工核对原文件',
                  }).then(() => { setDocumentCorrections((current) => { const next = { ...current }; delete next[fieldKey(field) || '']; return next; }); return load(); }).then(() => void message.success('已修正并重新校验'))
                    .catch((error: unknown) => void message.error(error instanceof Error ? error.message : '修正失败'))
                    .finally(() => setSavingCell(undefined));
                }}>保存修正</Button>
              </Space.Compact> : null}
              {!field.editable && <Typography.Text type="secondary">{field.excluded ? '已排除的记录不可修正' : ['FORMULA', 'DERIVED', 'STATIC'].includes(field.valueSource) ? '公式、派生值和静态内容由源文件计算，不能直接修改' : '此字段当前只读'}</Typography.Text>}
              {!field.excluded && field.valueStatus !== 'VALID' && field.editable && field.sheetId && field.address ? (
                !['DOCX', 'IMAGE'].includes(preview.job.sourceFormat) ? <Button size="small" onClick={() => selectField(field)}>定位并修正</Button> : null
              ) : null}
            </Space>}
            renderFieldMeta={(field) => <Space wrap size={4}><Tag>类型：{fieldTypeLabel(field.valueType)}</Tag>{field.required ? <Tag color="orange">必填</Tag> : null}{field.identity ? <Tag color="blue">记录标识</Tag> : null}</Space>}
          />
        ) : null}
        {activeTab === 'structure' ? (
          <DataFieldStructureBrowser
            workbook={workbook}
            selectedBindingId={selectedBindingId}
            onSelectField={selectDefinition}
          />
        ) : null}
      </>}
      footer={preview.job.status === 'COMPLETED' ? (
        workspaceMode === 'EXPERIMENT' ? (
          preview.templateContract?.importContractVersion === 9 && assemblyPlan ? <div style={{ width: '100%' }}>
            <Alert style={{ marginTop: 8 }} type="info" showIcon message="导入数据已确认；实验草稿尚未发布" />
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

function WorkbenchNotice({ preview, processing, onIssue }: { preview: DataPreview; processing: boolean; onIssue: (issue: DataPreview['issues'][number]) => void }) {
  if (processing) return <Alert banner showIcon type="info" message="系统正在读取工作簿并校验字段，完成后自动更新。" />;
  const statusNotice = preview.job.status === 'FAILED'
    ? <Alert banner showIcon type="error" message={preview.job.errorMessage || '文件处理失败，请检查原文件。'} />
    : preview.job.compatibilityStatus === 'INCOMPATIBLE'
      ? <Alert banner showIcon type="error" message="当前文件与所选模板不匹配，请选择正确模板或检查表头。" />
      : preview.job.compatibilityStatus === 'REVIEW_REQUIRED'
        ? <Alert banner showIcon type="warning" message="文件与所选模板的结构不一致，无法可靠读取，请重新选择模板或文件。" />
        : undefined;
  return <Space direction="vertical" size={8} style={{ width: '100%' }}>
    {statusNotice}
    <DataImportQualityNotice issues={preview.issues} sheets={preview.sheets} onLocate={onIssue} />
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

function fieldTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    TEXT: '文本', STRING: '文本', NUMBER: '数值', DECIMAL: '小数', INTEGER: '整数',
    DATE: '日期', DATETIME: '日期时间', BOOLEAN: '是/否', ENUM: '选项',
  };
  return labels[(value || 'TEXT').toUpperCase()] || '文本';
}
