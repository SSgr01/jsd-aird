import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileExcelOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { templateApi } from '@/services/templates/template-api';
import type { TemplateListItem } from '@/features/template-workspace/types';
import { downloadFile, stageFile } from '@/services/files/file-api';
import {
  createQuotationImport,
  deleteQuotationImport,
  listQuotationImports,
  retryQuotationImport,
  type QuotationImportJob,
  type QuotationSourceFormat,
} from '@/services/quotations/quotation-api';

const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'PARSING', label: '解析中' },
  { key: 'COMPLETED', label: '已解析' },
  { key: 'FAILED', label: '失败' },
];

const visibilityOptions: Array<{ value: 'ALL' | 'RND' | 'PROJECT'; label: string }> = [
  { value: 'ALL', label: '全员可见' },
  { value: 'RND', label: '研发部可见' },
  { value: 'PROJECT', label: '项目组可见' },
];

const stageLabels: Record<string, string> = {
  PREPARING: '准备解析',
  LOADING_FILE: '读取文件',
  PARSING_WORKBOOK: '解析报价表',
  BUILDING_RECORD: '生成报价记录',
  PERSISTING_RESULT: '保存结果',
  COMPLETED: '解析完成',
  FAILED: '解析失败',
};

const formatOf = (fileName: string): QuotationSourceFormat => {
  if (/\.xls$/i.test(fileName)) return 'XLS';
  if (/\.csv$/i.test(fileName)) return 'CSV';
  return 'XLSX';
};

const stageText = (job: QuotationImportJob) => {
  if (job.status === 'FAILED') return '解析失败';
  if (job.status === 'COMPLETED') return '解析完成';
  return (job.currentStage && stageLabels[job.currentStage]) || '等待解析';
};

export function QuotationUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canUpload = usePermission('quotation.create');
  const canDelete = usePermission('quotation.delete');
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [templates, setTemplates] = useState<TemplateListItem[]>([]);
  const [templatesLoading, setTemplatesLoading] = useState(true);
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [visibility, setVisibility] = useState<'ALL' | 'RND' | 'PROJECT'>('ALL');
  const [jobs, setJobs] = useState<QuotationImportJob[]>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string>();
  const [deletingId, setDeletingId] = useState<string>();
  const [retryingId, setRetryingId] = useState<string>();

  const loadJobs = useCallback(
    async ({ silent = false }: { silent?: boolean } = {}) => {
      if (!silent) setLoading(true);
      try {
        const all = await listQuotationImports();
        const matched = all.filter((job) => {
          const keywordMatched =
            !keyword ||
            job.sourceFileName.toLowerCase().includes(keyword.toLowerCase()) ||
            (job.customerName ?? '').includes(keyword) ||
            (job.quotationNo ?? '').includes(keyword);
          const statusMatched = status === 'ALL' || job.status === status;
          return keywordMatched && statusMatched;
        });
        const start = (page.current - 1) * page.pageSize;
        setJobs(matched.slice(start, start + page.pageSize));
        setPage((current) => ({ ...current, total: matched.length }));
      } catch (error) {
        if (!silent) {
          void message.error(error instanceof Error ? error.message : '报价上传记录加载失败');
        }
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [keyword, message, page.current, page.pageSize, status],
  );

  useEffect(() => {
    void loadJobs();
  }, [loadJobs]);

  useEffect(() => {
    void templateApi.list({ status: 'PUBLISHED', page: 1, size: 100 })
      .then((result) => setTemplates(result.items.filter((item) => item.format === 'XLSX')))
      .catch((error) => void message.error(error instanceof Error ? error.message : '识别模板加载失败'))
      .finally(() => setTemplatesLoading(false));
  }, [message]);

  useEffect(() => {
    if (!jobs.some((job) => job.status === 'PARSING')) return;
    const timer = window.setInterval(() => void loadJobs({ silent: true }), 2000);
    return () => window.clearInterval(timer);
  }, [jobs, loadJobs]);

  const validateFile: UploadProps['beforeUpload'] = (file) => {
    if (!/\.(xlsx?|csv)$/i.test(file.name) || file.size === 0) {
      void message.error('报价记录仅支持 XLSX / XLS / CSV 文件，且文件不能为空');
      return Upload.LIST_IGNORE;
    }
    if (file.size > 200 * 1024 * 1024) {
      void message.error('单个报价文件不能超过 200MB');
      return Upload.LIST_IGNORE;
    }
    const signature = `${file.name.toLowerCase()}|${file.size}|${file.lastModified}`;
    if (
      files.some((item) => {
        const existing = item.originFileObj;
        return (
          existing &&
          `${existing.name.toLowerCase()}|${existing.size}|${existing.lastModified}` === signature
        );
      })
    ) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const submit = async () => {
    if (!templateVersionId) {
      void message.warning('请先选择识别模板');
      return;
    }
    if (visibility === 'PROJECT' && !projectRelations.some((item) => item.projectId)) {
      void message.warning('项目组可见时请先关联项目');
      return;
    }
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) {
      void message.warning('请先选择报价记录文件');
      return;
    }
    setUploading(true);
    const relation = projectRelations.find((item) => item.projectId) ?? {};
    let failed = 0;
    for (const file of sourceFiles) {
      try {
        const staged = await stageFile(file, 'QUOTATION_SOURCE');
        await createQuotationImport({
          fileId: staged.fileId,
          fileName: staged.originalName || file.name,
          sha256: staged.sha256,
          format: formatOf(file.name),
          templateVersionId,
          ...relation,
          visibility,
        });
      } catch (error) {
        failed += 1;
        void message.error(
          error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`,
        );
      }
    }
    setUploading(false);
    setFiles([]);
    setPage((current) => ({ ...current, current: 1 }));
    await loadJobs();
    if (failed) {
      void message.warning(`${sourceFiles.length - failed} 个文件已上传，${failed} 个文件上传失败`);
    } else {
      void message.success(`已上传 ${sourceFiles.length} 个报价文件，正在后台解析`);
    }
  };

  const retry = async (job: QuotationImportJob) => {
    setRetryingId(job.id);
    try {
      await retryQuotationImport(job.id);
      void message.success(`“${job.sourceFileName}”已重新提交解析`);
      await loadJobs();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重新解析失败');
    } finally {
      setRetryingId(undefined);
    }
  };

  const removeJob = (job: QuotationImportJob) => {
    Modal.confirm({
      title: '删除上传记录？',
      content:
        job.status === 'PARSING'
          ? `将取消“${job.sourceFileName}”的解析任务并删除上传记录，原始文件不会被物理删除。`
          : `将删除“${job.sourceFileName}”的上传记录，原始文件与已生成的报价记录不会被删除。`,
      okText: job.status === 'PARSING' ? '取消解析并删除' : '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeletingId(job.id);
        try {
          await deleteQuotationImport(job.id);
          message.success('上传记录已删除');
          await loadJobs();
        } catch (error) {
          message.error(error instanceof Error ? error.message : '上传记录删除失败');
        } finally {
          setDeletingId(undefined);
        }
      },
    });
  };

  const records: UploadWorkspaceRecord[] = jobs.map((job) => ({
    id: job.id,
    name: job.sourceFileName,
    icon: <FileExcelOutlined />,
    meta:
      [job.quotationNo, job.customerName, job.itemCount ? `${job.itemCount} 条明细` : undefined]
        .filter(Boolean)
        .join(' · ') || '报价记录文件',
    detail: `关联项目：${[job.projectName, job.stageName, job.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityOptions.find((item) => item.value === job.visibility)?.label || '全员可见'} · ${stageText(job)} · 已完成 ${job.status === 'COMPLETED' ? 100 : (job.progress ?? 0)}%${
      job.errorMessage ? ` · 失败原因：${job.errorMessage}` : ''
    }`,
    status:
      job.status === 'FAILED'
        ? { label: '解析失败', color: 'error' }
        : job.status === 'COMPLETED'
          ? { label: '已解析', color: 'success' }
          : {
              label: `${stageText(job)}${job.progress ? ` ${job.progress}%` : ''}`,
              color: 'processing',
            },
    progress: job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : (job.progress ?? 0),
    actions: (
      <Space size={2}>
        <Button
          type="link"
          icon={<EyeOutlined />}
          disabled={job.status !== 'COMPLETED' || !job.quotationId}
          onClick={() => navigate(`/quotations/${job.quotationId}`, {
            state: { returnTo: '/quotations/upload' },
          })}
        >
          查看
        </Button>
        <Button
          type="link"
          icon={<DownloadOutlined />}
          loading={downloadingId === job.id}
          onClick={() => {
            setDownloadingId(job.id);
            void downloadFile(job.sourceFileId, job.sourceFileName)
              .then(() => message.success('文件下载已开始'))
              .catch((error) =>
                message.error(error instanceof Error ? error.message : '文件下载失败'),
              )
              .finally(() => setDownloadingId(undefined));
          }}
        >
          下载
        </Button>
        {canUpload && (
          <Button
            type="link"
            icon={<ReloadOutlined />}
            loading={retryingId === job.id}
            onClick={() => void retry(job)}
          >
            重试
          </Button>
        )}
        {canDelete && (
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            loading={deletingId === job.id}
            disabled={Boolean(deletingId)}
            onClick={() => removeJob(job)}
          >
            删除
          </Button>
        )}
      </Space>
    ),
  }));

  return (
    <UploadWorkspace
      breadcrumbs={[{ title: '报价记录' }, { title: '报价上传' }]}
      title="报价上传"
      headerActions={
        <Button type="primary" onClick={() => navigate('/quotations/list')}>
          报价记录列表
        </Button>
      }
      leftTitle="上传设置"
      classification={
        <Form layout="vertical" component={false}>
          <Form.Item
            label="识别模板"
            required
            validateStatus={templates.length === 0 && !templatesLoading ? 'error' : undefined}
            help={templates.length === 0 && !templatesLoading
              ? '暂无已发布的 Excel 模板，请先在模板中心发布报价模板'
              : undefined}
          >
            <Select
              showSearch
              optionFilterProp="label"
              loading={templatesLoading}
              value={templateVersionId}
              onChange={setTemplateVersionId}
              options={templates.map((item) => ({
                value: item.versionId,
                label: `${item.name} · ${item.templateCode} · V${item.versionNo}`,
              }))}
              placeholder="请选择已发布的 Excel 报价模板"
              notFoundContent="暂无已发布的 Excel 模板"
            />
          </Form.Item>
          <Form.Item label="关联项目 / 阶段 / 任务">
            <ProjectRelationPicker
              value={projectRelations}
              onChange={setProjectRelations}
              multiple={false}
            />
          </Form.Item>
          <Form.Item label="权限可见">
            <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
          </Form.Item>
        </Form>
      }
      accept=".xlsx,.xls,.csv"
      uploadDisabled={!canUpload}
      beforeUpload={validateFile}
      multiple
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) =>
        setFiles((current) => current.filter((item) => item.uid !== file.uid))
      }
      onClearFiles={() => setFiles([])}
      uploadMainText="拖拽报价文件到此处/点击选择文件"
      uploadHint="支持 XLSX / XLS / CSV，可批量上传，单个文件最大 200MB。"
      submitLabel="开始上传"
      submitIcon={<CloudUploadOutlined />}
      onSubmit={() => void submit()}
      submitting={uploading}
      submitDisabled={!canUpload || !templateVersionId}
      fileRequired={false}
      rightTitle="已上传文件"
      rightCount={page.total}
      rightFilters={statusFilters}
      activeFilter={status}
      onFilterChange={(value) => {
        setStatus(value);
        setPage((current) => ({ ...current, current: 1 }));
      }}
      searchValue={keyword}
      onSearchChange={(value) => {
        setKeyword(value);
        setPage((current) => ({ ...current, current: 1 }));
      }}
      searchPlaceholder="搜索文件名称/客户/报价编号"
      records={records}
      recordsLoading={loading && jobs.length === 0}
      pagination={{ current: page.current, pageSize: page.pageSize, total: page.total }}
      onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
    />
  );
}
