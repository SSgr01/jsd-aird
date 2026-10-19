import { FileExcelOutlined, FilePdfOutlined, FileWordOutlined, RightOutlined } from '@ant-design/icons';
import { Alert, App, Form, Select, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import { downloadFile } from '@/services/files';
import { dataApi, type DataJob, type DataTemplateOption } from '@/services/data/data-api';
import { templateCategory, templateGroups } from '@/services/data/import-guidance';
import { dataParseProgress, dataParseStageLabel } from '@/services/data/data-progress';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { listCategories } from '@/services/experiments/experiment-api';

const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'QUEUED', label: '上传中' },
  { key: 'PARSING', label: '解析中' },
  { key: 'WAITING_CONFIRM', label: '待确认数据' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'FAILED', label: '失败' },
  { key: 'CANCELLED', label: '已取消' },
];

const statusView: Record<string, { label: string; color: string }> = {
  CREATED: { label: '待处理', color: 'default' },
  QUEUED: { label: '上传中', color: 'processing' },
  PARSING: { label: '解析中', color: 'blue' },
  WAITING_SHEET: { label: '待确认 Sheet', color: 'gold' },
  WAITING_MAPPING: { label: '待字段映射', color: 'gold' },
  VALIDATING: { label: '校验中', color: 'processing' },
  WAITING_CONFIRM: { label: '待确认数据', color: 'orange' },
  COMMITTING: { label: '提交中', color: 'processing' },
  COMPLETED: { label: '已完成', color: 'success' },
  FAILED: { label: '处理失败', color: 'error' },
  CANCELLED: { label: '已取消', color: 'default' },
};

const supportedSourceFileName = /\.(?:xls|xlsx|csv|doc|docx|pdf)$/i;

function documentSourceFormat(fileName: string) {
  return /\.pdf$/i.test(fileName) ? 'PDF' : 'DOCX';
}

function jobRecord(
  job: DataJob,
  navigate: (path: string) => void,
  onDownload: (job: DataJob) => Promise<void>,
  onRetry: (job: DataJob) => Promise<void>,
  onDelete: (job: DataJob) => void,
  retrying: boolean,
  deleting: boolean,
): UploadWorkspaceRecord {
  const status = statusView[job.status] || { label: job.status, color: 'default' };
  const parseProgress = dataParseProgress(job);
  const terminal = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status);
  return {
    id: job.id,
    name: job.sourceFileName,
    icon: job.sourceFormat === 'PDF' ? <FilePdfOutlined />
      : job.sourceFormat === 'DOCX' ? <FileWordOutlined />
        : <FileExcelOutlined />,
    meta: job.templateVersionId ? `模板版本 ${job.templateVersionId}` : '自由识别',
    detail: job.status === 'FAILED' && job.errorMessage
      ? `${new Date(job.createdAt).toLocaleString('zh-CN')} · ${job.errorMessage}`
      : `${new Date(job.createdAt).toLocaleString('zh-CN')} · ${dataParseStageLabel(job)}`,
    status,
    progress: parseProgress,
    progressLabel: '解析进度',
    commonActions: {
      view: { onClick: () => navigate(job.templateVersionId ? `/data/import-jobs/${job.id}` : `/data/recognition-jobs/${job.id}`) },
      download: { onClick: () => { void onDownload(job); } },
      retry: { disabled: !terminal, loading: retrying, onClick: () => { void onRetry(job); } },
      delete: { disabled: !terminal, loading: deleting, onClick: () => onDelete(job) },
    },
  };
}

export function DataUploadPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [spreadsheetTemplateVersionId, setSpreadsheetTemplateVersionId] = useState<string>();
  const [wordTemplateVersionId, setWordTemplateVersionId] = useState<string>();
  const [templateCategoryFilter, setTemplateCategoryFilter] = useState<string>();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [loading, setLoading] = useState(false);
  const [jobs, setJobs] = useState<{ items: DataJob[]; page: number; size: number; total: number }>({ items: [], page: 1, size: 8, total: 0 });
  const [jobStatus, setJobStatus] = useState('ALL');
  const [jobKeyword, setJobKeyword] = useState('');
  const [jobsLoading, setJobsLoading] = useState(false);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [experimentCategoryId, setExperimentCategoryId] = useState<string>();
  const [retryingJobId, setRetryingJobId] = useState<string>();
  const [deletingJobId, setDeletingJobId] = useState<string>();

  useEffect(() => {
    setSpreadsheetTemplateVersionId(undefined);
    setWordTemplateVersionId(undefined);
    void dataApi.listTemplates()
      .then(setTemplates)
      .catch((error) => void message.error(error instanceof Error ? error.message : '模板加载失败'));
  }, [message]);

  useEffect(() => {
    void Promise.all([
      listCategories().then((items) => { setExperimentCategoryId((value) => value || items[0]?.id); }).catch(() => undefined),
    ]);
  }, []);

  const loadJobs = useCallback(async (_options: { silent?: boolean } = {}) => {
    if (!_options.silent) setJobsLoading(true);
    try {
      const page = await dataApi.listJobs({
        status: jobStatus === 'ALL' ? undefined : jobStatus,
        keyword: jobKeyword || undefined,
        page: jobs.page,
        size: jobs.size,
      });
      setJobs({ items: page.items, page: page.page, size: page.size, total: page.total });
    } catch (error) {
      if (!_options.silent) void message.error(error instanceof Error ? error.message : '导入任务加载失败');
    } finally {
      if (!_options.silent) setJobsLoading(false);
    }
  }, [jobKeyword, jobStatus, jobs.page, jobs.size, message]);

  useEffect(() => { void loadJobs(); }, [loadJobs]);

  useEffect(() => {
    const hasActiveJob = jobs.items.some((job) => !['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status));
    if (!hasActiveJob) return undefined;
    const timer = window.setInterval(() => void loadJobs({ silent: true }), 3000);
    return () => window.clearInterval(timer);
  }, [jobs.items, loadJobs]);

  const spreadsheetTemplates = useMemo(() => templates.filter((item) => item.format === 'XLSX'), [templates]);
  const wordTemplates = useMemo(() => templates.filter((item) => item.format === 'DOCX'), [templates]);
  const chosenSpreadsheetTemplate = useMemo(() => spreadsheetTemplates.find((item) => item.versionId === spreadsheetTemplateVersionId), [spreadsheetTemplateVersionId, spreadsheetTemplates]);
  const chosenWordTemplate = useMemo(() => wordTemplates.find((item) => item.versionId === wordTemplateVersionId), [wordTemplateVersionId, wordTemplates]);

  const submit = async () => {
    const selectedFiles = files.flatMap((item) => item.originFileObj ? [{ file: item.originFileObj, uid: item.uid }] : []);
    if (!selectedFiles.length) { void message.warning('请选择数据文件'); return; }
    const spreadsheetFiles = selectedFiles.filter(({ file }) => /\.(?:xls|xlsx|csv)$/i.test(file.name));
    if (spreadsheetFiles.length && !chosenSpreadsheetTemplate) { void message.warning('Excel / CSV 文件请选择已发布的 Excel 模板'); return; }
    if ((spreadsheetFiles.length && chosenSpreadsheetTemplate?.experimentImportReady
      || selectedFiles.some(({ file }) => /\.docx$/i.test(file.name)) && chosenWordTemplate?.experimentImportReady)
      && !experimentCategoryId) { void message.warning('实验模板暂未配置默认实验分类'); return; }
    setLoading(true);
    const failedUids = new Set<string>();
    let succeeded = 0;
    let skipped = 0;
    try {
      for (const entry of selectedFiles) {
        const updateUploadFile = (status: UploadFile['status'], percent: number) => {
          setFiles((current) => current.map((item) => item.uid === entry.uid ? { ...item, status, percent } : item));
        };
        updateUploadFile('uploading', 0);
        try {
          const staged = await dataApi.stageSource(entry.file, (percent) => updateUploadFile('uploading', percent));
          const isPdf = /\.pdf$/i.test(entry.file.name);
          const isWord = /\.docx?$/i.test(entry.file.name);
          const isDocx = /\.docx$/i.test(entry.file.name);
          const template = isDocx ? chosenWordTemplate : isWord ? undefined : chosenSpreadsheetTemplate;
          if (isPdf || isWord && !template) {
            await dataApi.createRecognitionJob({
              sourceFileId: staged.fileId,
              sourceFormat: documentSourceFormat(entry.file.name),
              recognitionMode: 'FREEFORM',
              importPurpose: 'DATA_ONLY',
              projectRelations,
            });
            succeeded += 1;
            updateUploadFile('done', 100);
            continue;
          }
          if (!template) throw new Error(isWord ? 'Word 文件请选择已发布的 Word 模板' : 'Excel / CSV 文件请选择已发布的 Excel 模板');
          const create = (duplicateOverride: boolean) => dataApi.createJob({
            sourceFileId: staged.fileId,
            templateVersionId: template.versionId,
            duplicateOverride,
            projectRelations,
            importPurpose: template.experimentImportReady ? 'EXPERIMENT_DRAFT' : 'DATA_ONLY',
            targetExperimentCategoryId: template.experimentImportReady ? experimentCategoryId : undefined,
          });
          try {
            await create(false);
          } catch (error) {
            if (!(error instanceof Error) || !error.message.includes('历史任务')) throw error;
            const override = await new Promise<boolean>((resolve) => {
              modal.confirm({
                title: `“${entry.file.name}”文件已成功导入过`,
                content: `${error.message}。确认后会保留旧任务并单独创建一次重复导入。`,
                okText: '确认重复导入', cancelText: '跳过本文件',
                onOk: () => resolve(true), onCancel: () => resolve(false),
              });
            });
            if (!override) { skipped += 1; continue; }
            await create(true);
          }
          succeeded += 1;
          updateUploadFile('done', 100);
        } catch (error) {
          failedUids.add(entry.uid);
          updateUploadFile('error', 0);
          void message.error(error instanceof Error ? `${entry.file.name}：${error.message}` : `${entry.file.name}：创建导入任务失败`);
        }
      }
      setFiles((current) => current.filter((item) => failedUids.has(item.uid)));
      await loadJobs();
      if (failedUids.size) void message.warning(`${succeeded} 个文件已接收，${skipped} 个跳过，${failedUids.size} 个失败，可重新提交失败文件`);
      else if (skipped) void message.info(`${succeeded} 个文件已接收，${skipped} 个重复文件已跳过`);
      else void message.success(`已接收 ${succeeded} 个文件，系统正在处理`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '创建导入任务失败');
    } finally {
      setLoading(false);
    }
  };

  const downloadJob = async (job: DataJob) => {
    try { await downloadFile(job.sourceFileId, job.sourceFileName); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };
  const retryJob = async (job: DataJob) => {
    setRetryingJobId(job.id);
    try {
      if (job.templateVersionId) await dataApi.reprocessJob(job.id);
      else await dataApi.retryRecognitionJob(job.id);
      await loadJobs();
      void message.success('已重新处理');
    }
    catch (error) { void message.error(error instanceof Error ? error.message : '重新处理失败'); }
    finally { setRetryingJobId(undefined); }
  };
  const deleteJob = (job: DataJob) => {
    modal.confirm({
      title: `删除“${job.sourceFileName}”的上传记录？`,
      content: '只隐藏上传历史；原文件、已导入数据和实验记录会保留。',
      okText: '删除记录', okButtonProps: { danger: true }, cancelText: '取消',
      onOk: async () => {
        setDeletingJobId(job.id);
        try { await dataApi.hideJob(job.id); await loadJobs(); void message.success('上传历史已隐藏'); }
        catch (error) { void message.error(error instanceof Error ? error.message : '删除上传记录失败'); }
        finally { setDeletingJobId(undefined); }
      },
    });
  };
  const records = jobs.items.map((job) => jobRecord(job, navigate, downloadJob, retryJob, deleteJob,
    retryingJobId === job.id, deletingJobId === job.id));

  return (
    <>
      <UploadWorkspace
      breadcrumbs={[{ title: '数据中心' }, { title: '数据上传' }]}
      title="数据上传"
      description="Excel / CSV 和 Word 可选择对应的已发布模板导入；Word 不选模板或上传 PDF 时使用自由识别。上传后可查看处理状态与失败原因。"
      leftTitle="选择导入模板"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="模板分类">
          <Select allowClear placeholder="全部模板分类" value={templateCategoryFilter}
            options={[...new Set(templates.map(templateCategory))].sort((a, b) => a.localeCompare(b, 'zh-CN')).map((value) => ({ value, label: value }))}
            onChange={(category) => {
              setTemplateCategoryFilter(category);
              if (category && spreadsheetTemplates.find((item) => item.versionId === spreadsheetTemplateVersionId && templateCategory(item) !== category)) setSpreadsheetTemplateVersionId(undefined);
              if (category && wordTemplates.find((item) => item.versionId === wordTemplateVersionId && templateCategory(item) !== category)) setWordTemplateVersionId(undefined);
            }} />
        </Form.Item>
        <Form.Item label="Excel / CSV 导入模板">
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="请选择已发布的 Excel 模板"
            value={spreadsheetTemplateVersionId}
            onChange={setSpreadsheetTemplateVersionId}
            options={templateGroups(spreadsheetTemplates, templateCategoryFilter)}
            notFoundContent={templateCategoryFilter ? '该分类暂无已发布模板，可切换分类' : '暂无已发布模板'}
          />
        </Form.Item>
        <Form.Item label="Word 导入模板（可选）">
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="选择 Word 模板；不选时使用自由识别"
            value={wordTemplateVersionId}
            onChange={setWordTemplateVersionId}
            options={templateGroups(wordTemplates, templateCategoryFilter)}
            notFoundContent={templateCategoryFilter ? '该分类暂无已发布 Word 模板，可切换分类' : '暂无已发布 Word 模板'}
          />
        </Form.Item>
        <Alert type="info" showIcon message="Excel / CSV 使用 Excel 模板；Word 可选 Word 模板或自由识别；PDF 使用自由识别。" />
        {chosenSpreadsheetTemplate && <Alert
          showIcon
          type={chosenSpreadsheetTemplate.experimentImportReady ? 'success' : 'info'}
          message={chosenSpreadsheetTemplate.experimentImportReady ? 'Excel 实验数据模板 · 可生成实验草稿' : 'Excel 普通数据模板'}
          description={chosenSpreadsheetTemplate.experimentImportReady
            ? `整份文件一个实验${chosenSpreadsheetTemplate.identityTypes?.length ? ` · 文件内关联标识：${chosenSpreadsheetTemplate.identityTypes.map(identityTypeLabel).join('、')}` : ''}`
            : '仅导入数据中心，不生成实验草稿。'}
        />}
        {chosenWordTemplate && <Alert
          showIcon
          type={chosenWordTemplate.experimentImportReady ? 'success' : 'info'}
          message={chosenWordTemplate.experimentImportReady ? 'Word 实验数据模板 · 可生成实验草稿' : 'Word 普通数据模板'}
          description={chosenWordTemplate.experimentImportReady
            ? `整份文件一个实验${chosenWordTemplate.identityTypes?.length ? ` · 文件内关联标识：${chosenWordTemplate.identityTypes.map(identityTypeLabel).join('、')}` : ''}`
            : '按模板字段识别并导入数据中心。'}
        />}
        <Form.Item label="关联项目 / 阶段 / 任务" extra="所选关联会应用到本次导入的工作表、数据行和来源文件。"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} /></Form.Item>
      </Form>}
      accept=".xls,.xlsx,.csv,.doc,.docx,.pdf"
      beforeUpload={(file) => {
        if (!supportedSourceFileName.test(file.name)) {
          void message.error(`不支持“${file.name}”：请上传 Excel、CSV、Word 或 PDF 文件`);
          return Upload.LIST_IGNORE;
        }
        return false;
      }}
      multiple
      maxCount={20}
      files={files}
      onFilesChange={setFiles}
      onRemoveFile={(file) => setFiles((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFiles([])}
      uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
      uploadHint="支持 XLS / XLSX / CSV / DOC / DOCX / PDF；单次最多 20 个文件，单个文件最大 100M。"
      uploadDisabled={loading}
      previewEmptyText="暂无待导入文件，点击上方区域选择文件"
      submitLabel="创建导入任务"
      submitIcon={<RightOutlined />}
      onSubmit={() => void submit()}
      submitting={loading}
      submitDisabled={files.some((item) => item.originFileObj && /\.(?:xls|xlsx|csv)$/i.test(item.name))
        && (!chosenSpreadsheetTemplate || (chosenSpreadsheetTemplate.experimentImportReady && !experimentCategoryId))
        || files.some((item) => item.originFileObj && /\.docx$/i.test(item.name))
        && Boolean(chosenWordTemplate?.experimentImportReady && !experimentCategoryId)}
      rightTitle="已上传数据"
      rightCount={jobs.total}
      rightFilters={statusFilters}
      activeFilter={jobStatus}
      onFilterChange={(key) => { setJobStatus(key); setJobs((current) => ({ ...current, page: 1 })); }}
      searchValue={jobKeyword}
      onSearchChange={(value) => { setJobKeyword(value); setJobs((current) => ({ ...current, page: 1 })); }}
      searchPlaceholder="搜索文件名称"
      records={records}
      recordsLoading={jobsLoading && jobs.items.length === 0}
      pagination={{ current: jobs.page, pageSize: jobs.size, total: jobs.total }}
      onPageChange={(page, pageSize) => setJobs((current) => ({ ...current, page, size: pageSize }))}
      />
    </>
  );
}

function identityTypeLabel(value: string) {
  return ({
    EXPERIMENT_NO: '实验编号',
    SAMPLE_NO: '样品编号',
    FORMULA_NO: '配方编号',
    BATCH_NO: '批次编号',
  } as Record<string, string>)[value] ?? value;
}
