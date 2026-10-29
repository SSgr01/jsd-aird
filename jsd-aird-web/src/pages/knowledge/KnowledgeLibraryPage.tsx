import { FileTextOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Form, Input, Select, Space, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import { downloadPreviewFile } from '@/components/file-preview';
import { fetchFileBlob, fileContentUrl, stageFile } from '@/services/files';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeUploadCapabilities, type KnowledgeUploadContext, type KnowledgeUploadTask, type UploadPreflight } from '@/services/knowledge';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';

const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

async function runLimited<T, R>(items: T[], limit: number, worker: (item: T, index: number) => Promise<R>) {
  const results = new Array<R>(items.length);
  let next = 0;
  const run = async () => {
    while (true) {
      const index = next;
      next += 1;
      if (index >= items.length) return;
      const item = items[index];
      if (item === undefined) return;
      results[index] = await worker(item, index);
    }
  };
  await Promise.all(Array.from({ length: Math.min(Math.max(1, limit), items.length) }, () => run()));
  return results;
}

function taskStatus(task: KnowledgeUploadTask) {
  if (task.status === 'FAILED') return { label: '处理失败', color: 'error' };
  if (task.status === 'DUPLICATE') return { label: '文件重复', color: 'warning' };
  if (task.status === 'COMPLETED') return { label: '解析完成，待校对', color: 'success' };
  if (task.status === 'QUEUED') return { label: '解析排队', color: 'processing' };
  return { label: '正在上传', color: 'processing' };
}

export function KnowledgeLibraryPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [taskFilter, setTaskFilter] = useState('ALL');
  const [libraryScope, setLibraryScope] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [categoryId, setCategoryId] = useState<string>();
  const [uploadTags, setUploadTags] = useState<string[]>([]);
  const [visibility, setVisibility] = useState('研发部可见');
  const [sourceDescription, setSourceDescription] = useState('');
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [uploading, setUploading] = useState(false);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [uploadTasks, setUploadTasks] = useState<KnowledgeUploadTask[]>([]);
  const [uploadCapabilities, setUploadCapabilities] = useState<KnowledgeUploadCapabilities>();
  const [tasksLoading, setTasksLoading] = useState(true);
  const uploadLock = useRef(false);

  const loadUploadTasks = useCallback(async (silent = false) => {
    if (!silent) setTasksLoading(true);
    try {
      setUploadTasks(await knowledgeApi.uploadTasks());
    } catch (error) {
      if (!silent) void message.error(error instanceof Error ? error.message : '上传记录加载失败');
    } finally {
      if (!silent) setTasksLoading(false);
    }
  }, [message]);

  useEffect(() => { void loadUploadTasks(); }, [loadUploadTasks]);

  useEffect(() => {
    void knowledgeApi.uploadCapabilities().then(setUploadCapabilities)
      .catch((error) => void message.error(error instanceof Error ? error.message : '上传能力加载失败'));
  }, [message]);

  useEffect(() => {
    if (!uploadTasks.some((task) => ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status))) return undefined;
    const timer = window.setInterval(() => void loadUploadTasks(true), 4000);
    return () => window.clearInterval(timer);
  }, [loadUploadTasks, uploadTasks]);

  useEffect(() => {
    setCategoryId(undefined);
    void knowledgeApi.categories(libraryScope).then(setCategories).catch(() => setCategories([]));
  }, [libraryScope]);

  const chooseVersionResolution = (preflight: UploadPreflight) => new Promise<{ resolution: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string }>((resolve) => {
    let targetDocumentId = preflight.possibleVersions[0]?.documentId;
    modal.confirm({
      title: `“${preflight.originalName}”疑似已有文档的新版本`,
      content: <Space direction="vertical" style={{ width: '100%' }}><Typography.Text>请选择作为哪个文档的新版本，或明确创建新文档。</Typography.Text><Select style={{ width: '100%' }} defaultValue={targetDocumentId} onChange={(value) => { targetDocumentId = value; }} options={preflight.possibleVersions.map((item) => ({ value: item.documentId, label: `${item.title} · V${item.versionNo} · 相似度 ${Math.round(item.similarity * 100)}%` }))} /></Space>,
      okText: '作为新版本', cancelText: '创建新文档', closable: false, maskClosable: false,
      onOk: () => resolve({ resolution: 'NEW_VERSION', targetDocumentId }),
      onCancel: () => resolve({ resolution: 'NEW_DOCUMENT' }),
    });
  });

  const chooseExactDuplicateResolution = (preflight: UploadPreflight) => new Promise<{ resolution: 'NEW_VERSION' | 'SKIP'; targetDocumentId?: string }>((resolve) => {
    const duplicate = preflight.exactMatches[0];
    if (!duplicate) { resolve({ resolution: 'SKIP' }); return; }
    modal.confirm({
      title: `“${preflight.originalName}”已存在`,
      content: <Typography.Text>文件内容与“{duplicate.title} V{duplicate.versionNo}”完全相同，不能创建重复文档。是否作为该文档的新版本重新解析？</Typography.Text>,
      okText: '作为新版本', cancelText: '跳过', closable: false, maskClosable: false,
      onOk: () => resolve({ resolution: 'NEW_VERSION', targetDocumentId: duplicate.documentId }),
      onCancel: () => resolve({ resolution: 'SKIP' }),
    });
  });

  const removeUploadTask = async (taskId: string) => {
    try {
      await knowledgeApi.deleteUploadTask(taskId);
      setUploadTasks((current) => current.filter((item) => item.id !== taskId));
      void message.success('上传记录已移除');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '移除上传记录失败');
    }
  };

  const retryUploadTask = async (task: KnowledgeUploadTask) => {
    if (task.documentId && task.documentVersionId) {
      try {
        await knowledgeApi.reparseUploadTask(task.documentId, task.documentVersionId);
        const updated = await knowledgeApi.updateUploadTask(task.id, {
          status: 'QUEUED', progress: 8, sourceFileId: task.sourceFileId,
          uploadContext: task.uploadContext, documentId: task.documentId,
          documentVersionId: task.documentVersionId, message: '已重新提交解析',
        });
        setUploadTasks((current) => [updated, ...current.filter((item) => item.id !== updated.id)]);
        void message.success('已重新提交解析，历史解析记录会保留');
      } catch (error) { void message.error(error instanceof Error ? error.message : '重新解析失败'); }
      return;
    }
    const context = task.uploadContext;
    if (!task.sourceFileId || !context?.categoryId) {
      void message.warning('这条记录没有可重试的原始文件或上传配置，请重新选择文件上传');
      return;
    }
    try {
      const preflight = await knowledgeApi.preflight(task.sourceFileId, context.categoryId);
      let resolution: { resolution: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string } = { resolution: 'NEW_DOCUMENT' };
      if (preflight.decision === 'EXACT_DUPLICATE') {
        const selected = await chooseExactDuplicateResolution(preflight);
        if (selected.resolution === 'SKIP') {
          const updated = await knowledgeApi.updateUploadTask(task.id, { status: 'DUPLICATE', progress: 100, message: '与已有文件内容相同，已跳过' });
          setUploadTasks((current) => [updated, ...current.filter((item) => item.id !== updated.id)]);
          return;
        }
        resolution = { resolution: 'NEW_VERSION', targetDocumentId: selected.targetDocumentId };
      } else if (preflight.decision === 'POSSIBLE_VERSION') {
        resolution = await chooseVersionResolution(preflight);
      }
      const created = await knowledgeApi.createGoverned({
        fileId: task.sourceFileId,
        title: task.fileName.replace(/\.[^.]+$/, ''),
        libraryScope: context.libraryScope,
        categoryId: context.categoryId,
        tags: context.tags,
        resolution: resolution.resolution,
        targetDocumentId: resolution.targetDocumentId,
        sourceInfo: { description: context.sourceDescription || '', originalName: task.fileName, visibility: context.visibility },
        projectRelations: context.projectRelations,
      });
      const updated = await knowledgeApi.updateUploadTask(task.id, {
        status: 'QUEUED', progress: 100, sourceFileId: task.sourceFileId, uploadContext: context,
        documentId: created.id, documentVersionId: created.currentVersionId, message: '已重新提交解析',
      });
      setUploadTasks((current) => [updated, ...current.filter((item) => item.id !== updated.id)]);
      void message.success('已重新进入解析队列');
    } catch (error) { void message.error(error instanceof Error ? error.message : '上传记录重试失败'); }
  };

  const upload = async () => {
    if (uploadLock.current) return;
    const files = fileList.reduce<File[]>((result, item) => {
      if (item.originFileObj) result.push(item.originFileObj);
      return result;
    }, []);
    if (!files.length) { void message.warning('请选择文件'); return; }
    if (!categoryId) { void message.warning('请选择保存分类'); return; }
    uploadLock.current = true;
    setUploading(true);
    try {
      let succeeded = 0; let duplicates = 0; let failed = 0;
      const tags = uploadTags.map((item) => item.trim()).filter(Boolean);
      const uploadContext: KnowledgeUploadContext = {
        libraryScope, categoryId, tags, sourceDescription: sourceDescription.trim(), visibility, projectRelations,
      };
      const createdEntries = await Promise.all(files.map(async (file) => {
        try {
          const task = await knowledgeApi.createUploadTask({ fileName: file.name, size: file.size, uploadContext });
          return { file, task };
        } catch (error) {
          failed += 1;
          void message.error(error instanceof Error ? `${file.name}：上传记录创建失败：${error.message}` : `${file.name}：上传记录创建失败`);
          return undefined;
        }
      }));
      const entries = createdEntries.filter((entry): entry is NonNullable<typeof entry> => Boolean(entry));
      setUploadTasks((current) => [...entries.map((entry) => entry.task), ...current]);
      const prepared = await runLimited(entries, 3, async ({ file, task }) => {
        const update = async (status: Exclude<KnowledgeUploadTask['status'], 'COMPLETED'>, progress: number,
          extra: Partial<Pick<KnowledgeUploadTask, 'sourceFileId' | 'uploadContext' | 'documentId' | 'documentVersionId' | 'message'>> = {}) => {
          const updated = await knowledgeApi.updateUploadTask(task.id, { status, progress, ...extra });
          setUploadTasks((current) => [updated, ...current.filter((item) => item.id !== updated.id)]);
          return updated;
        };
        try {
          const staged = await stageFile(file, 'KNOWLEDGE');
          await update('CHECKING', 45, { sourceFileId: staged.fileId, uploadContext });
          const preflight = await knowledgeApi.preflight(staged.fileId, categoryId);
          return { file, task, staged, preflight, error: undefined, update };
        } catch (error) {
          await update('FAILED', 100, { message: error instanceof Error ? error.message : '文件暂存失败' }).catch(() => undefined);
          return { file, task, staged: undefined, preflight: undefined, error, update };
        }
      });
      for (const item of prepared) {
        if (!item.preflight || !item.staged) { failed += 1; continue; }
        let resolution: { resolution: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string };
        if (item.preflight.decision === 'EXACT_DUPLICATE') {
          const duplicateChoice = await chooseExactDuplicateResolution(item.preflight);
          if (duplicateChoice.resolution === 'SKIP') {
            await item.update('DUPLICATE', 100, { message: '与已有文件内容相同，已跳过' });
            duplicates += 1;
            continue;
          }
          resolution = { resolution: 'NEW_VERSION', targetDocumentId: duplicateChoice.targetDocumentId };
        } else {
          resolution = item.preflight.decision === 'POSSIBLE_VERSION' ? await chooseVersionResolution(item.preflight) : { resolution: 'NEW_DOCUMENT' };
        }
        try {
          await item.update('SUBMITTING', 78);
          const created = await knowledgeApi.createGoverned({ fileId: item.staged.fileId, title: item.file.name.replace(/\.[^.]+$/, ''), libraryScope, categoryId, tags, resolution: resolution.resolution, targetDocumentId: resolution.targetDocumentId, sourceInfo: { description: sourceDescription.trim(), originalName: item.file.name, visibility }, projectRelations });
          await item.update('QUEUED', 100, { documentId: created.id, documentVersionId: created.currentVersionId, message: '已提交解析，离开页面不会取消任务' });
          succeeded += 1;
        } catch (error) {
          let failure: unknown = error;
          let recoveredDuplicate = false;
          if (error instanceof Error && error.message.includes('相同文件已存在')) {
            try {
              // The parallel preflight can become stale when the same file is
              // selected more than once in one batch. Recheck after the first
              // copy is created so the duplicate gets the same explicit choice
              // as a duplicate uploaded in a later batch.
              const latest = await knowledgeApi.preflight(item.staged.fileId, categoryId);
              if (latest.decision === 'EXACT_DUPLICATE') {
                const duplicateChoice = await chooseExactDuplicateResolution(latest);
                if (duplicateChoice.resolution === 'SKIP') {
                  await item.update('DUPLICATE', 100, { message: '与已有文件内容相同，已跳过' });
                  duplicates += 1;
                  continue;
                }
                const created = await knowledgeApi.createGoverned({ fileId: item.staged.fileId, title: item.file.name.replace(/\.[^.]+$/, ''), libraryScope, categoryId, tags, resolution: 'NEW_VERSION', targetDocumentId: duplicateChoice.targetDocumentId, sourceInfo: { description: sourceDescription.trim(), originalName: item.file.name, visibility }, projectRelations });
                await item.update('QUEUED', 100, { documentId: created.id, documentVersionId: created.currentVersionId, message: '已提交解析，离开页面不会取消任务' });
                succeeded += 1;
                recoveredDuplicate = true;
              }
            } catch (recoveryError) {
              failure = recoveryError;
            }
          }
          if (!recoveredDuplicate) {
            await item.update('FAILED', 100, { message: failure instanceof Error ? failure.message : '文件创建失败' }).catch(() => undefined);
            failed += 1;
          }
        }
      }
      setFileList([]);
      await loadUploadTasks(true);
      if (duplicates || failed) void message.warning(`${succeeded} 个文件已进入解析队列，${duplicates} 个完全重复，${failed} 个失败`);
      else void message.success(`已提交 ${succeeded} 个文件；解析完成后进入人工校对`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '文件上传失败'); await loadUploadTasks(true); }
    finally { uploadLock.current = false; setUploading(false); }
  };

  const validateSelectedFile = (file: File) => {
    if (!uploadCapabilities) {
      void message.warning('上传能力仍在加载，请稍后重试');
      return Upload.LIST_IGNORE;
    }
    const dot = file.name.lastIndexOf('.');
    const extension = dot >= 0 ? file.name.slice(dot).toLowerCase() : '';
    if (!uploadCapabilities.supportedExtensions.includes(extension)) {
      void message.error(`${file.name}：不支持该文件格式`);
      return Upload.LIST_IGNORE;
    }
    if (file.size > uploadCapabilities.maxFileBytes) {
      void message.error(`${file.name}：文件超过 ${formatSize(uploadCapabilities.maxFileBytes)} 限制`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const allTasks = [...uploadTasks].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
  const inProgressCount = allTasks.filter((task) => ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status)).length;
  const failedCount = allTasks.filter((task) => ['FAILED', 'DUPLICATE'].includes(task.status)).length;
  const completedCount = allTasks.filter((task) => task.status === 'COMPLETED').length;
  const visibleTask = (task: KnowledgeUploadTask) => taskFilter === 'ALL'
    || (taskFilter === 'PROCESSING' && ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status))
    || (taskFilter === 'FAILED' && ['FAILED', 'DUPLICATE'].includes(task.status))
    || (taskFilter === 'COMPLETED' && task.status === 'COMPLETED');
  const taskRecords: UploadWorkspaceRecord[] = allTasks.filter(visibleTask).map((task) => {
    const state = taskStatus(task);
    const active = ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status);
    return {
      id: `upload-task-${task.id}`, name: task.fileName, icon: <FileTextOutlined />,
      meta: `${formatSize(task.size)}${task.message ? ` · ${task.message}` : ''}`,
      detail: `上传时间 ${new Date(task.createdAt).toLocaleString('zh-CN')}`,
      status: state, progress: task.progress,
      commonActions: {
        view: { onClick: () => navigate(task.documentId ? `/knowledge/documents/${task.documentId}` : `/knowledge/upload-tasks/${task.id}`) },
        download: {
          disabled: !task.sourceFileId,
          title: task.sourceFileId ? undefined : '原文件尚未暂存，无法下载',
          onClick: () => {
            if (!task.sourceFileId) return;
            void downloadPreviewFile({ fileName: task.fileName, load: () => fetchFileBlob(task.sourceFileId!), downloadUrl: fileContentUrl(task.sourceFileId) })
              .catch((error: unknown) => void message.error(error instanceof Error ? error.message : '原文件下载失败'));
          },
        },
        retry: {
          disabled: active || (!task.sourceFileId && !(task.documentId && task.documentVersionId)),
          title: !task.sourceFileId && !(task.documentId && task.documentVersionId) ? '原文件不可用，请重新选择文件' : undefined,
          onClick: () => void retryUploadTask(task),
        },
        delete: {
          disabled: active,
          title: active ? '处理中的任务不能删除' : undefined,
          onClick: () => modal.confirm({
            title: `删除“${task.fileName}”的上传记录？`,
            content: '只隐藏上传历史；原文件、知识文档及解析结果会保留。',
            okText: '删除记录', okButtonProps: { danger: true }, cancelText: '取消',
            onOk: () => removeUploadTask(task.id),
          }),
        },
      },
    };
  });

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: '研发知识库' }, { title: '资料上传' }]}
      title="资料上传"
      description="文件解析完成后进入人工校对，确认后发布。"
      leftTitle="基础分类"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="资料范围" required><Select value={libraryScope} onChange={setLibraryScope} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} /></Form.Item>
        <Form.Item label="保存分类" required help="同分类下名称相近时，会提示作为新版本上传。"><Select allowClear value={categoryId} onChange={setCategoryId} placeholder="选择知识库分类" options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
        <Form.Item label="标签"><Select mode="tags" allowClear tokenSeparators={[',', '，']} value={uploadTags} onChange={setUploadTags} placeholder="选择或输入标签，点击 × 移除" /></Form.Item>
        <Form.Item label="来源信息"><Input.TextArea rows={3} value={sourceDescription} onChange={(event) => setSourceDescription(event.target.value)} placeholder="资料来源、提供方或获取背景" /></Form.Item>
        <Form.Item label="关联项目 / 阶段 / 任务" extra="批量上传时，所选关联会应用到所有文件；新版本沿用原有关联。"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} /></Form.Item>
        <Form.Item label="权限可见" required><Select value={visibility} onChange={setVisibility} options={[{ value: '研发部可见', label: '研发部可见' }, { value: '全员可见', label: '全员可见' }, { value: '项目组可见', label: '项目组可见' }]} /></Form.Item>
      </Form>}
      accept={uploadCapabilities?.supportedExtensions.join(',') || ''}
      beforeUpload={validateSelectedFile}
      uploadDisabled={!uploadCapabilities}
      multiple files={fileList} onFilesChange={setFileList}
      onRemoveFile={(file) => setFileList((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFileList([])}
      uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
      uploadHint={uploadCapabilities ? `支持文档、图片、音频和视频，可批量上传，单个文件最大 ${formatSize(uploadCapabilities.maxFileBytes)}。` : '正在读取服务端支持的文件格式和大小限制…'}
      submitLabel="开始上传" submitIcon={<UploadOutlined />} onSubmit={() => void upload()} submitting={uploading}
      rightTitle="上传记录" rightCount={taskRecords.length}
      rightFilters={[{ key: 'ALL', label: '全部', count: allTasks.length }, { key: 'PROCESSING', label: '进行中', count: inProgressCount }, { key: 'COMPLETED', label: '已完成', count: completedCount }, { key: 'FAILED', label: '需处理', count: failedCount }]}
      activeFilter={taskFilter} onFilterChange={setTaskFilter}
      recordsLoading={tasksLoading}
      records={taskRecords}
    />
  </>;
}
