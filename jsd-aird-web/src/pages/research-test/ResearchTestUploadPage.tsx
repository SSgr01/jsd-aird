import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, DatePicker, Form, Input, Modal, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import { downloadFile } from '@/services/files/file-api';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import {
  deleteResearchTestUpload,
  listResearchTestUploads,
  registerResearchTestUpload,
  retryResearchTestUpload,
  stageResearchTestFile,
  type ResearchTestUpload,
  type ResearchTestType,
} from '@/services/research-test/research-test-api';

const allowed = /\.(pdf|doc|docx|xls|xlsx|csv|png|jpe?g|gif|webp|bmp|tiff?)$/i;
const reportTypeOptions = [{ value: '综合测试报告', label: '综合测试报告' }];
const standardCategoryOptions = ['国家标准', '行业标准', '企业标准', '客户标准', '内部测试方法']
  .map((value) => ({ value, label: value }));
const visibilityOptions = [
  { value: 'ALL', label: '全员可见' },
  { value: 'RND', label: '仅研发部门' },
  { value: 'PROJECT', label: '仅关联项目' },
];
type UploadTask = {
  id: string;
  file: File;
  progress: number;
  status: 'UPLOADING' | 'FAILED';
  stage: string;
  error?: string;
};

export function ResearchTestUploadPage({ type = 'REPORT' }: { type?: ResearchTestType }) {
  const standard = type === 'STANDARD';
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [rows, setRows] = useState<ResearchTestUpload[]>([]);
  const [reportType, setReportType] = useState(reportTypeOptions[0]?.value ?? '综合测试报告');
  const [standardCategory, setStandardCategory] = useState<string>();
  const [scope, setScope] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState<string>();
  const [effectiveTo, setEffectiveTo] = useState<string>();
  const [visibility, setVisibility] = useState('ALL');
  const [relations, setRelations] = useState<ProjectRelationTarget[]>([]);
  const [uploading, setUploading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [tasks, setTasks] = useState<UploadTask[]>([]);
  const taskControllers = useRef(new Map<string, AbortController>());
  const [retryingTaskId, setRetryingTaskId] = useState<string>();
  const [retryingUploadId, setRetryingUploadId] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listResearchTestUploads(type, {
        keyword,
        page: page.current,
        size: page.pageSize,
      });
      setRows(result.items);
      setPage((current) => ({ ...current, total: result.total }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '已上传文件加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, type]);
  useEffect(() => {
    void load();
  }, [load]);

  const validate: UploadProps['beforeUpload'] = (file) => {
    if (!file.size || !allowed.test(file.name)) {
      void message.error('仅支持 PDF / Word / Excel / CSV / 图片，且文件不能为空');
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

  const uploadFiles = async (sourceFiles: File[]) => {
    if (!sourceFiles.length) {
      void message.warning(`请先选择${standard ? '测试方法' : '报告'}文件`);
      return;
    }
    if (standard && !standardCategory) {
      void message.warning('请选择标准类别');
      return;
    }
    if (standard && !effectiveFrom) {
      void message.warning('请选择生效日期');
      return;
    }
    const relation = relations.find((item) => item.projectId);
    setUploading(true);
    let failed = 0;
    let cancelled = 0;
    try {
      for (const file of sourceFiles) {
        const taskId = `${file.name}-${file.size}-${Date.now()}-${Math.random()}`;
        const controller = new AbortController();
        taskControllers.current.set(taskId, controller);
        setTasks((current) => [
          { id: taskId, file, progress: 10, status: 'UPLOADING', stage: '正在上传原始文件' },
          ...current.filter((task) => !(task.file.name === file.name && task.status === 'FAILED')),
        ]);
        try {
          const staged = await stageResearchTestFile(file, type, controller.signal);
          setTasks((current) =>
            current.map((task) =>
              task.id === taskId ? { ...task, progress: 65, stage: `正在生成${standard ? '测试方法' : '报告'}草稿` } : task,
            ),
          );
          await registerResearchTestUpload(type, {
            ...staged,
            category: standard ? standardCategory : reportType,
            scope: standard ? scope : undefined,
            effectiveFrom: standard ? effectiveFrom : undefined,
            effectiveTo: standard ? effectiveTo : undefined,
            visibility,
            projectId: relation?.projectId,
            stageId: relation?.stageId,
            taskId: relation?.taskId,
          }, controller.signal);
          setTasks((current) => current.filter((task) => task.id !== taskId));
        } catch (error) {
          if (controller.signal.aborted) {
            cancelled += 1;
            setTasks((current) => current.filter((task) => task.id !== taskId));
            continue;
          }
          failed += 1;
          const reason = error instanceof Error ? error.message : '上传失败';
          setTasks((current) =>
            current.map((task) =>
              task.id === taskId
                ? { ...task, progress: 0, status: 'FAILED', stage: '上传失败', error: reason }
                : task,
            ),
          );
          void message.error(
            error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`,
          );
        } finally {
          taskControllers.current.delete(taskId);
        }
      }
      // A cancelled task is immediately replaced by retryTask; do not let
      // the old invocation clear its file queue or show a misleading success.
      if (cancelled === sourceFiles.length) return;
      setFiles([]);
      await load();
      const succeeded = sourceFiles.length - failed;
      if (failed) void message.warning(`${succeeded} 个文件上传成功，${failed} 个失败`);
      else void message.success(`已上传 ${succeeded} 个文件并生成${standard ? '测试方法' : '报告'}草稿`);
    } finally {
      setUploading(false);
    }
  };

  const retryTask = async (task: UploadTask) => {
    setRetryingTaskId(task.id);
    try {
      const controller = taskControllers.current.get(task.id);
      if (controller) {
        controller.abort();
        // Wait for the aborted request to leave the task map before starting
        // the replacement, so two uploads cannot race on the same file.
        for (let attempt = 0; attempt < 50 && taskControllers.current.has(task.id); attempt += 1) {
          await new Promise((resolve) => window.setTimeout(resolve, 20));
        }
        setTasks((current) => current.filter((item) => item.id !== task.id));
      }
      await uploadFiles([task.file]);
    } finally {
      setRetryingTaskId(undefined);
    }
  };

  const retryUploadedRow = async (row: ResearchTestUpload) => {
    setRetryingUploadId(row.id);
    try {
      await retryResearchTestUpload(type, row.id);
      await load();
      void message.success(`“${row.originalName}”已重新提交处理`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '研发测试文件重新解析失败');
    } finally {
      setRetryingUploadId(undefined);
    }
  };

  const visibleRows = useMemo(
    () => rows.filter((row) => filter === 'ALL' || uploadStatus(row.status) === filter),
    [filter, rows],
  );
  const taskRecords: UploadWorkspaceRecord[] = tasks
    .filter((task) => filter === 'ALL' || uploadStatus(task.status) === filter)
    .filter((task) => !keyword || task.file.name.toLowerCase().includes(keyword.toLowerCase()))
    .map((task) => ({
      id: task.id,
      name: task.file.name,
      meta: `${formatLabel(task.file.name)} · ${(task.file.size / 1024 / 1024).toFixed(2)} MB`,
      detail: `${task.stage} · 保存位置：${standard ? standardCategory || '未选择标准类别' : reportType} · ${relationLabel(relations)} · ${visibilityLabel(visibility)}${task.error ? ` · 失败原因：${task.error}` : ''}`,
      status:
        task.status === 'FAILED'
          ? { label: '失败', color: 'error' }
          : { label: `解析中 ${task.progress}%`, color: 'processing' },
      progress: task.progress,
      actions: (
        <Button
          type="link"
          icon={<ReloadOutlined />}
          loading={retryingTaskId === task.id}
          onClick={() => void retryTask(task)}
        >
          重试
        </Button>
      ),
    }));
  const records: UploadWorkspaceRecord[] = [
    ...taskRecords,
    ...visibleRows.map((row) => ({
      id: row.id,
      name: row.originalName,
      meta: `${formatLabel(row.originalName)} · ${new Date(row.createdAt).toLocaleString('zh-CN')}`,
      detail: `解析完成，已生成${standard ? '测试方法' : '测试报告'}草稿 · 保存位置：${row.category || '未分类'} · ${[row.projectName, row.stageName, row.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityLabel(row.visibility)}`,
      status: { label: '已解析', color: 'success' },
      progress: 100,
      actions: (
        <Space size={2}>
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/research-test/${standard ? 'standards' : 'reports'}/${row.recordId}`)}
          >
            查看
          </Button>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            onClick={() =>
              void downloadFile(row.fileId, row.originalName).catch((error) =>
                message.error(error instanceof Error ? error.message : '文件下载失败'),
              )
            }
          >
            下载
          </Button>
          <Button
            type="link"
            icon={<ReloadOutlined />}
            loading={retryingUploadId === row.id}
            onClick={() => void retryUploadedRow(row)}
          >
            重试
          </Button>
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            onClick={() =>
              Modal.confirm({
                title: `删除“${row.originalName}”的上传记录？`,
                content: '仅删除上传记录，不删除已经生成的报告草稿。',
                okText: '删除',
                cancelText: '取消',
                okButtonProps: { danger: true },
                onOk: async () => {
                  await deleteResearchTestUpload(type, row.id);
                  await load();
                  void message.success('上传记录已删除，报告草稿仍保留');
                },
              })
            }
          >
            删除
          </Button>
        </Space>
      ),
    })),
  ];

  return (
    <>
      <UploadWorkspace
        breadcrumbs={[{ title: '研发测试中心' }, { title: standard ? '测试标准上传' : '报告上传' }]}
        title={standard ? '测试标准上传' : '报告上传'}
        description={`${standard ? '测试方法' : 'Excel 报告'}按原文档格式打开；图片自动调用 OCR 转为可维护的 Excel 草稿，原始文件和项目关联信息会完整保留。`}
        headerActions={<Button type="primary" onClick={() => navigate(`/research-test/${standard ? 'standards' : 'reports'}`)}>{standard ? '测试标准方法列表' : '报告列表'}</Button>}
        leftTitle="基础分类"
        classification={
          <Form layout="vertical" component={false}>
            {standard ? <>
              <Form.Item label="标准类别" required>
                <Select value={standardCategory} onChange={setStandardCategory} options={standardCategoryOptions} placeholder="请选择标准类别" />
              </Form.Item>
              <Form.Item label="适用对象">
                <Input value={scope} onChange={(event) => setScope(event.target.value)} placeholder="请输入适用对象（选填）" />
              </Form.Item>
              <Form.Item label="生效日期" required>
                <DatePicker style={{ width: '100%' }} onChange={(_, value) => setEffectiveFrom(Array.isArray(value) ? value[0] : value || undefined)} />
              </Form.Item>
              <Form.Item label="失效日期">
                <DatePicker style={{ width: '100%' }} onChange={(_, value) => setEffectiveTo(Array.isArray(value) ? value[0] : value || undefined)} />
              </Form.Item>
            </> : <Form.Item label="报告种类">
              <Select value={reportType} onChange={setReportType} options={reportTypeOptions} />
            </Form.Item>}
            <Form.Item label="关联项目 / 阶段 / 任务">
              <ProjectRelationPicker value={relations} onChange={setRelations} multiple={false} />
            </Form.Item>
            <Form.Item label="权限可见">
              <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
            </Form.Item>
          </Form>
        }
        accept=".pdf,.doc,.docx,.xls,.xlsx,.csv,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff"
        beforeUpload={validate}
        multiple
        files={files}
        onFilesChange={setFiles}
        onRemoveFile={(file) =>
          setFiles((current) => current.filter((item) => item.uid !== file.uid))
        }
        onClearFiles={() => setFiles([])}
        uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
        uploadHint="支持 PDF / Office / CSV / 图片，可批量上传，单个文件最大100M。"
        submitLabel="开始上传"
        submitIcon={<CloudUploadOutlined />}
        onSubmit={() =>
          void uploadFiles(
            files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : [])),
          )
        }
        submitting={uploading}
        rightTitle="已上传文件"
        rightCount={page.total + tasks.length}
        rightFilters={[
          { key: 'ALL', label: '全部' },
          { key: 'PARSING', label: '解析中' },
          { key: 'COMPLETED', label: '已解析' },
          { key: 'FAILED', label: '失败' },
        ]}
        activeFilter={filter}
        onFilterChange={setFilter}
        searchValue={keyword}
        onSearchChange={(value) => {
          setKeyword(value);
          setPage((current) => ({ ...current, current: 1 }));
        }}
        records={records}
        recordsLoading={loading}
        pagination={page}
        onPageChange={(current, pageSize) => setPage({ current, pageSize, total: page.total })}
      />
    </>
  );
}

function formatLabel(fileName: string) {
  const extension = fileName.split('.').pop()?.toLowerCase();
  if (extension === 'xlsx' || extension === 'xls') return 'Excel';
  if (extension === 'docx' || extension === 'doc') return 'Word';
  if (extension === 'csv') return 'CSV';
  if (extension === 'pdf') return 'PDF';
  return '图片 OCR → Excel';
}

function relationLabel(relations: ProjectRelationTarget[]) {
  const relation = relations.find((item) => item.projectId);
  return relation
    ? [relation.projectName, relation.stageName, relation.taskName].filter(Boolean).join(' · ')
    : '未关联项目';
}

function visibilityLabel(value?: string) {
  return visibilityOptions.find((item) => item.value === value)?.label || '全员可见';
}

function uploadStatus(status: string) {
  if (status === 'UPLOADING' || status === 'PARSING') return 'PARSING';
  if (status === 'FAILED') return 'FAILED';
  return 'COMPLETED';
}
