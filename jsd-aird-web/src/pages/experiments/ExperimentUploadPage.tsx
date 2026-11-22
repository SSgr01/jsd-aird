import { useLatestRequest } from '@/services/query/use-request-guard';
import { usePolling } from '@/hooks/use-polling';
import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, Col, Form, Popconfirm, Radio, Row, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { generateUUID } from '@/utils/uuid';

import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { dataApi, type DataJob, type DataTemplateOption, type ExperimentSyncStatus, type ExperimentConversion } from '@/services/data/data-api';
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
import { triggerNativeDownload } from '@/services/files/file-api';
import { useAuthStore } from '@/stores/auth-store';
import { loadAllPages } from './experiment-import-history-utils';

type UnifiedJob =
  | { kind: 'FREEFORM'; job: ExperimentImportJob }
  | { kind: 'TEMPLATE'; job: DataJob }
  | { kind: 'CONVERSION'; job: ExperimentConversion & { sourceFileName: string } };

const ACTIVE_JOB_STATES = ['CREATED', 'QUEUED', 'PARSING', 'VALIDATING', 'COMMITTING'];

export function ExperimentUploadPage() {
  const { message } = App.useApp();
  const user = useAuthStore((state) => state.user);
  const can = (action: string) => user?.permissions.includes(`eln.upload.${action}`) ?? false;
  const canUpload = can('create');
  const navigate = useNavigate();
  const [mode, setMode] = useState<'TEMPLATE' | 'CONTENT_ONLY' | 'CONVERSION'>('TEMPLATE');
  const canAI = user?.permissions.includes('ai.use') ?? false;
  const [conversions, setConversions] = useState<ExperimentConversion[]>([]);
  const [startingBatch, setStartingBatch] = useState<string>();
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

  const loads = useLatestRequest('experiment-uploads');
  const load = useCallback(async ({ silent = false }: { silent?: boolean } = {}) => {
    const isCurrent = loads.begin();
    if (!silent) setLoading(true);
    try {
      const [free, allTemplateJobs, conversionJobs] = await Promise.all([
        listExperimentImports(),
        loadAllPages((page, size) => dataApi.listExperimentJobs({ page, size })),
        dataApi.conversions(),
      ]);
      if (!isCurrent()) return;
      setFreeJobs(free);
      setConversions(conversionJobs);
      setTemplateJobs(allTemplateJobs
        .filter((item) => item.importPurpose === 'EXPERIMENT_DRAFT'));
    } catch (error) {
      if (isCurrent() && !silent) void message.error(error instanceof Error ? error.message : '实验上传记录加载失败');
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [message, loads]);

  useEffect(() => {
    setTemplatesLoading(true);
    // Categories and templates only feed the upload form. A read-only visitor
    // sees the import history only, so skip the calls they are not granted.
    const catalog = canUpload
      ? [
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
      ]
      : [Promise.resolve().finally(() => setTemplatesLoading(false))];
    void Promise.all([...catalog, load()]);
  }, [canUpload, load]);

  const running = useMemo(
    () => [...freeJobs, ...templateJobs].some((job) => ACTIVE_JOB_STATES.includes(job.status)) || conversions.some(job => job.status === 'QUEUED'),
    [freeJobs, templateJobs, conversions],
  );
  usePolling(() => load({ silent: true }), running, 2000);

  const chosenTemplate = useMemo(
    () => templates.find((item) => item.versionId === templateVersionId),
    [templateVersionId, templates],
  );

  const validate: UploadProps['beforeUpload'] = (file) => {
    if (mode === 'CONVERSION' && (!/\.(xlsx|docx)$/i.test(file.name) || file.size === 0 || file.size > 8 * 1024 * 1024)) {
      void message.error('专家转换支持 XLSX / DOCX，单个文件最大 8 MiB'); return Upload.LIST_IGNORE;
    }
    const accepted = mode === 'CONTENT_ONLY' ? /\.(xlsx|xls|csv|doc|docx|pdf|jpg|jpeg|png|gif|webp|bmp|tif|tiff)$/i : /\.(xlsx|xls|csv|docx|jpg|jpeg|png|gif|webp|bmp|tif|tiff)$/i;
    if (file.size === 0 || file.size > 200 * 1024 * 1024 || !accepted.test(file.name)) {
      void message.error('实验模板导入支持 XLSX / XLS / CSV / DOCX 及常见图片格式');
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const submit = async () => {
    if (uploading || !canUpload) return;
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) { void message.warning('请先选择实验文件'); return; }
    if (mode === 'CONVERSION' && (!canAI || sourceFiles.length > 100)) { void message.warning('专家转换需要 AI 使用权限，每批最多 100 个文件'); return; }
    if (!experimentCategoryId) { void message.warning('请选择实验分类'); return; }
    if (mode !== 'CONTENT_ONLY' && !chosenTemplate) {
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
      const batchId = generateUUID();
      for (const file of sourceFiles) {
        try {
          const staged = await stageExperimentFile(file);
          if (mode === 'CONVERSION') {
            await dataApi.createConversion({ batchId, sourceFileId: staged.fileId, templateVersionId: chosenTemplate!.versionId, categoryId: experimentCategoryId });
          } else if (mode === 'CONTENT_ONLY') {
            const ext = file.name.split('.').pop()?.toLowerCase();
            const format = ext === 'xls' ? 'XLS' : ext === 'xlsx' ? 'XLSX' : ext === 'csv' ? 'CSV' : ext === 'pdf' ? 'PDF' : ext === 'doc' || ext === 'docx' ? 'DOCX' : 'IMAGE';
            const relation = projectRelations[0];
            await importExperimentFile({fileId:staged.fileId,fileName:staged.originalName,sha256:staged.sha256,format,
              processingMode:'CONTENT_ONLY',title:file.name.replace(/\.[^.]+$/,''),
              ownerName:user?.displayName?.trim() || user?.username || '',
              experimentDate:new Date().toLocaleDateString('sv-SE'),
              categoryId:experimentCategoryId,categoryName:categories.find(c=>c.id===experimentCategoryId)?.name,
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
      if (mode === 'CONVERSION' && templateJobCount) {
        await startBatch(batchId);
        void message.success(`${templateJobCount} 个原件已保存；专家转换结果将进入待人工核对${failed ? `，${failed} 个上传失败` : ''}`);
        return;
      }
      if (!failed) {
        const parts: string[] = [];
        if (templateJobCount) parts.push(`${templateJobCount} 个${mode === 'CONTENT_ONLY' ? '无模板导入' : '按模板导入'}任务`);
        void message.success(`已提交 ${parts.join('、') || '0 个文件'}`);
      } else void message.warning(`${sourceFiles.length - failed} 个文件已提交，${failed} 个失败`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验上传失败，请重试');
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
    ...templateJobs.filter(job => !conversions.some(item => item.importJobId === job.id)).map((job) => ({ kind: 'TEMPLATE' as const, job })),
    ...conversions.map((job) => ({ kind: 'CONVERSION' as const, job: { ...job, sourceFileName: job.sourceName } })),
  ].filter((item) => {
    const convertedJob = item.kind === 'CONVERSION' ? templateJobs.find(job => job.id === item.job.importJobId) : undefined;
    const status = item.kind === 'CONVERSION'
      ? convertedJob?.status === 'COMPLETED' ? 'COMPLETED' : item.job.status === 'FAILED' || convertedJob?.status === 'FAILED' ? 'FAILED' : item.job.status === 'WAITING_REVIEW' ? 'WAITING_MAPPING' : 'PARSING'
      : item.job.status === 'WAITING_CONFIRM' ? 'WAITING_MAPPING' : item.job.status;
    return (filter === 'ALL' || status === filter)
      && (!keyword || item.job.sourceFileName.toLowerCase().includes(keyword.toLowerCase()));
  }).sort((a, b) => new Date(b.job.createdAt).getTime() - new Date(a.job.createdAt).getTime()), [filter, freeJobs, keyword, templateJobs, conversions]);

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
  const assemblyReads = useLatestRequest(assemblyPollingKey);
  usePolling(async () => {
    const isCurrent = assemblyReads.begin();
    const ids = assemblyPollingKey.split('|');
    const items = await Promise.all(ids.map(async (id) => [id, await dataApi.experimentSyncStatus(id)] as const));
    if (isCurrent()) setAssemblyStatuses((current) => ({ ...current, ...Object.fromEntries(items) }));
  }, Boolean(assemblyPollingKey), 1800, assemblyPollingKey);

  const records: UploadWorkspaceRecord[] = visibleJobs
    .map((item) => item.kind === 'TEMPLATE' ? templateRecord(item.job) : item.kind === 'CONVERSION' ? conversionRecord(item.job) : freeRecord(item.job));

  return <>
    <UploadWorkspace
      uploadDisabled={!canUpload}
      uploadReadOnly={!canUpload}
      showSubmit={canUpload}
      breadcrumbs={[{ title: '电子实验记录本' }, { title: '实验数据导入' }]}
      title="文件导入"
      description="原始实验可批量交给专家转换，结果保留为待人工核对。无模板导入的草稿负责人和日期默认使用当前用户及今天，可在编辑页修改。"
      headerActions={<Space>
        <Button type="primary" onClick={() => navigate('/experiments')}>实验列表</Button>
      </Space>}
      leftTitle="基础信息"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="导入方式"><Radio.Group value={mode} onChange={(e) => { setMode(e.target.value); setFiles([]); }} options={[{value:'TEMPLATE',label:'按模板导入'},{value:'CONTENT_ONLY',label:'无模板导入'},...(canAI ? [{value:'CONVERSION',label:'专家转换'}] : [])]} optionType="button" /></Form.Item>

        <Row gutter={12}>
          <Col span={12}>
            <Form.Item label="实验分类" required>
              <Select value={experimentCategoryId} onChange={setExperimentCategoryId} options={categories.map((item) => ({ value: item.id, label: item.name }))} placeholder="请选择实验分类" />
            </Form.Item>
          </Col>
        </Row>
        {mode !== 'CONTENT_ONLY' && <>
          <Form.Item label="实验数据模板" required>
            <Select showSearch optionFilterProp="label" loading={templatesLoading} value={templateVersionId} onChange={setTemplateVersionId} options={templates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))} placeholder="请选择实验数据模板" notFoundContent={templatesLoading ? '加载中' : '没有匹配的实验数据模板'} />
          </Form.Item>
        </>}
        {mode === 'CONVERSION' ? <p>转换后在核对工作台关联项目。机器校验通过仍需人工确认。</p> : <Form.Item label="关联项目 / 阶段 / 任务"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} multiple={false} /></Form.Item>}
      </Form>}
      accept={mode === 'CONVERSION' ? '.xlsx,.docx' : mode === 'CONTENT_ONLY' ? '.doc,.docx,.pdf,.xls,.xlsx,.csv,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff' : '.docx,.xls,.xlsx,.csv,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff'}
      beforeUpload={validate}
      multiple
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFiles([])}
      uploadHint={mode === 'CONVERSION' ? '支持 XLSX / DOCX 原件，每批最多100份，单份最大8 MiB。原件保留，转换后逐份核对。' : mode === 'CONTENT_ONLY' ? '支持 Excel / Word / PDF / 图片，可批量上传，单个文件最大200MB。' : '支持 XLSX / DOCX / 图片，可批量上传，单个文件最大200MB。'}
      uploadMainText="拖拽文件到此处或点击选择文件"
      submitLabel={mode === 'CONVERSION' ? '上传并交给专家转换' : '开始上传'}
      submitIcon={<CloudUploadOutlined />}
      onSubmit={() => void submit()}
      submitting={uploading}
      submitDisabled={!experimentCategoryId || (mode === 'TEMPLATE' && !chosenTemplate)}
      rightTitle="导入历史"
      rightCount={jobs.length}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PARSING', label: '处理中' }, { key: 'WAITING_MAPPING', label: '待核对' }, { key: 'COMPLETED', label: '导入完成' }, { key: 'FAILED', label: '失败' }]}
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
      actions: <Space size={2}>{job.experimentId ? <Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/experiments/${job.experimentId}`)}>打开实验</Button> : null}{can('view') && <Button type="link" icon={<DownloadOutlined />} onClick={() => triggerNativeDownload(`/api/v1/experiment-imports/${job.id}/source`, job.sourceFileName)}>下载原文件</Button>}{(canUpload || can('retry')) && <Button type="link" icon={<ReloadOutlined />} disabled={!['FAILED', 'CANCELLED'].includes(job.status)} loading={retryingId === job.id} onClick={() => void retry(job)}>重试</Button>}{can('delete') && <Popconfirm title="确认删除该导入记录？" description="删除后记录将从列表中移除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => deleteFree(job)}><Button type="link" danger icon={<DeleteOutlined />} disabled={ACTIVE_JOB_STATES.includes(job.status)}>删除</Button></Popconfirm>}</Space>,
    };
  }

  async function startBatch(batchId: string, retryFailed = false) {
    setStartingBatch(batchId);
    try {
      const result = await dataApi.startConversions(batchId, retryFailed);
      const failed = result.tasks.filter(item => !item.accepted).length;
      if (failed) void message.warning(`${failed} 个转换任务暂未启动，可点击“启动转换”继续`);
    } catch { void message.warning('原件已保存，专家暂未启动；请稍后点击“启动转换”。'); }
    finally { setStartingBatch(undefined); }
  }

  function conversionRecord(job: ExperimentConversion): UploadWorkspaceRecord {
    const linked = templateJobs.find(item => item.id === job.importJobId);
    const reviewed = linked?.status === 'COMPLETED';
    const failed = job.status === 'FAILED' || linked?.status === 'FAILED';
    return {
      id: `conversion-${job.id}`, name: job.sourceName,
      meta: `专家转换 · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: job.errorMessage || linked?.errorMessage || (reviewed ? '导入已核对；实验草稿仍需发布' : job.importJobId
        ? `未落位 ${job.report?.unplaced?.length ?? 0} 项 · 源冲突 ${job.report?.source_conflicts?.length ?? 0} 项 · 请对照原件核对`
        : '原件已保存，等待专家处理；模型服务不可用时可稍后继续'),
      status: { label: reviewed ? '导入已核对' : failed ? '需处理' : job.importJobId ? '待人工核对' : '待转换 / 处理中', color: reviewed ? 'success' : failed ? 'error' : job.importJobId ? 'warning' : 'processing' },
      actions: <Space size={2} wrap>
        {job.importJobId && (canUpload || can('update')) && <Button type="link" onClick={() => navigate(`/experiments/template-imports/${job.importJobId}`)}>核对结果</Button>}
        {canAI && job.status === 'QUEUED' && <Button type="link" loading={startingBatch === job.batchId} onClick={() => void startBatch(job.batchId)}>启动转换</Button>}
        {canAI && job.status === 'FAILED' && <Button type="link" loading={startingBatch === job.batchId} onClick={() => void startBatch(job.batchId, true)}>重试转换</Button>}
        {canAI && <Button type="link" href={`/agent/?conversion=${job.id}`}>查看专家处理</Button>}
        {canUpload && <Button type="link" onClick={() => triggerNativeDownload(`/api/v1/data/experiment-conversions/${job.id}/source`, job.sourceName)}>下载原件</Button>}
      </Space>,
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
          : { label: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? '待核对数据' : `处理中 ${progress}%`, color: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? 'warning' : 'processing' },
      progress,
      actions: <Space size={2}>{(canUpload || can('update')) && <Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/experiments/template-imports/${job.id}`)}>确认数据</Button>}{(canUpload || can('retry')) && <Button type="link" icon={<ReloadOutlined />} disabled={!['FAILED', 'CANCELLED'].includes(job.status)} loading={retryingId === job.id} onClick={() => void retryTemplate(job)}>重试</Button>}{can('delete') && <Popconfirm title="确认删除该导入记录？" description="删除后记录将从列表中移除。" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => deleteTemplate(job)}><Button type="link" danger icon={<DeleteOutlined />} disabled={ACTIVE_JOB_STATES.includes(job.status)}>删除</Button></Popconfirm>}{can('view') && <Button type="link" icon={<DownloadOutlined />} onClick={() => triggerNativeDownload(`/api/v1/data/experiment-import-jobs/${job.id}/source`, job.sourceFileName)}>下载原文件</Button>}</Space>,
    };
  }

  async function retryTemplate(job: DataJob) {
    setRetryingId(job.id);
    try { await dataApi.retryExperimentJob(job.id,job.revision); await load(); void message.success(`“${job.sourceFileName}”已加入重新处理队列`); }
    catch (error) { void message.error(error instanceof Error ? error.message : '重试失败'); }
    finally { setRetryingId(undefined); }
  }

  async function deleteTemplate(job: DataJob) {
    try {
      await dataApi.deleteExperimentJob(job.id,job.revision);
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
