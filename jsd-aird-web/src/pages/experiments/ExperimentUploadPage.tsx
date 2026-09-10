import { CloudUploadOutlined, DeleteOutlined, DownloadOutlined, EyeOutlined, PictureOutlined } from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, TreeSelect, Typography, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import {
  importExperimentFile,
  deleteExperimentImport,
  listCategories,
  listExperimentImports,
  stageExperimentFile,
  type Category,
  type ExperimentImportJob,
} from '@/services/experiments/experiment-api';
import {
  getProjectStages,
  getProjects,
  getStageTasks,
  type ProjectStage,
  type ProjectTask,
} from '@/services/project/project-api';
import { downloadFile } from '@/services/files/file-api';
import { dataApi, type DataTemplateOption } from '@/services/data/data-api';

type LinkNode = {
  value: string;
  label: string;
  isLeaf?: boolean;
  selectable?: boolean;
  children?: LinkNode[];
};
type ExperimentLink = { projectId?: string; stageId?: string; taskId?: string };
function findLink(nodes: LinkNode[], target: string, parents: ExperimentLink = {}): ExperimentLink {
  for (const node of nodes) {
    const [type, id] = node.value.split(':');
    const current = {
      ...parents,
      ...(type === 'project' ? { projectId: id } : {}),
      ...(type === 'stage' ? { stageId: id } : {}),
      ...(type === 'task' ? { taskId: id } : {}),
    };
    if (node.value === target) return current;
    const child = findLink(node.children ?? [], target, current);
    if (child.projectId || child.stageId || child.taskId) return child;
  }
  return {};
}
function attachChildren(nodes: LinkNode[], target: string, children: LinkNode[]): LinkNode[] {
  return nodes.map((node) =>
    node.value === target
      ? { ...node, children }
      : node.children
        ? { ...node, children: attachChildren(node.children, target, children) }
        : node,
  );
}

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
  const [link, setLink] = useState<string>();
  const [linkTree, setLinkTree] = useState<LinkNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
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
  const previousExpanded = useRef<string[]>([]);
  const load = useCallback(async () => {
    setLoading(true);
    try {
      setJobs(await listExperimentImports());
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '实验上传记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);
  useEffect(() => {
    void Promise.all([
      listCategories().then((items) => {
        setCategories(items);
        setLocation((current) => current ?? items[0]?.name);
      }),
      load(),
      getProjects({ page: 1, size: 200 }).then((result) =>
        setLinkTree(
          result.items.map((project) => ({
            value: `project:${project.id}`,
            label: `${project.projectCode}·${project.name}`,
            selectable: false,
            isLeaf: false,
          })),
        ),
      ),
      dataApi.listTemplates().then((items) => {
        const values = items.filter((item) => item.importContractVersion === 9 && item.templateUsage === 'EXPERIMENT_DATA');
        setExperimentTemplates(values); setTemplateVersionId((current) => current ?? values[0]?.versionId);
      }),
    ]);
  }, [load]);
  const expandRelation = (keys: Array<string | number>) => {
    const values = keys.map(String);
    const target = values.find((key) => !previousExpanded.current.includes(key));
    setExpandedKeys(values);
    previousExpanded.current = values;
    if (!target) return;
    const [type, id] = target.split(':');
    if (!id) return;
    const request =
      type === 'project'
        ? getProjectStages(id).then((items: ProjectStage[]) =>
            items.map<LinkNode>((stage) => ({
              value: `stage:${stage.id}`,
              label: stage.name,
              selectable: false,
              isLeaf: false,
            })),
          )
        : type === 'stage'
          ? getStageTasks(id).then((items: ProjectTask[]) =>
              items.map<LinkNode>((task) => ({
                value: `task:${task.id}`,
                label: task.name,
                selectable: true,
                isLeaf: true,
              })),
            )
          : Promise.resolve([]);
    void request.then((children) =>
      setLinkTree((current) => attachChildren(current, target, children)),
    );
  };
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
          const relation = link ? findLink(linkTree, link) : {};
          const job = await dataApi.createJob({ sourceFileId: staged.fileId, templateVersionId,
            importPurpose: 'EXPERIMENT_DRAFT', targetExperimentCategoryId: category.id,
            projectRelations: relation.projectId ? [{ projectId: relation.projectId, stageId: relation.stageId, taskId: relation.taskId }] : [] });
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
          ...(link ? findLink(linkTree, link) : {}),
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
      void message.warning(`${sourceFiles.length - failed} 个文件导入成功，${failed} 个失败`);
    else void message.success(`已成功导入 ${sourceFiles.length} 个实验文件`);
  };
  const openOcr = () => { setOcrFiles([]); setOcrOpen(true); };
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
          await importExperimentFile({ fileId: staged.fileId, fileName: staged.originalName, sha256: staged.sha256,
            format: formatForFile(file.name), categoryId: category?.id, categoryName: category?.name,
            ...(link ? findLink(linkTree, link) : {}), experimentDate: new Date().toISOString().slice(0, 10), visibility });
        } catch { failed += 1; }
      }
      await load();
      if (failed) void message.warning(`${sourceFiles.length - failed} 个识别任务已提交，${failed} 个失败`);
      else void message.success(`已提交 ${sourceFiles.length} 个 OCR 识别任务`);
      setOcrFiles([]); setOcrOpen(false);
    } finally { setOcrSubmitting(false); }
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
    .map((job) => ({
      id: job.id,
      name: job.sourceFileName,
      meta: `${formatLabel(job.sourceFormat)} · ${new Date(job.createdAt).toLocaleString('zh-CN')}`,
      detail: `保存位置：${job.categoryName || '未分类'} · ${[job.projectName, job.stageName, job.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityText(job.visibility)}${job.errorMessage ? ` · ${job.errorMessage}` : ''}`,
      status:
        job.status === 'COMPLETED'
          ? { label: '已解析', color: 'success' }
          : job.status === 'FAILED'
            ? { label: '解析失败', color: 'error' }
            : { label: '解析中', color: 'processing' },
      progress: job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : 50,
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
            danger
            icon={<DeleteOutlined />}
            disabled={!job.allowedActions?.includes('DELETE')}
            onClick={() => Modal.confirm({
              title: `删除“${job.sourceFileName}”的上传记录？`,
              content: job.experimentId
                ? '仅删除上传记录，不删除已生成的实验草稿、原始附件或来源追溯关系。'
                : '仅删除上传记录，不删除原始附件。',
              okText: '删除',
              cancelText: '取消',
              okButtonProps: { danger: true },
              onOk: async () => {
                try {
                  await deleteExperimentImport(job.id);
                  await load();
                  void message.success('上传记录已删除，实验草稿仍保留');
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
    }));
  return (
    <>
    <UploadWorkspace
      breadcrumbs={[{ title: '电子实验记录本' }, { title: '实验上传' }]}
      title="实验上传"
      description="上传后自动解析 Word / Excel 并生成独立实验文件；数据不会写入模板中心。"
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
            <TreeSelect
              value={link}
              onChange={setLink}
              allowClear
              placeholder="未关联项目"
              treeData={linkTree}
              treeExpandedKeys={expandedKeys}
              onTreeExpand={expandRelation}
              treeNodeFilterProp="label"
            />
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
      uploadHint="支持 XLSX / XLS / CSV / DOCX / PDF / JPG / PNG / TIF，支持批量上传；原始文件会保留。"
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
      <Upload.Dragger className="ocr-upload-dragger" accept=".pdf,.jpg,.jpeg,.png,.tif,.tiff" multiple fileList={ocrFiles} showUploadList beforeUpload={(file) => {
        if (!file.size || !/\.(pdf|jpg|jpeg|png|tif|tiff)$/i.test(file.name)) { void message.error('OCR仅支持 PDF / JPG / PNG / TIF'); return Upload.LIST_IGNORE; }
        if (ocrFiles.some((item) => item.name === file.name && item.size === file.size)) return Upload.LIST_IGNORE;
        return false;
      }} onChange={({ fileList }) => setOcrFiles(fileList)}><p><PictureOutlined /> 点击或拖拽照片 / PDF 到此处</p><p>支持多页图片，识别后请在实验工作台人工复核</p></Upload.Dragger>
      <Space style={{ marginTop: 16, width: '100%', justifyContent: 'flex-end' }}><Button onClick={() => setOcrOpen(false)}>取消</Button><Button type="primary" disabled={!ocrFiles.length} loading={ocrSubmitting} onClick={() => void submitOcr()}>开始分析</Button></Space>
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
  return ({ XLSX: 'Excel', XLS: '旧版 Excel', CSV: 'CSV', DOCX: 'Word', PDF: 'PDF 扫描件', IMAGE: '图片 OCR' } as Record<string, string>)[format] ?? format;
}
