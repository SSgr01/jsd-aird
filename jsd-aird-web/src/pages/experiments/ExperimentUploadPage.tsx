import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  PictureOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { Alert, App, Button, Form, Modal, Radio, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { dataApi, type DataJob, type DataTemplateOption } from '@/services/data/data-api';
import { dataParseProgress, dataParseStageLabel } from '@/services/data/data-progress';
import {
  deleteExperimentImport,
  importExperimentFile,
  listCategories,
  listExperimentImports,
  retryExperimentImport,
  stageExperimentFile,
  type Category,
  type ExperimentImportJob,
} from '@/services/experiments/experiment-api';
import { downloadFile } from '@/services/files/file-api';

const visibilityOptions = [
  { value: 'ALL' as const, label: '全员可见' },
  { value: 'QUALITY' as const, label: '品管部可见' },
  { value: 'PROJECT' as const, label: '项目组可见' },
];
const visibilityText = (value?: 'ALL' | 'QUALITY' | 'PROJECT') =>
  visibilityOptions.find((item) => item.value === value)?.label || '全员可见';

type UploadMode = 'FREEFORM' | 'TEMPLATE';
type UnifiedJob =
  | { kind: 'FREEFORM'; job: ExperimentImportJob }
  | { kind: 'TEMPLATE'; job: DataJob };

export function ExperimentUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [mode, setMode] = useState<UploadMode>('FREEFORM');
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [freeJobs, setFreeJobs] = useState<ExperimentImportJob[]>([]);
  const [templateJobs, setTemplateJobs] = useState<DataJob[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [location, setLocation] = useState<string>();
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [experimentCategoryId, setExperimentCategoryId] = useState<string>();
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [visibility, setVisibility] = useState<'ALL' | 'QUALITY' | 'PROJECT'>('ALL');
  const [uploading, setUploading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState({ current: 1, pageSize: 8 });
  const [ocrOpen, setOcrOpen] = useState(false);
  const [ocrFiles, setOcrFiles] = useState<UploadFile[]>([]);
  const [ocrSubmitting, setOcrSubmitting] = useState(false);
  const [retryingId, setRetryingId] = useState<string>();

  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      const [free, templatesPage] = await Promise.all([
        listExperimentImports(),
        dataApi.listExperimentJobs({ page: 1, size: 100 }),
      ]);
      setFreeJobs(free);
      setTemplateJobs(templatesPage.items.filter((item) => item.importPurpose === 'EXPERIMENT_DRAFT'));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验上传记录加载失败');
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void Promise.all([
      listCategories().then((items) => {
        setCategories(items);
        setLocation((current) => current ?? items[0]?.name);
        setExperimentCategoryId((current) => current ?? items[0]?.id);
      }).catch((error) => void message.error(error instanceof Error ? error.message : '实验分类加载失败')),
      dataApi.listTemplates().then((items) => {
        const experimentTemplates = items.filter((item) => item.format === 'XLSX' && item.experimentImportReady);
        setTemplates(experimentTemplates);
        setTemplateVersionId((current) => current ?? experimentTemplates[0]?.versionId);
      }).catch((error) => void message.error(error instanceof Error ? error.message : '实验模板加载失败')),
      load(),
    ]);
  }, [load]);

  const running = useMemo(
    () => [...freeJobs, ...templateJobs].some((job) => ['QUEUED', 'PARSING', 'VALIDATING', 'WAITING_MAPPING', 'WAITING_CONFIRM', 'COMMITTING'].includes(job.status)),
    [freeJobs, templateJobs],
  );
  useEffect(() => {
    if (!running) return undefined;
    const timer = window.setInterval(() => void load(false), 2000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  const chosenTemplate = useMemo(
    () => templates.find((item) => item.versionId === templateVersionId),
    [templateVersionId, templates],
  );

  const validate: UploadProps['beforeUpload'] = (file) => {
    const accepted = mode === 'TEMPLATE'
      ? /\.(xlsx|xls|csv)$/i
      : /\.(xlsx|xls|csv|doc|docx|pdf|jpg|jpeg|png|tif|tiff)$/i;
    if (file.size === 0 || !accepted.test(file.name)) {
      void message.error(mode === 'TEMPLATE'
        ? '按模板导入仅支持 XLSX / XLS / CSV 文件'
        : '自由上传支持 XLSX / XLS / CSV / DOCX / PDF / JPG / PNG / TIF 文件');
      return Upload.LIST_IGNORE;
    }
    if (files.some((item) => item.name.toLowerCase() === file.name.toLowerCase() && item.size === file.size)) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const submit = async () => {
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) { void message.warning('请先选择实验文件'); return; }
    if (mode === 'TEMPLATE' && !chosenTemplate) { void message.warning('请选择已发布的实验数据模板'); return; }
    if (mode === 'TEMPLATE' && !experimentCategoryId) { void message.warning('请选择实验分类'); return; }
    const selectedTemplateVersionId = chosenTemplate?.versionId;
    if (mode === 'TEMPLATE' && !selectedTemplateVersionId) { void message.warning('请选择已发布的实验数据模板'); return; }
    setUploading(true);
    let failed = 0;
    try {
      for (const file of sourceFiles) {
        try {
          if (mode === 'TEMPLATE') {
            // Keep the source owned by the experiment module while the
            // confirmation workspace is shared with Data Center.
            const staged = await stageExperimentFile(file);
            const job = await dataApi.createExperimentJob({
              sourceFileId: staged.fileId,
              templateVersionId: selectedTemplateVersionId!,
              importPurpose: 'EXPERIMENT_DRAFT',
              targetExperimentCategoryId: experimentCategoryId!,
              projectRelations,
            });
            navigate(`/experiments/template-imports/${job.id}`);
          } else {
            const staged = await stageExperimentFile(file);
            const category = categories.find((item) => item.name === location);
            const job = await importExperimentFile({
              fileId: staged.fileId,
              fileName: staged.originalName,
              sha256: staged.sha256,
              format: formatForFile(file.name),
              categoryId: category?.id,
              categoryName: category?.name,
              ...(projectRelations[0] ? {
                projectId: projectRelations[0].projectId,
                stageId: projectRelations[0].stageId,
                taskId: projectRelations[0].taskId,
              } : {}),
              experimentDate: new Date().toISOString().slice(0, 10),
              visibility,
            });
            if (sourceFiles.length === 1) navigate(`/experiments/imports/${job.jobId}`);
          }
        } catch (error) {
          failed += 1;
          void message.error(error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`);
        }
      }
      setFiles([]);
      await load();
      if (!failed) void message.success(mode === 'TEMPLATE' ? '已创建模板导入任务' : `已提交 ${sourceFiles.length} 个自由上传文件`);
      else void message.warning(`${sourceFiles.length - failed} 个文件已提交，${failed} 个失败`);
    } finally {
      setUploading(false);
    }
  };

  const submitOcr = async () => {
    const sourceFiles = ocrFiles.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) return;
    setOcrSubmitting(true);
    let failed = 0;
    try {
      const category = categories.find((item) => item.name === location);
      for (const file of sourceFiles) {
        try {
          const staged = await stageExperimentFile(file);
          const job = await importExperimentFile({
            fileId: staged.fileId, fileName: staged.originalName, sha256: staged.sha256,
            format: formatForFile(file.name), categoryId: category?.id, categoryName: category?.name,
            experimentDate: new Date().toISOString().slice(0, 10), visibility,
          });
          if (sourceFiles.length === 1) navigate(`/experiments/imports/${job.jobId}`);
        } catch { failed += 1; }
      }
      await load();
      if (failed) void message.warning(`${sourceFiles.length - failed} 个自由上传任务已提交，${failed} 个失败`);
      else void message.success(`已提交 ${sourceFiles.length} 个自由上传任务`);
      setOcrFiles([]); setOcrOpen(false);
    } finally { setOcrSubmitting(false); }
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

  const records: UploadWorkspaceRecord[] = jobs
    .slice((page.current - 1) * page.pageSize, page.current * page.pageSize)
    .map((item) => item.kind === 'TEMPLATE' ? templateRecord(item.job) : freeRecord(item.job));

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: '电子实验记录本' }, { title: '实验上传' }]}
      title="实验上传"
      description="上传 Word / Excel 或实验记录图片；按模板导入时复用数据中心字段映射工作台"
      headerActions={<Space>
        {mode === 'FREEFORM' ? <Button icon={<PictureOutlined />} onClick={() => { setOcrFiles([]); setOcrOpen(true); }}>拍照录入</Button> : null}
        <Button type="primary" onClick={() => navigate('/experiments')}>实验列表</Button>
      </Space>}
      leftTitle="录入方式"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="录入方式" required>
          <Radio.Group value={mode} onChange={(event) => { setMode(event.target.value as UploadMode); setFiles([]); }} optionType="button" buttonStyle="solid" options={[{ value: 'FREEFORM', label: '自由上传' }, { value: 'TEMPLATE', label: '按模板导入' }]} />
        </Form.Item>
        {mode === 'TEMPLATE' ? <>
          <Form.Item label="实验数据模板" required>
            <Select showSearch optionFilterProp="label" value={templateVersionId} onChange={setTemplateVersionId} options={templates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))} placeholder="请选择实验数据模板" notFoundContent="暂无已发布实验数据模板" />
          </Form.Item>
          {chosenTemplate ? <Alert showIcon type="info" message="模板导入确认" /> : null}
        </> : <>
          <Form.Item label="关联项目 / 阶段 / 任务"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} multiple={false} /></Form.Item>
          <Form.Item label="权限可见"><Select value={visibility} onChange={setVisibility} options={visibilityOptions} /></Form.Item>
        </>}
      </Form>}
      accept={mode === 'TEMPLATE' ? '.xls,.xlsx,.csv' : '.xlsx,.xls,.csv,.doc,.docx,.pdf,.jpg,.jpeg,.png,.gif,.webp,.bmp,.tif,.tiff'}
      beforeUpload={validate}
      multiple
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFiles([])}
      uploadHint={mode === 'TEMPLATE' ? '支持 XLS / XLSX / CSV' : '支持 Excel / Word / PDF / 图片'}
      uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
      submitLabel="开始上传"
      submitIcon={<CloudUploadOutlined />}
      onSubmit={() => void submit()}
      submitting={uploading}
      submitDisabled={mode === 'TEMPLATE' ? !chosenTemplate || !experimentCategoryId : false}
      rightTitle="已上传文件"
      rightCount={jobs.length}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PARSING', label: '处理中' }, { key: 'WAITING_MAPPING', label: '待确认' }, { key: 'COMPLETED', label: '已完成' }, { key: 'FAILED', label: '失败' }]}
      activeFilter={filter}
      onFilterChange={(value) => { setFilter(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword}
      onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }}
      records={records}
      recordsLoading={loading}
      pagination={{ current: page.current, pageSize: page.pageSize, total: jobs.length }}
      onPageChange={(current, pageSize) => setPage({ current, pageSize })}
    />
    <ModalShell open={ocrOpen} onClose={() => setOcrOpen(false)} files={ocrFiles} setFiles={setOcrFiles} submitting={ocrSubmitting} onSubmit={() => void submitOcr()} />
  </>;

  function freeRecord(job: ExperimentImportJob): UploadWorkspaceRecord {
    const progress = job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : Math.min(99, Math.max(0, job.progress ?? 0));
    return {
      id: `free-${job.id}`,
      name: job.sourceFileName,
      meta: `${formatLabel(job.sourceFormat)} · 自由上传 · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: [job.currentStage || (job.status === 'COMPLETED' ? '已完成' : '等待解析'), `来源归属：实验记录本 · ${visibilityText(job.visibility)}`, job.errorMessage ? `失败原因：${job.errorMessage}` : null].filter(Boolean).join(' · '),
      status: job.status === 'COMPLETED' ? { label: '已创建草稿', color: 'success' } : job.status === 'FAILED' ? { label: '解析失败', color: 'error' } : { label: `处理中 ${progress}%`, color: 'processing' },
      progress,
      actions: <Space size={2}><Button type="link" icon={<EyeOutlined />} onClick={() => job.experimentId ? navigate(`/experiments/${job.experimentId}`) : navigate(`/experiments/imports/${job.id}`)}>{job.experimentId ? '打开实验' : '查看状态'}</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadFile(job.sourceFileId, job.sourceFileName).catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}>下载原文件</Button><Button type="link" icon={<ReloadOutlined />} loading={retryingId === job.id} onClick={() => void retry(job)}>重试</Button><Button type="link" danger icon={<DeleteOutlined />} disabled={!['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)} onClick={() => void deleteFree(job)}>删除</Button></Space>,
    };
  }

  function templateRecord(job: DataJob): UploadWorkspaceRecord {
    const progress = dataParseProgress(job);
    return {
      id: `template-${job.id}`,
      name: job.sourceFileName,
      meta: `按模板导入 · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: dataParseStageLabel(job),
      status: job.status === 'COMPLETED' ? { label: '已完成', color: 'success' } : job.status === 'FAILED' ? { label: '处理失败', color: 'error' } : { label: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? '待确认映射' : `处理中 ${progress}%`, color: job.status === 'WAITING_MAPPING' || job.status === 'WAITING_CONFIRM' ? 'warning' : 'processing' },
      progress,
      actions: <Space size={2}><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/experiments/template-imports/${job.id}`)}>查看映射</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => void dataApi.sourceBlob(job.sourceFileId).then((blob) => downloadBlob(blob, job.sourceFileName)).catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}>下载原文件</Button></Space>,
    };
  }

  async function deleteFree(job: ExperimentImportJob) {
    try { await deleteExperimentImport(job.id); await load(); void message.success('自由上传记录已删除'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '删除失败'); }
  }
}

function ModalShell({ open, onClose, files, setFiles, submitting, onSubmit }: { open: boolean; onClose: () => void; files: UploadFile[]; setFiles: (files: UploadFile[]) => void; submitting: boolean; onSubmit: () => void }) {
  return <Modal title="拍照 / OCR录入" open={open} onCancel={onClose} footer={null} width={620}>
    <Upload.Dragger accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff" multiple fileList={files} showUploadList beforeUpload={(file) => { if (!file.size || !/\.(pdf|jpg|jpeg|png|tif|tiff)$/i.test(file.name)) return Upload.LIST_IGNORE; return false; }} onChange={({ fileList }) => setFiles(fileList)}><p><PictureOutlined /> 点击或拖拽照片 / PDF 到此处</p></Upload.Dragger>
    <Space style={{ marginTop: 16, width: '100%', justifyContent: 'flex-end' }}><Button onClick={onClose}>取消</Button><Button type="primary" disabled={!files.length} loading={submitting} onClick={onSubmit}>开始上传</Button></Space>
  </Modal>;
}

function formatForFile(name: string) {
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'xlsx') return 'XLSX';
  if (extension === 'xls') return 'XLS';
  if (extension === 'csv') return 'CSV';
  if (extension === 'doc' || extension === 'docx') return 'DOCX';
  if (extension === 'pdf') return 'PDF';
  return 'IMAGE';
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
