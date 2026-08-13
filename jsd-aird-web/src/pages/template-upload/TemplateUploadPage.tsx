import {
  DeleteOutlined,
  CloudUploadOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Modal,
  Progress,
  Select,
  Space,
  Table,
  Upload,
} from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import type { TemplateFormat } from '@/features/template-workspace/types';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import {
  templateApi,
  type CreateTemplateInput,
  type RecognitionSuggestion,
  type TemplateImportJob,
  type TemplateCategory,
} from '@/services/templates/template-api';
import { HttpError } from '@/services/http/errors';

import { buildDisplaySuggestions, recognitionCounts } from './recognition-display';

const accepted = '.xlsx,.docx';
export function TemplateUploadPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [jobs, setJobs] = useState<TemplateImportJob[]>([]);
  const [uploading, setUploading] = useState(false);
  const [selectedJob, setSelectedJob] = useState<TemplateImportJob>();
  const [viewingJob, setViewingJob] = useState<TemplateImportJob>();
  const [viewSuggestions, setViewSuggestions] = useState<RecognitionSuggestion[]>([]);
  const [viewLoading, setViewLoading] = useState(false);
  const [deletingJobId, setDeletingJobId] = useState<string>();
  const [retryingJobId, setRetryingJobId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [categories, setCategories] = useState<TemplateCategory[]>([]);
  const [jobFilter, setJobFilter] = useState('ALL');
  const [jobKeyword, setJobKeyword] = useState('');
  const [recordPage, setRecordPage] = useState({ current: 1, pageSize: 8 });
  const [uploadCategory, setUploadCategory] = useState<string>();
  const [form] = Form.useForm<CreateTemplateInput>();
  const displaySuggestions = useMemo(
    () => buildDisplaySuggestions(viewSuggestions),
    [viewSuggestions],
  );

  const validateTemplateFile: UploadProps['beforeUpload'] = (file) => {
    const name = file.name.toLowerCase();
    const supported = name.endsWith('.xlsx') || name.endsWith('.docx');
    if (!supported || file.size === 0) {
      void message.error('仅支持 XLSX / DOCX 文件，且文件不能为空');
      return Upload.LIST_IGNORE;
    }
    const signature = `${file.name.toLowerCase()}|${file.size}|${file.lastModified}`;
    const duplicate = files.some((item) => {
      const existing = item.originFileObj;
      return existing && `${existing.name.toLowerCase()}|${existing.size}|${existing.lastModified}` === signature;
    });
    if (duplicate) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const load = useCallback(async () => {
    try {
      const [imports, categoryItems] = await Promise.all([
        templateApi.listImports(),
        templateApi.listCategories(),
      ]);
      setJobs(imports);
      setCategories(categoryItems);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别记录加载失败');
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const categoryId = searchParams.get('categoryId');
    if (!categoryId || !categories.length || uploadCategory) return;
    const category = categories.find((item) => item.id === categoryId);
    if (category) setUploadCategory(category.name);
  }, [categories, searchParams, uploadCategory]);

  useEffect(() => {
    setRecordPage((value) => ({ ...value, current: 1 }));
  }, [jobFilter, jobKeyword]);

  const running = useMemo(
    () => jobs.some((job) => !['PARSED', 'FAILED'].includes(job.status)),
    [jobs],
  );

  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void load(), 2000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  const startUpload = async () => {
    const sourceFiles = files.flatMap((item) => item.originFileObj ? [item.originFileObj] : []);
    if (!sourceFiles.length) {
      void message.warning('请先选择 Excel 或 Word 文件');
      return;
    }
    if (sourceFiles.some((file) => {
      const name = file.name.toLowerCase();
      return file.size === 0 || (!name.endsWith('.xlsx') && !name.endsWith('.docx'));
    })) {
      void message.error('仅支持 XLSX / DOCX 文件，且文件不能为空');
      return;
    }
    setUploading(true);
    try {
      let failed = 0;
      for (const file of sourceFiles) {
        const format: TemplateFormat = file.name.toLowerCase().endsWith('.xlsx') ? 'XLSX' : 'DOCX';
        try {
          const staged = await templateApi.stageOfficeFile(file);
          const selectedCategory = categories.find((item) => item.name === uploadCategory);
          const options = { categoryId: selectedCategory?.id, operationSource: 'TEMPLATE_UPLOAD_PAGE' };
          try {
            await templateApi.createImport(staged.fileId, format, options);
          } catch (error) {
            if (!(error instanceof HttpError) || error.code !== 'RESOURCE_CONFLICT') throw error;
            const override = await new Promise<boolean>((resolve) => { modal.confirm({
              title: `“${file.name}”已经上传过`,
              content: '可以查看原任务，或确认重新解析并保留两次识别记录。',
              okText: '仍然重新解析', cancelText: '取消本次上传',
              onOk: () => resolve(true), onCancel: () => resolve(false),
            }); });
            if (!override) { failed += 1; continue; }
            await templateApi.createImport(staged.fileId, format, {
              ...options, duplicateOverride: true, operationSource: 'DUPLICATE_OVERRIDE',
            });
          }
        } catch (error) {
          failed += 1;
          void message.error(error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`);
        }
      }
      setFiles([]);
      await load();
      if (failed) void message.warning(`${sourceFiles.length - failed} 个文件已接收，${failed} 个文件上传失败`);
      else void message.success(`已接收 ${sourceFiles.length} 个文件，系统正在自动识别`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '文件上传失败');
    } finally {
      setUploading(false);
    }
  };

  const filteredJobs = useMemo(() => jobs.filter((job) => {
    const filterMatch = jobFilter === 'ALL' || (jobFilter === 'PROCESSING' ? !['PARSED', 'FAILED'].includes(job.status) : job.status === jobFilter);
    const keywordMatch = !jobKeyword || job.sourceFileName.toLowerCase().includes(jobKeyword.toLowerCase());
    return filterMatch && keywordMatch;
  }), [jobFilter, jobKeyword, jobs]);

  const allUploadRecords: UploadWorkspaceRecord[] = filteredJobs.map((job) => {
    const status = job.status === 'FAILED'
      ? { label: '识别失败', color: 'error' }
      : job.status === 'PARSED'
        ? { label: recognitionStatus(job) === 'COMPLETE' ? '已识别' : '待复核', color: recognitionStatus(job) === 'COMPLETE' ? 'success' : 'warning' }
        : { label: '识别中', color: 'processing' };
    return {
      id: job.id,
      name: job.sourceFileName,
      meta: `${job.format === 'XLSX' ? 'Excel' : 'Word'}${job.categoryName ? ` · ${job.categoryName}` : ' · 未分类'} · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: job.status === 'PARSED' ? recognitionLabel(job) : stageLabel(job.currentStage),
      status,
      progress: job.progress,
      actions: <Space size={2} wrap>
        <Button type="link" disabled={job.status !== 'PARSED'} onClick={() => void openRecognition(job)}>查看识别结果</Button>
        <Button type="link" disabled={job.status !== 'PARSED'} onClick={() => openCreate(job)}>创建模板</Button>
        <Button type="link" icon={<ReloadOutlined />} loading={retryingJobId === job.id} disabled={!['PARSED', 'FAILED'].includes(job.status)} onClick={() => void retryRecognition(job)}>重试</Button>
        <Button type="link" danger icon={<DeleteOutlined />} loading={deletingJobId === job.id} disabled={!['PARSED', 'FAILED'].includes(job.status)} aria-label={`删除 ${job.sourceFileName}`} onClick={() => Modal.confirm({ title: `删除“${job.sourceFileName}”的识别记录？`, content: '只删除识别记录，不删除原始文件；已生成模板的记录不能删除。', okText: '删除记录', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => deleteRecognition(job) })} />
      </Space>,
    };
  });
  const uploadRecords = allUploadRecords.slice((recordPage.current - 1) * recordPage.pageSize, recordPage.current * recordPage.pageSize);

  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(allUploadRecords.length / recordPage.pageSize));
    if (recordPage.current > maxPage) setRecordPage((value) => ({ ...value, current: maxPage }));
  }, [allUploadRecords.length, recordPage.current, recordPage.pageSize]);

  const openCreate = (job: TemplateImportJob) => {
    setSelectedJob(job);
    form.setFieldsValue({
      name: job.sourceFileName.replace(/\.(xlsx|docx)$/i, ''),
      format: job.format,
      category: job.categoryName ?? uploadCategory,
    });
  };

  const openRecognition = async (job: TemplateImportJob) => {
    setViewingJob(job);
    setViewLoading(true);
    try {
      const suggestions = await templateApi.listRecognitionSuggestions(job.id);
      setViewSuggestions(suggestions);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别详情加载失败');
    } finally {
      setViewLoading(false);
    }
  };

  const deleteRecognition = async (job: TemplateImportJob) => {
    setDeletingJobId(job.id);
    try {
      await templateApi.deleteImport(job.id);
      if (viewingJob?.id === job.id) setViewingJob(undefined);
      await load();
      void message.success('识别记录已删除');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别记录删除失败');
    } finally {
      setDeletingJobId(undefined);
    }
  };

  const createTemplate = async () => {
    if (!selectedJob) return;
    const input = await form.validateFields();
    setCreating(true);
    try {
      const workspace = await templateApi.create({
        ...input,
        format: selectedJob.format,
        importJobId: selectedJob.id,
      });
      navigate(`/templates/${workspace.versionId}/workspace?importJobId=${selectedJob.id}`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板草稿生成失败');
    } finally {
      setCreating(false);
    }
  };

  const retryRecognition = async (job: TemplateImportJob) => {
    setRetryingJobId(job.id);
    try {
      if (job.generatedTemplateVersionId && job.workspaceHash) {
        const useDraftSnapshot = Boolean(job.generatedTemplateVersionId && job.workspaceHash);
        await templateApi.retryImport(
          job.id,
          useDraftSnapshot ? 'CURRENT_DRAFT_SNAPSHOT' : 'ORIGINAL_FILE',
          useDraftSnapshot ? job.workspaceHash : undefined,
        );
      } else {
        await templateApi.retryImport(job.id, 'ORIGINAL_FILE');
      }
      await load();
      void message.success(
        job.generatedTemplateVersionId && job.workspaceHash
          ? '已按当前已保存草稿重新识别，历史运行会继续保留'
          : '已重新开始识别',
      );
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重新识别失败');
    } finally {
      setRetryingJobId(undefined);
    }
  };

  return (
    <>
      <UploadWorkspace
        breadcrumbs={[{ title: '模板中心' }, { title: '模板上传' }]}
        title="模板上传"
        description="上传后系统自动识别字段、业务分组和表格区域；进入工作台后只需确认少量不确定内容。"
        leftTitle="基础分类"
        classification={<Form layout="vertical" component={false}>
          <Form.Item label="模板分类">
            <Select allowClear placeholder="选择模板分类" value={uploadCategory} onChange={(value) => { setUploadCategory(value); form.setFieldValue('category', value); }} options={categories.map((item) => ({ value: item.name, label: item.name }))} />
          </Form.Item>
          <Form.Item label="权限可见">
            <Select defaultValue="全员可见" options={[{ value: '全员可见', label: '全员可见' }, { value: '研发部可见', label: '研发部可见' }, { value: '项目组可见', label: '项目组可见' }]} />
          </Form.Item>
        </Form>}
        accept={accepted}
        beforeUpload={validateTemplateFile}
        multiple
        files={files}
        onFilesChange={setFiles}
        onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
        onClearFiles={() => setFiles([])}
        uploadMainText="拖拽文件到此处，或点击选择文件"
        uploadHint="支持 XLSX / DOCX，支持批量上传；原始版式会完整保留。"
        submitLabel="开始识别"
        submitIcon={<CloudUploadOutlined />}
        onSubmit={() => void startUpload()}
        submitting={uploading}
        rightTitle="已上传文件"
        rightCount={filteredJobs.length}
        rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PROCESSING', label: '识别中' }, { key: 'PARSED', label: '已识别' }, { key: 'FAILED', label: '失败' }]}
        activeFilter={jobFilter}
        onFilterChange={(value) => { setJobFilter(value); setRecordPage((current) => ({ ...current, current: 1 })); }}
        searchValue={jobKeyword}
        onSearchChange={(value) => { setJobKeyword(value); setRecordPage((current) => ({ ...current, current: 1 })); }}
        records={uploadRecords}
        recordsLoading={uploading}
        pagination={{ ...recordPage, total: allUploadRecords.length }}
        onPageChange={(current, pageSize) => setRecordPage({ current, pageSize })}
      />

      <Modal
        title="确认模板信息"
        open={Boolean(selectedJob)}
        okText="进入工作台"
        cancelText="取消"
        confirmLoading={creating}
        onOk={() => void createTemplate()}
        onCancel={() => setSelectedJob(undefined)}
      >
        <Alert
          type={selectedJob ? recognitionAlertType(selectedJob) : 'info'}
          showIcon
          message={selectedJob ? recognitionLabel(selectedJob) : '系统已完成初步识别'}
          description={selectedJob ? recognitionDescription(selectedJob) : undefined}
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="模板名称"
            rules={[{ required: true, message: '请输入模板名称' }]}
          >
            <Input autoFocus maxLength={200} />
          </Form.Item>
          <Form.Item name="category" label="模板分类">
            <Select
              allowClear
              placeholder="选择分类（可在模板列表中管理）"
              options={categories.map((item) => ({ value: item.name, label: item.name }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={viewingJob ? `识别结果：${viewingJob.sourceFileName}` : '识别结果'}
        open={Boolean(viewingJob)}
        width={960}
        footer={
          viewingJob ? (
            <Space>
              <Button onClick={() => setViewingJob(undefined)}>关闭</Button>
              <Button
                type="primary"
                onClick={() => {
                  const job = viewingJob;
                  setViewingJob(undefined);
                  if (job) openCreate(job);
                }}
              >
                填写模板信息并创建
              </Button>
            </Space>
          ) : null
        }
        onCancel={() => setViewingJob(undefined)}
      >
        {viewLoading ? (
          <Progress percent={60} status="active" showInfo={false} />
        ) : (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Alert
              type={viewingJob ? recognitionAlertType(viewingJob) : 'info'}
              showIcon
              message={viewingJob ? recognitionLabel(viewingJob) : '识别结果'}
              description={
                viewingJob
                  ? recognitionDescription(viewingJob)
                  : undefined
              }
            />
            <Table
              size="small"
              rowKey="id"
              pagination={{ pageSize: 8 }}
              dataSource={displaySuggestions}
              columns={[
                {
                  title: '识别项目',
                  dataIndex: 'label',
                },
                { title: '说明', dataIndex: 'details' },
              ]}
            />
          </Space>
        )}
      </Modal>
    </>
  );
}

function recognitionStatus(job: TemplateImportJob) {
  if (job.status === 'FAILED') return 'FAILED';
  if (job.recognitionRunStatus === 'PARTIAL') return 'REVIEW_REQUIRED';
  return (
    job.recognitionSummary?.recognitionStatus ?? job.result.recognitionStatus ?? 'REVIEW_REQUIRED'
  );
}

function recognitionLabel(job: TemplateImportJob) {
  switch (recognitionStatus(job)) {
    case 'COMPLETE':
      return '识别完成';
    case 'FAILED':
      return '识别失败';
    case 'NO_PHYSICAL_TABLE':
      return '未发现可识别区域';
    case 'REVIEW_REQUIRED':
      return job.recognitionRunStatus === 'PARTIAL' ? '部分识别完成，待复核' : '解析完成，识别待复核';
    default:
      return job.status === 'PARSED' ? '解析完成，识别待复核' : stageLabel(job.currentStage);
  }
}

function recognitionAlertType(job: TemplateImportJob): 'success' | 'error' | 'warning' | 'info' {
  const status = recognitionStatus(job);
  if (status === 'FAILED') return 'error';
  if (status === 'COMPLETE') return 'success';
  if (status === 'REVIEW_REQUIRED') return 'warning';
  return 'info';
}

function recognitionDescription(job: TemplateImportJob) {
  const counts = recognitionCounts(job);
  const retry = job.retryCount > 0 ? `已重识别 ${job.retryCount} 次` : '尚未人工重识别';
  return `识别字段 ${counts.fields} 项，其中待确认 ${counts.pending} 项；结构候选 ${counts.structures} 组，结构冲突 ${counts.conflicts} 组，质量问题 ${counts.quality} 项；${retry}。`;
}

function stageLabel(stage?: string) {
  const labels: Record<string, string> = {
    LOADING_FILE: '正在读取文件',
    READING_STRUCTURE: '正在分析表格结构',
    RECOGNIZING_FIELDS: '正在识别业务字段',
    RECOGNIZING_COMPLEX_REGIONS: '正在识别明细表和复杂区域',
    RECOGNIZING_WORKBOOK_SEMANTICS: '正在理解整份工作簿',
    AI_RECOGNITION: '正在理解业务含义',
    BUILDING_DRAFT: '正在生成可编辑模板',
    CHECKING_RESULT: '正在检查识别结果',
    PERSISTING_RESULT: '正在保存识别结果',
  };
  return labels[stage ?? ''] ?? '等待后台处理';
}
