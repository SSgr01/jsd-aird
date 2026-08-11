import { DatabaseOutlined, DownloadOutlined, EyeOutlined, FileExcelOutlined, RightOutlined } from '@ant-design/icons';
import { App, Button, Form, Select } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { dataApi, dataTypeOptions, type DataCategory, type DataJob, type DataTemplateOption, type DataType } from '@/services/data/data-api';

const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'QUEUED', label: '上传中' },
  { key: 'PARSING', label: '解析中' },
  { key: 'WAITING_CONFIRM', label: '待审核' },
  { key: 'COMPLETED', label: '已完成' },
];

const statusView: Record<string, { label: string; color: string }> = {
  CREATED: { label: '待处理', color: 'default' },
  QUEUED: { label: '上传中', color: 'processing' },
  PARSING: { label: '解析中', color: 'blue' },
  WAITING_SHEET: { label: '待确认 Sheet', color: 'gold' },
  WAITING_MAPPING: { label: '待字段映射', color: 'gold' },
  VALIDATING: { label: '校验中', color: 'processing' },
  WAITING_CONFIRM: { label: '待审核', color: 'orange' },
  COMMITTING: { label: '提交中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  FAILED: { label: '处理失败', color: 'error' },
  CANCELLED: { label: '已取消', color: 'default' },
};

function jobRecord(job: DataJob, navigate: (path: string) => void, onPreview: (job: DataJob) => void, onDownload: (job: DataJob) => void): UploadWorkspaceRecord {
  const status = statusView[job.status] || { label: job.status, color: 'default' };
  return {
    id: job.id,
    name: job.sourceFileName,
    icon: <FileExcelOutlined />,
    meta: `${dataTypeOptions.find((item) => item.value === job.targetDataType)?.label || job.targetDataType} · 模板版本 ${job.templateVersionId}`,
    detail: `${new Date(job.createdAt).toLocaleString('zh-CN')} · ${job.currentStage || '等待处理'}`,
    status,
    progress: job.progress,
    actions: <><Button type="link" icon={<EyeOutlined />} onClick={() => onPreview(job)}>预览</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => onDownload(job)}>下载</Button><Button type="link" icon={<RightOutlined />} onClick={() => navigate(`/data/import-jobs/${job.id}`)}>查看导入任务</Button></>,
  };
}

export function DataUploadPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [target, setTarget] = useState<DataType>('MATERIAL');
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [categoryId, setCategoryId] = useState<string>();
  const [categories, setCategories] = useState<DataCategory[]>([]);
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [jobs, setJobs] = useState<{ items: DataJob[]; page: number; size: number; total: number }>({ items: [], page: 1, size: 8, total: 0 });
  const [jobStatus, setJobStatus] = useState('ALL');
  const [jobKeyword, setJobKeyword] = useState('');
  const [jobsLoading, setJobsLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  useEffect(() => {
    setTemplateVersionId(undefined);
    setCategoryId(undefined);
    void dataApi.listTemplates(target)
      .then((items) => setTemplates(items.filter((item) => item.format === 'XLSX')))
      .catch((error) => void message.error(error instanceof Error ? error.message : '模板加载失败'));
  }, [message, target]);

  useEffect(() => {
    void dataApi.listCategories().then(setCategories).catch(() => setCategories([]));
  }, []);

  const loadJobs = useCallback(async () => {
    setJobsLoading(true);
    try {
      const page = await dataApi.listJobs({
        targetDataType: target,
        status: jobStatus === 'ALL' ? undefined : jobStatus,
        keyword: jobKeyword || undefined,
        page: jobs.page,
        size: jobs.size,
      });
      setJobs({ items: page.items, page: page.page, size: page.size, total: page.total });
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '导入任务加载失败');
    } finally {
      setJobsLoading(false);
    }
  }, [jobKeyword, jobStatus, jobs.page, jobs.size, message, target]);

  useEffect(() => { void loadJobs(); }, [loadJobs]);

  useEffect(() => {
    const hasActiveJob = jobs.items.some((job) => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status));
    if (!hasActiveJob) return undefined;
    const timer = window.setInterval(() => void loadJobs(), 3000);
    return () => window.clearInterval(timer);
  }, [jobs.items, loadJobs]);

  const chosen = useMemo(() => templates.find((item) => item.versionId === templateVersionId), [templateVersionId, templates]);

  const submit = async () => {
    const file = files[0]?.originFileObj;
    if (!chosen || !file) { void message.warning('请选择已发布模板和数据文件'); return; }
    setLoading(true);
    try {
      const staged = await dataApi.stageSource(file);
      const create = async (duplicateOverride: boolean) => dataApi.createJob({ sourceFileId: staged.fileId, templateVersionId: chosen.versionId, targetDataType: target, categoryId, duplicateOverride });
      try {
        const job = await create(false);
        navigate(`/data/import-jobs/${job.id}`);
      } catch (error) {
        if (error instanceof Error && error.message.includes('历史任务')) {
          modal.confirm({
            title: '文件已成功导入过',
            content: `${error.message}。是否明确创建一次重复导入？重复导入不会覆盖历史修订。`,
            okText: '确认重复导入',
            cancelText: '取消',
            onOk: async () => {
              setLoading(true);
              try {
                const job = await create(true);
                navigate(`/data/import-jobs/${job.id}`);
              } catch (retryError) {
                void message.error(retryError instanceof Error ? retryError.message : '重复导入创建失败');
              } finally {
                setLoading(false);
              }
            },
          });
        } else throw error;
      }
      setFiles([]);
      await loadJobs();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '创建导入任务失败');
    } finally {
      setLoading(false);
    }
  };

  const fileDescriptor = (job: DataJob): FilePreviewDescriptor => ({ fileName: job.sourceFileName, load: () => dataApi.sourceBlob(job.sourceFileId) });
  const openPreview = (job: DataJob) => setPreviewFile(fileDescriptor(job));
  const downloadJob = async (job: DataJob) => {
    try { await downloadPreviewFile(fileDescriptor(job)); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };
  const records = jobs.items.map((job) => jobRecord(job, navigate, openPreview, () => { void downloadJob(job); }));

  return (
    <>
      <UploadWorkspace
      breadcrumbs={[{ title: '数据中心' }, { title: '数据上传' }]}
      title="数据上传"
      description="选择已发布的数据中心模板，上传后按 Sheet、字段和质量问题逐步确认。"
      headerActions={<Button icon={<DatabaseOutlined />} onClick={() => navigate('/data/view')}>查看正式数据</Button>}
      leftTitle="数据分类"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="数据类型" required>
          <Select value={target} options={dataTypeOptions} onChange={setTarget} />
        </Form.Item>
        <Form.Item label="导入模板" required help="仅显示已发布且适用于当前数据类型的 XLSX 模板。">
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="请选择数据中心模板"
            value={templateVersionId}
            onChange={setTemplateVersionId}
            options={templates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))}
            notFoundContent="暂无适用的已发布模板"
          />
        </Form.Item>
        <Form.Item label="归档分类" help="未选择时自动归入当前数据类型的内置分类。">
          <Select allowClear value={categoryId} onChange={setCategoryId} placeholder="选择数据分类" options={categories.filter((item) => !item.targetDataType || item.targetDataType === target).map((item) => ({ value: item.id, label: item.name }))} />
        </Form.Item>
      </Form>}
      accept=".xls,.xlsx,.csv"
      maxCount={1}
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFiles([])}
      uploadMainText="拖拽文件到此处，或点击选择文件"
      uploadHint="支持 XLS / XLSX / CSV；原文件会保留并用于后续来源追溯。"
      previewEmptyText="暂无待导入文件，点击上方区域选择文件"
      submitLabel="创建导入任务"
      submitIcon={<RightOutlined />}
      onSubmit={() => void submit()}
      submitting={loading}
      submitDisabled={!chosen}
      rightTitle="已上传数据"
      rightCount={jobs.total}
      rightFilters={statusFilters}
      activeFilter={jobStatus}
      onFilterChange={(key) => { setJobStatus(key); setJobs((current) => ({ ...current, page: 1 })); }}
      searchValue={jobKeyword}
      onSearchChange={(value) => { setJobKeyword(value); setJobs((current) => ({ ...current, page: 1 })); }}
      searchPlaceholder="搜索文件名称"
      records={records}
      recordsLoading={jobsLoading}
      pagination={{ current: jobs.page, pageSize: jobs.size, total: jobs.total }}
      onPageChange={(page, pageSize) => setJobs((current) => ({ ...current, page, size: pageSize }))}
      />
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    </>
  );
}
