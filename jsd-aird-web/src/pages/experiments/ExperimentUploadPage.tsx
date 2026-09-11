import { CloudUploadOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined, PictureOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, Typography, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
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
import { dataApi, type DataTemplateOption } from '@/services/data/data-api';

const visibilityOptions = [
  { value: 'ALL' as const, label: '全员可见' },
  { value: 'QUALITY' as const, label: '品管部可见' },
  { value: 'PROJECT' as const, label: '项目组可见' },
];
const visibilityText = (value?: 'ALL' | 'QUALITY' | 'PROJECT') =>
  visibilityOptions.find((item) => item.value === value)?.label || '全员可见';

export function ExperimentUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [jobs, setJobs] = useState<ExperimentImportJob[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [location, setLocation] = useState<string>();
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [uploading, setUploading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [filter, setFilter] = useState('ALL');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState({ current: 1, pageSize: 8 });
  const [visibility, setVisibility] = useState<'ALL' | 'QUALITY' | 'PROJECT'>('ALL');
  const [ocrOpen, setOcrOpen] = useState(false);
  const [ocrFiles, setOcrFiles] = useState<UploadFile[]>([]);
  const [ocrSubmitting, setOcrSubmitting] = useState(false);
  const [experimentTemplates, setExperimentTemplates] = useState<DataTemplateOption[]>([]);
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [retryingId, setRetryingId] = useState<string>();
  const load = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    try {
      setJobs(await listExperimentImports());
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
      }),
      load(),
      dataApi.listTemplates().then((items) => {
        const values = items.filter((item) => item.importContractVersion === 9 && item.templateUsage === 'EXPERIMENT_DATA');
        setExperimentTemplates(values); setTemplateVersionId((current) => current ?? values[0]?.versionId);
      }),
    ]);
  }, [load]);
  const running = useMemo(() => jobs.some((job) => job.status === 'PARSING'), [jobs]);
  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void load(false), 2000);
    return () => window.clearInterval(timer);
  }, [load, running]);
  const validate: UploadProps['beforeUpload'] = (file) => {
    if (file.size === 0 || !/\.(xlsx|xls|csv|doc|docx|pdf|jpg|jpeg|png|tif|tiff)$/i.test(file.name)) {
      void message.error('仅支持 XLSX / XLS / CSV / DOCX / PDF / JPG / PNG / TIF 文件，且文件不能为空');
      return Upload.LIST_IGNORE;
    }
    if (
      files.some(
        (item) => item.name.toLowerCase() === file.name.toLowerCase() && item.size === file.size,
      )
    ) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };
  const submit = async () => {
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) {
      void message.warning('请先选择实验文件');
      return;
    }
    const relation = projectRelations.find((item) => item.projectId);
    setUploading(true);
    let failed = 0;
    let firstDataJobId: string | undefined;
    for (const file of sourceFiles) {
      try {
        if (/\.(xlsx|xls|csv)$/i.test(file.name)) {
          if (!templateVersionId) throw new Error('请先选择已发布的实验数据模板');
          const category = categories.find((item) => item.name === location);
          if (!category) throw new Error('请先选择实验分类');
          const staged = await dataApi.stageSource(file);
          const job = await dataApi.createJob({ sourceFileId: staged.fileId, templateVersionId,
            importPurpose: 'EXPERIMENT_DRAFT', targetExperimentCategoryId: category.id,
            projectRelations: relation ? [{ projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId }] : [] });
          firstDataJobId ??= job.id;
          continue;
        }
        const staged = await stageExperimentFile(file);
        const category = categories.find((item) => item.name === location);
        await importExperimentFile({
          fileId: staged.fileId,
          fileName: staged.originalName,
          sha256: staged.sha256,
          format: formatForFile(file.name),
          categoryId: category?.id,
          categoryName: category?.name,
          ...(relation ? { projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId } : {}),
          experimentDate: new Date().toISOString().slice(0, 10),
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
    await load();
    if (firstDataJobId) navigate(`/data/import-jobs/${firstDataJobId}`);
    if (failed)
      void message.warning(`${sourceFiles.length - failed} 个文件已提交解析，${failed} 个提交失败`);
    else void message.success(`已提交 ${sourceFiles.length} 个实验文件，后台解析中`);
  };
  const openOcr = () => { setOcrFiles([]); setOcrOpen(true); };
  const submitOcr = async () => {
    const sourceFiles = ocrFiles.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) return;
    const relation = projectRelations.find((item) => item.projectId);
    setOcrSubmitting(true);
    let failed = 0;
    try {
      const category = categories.find((item) => item.name === location);
      for (const file of sourceFiles) {
        try {
          const staged = await stageExperimentFile(file);
          await importExperimentFile({
            fileId: staged.fileId,
            fileName: staged.originalName,
            sha256: staged.sha256,
            format: formatForFile(file.name),
            categoryId: category?.id,
            categoryName: category?.name,
            ...(relation ? { projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId } : {}),
            experimentDate: new Date().toISOString().slice(0, 10),
            visibility,
          });
        } catch {
          failed += 1;
        }
      }
      await load();
      if (failed) void message.warning(`${sourceFiles.length - failed} 个识别任务已提交，${failed} 个失败`);
      else void message.success(`已提交 ${sourceFiles.length} 个 OCR 识别任务`);
      setOcrFiles([]);
      setOcrOpen(false);
    } finally {
      setOcrSubmitting(false);
    }
  };
  const retry = async (job: ExperimentImportJob) => {
    setRetryingId(job.id);
    try {
      await retryExperimentImport(job.id);
      await load();
      void message.success(`“${job.sourceFileName}”已提交重新解析，后台处理中`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重新解析失败，请稍后再试');
    } finally {
      setRetryingId(undefined);
    }
  };
  const filtered = useMemo(
    () =>
      jobs.filter(
        (job) =>
          (filter === 'ALL' || job.status === filter) &&
          (!keyword || job.sourceFileName.toLowerCase().includes(keyword.toLowerCase())),
      ),
    [filter, jobs, keyword],
  );
  const records: UploadWorkspaceRecord[] = filtered
    .slice((page.current - 1) * page.pageSize, page.current * page.pageSize)
    .map((job) => {
      const progress = job.status === 'COMPLETED'
        ? 100
        : job.status === 'FAILED'
          ? 0
          : Math.min(99, Math.max(0, job.progress ?? 0));
      return {
        id: job.id,
        name: job.sourceFileName,
        meta: `${formatLabel(job.sourceFormat)} · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
        detail: [
          experimentImportProgressDescription(job, progress),
          `保存位置：${job.categoryName || '未分类'} · ${[job.projectName, job.stageName, job.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityText(job.visibility)}`,
          job.errorMessage ? `失败原因：${job.errorMessage}` : null,
        ].filter(Boolean).join(' · '),
        status:
          job.status === 'COMPLETED'
            ? { label: '已解析', color: 'success' }
          : job.status === 'FAILED'
            ? { label: '解析失败', color: 'error' }
            : { label: `解析中 ${progress}%`, color: 'processing' },
        progress,
        actions: (
          <Space size={2}>
          <Button
            type="link"
            icon={<EyeOutlined />}
            disabled={!job.experimentId}
            onClick={() => job.experimentId && navigate(`/experiments/${job.experimentId}`)}
          >
            查看
          </Button>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            disabled={!job.sourceFileId}
            onClick={() =>
              job.sourceFileId &&
              void downloadFile(job.sourceFileId, job.sourceFileName).catch(
                (error) =>
                  void message.error(error instanceof Error ? error.message : '源文件下载失败'),
              )
            }
          >
            下载
          </Button>
          <Button
            type="link"
            icon={<ReloadOutlined />}
            loading={retryingId === job.id}
            onClick={() => void retry(job)}
          >
            重试
          </Button>
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            disabled={!['PARSING', 'COMPLETED', 'FAILED'].includes(job.status)}
            onClick={() => Modal.confirm({
              title: `删除“${job.sourceFileName}”的上传记录？`,
              content: job.status === 'PARSING'
                ? '删除后将取消后台解析任务，原始文件仍保留在文件存储中。'
                : job.experimentId
                ? '仅删除上传记录，不删除已生成的实验草稿、原始附件或来源追溯关系。'
                : '仅删除上传记录，不删除原始附件。',
              okText: job.status === 'PARSING' ? '取消解析并删除' : '删除',
              cancelText: '取消',
              okButtonProps: { danger: true },
              onOk: async () => {
                try {
                  await deleteExperimentImport(job.id);
                  await load();
                  void message.success(job.status === 'PARSING' ? '解析任务已取消，上传记录已删除' : '上传记录已删除，实验草稿仍保留');
                } catch (error) {
                  void message.error(error instanceof Error ? error.message : '上传记录删除失败');
                }
              },
            })}
          >
            删除
          </Button>
        </Space>
        ),
      };
    });
  return (
    <>
    <UploadWorkspace
      breadcrumbs={[{ title: '电子实验记录本' }, { title: '实验上传' }]}
      title="实验上传"
      description="上传 Word / Excel 或实验记录图片；图片将复用模板中心 OCR 识别并生成可编辑的 Excel 实验草稿。"
      headerActions={<Space><Button icon={<PictureOutlined />} onClick={openOcr}>拍照录入</Button><Button type="primary" onClick={() => navigate('/experiments')}>实验列表</Button></Space>}
      leftTitle="基础分类"
      classification={
        <Form layout="vertical" component={false}>
          <Form.Item label="保存位置">
            <Select
              value={location}
              onChange={setLocation}
              options={categories.map((item) => ({ value: item.name, label: item.name }))}
            />
          </Form.Item>
          <Form.Item label="关联项目 / 阶段 / 任务">
            <ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} multiple={false} />
          </Form.Item>
          <Form.Item label="权限可见">
            <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
          </Form.Item>
          <Form.Item label="Excel实验模板" extra="XLSX / XLS / CSV 将进入数据中心完成预览和确认；Word、PDF和图片继续原实验导入流程。">
            <Select showSearch optionFilterProp="label" value={templateVersionId} onChange={setTemplateVersionId}
              placeholder="选择已发布的实验数据模板" options={experimentTemplates.map((item) => ({ value: item.versionId, label: `${item.name} · V${item.versionNo}` }))} />
          </Form.Item>
        </Form>
      }
      accept=".xlsx,.xls,.csv,.doc,.docx,.pdf,.jpg,.jpeg,.png,.tif,.tiff"
      beforeUpload={validate}
      multiple
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) =>
        setFiles((current) => current.filter((item) => item.uid !== file.uid))
      }
      onClearFiles={() => setFiles([])}
      uploadHint="支持 XLSX / XLS / CSV / DOCX / PDF / JPG / PNG / TIF，图片会通过模板中心 OCR 生成 Excel；原始文件会保留。"
      submitLabel="开始上传"
      submitIcon={<CloudUploadOutlined />}
      onSubmit={() => void submit()}
      submitting={uploading}
      rightTitle="已上传文件"
      rightCount={filtered.length}
      rightFilters={[
        { key: 'ALL', label: '全部' },
        { key: 'PARSING', label: '解析中' },
        { key: 'COMPLETED', label: '已解析' },
        { key: 'FAILED', label: '失败' },
      ]}
      activeFilter={filter}
      onFilterChange={(value) => {
        setFilter(value);
        setPage((current) => ({ ...current, current: 1 }));
      }}
      searchValue={keyword}
      onSearchChange={(value) => {
        setKeyword(value);
        setPage((current) => ({ ...current, current: 1 }));
      }}
      records={records}
      recordsLoading={loading}
      pagination={{ ...page, total: filtered.length }}
      onPageChange={(current, pageSize) => setPage({ current, pageSize })}
    />
    <Modal title="拍照 / OCR录入" open={ocrOpen} onCancel={() => setOcrOpen(false)} footer={null} width={620}>
      <Typography.Text type="secondary">选择纸质实验记录照片或扫描 PDF，提交后生成可人工复核的实验草稿。</Typography.Text>
      <Upload.Dragger
        className="ocr-upload-dragger"
        accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff"
        multiple
        fileList={ocrFiles}
        showUploadList
        beforeUpload={(file) => {
          if (!file.size || !/\.(pdf|jpg|jpeg|png|tif|tiff)$/i.test(file.name)) {
            void message.error('OCR仅支持 PDF / JPG / PNG / TIF');
            return Upload.LIST_IGNORE;
          }
          if (ocrFiles.some((item) => item.name === file.name && item.size === file.size)) return Upload.LIST_IGNORE;
          return false;
        }}
        onChange={({ fileList }) => setOcrFiles(fileList)}
      >
        <p><PictureOutlined /> 点击或拖拽照片 / PDF 到此处</p>
        <p>支持多页图片，识别后请在实验工作台人工复核</p>
      </Upload.Dragger>
      <Space style={{ marginTop: 16, width: '100%', justifyContent: 'flex-end' }}>
        <Button onClick={() => setOcrOpen(false)}>取消</Button>
        <Button type="primary" disabled={!ocrFiles.length} loading={ocrSubmitting} onClick={() => void submitOcr()}>开始分析</Button>
      </Space>
    </Modal>
    </>
  );
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
  return ({ XLSX: 'Excel', XLS: '旧版 Excel', CSV: 'CSV', DOCX: 'Word', PDF: 'PDF 扫描件', IMAGE: '图片 OCR → Excel' } as Record<string, string>)[format] ?? format;
}

function experimentImportStageLabel(stage?: string) {
  const labels: Record<string, string> = {
    PREPARING: '正在准备任务',
    LOADING_FILE: '正在读取文件',
    READING_STRUCTURE: '正在分析文件结构',
    PARSING_COMPLETED: '文件解析完成，正在生成实验',
    BUILDING_EXPERIMENT: '正在生成实验记事本',
    PERSISTING_RESULT: '正在保存解析结果',
    DUPLICATE_RESOLVED: '发现已有结果，正在复用',
  };
  return labels[stage ?? ''] ?? '等待后台处理';
}

function experimentImportProgressDescription(job: ExperimentImportJob, progress: number) {
  if (job.status === 'PARSING') return `${experimentImportStageLabel(job.currentStage)} · 已完成 ${progress}%`;
  if (job.status === 'COMPLETED') return '解析完成，已生成实验记录本';
  if (job.status === 'FAILED') return '解析失败，可点击重试';
  if (job.status === 'CANCELLED') return '解析任务已取消';
  return '等待后台解析任务';
}
