import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, Col, Form, Popconfirm, Radio, Input, Row, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { dataApi, type DataJob, type DataTemplateOption, type ExperimentSyncStatus } from '@/services/data/data-api';
import { dataParseProgress, dataParseStageLabel } from '@/services/data/data-progress';
import {
  importExperimentFile,
  deleteExperimentImport,
  listCategories,
  listExperimentImports,
  retryExperimentImport,
  stageExperimentFile,
  type Category,
  type ExperimentImportJob,
} from '@/services/experiments/experiment-api';
import { downloadFile } from '@/services/files/file-api';
import { useAuthStore } from '@/stores/auth-store';
import { loadAllPages } from './experiment-import-history-utils';

type UnifiedJob =
  | { kind: 'FREEFORM'; job: ExperimentImportJob }
  | { kind: 'TEMPLATE'; job: DataJob };

const ACTIVE_JOB_STATES = ['CREATED', 'QUEUED', 'PARSING', 'VALIDATING', 'COMMITTING'];

export function ExperimentUploadPage() {
  const { message } = App.useApp();
  const user = useAuthStore((state) => state.user);
  useEffect(() => { setOwnerName(user?.displayName || user?.username || ''); }, [user]);
  const navigate = useNavigate();
  const [mode, setMode] = useState<'TEMPLATE' | 'CONTENT_ONLY'>('TEMPLATE');
  const [ownerName, setOwnerName] = useState('');
  const [experimentDate, setExperimentDate] = useState(new Date().toLocaleDateString('sv-SE'));
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [freeJobs, setFreeJobs] = useState<ExperimentImportJob[]>([]);
  const [templateJobs, setTemplateJobs] = useState<DataJob[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(false);
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [experimentCategoryId, setExperimentCategoryId] = useState<string>();
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [uploading, setUploading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState({ current: 1, pageSize: 8 });
  const [retryingId, setRetryingId] = useState<string>();
  const [assemblyStatuses, setAssemblyStatuses] = useState<Record<string, ExperimentSyncStatus>>({});
  const requestedAssemblyStatuses = useRef(new Set<string>());

  const load = useCallback(async ({ silent = false }: { silent?: boolean } = {}) => {
    if (!silent) setLoading(true);
    try {
      const [free, allTemplateJobs] = await Promise.all([
        listExperimentImports(),
        loadAllPages((page, size) => dataApi.listExperimentJobs({ page, size })),
      ]);
      setFreeJobs(free);
      setTemplateJobs(allTemplateJobs
        .filter((item) => item.importPurpose === 'EXPERIMENT_DRAFT'));
    } catch (error) {
      if (!silent) void message.error(error instanceof Error ? error.message : '实验上传记录加载失败');
    } finally {
      if (!silent) setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    setTemplatesLoading(true);
    void Promise.all([
      listCategories().then((items) => {
        setCategories(items);
        setExperimentCategoryId((current) => current ?? items[0]?.id);
      }).catch((error) => void message.error(error instanceof Error ? error.message : '实验分类加载失败')),
      dataApi.listTemplates().then((result) => {
        const experimentTemplates = result.filter(
          (item) => item.format === 'XLSX' && item.experimentImportReady === true,
        );
        setTemplates(experimentTemplates);
        setTemplateVersionId((current) =>
          current && experimentTemplates.some((item) => item.versionId === current)
            ? current
            : experimentTemplates[0]?.versionId,
        );
      }).catch((error) => void message.error(error instanceof Error ? error.message : '实验模板加载失败')).finally(() => setTemplatesLoading(false)),
      load(),
    ]);
  }, [load]);

  const running = useMemo(
    () => [...freeJobs, ...templateJobs].some((job) => ACTIVE_JOB_STATES.includes(job.status)),
    [freeJobs, templateJobs],
  );
  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => void load({ silent: true }), 2000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  const chosenTemplate = useMemo(
    () => templates.find((item) => item.versionId === templateVersionId),
    [templateVersionId, templates],
  );

  const validate: UploadProps['beforeUpload'] = (file) => {
    const accepted = mode === 'CONTENT_ONLY' ? /\.(xlsx|xls|csv|doc|docx|pdf|jpg|jpeg|png|gif|webp|bmp|tif|tiff)$/i : /\.(xlsx|xls|csv|docx|jpg|jpeg|png|gif|webp|bmp|tif|tiff)$/i;
    if (file.size === 0 || file.size > 200 * 1024 * 1024 || !accepted.test(file.name)) {
      void message.error('实验模板导入支持 XLSX / XLS / CSV / DOCX 及常见图片格式');
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const submit = async () => {
    if (uploading) return;
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) { void message.warning('请先选择实验文件'); return; }
    if (!experimentCategoryId) { void message.warning('请选择实验分类'); return; }
    if (mode === 'CONTENT_ONLY' && (!ownerName.trim() || !experimentDate)) { void message.warning('请填写负责人和实验日期'); return; }
    if (mode === 'TEMPLATE' && !chosenTemplate) {
      void message.warning(templates.length
        ? '请选择可用于实验导入的已发布模板'
        : '暂无可用模板，可选择无模板导入');
      return;
    }
    setUploading(true);
    const failedNames = new Set<string>();
    let failed = 0;
    let templateJobCount = 0;
    try {
      for (const file of sourceFiles) {
        try {
          const staged = await stageExperimentFile(file);
          if (mode === 'CONTENT_ONLY') {
            const ext = file.name.split('.').pop()?.toLowerCase();
            const format = ext === 'xls' ? 'XLS' : ext === 'xlsx' ? 'XLSX' : ext === 'csv' ? 'CSV' : ext === 'pdf' ? 'PDF' : ext === 'doc' || ext === 'docx' ? 'DOCX' : 'IMAGE';
            const relation = projectRelations[0];
            await importExperimentFile({fileId:staged.fileId,fileName:staged.originalName,sha256:staged.sha256,format,
              processingMode:'CONTENT_ONLY',title:file.name.replace(/\.[^.]+$/,''),ownerName:ownerName.trim(),
              experimentDate,categoryId:experimentCategoryId,categoryName:categories.find(c=>c.id===experimentCategoryId)?.name,
              projectId:relation?.projectId,stageId:relation?.stageId,taskId:relation?.taskId});
          } else await dataApi.createExperimentJob({
            sourceFileId: staged.fileId,
            templateVersionId: chosenTemplate!.versionId,
            duplicateOverride: true,
            importPurpose: 'EXPERIMENT_DRAFT',
            targetExperimentCategoryId: experimentCategoryId,
            projectRelations,
          });
          templateJobCount += 1;
        } catch (error) {
          failedNames.add(file.name);
          failed += 1;
          void message.error(error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`);
        }
      }
      setFiles(current => current.filter(f => failedNames.has(f.name)));
      await load();
      if (!failed) {
        const parts: string[] = [];
        if (templateJobCount) parts.push(`${templateJobCount} 个${mode === 'CONTENT_ONLY' ? '无模板导入' : '按模板导入'}任务`);
        void message.success(`已提交 ${parts.join('、') || '0 个文件'}`);
      } else void message.warning(`${sourceFiles.length - failed} 个文件已提交，${failed} 个失败`);
    } finally {
      setUploading(false);
    }
  };

  const retry = async (job: ExperimentImportJob) => {
    setRetryingId(job.id);
    try { await retryExperimentImport(job.id); await load(); void message.success(`“${job.sourceFileName}”已提交重新解析`); }
    catch (error) { void message.error(error instanceof Error ? error.message : '重新解析失败'); }
    finally { setRetryingId(undefined); }
  };

  const jobs = useMemo<UnifiedJob[]>(() => [
    ...freeJobs.map((job) => ({ kind: 'FREEFORM' as const, job })),
    ...templateJobs.map((job) => ({ kind: 'TEMPLATE' as const, job })),
  ].filter((item) => {
    const status = item.job.status === 'WAITING_CONFIRM' ? 'WAITING_MAPPING' : item.job.status;
    return (filter === 'ALL' || status === filter)
      && (!keyword || item.job.sourceFileName.toLowerCase().includes(keyword.toLowerCase()));
  }).sort((a, b) => new Date(b.job.createdAt).getTime() - new Date(a.job.createdAt).getTime()), [filter, freeJobs, keyword, templateJobs]);

  const visibleJobs = useMemo(
    () => jobs.slice((page.current - 1) * page.pageSize, page.current * page.pageSize),
    [jobs, page.current, page.pageSize],
  );
  useEffect(() => {
    const targets = visibleJobs.flatMap((item) => item.kind === 'TEMPLATE' && item.job.status === 'COMPLETED'
      && !assemblyStatuses[item.job.id] && !requestedAssemblyStatuses.current.has(item.job.id) ? [item.job.id] : []);
    if (!targets.length) return;
    targets.forEach((id) => requestedAssemblyStatuses.current.add(id));
    void Promise.all(targets.map(async (id) => [id, await dataApi.experimentSyncStatus(id)] as const))
      .then((items) => setAssemblyStatuses((current) => ({ ...current, ...Object.fromEntries(items) })))
      .catch(() => targets.forEach((id) => requestedAssemblyStatuses.current.delete(id)));
  }, [assemblyStatuses, visibleJobs]);
  const assemblyPollingKey = visibleJobs.flatMap((item) => item.kind === 'TEMPLATE'
    && assemblyStatuses[item.job.id]?.links.some((link) => ['READY', 'RUNNING'].includes(link.status))
    ? [item.job.id] : []).join('|');
  useEffect(() => {
    const ids = assemblyPollingKey ? assemblyPollingKey.split('|') : [];
    if (!ids.length) return undefined;
    const timer = window.setInterval(() => {
      void Promise.all(ids.map(async (id) => [id, await dataApi.experimentSyncStatus(id)] as const))
        .then((items) => setAssemblyStatuses((current) => ({ ...current, ...Object.fromEntries(items) })))
        .catch(() => undefined);
    }, 1800);
    return () => window.clearInterval(timer);
  }, [assemblyPollingKey]);

  const records: UploadWorkspaceRecord[] = visibleJobs
    .map((item) => item.kind === 'TEMPLATE' ? templateRecord(item.job) : freeRecord(item.job));

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: '电子实验记录本' }, { title: '实验数据导入' }]}
      title="文件导入"
      description="选择按模板导入或无模板导入，读取文件内容后进入实验编辑器。"
      headerActions={<Space>
        <Button type="primary" onClick={() => navigate('/experiments')}>实验列表</Button>
      </Space>}
      leftTitle="基础信息"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="导入方式"><Radio.Group value={mode} onChange={(e) => { setMode(e.target.value); setFiles([]); }} options={[{value:'TEMPLATE',label:'按模板导入'},{value:'CONTENT_ONLY',label:'无模板导入'}]} optionType="button" /></Form.Item>
        {mode === 'CONTENT_ONLY' && <><Form.Item label="负责人" required><Input value={ownerName} onChange={e=>setOwnerName(e.target.value)} /></Form.Item><Form.Item label="实验日期" required><Input type="date" value={experimentDate} onChange={e=>setExperimentDate(e.target.value)} /></Form.Item></>}

        <Row gutter={12}>
          <Col span={12}>
            <Form.Item label="实验分类" required>
              <Select value={experimentCategoryId} onChange={setExperimentCategoryId} options={categories.map((item) => ({ value: item.id, label: item.name }))} placeholder="请选择实验分类" />
            </Form.Item>
          </Col>
        </Row>
        {mode === 'TEMPLATE' && <>
          <Form.Item label="实验数据模板" required>
            <Select showSearch optionFilterProp="label" loading={templatesLoading} value={templateVersionId} onChange={setTemplateVersionId} options={templates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))} placeholder="请选择实验数据模板" notFoundContent={templatesLoading ? '加载中' : '没有匹配的实验数据模板'} />
          </Form.Item>
        </>}
        <Form.Item label="关联项目 / 阶段 / 任务"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} multiple={false} /></Form.Item>
      </Form>}
      accept={mode === 'CONTENT_ONLY' ? '.doc,.docx,.pdf,.xls,.xlsx,.csv,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff' : '.docx,.xls,.xlsx,.csv,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff'}
      beforeUpload={validate}
      multiple
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFiles([])}
      uploadHint={mode === 'CONTENT_ONLY' ? '支持 Excel / Word / PDF / 图片，可批量上传，单个文件最大200MB。' : '支持 XLSX / DOCX / 图片，可批量上传，单个文件最大200MB。'}
      uploadMainText="拖拽文件到此处或点击选择文件"
      submitLabel="开始上传"
      submitIcon={<CloudUploadOutlined />}
      onSubmit={() => void submit()}
      submitting={uploading}
      submitDisabled={!experimentCategoryId || (mode === 'TEMPLATE' && !chosenTemplate)}
      rightTitle="导入历史"
      rightCount={jobs.length}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PARSING', label: '处理中' }, { key: 'WAITING_MAPPING', label: '待确认' }, { key: 'COMPLETED', label: '导入完成' }, { key: 'FAILED', label: '失败' }]}
      activeFilter={filter}
      onFilterChange={(value) => { setFilter(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword}
      onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }}
      records={records}
      recordsLoading={loading && freeJobs.length + templateJobs.length === 0}
      pagination={{ current: page.current, pageSize: page.pageSize, total: jobs.length }}
      onPageChange={(current, pageSize) => setPage({ current, pageSize })}
    />
  </>;

  function freeRecord(job: ExperimentImportJob): UploadWorkspaceRecord {
    const progress = job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : Math.min(99, Math.max(0, job.progress ?? 0));
    return {
      id: `free-${job.id}`,
      name: job.sourceFileName,
      meta: `${formatLabel(job.sourceFormat)} · 无模板导入 · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: [(job.status === 'COMPLETED' ? '草稿已生成，尚未发布' : job.currentStage || '等待读取'), job.errorMessage ? `失败原因：${job.errorMessage}` : null].filter(Boolean).join(' · '),
      status: job.status === 'COMPLETED' ? { label: '草稿已生成 · 尚未发布', color: 'success' } : job.status === 'FAILED' ? { label: '解析失败', color: 'error' } : { label: `处理中 ${progress}%`, color: 'processing' },
      progress,
      actions: <Space size={2}>{job.experimentId ? <Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/experiments/${job.experimentId}`)}>打开实验</Button> : null}<Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadFile(job.sourceFileId, job.sourceFileName).catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}>下载原文件</Button><Button type="link" icon={<ReloadOutlined />} disabled={!['FAILED', 'CANCELLED'].includes(job.status)} loading={retryingId === job.id} onClick={() => void retry(job)}>重试</Button><Popconfirm title="确认删除该导入记录？" description="删除后记录将从列表中移除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => deleteFree(job)}><Button type="link" danger icon={<DeleteOutlined />} disabled={ACTIVE_JOB_STATES.includes(job.status)}>删除</Button></Popconfirm></Space>,
    };
  }

  function templateRecord(job: DataJob): UploadWorkspaceRecord {
    const progress = dataParseProgress(job);
    const assembly = assemblyStatuses[job.id];
    const assemblyLinks = assembly?.links ?? [];
    const linkedExperiments = assemblyLinks.filter((item) => item.experimentId);
    const pendingCandidates = assembly?.plan.candidates.filter((item) =>
      ['NEEDS_REVIEW', 'BLOCKED', 'FAILED'].includes(item.status),
    ) ?? [];
    const pendingReasons = pendingCandidates.flatMap((item) =>
      [...item.conflicts, ...item.warnings].map((entry) => entry.message),
    );
    const linkReasons = assemblyLinks.flatMap((item) =>
      item.errorMessage || item.resolutionReason ? [item.errorMessage || item.resolutionReason || ''] : [],
    );
    const hasPendingAssembly = pendingCandidates.length > 0;
    const assemblyText = linkedExperiments.length
      ? linkedExperiments.length + ' 个实验草稿已生成，尚未发布' + (hasPendingAssembly ? '；另有 ' + pendingCandidates.length + ' 项待处理' : '')
      : hasPendingAssembly ? '待处理，尚未生成草稿' + (pendingReasons[0] ? '：' + pendingReasons[0] : '')
        : !assembly ? '导入完成，正在读取实验草稿状态'
          : assembly.plan.readyCount ? '实验草稿生成中' : '导入完成，尚未生成实验草稿';
    return {
      id: `template-${job.id}`,
      name: job.sourceFileName,
      meta: `按模板导入 · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: job.status === 'COMPLETED' ? [assemblyText, ...pendingReasons, ...linkReasons].filter(Boolean).slice(0, 3).join(' · ') : dataParseStageLabel(job),
      status: job.status === 'COMPLETED'
        ? linkedExperiments.length ? { label: '草稿已生成 · 尚未发布', color: 'success' }
          : hasPendingAssembly ? { label: '待处理 · 未生成草稿', color: 'warning' }
            : assembly?.plan.readyCount ? { label: '草稿生成中', color: 'processing' }
              : { label: '导入完成', color: 'success' }
        : job.status === 'FAILED' ? { label: '处理失败', color: 'error' }
          : { label: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? '待确认数据' : `处理中 ${progress}%`, color: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? 'warning' : 'processing' },
      progress,
      actions: <Space size={2}><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/experiments/template-imports/${job.id}`)}>确认数据</Button><Button type="link" icon={<ReloadOutlined />} disabled={!['FAILED', 'CANCELLED'].includes(job.status)} loading={retryingId === job.id} onClick={() => void retryTemplate(job)}>重试</Button><Popconfirm title="确认删除该导入记录？" description="删除后记录将从列表中移除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => deleteTemplate(job)}><Button type="link" danger icon={<DeleteOutlined />} disabled={ACTIVE_JOB_STATES.includes(job.status)}>删除</Button></Popconfirm><Button type="link" icon={<DownloadOutlined />} onClick={() => void dataApi.sourceBlob(job.sourceFileId).then((blob) => downloadBlob(blob, job.sourceFileName)).catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}>下载原文件</Button></Space>,
    };
  }

  async function retryTemplate(job: DataJob) {
    setRetryingId(job.id);
    try { await dataApi.retryExperimentJob(job.id); await load(); void message.success(`“${job.sourceFileName}”已加入重新处理队列`); }
    catch (error) { void message.error(error instanceof Error ? error.message : '重试失败'); }
    finally { setRetryingId(undefined); }
  }

  async function deleteTemplate(job: DataJob) {
    try {
      await dataApi.deleteExperimentJob(job.id);
      setAssemblyStatuses((current) => { const next = { ...current }; delete next[job.id]; return next; });
      requestedAssemblyStatuses.current.delete(job.id);
      await load();
      void message.success('导入记录已删除');
    } catch (error) { void message.error(error instanceof Error ? error.message : '删除失败'); }
  }

  async function deleteFree(job: ExperimentImportJob) {
    try { await deleteExperimentImport(job.id); await load(); void message.success('无模板导入记录已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '删除失败'); }
  }
}

function formatLabel(format: string) {
  return ({ XLSX: 'Excel', XLS: '旧版 Excel', CSV: 'CSV', DOCX: 'Word', PDF: 'PDF', IMAGE: '图片' } as Record<string, string>)[format] ?? format;
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = name; anchor.click();
  URL.revokeObjectURL(url);
}
