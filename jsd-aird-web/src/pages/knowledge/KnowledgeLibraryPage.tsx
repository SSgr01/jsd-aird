import { DeleteOutlined, DownloadOutlined, EyeOutlined, FileTextOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Form, Input, Select, Space, Typography } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { downloadPreviewFile } from '@/components/file-preview';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import { stageFile } from '@/services/files';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument, type UploadPreflight } from '@/services/knowledge';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { documentWorkflowStatus } from './knowledge-workflow-status';
import { loadKnowledgeUploadTasks, removeKnowledgeUploadTask, subscribeKnowledgeUploadTasks, updateKnowledgeUploadTask, type KnowledgeUploadTask } from './knowledge-upload-tasks';

const aiLabels: Record<string, [string, string]> = {
  PENDING: ['待授权', 'gold'], APPROVED: ['已授权', 'green'], REJECTED: ['已拒绝', 'red'], REVOKED: ['已撤销', 'orange'],
};
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
  if (task.status === 'FAILED') return { label: '上传失败', color: 'error' };
  if (task.status === 'DUPLICATE') return { label: '文件重复', color: 'warning' };
  if (task.status === 'QUEUED') return { label: '解析排队', color: 'processing' };
  return { label: '正在上传', color: 'processing' };
}

function mergeUploadTasks(tasks: KnowledgeUploadTask[]) {
  const latest = new Map<string, KnowledgeUploadTask>();
  for (const task of tasks) {
    const key = `${task.fileName}\u0000${task.size}`;
    const current = latest.get(key);
    if (!current || task.updatedAt >= current.updatedAt) latest.set(key, task);
  }
  return [...latest.values()].sort((left, right) => right.updatedAt - left.updatedAt);
}

export function KnowledgeLibraryPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgeDocument[]>([]);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [libraryScope, setLibraryScope] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [categoryId, setCategoryId] = useState<string>();
  const [tagsText, setTagsText] = useState('');
  const [sourceDescription, setSourceDescription] = useState('');
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [uploadTasks, setUploadTasks] = useState<KnowledgeUploadTask[]>(() => loadKnowledgeUploadTasks());
  const uploadLock = useRef(false);

  useEffect(() => subscribeKnowledgeUploadTasks(() => setUploadTasks(loadKnowledgeUploadTasks())), []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await knowledgeApi.list({ keyword: keyword || undefined, status: status === 'ALL' ? undefined : status, page: page.current, size: page.pageSize });
      setItems(result.items);
      setPage((current) => ({ ...current, current: result.page, pageSize: result.size, total: result.total }));
    } catch (error) { void message.error(error instanceof Error ? error.message : '知识库加载失败'); }
    finally { setLoading(false); }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!uploadTasks.some((task) => ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status))) return undefined;
    const timer = window.setInterval(() => { void load(); }, 3000);
    return () => window.clearInterval(timer);
  }, [load, uploadTasks]);
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
      const tags = tagsText.split(/[,，]/).map((item) => item.trim()).filter(Boolean);
      const taskIds = files.map((_, index) => `${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`);
      const tasks = files.map((file, index): KnowledgeUploadTask => ({ id: taskIds[index]!, fileName: file.name, size: file.size, status: 'STAGING', progress: 8, updatedAt: Date.now() }));
      tasks.forEach(updateKnowledgeUploadTask);
      setUploadTasks(loadKnowledgeUploadTasks());
      const prepared = await runLimited(files, 3, async (file, index) => {
        const taskId = taskIds[index]!;
        const update = (status: KnowledgeUploadTask['status'], progress: number, extra: Partial<KnowledgeUploadTask> = {}) => {
          updateKnowledgeUploadTask({ id: taskId, fileName: file.name, size: file.size, status, progress, updatedAt: Date.now(), ...extra });
        };
        try {
          const staged = await stageFile(file, 'KNOWLEDGE');
          update('CHECKING', 45);
          const preflight = await knowledgeApi.preflight(staged.fileId, categoryId);
          return { file, taskId, staged, preflight, error: undefined, update };
        } catch (error) {
          update('FAILED', 100, { message: error instanceof Error ? error.message : '文件暂存失败' });
          return { file, taskId, staged: undefined, preflight: undefined, error, update };
        } finally {
          setUploadTasks(loadKnowledgeUploadTasks());
        }
      });
      for (const item of prepared) {
        if (!item.preflight || !item.staged) { failed += 1; continue; }
        let resolution: { resolution: 'NEW_DOCUMENT' | 'NEW_VERSION'; targetDocumentId?: string };
        if (item.preflight.decision === 'EXACT_DUPLICATE') {
          const duplicateChoice = await chooseExactDuplicateResolution(item.preflight);
          if (duplicateChoice.resolution === 'SKIP') {
            removeKnowledgeUploadTask(item.taskId);
            duplicates += 1;
            continue;
          }
          resolution = { resolution: 'NEW_VERSION', targetDocumentId: duplicateChoice.targetDocumentId };
        } else {
          resolution = item.preflight.decision === 'POSSIBLE_VERSION' ? await chooseVersionResolution(item.preflight) : { resolution: 'NEW_DOCUMENT' };
        }
        try {
          item.update('SUBMITTING', 78);
          const created = await knowledgeApi.createGoverned({ fileId: item.staged.fileId, title: item.file.name.replace(/\.[^.]+$/, ''), libraryScope, categoryId, tags, resolution: resolution.resolution, targetDocumentId: resolution.targetDocumentId, sourceInfo: { description: sourceDescription.trim(), originalName: item.file.name }, projectRelations });
          item.update('QUEUED', 100, { documentId: created.id, message: '已提交解析，离开页面不会取消任务' });
          succeeded += 1;
          if (succeeded === 1 || succeeded % 4 === 0) await load();
        } catch (error) {
          item.update('FAILED', 100, { message: error instanceof Error ? error.message : '文件创建失败' });
          failed += 1;
        } finally {
          setUploadTasks(loadKnowledgeUploadTasks());
        }
      }
      setFileList([]);
      await load();
      if (duplicates || failed) void message.warning(`${succeeded} 个文件已进入解析队列，${duplicates} 个完全重复，${failed} 个失败`);
      else void message.success(`已提交 ${succeeded} 个文件；解析完成后进入人工校对`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '文件上传失败'); }
    finally { uploadLock.current = false; setUploading(false); }
  };

  const grant = (item: KnowledgeDocument, action: 'APPROVE' | 'REVOKE') => {
    modal.confirm({
      title: action === 'APPROVE' ? `允许“${item.title}”用于 AI 问答？` : `撤销“${item.title}”的 AI 使用授权？`,
      content: action === 'APPROVE' ? '授权作用于整个文档，并自动覆盖后续修订和新文件版本。' : '撤销后将取消待执行任务并清除该文档已有向量；关键词检索不受影响。',
      okText: action === 'APPROVE' ? '确认授权' : '确认撤销', cancelText: '取消',
      onOk: async () => {
        try {
          await knowledgeApi.grant(item.id, action);
          void message.success(action === 'APPROVE' ? '文档已获得 AI 授权' : '文档 AI 授权已撤销');
          await load();
        } catch (reason) {
          void message.error(reason instanceof Error ? reason.message : 'AI 授权操作失败');
          throw reason;
        }
      },
    });
  };

  const descriptor = (item: KnowledgeDocument) => ({ fileName: item.originalName, contentType: item.contentType, size: item.size, load: () => knowledgeApi.contentBlob(item.id) });
  const downloadDocument = async (item: KnowledgeDocument) => {
    try { await downloadPreviewFile(descriptor(item)); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };
  const visibleTask = (task: KnowledgeUploadTask) => status === 'ALL'
    || (status === 'PROCESSING' && ['STAGING', 'CHECKING', 'SUBMITTING', 'QUEUED'].includes(task.status))
    || (status === 'FAILED' && ['FAILED', 'DUPLICATE'].includes(task.status));
  const taskRecords: UploadWorkspaceRecord[] = mergeUploadTasks(uploadTasks).filter(visibleTask).filter((task) => !task.documentId || !items.some((item) => item.id === task.documentId)).map((task) => {
    const state = taskStatus(task);
    return {
      id: `upload-task-${task.id}`, name: task.fileName, icon: <FileTextOutlined />,
      meta: `${formatSize(task.size)} · ${task.message || '任务仍在进行，离开页面不会取消'}`,
      detail: `上传任务更新时间 ${new Date(task.updatedAt).toLocaleString('zh-CN')}`,
      status: state, progress: task.progress,
      actions: ['FAILED', 'DUPLICATE'].includes(task.status)
        ? <Button type="link" danger={task.status === 'FAILED'} icon={<DeleteOutlined />} onClick={() => removeKnowledgeUploadTask(task.id)}>清除</Button>
        : undefined,
    };
  });
  const records: UploadWorkspaceRecord[] = [...taskRecords, ...items.map((item) => {
    const state = documentWorkflowStatus(item);
    const ai = aiLabels[item.aiStatus] || [item.aiStatus, 'default'];
    return {
      id: item.id, name: item.title, icon: <FileTextOutlined />,
      meta: `${item.categoryName || '未分类'} · ${item.originalName} · ${formatSize(item.size)}`,
      detail: `更新于 ${new Date(item.updatedAt).toLocaleString('zh-CN')} · AI ${ai[0]}`,
      status: state,
      actions: <Space size={4} wrap><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>查看</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadDocument(item)}>下载</Button>{item.aiStatus === 'APPROVED' ? <Button type="link" danger onClick={() => grant(item, 'REVOKE')}>撤销 AI</Button> : <Button type="link" icon={<SafetyCertificateOutlined />} disabled={!item.currentPublicationId || item.reviewStatus !== 'PUBLISHED'} onClick={() => grant(item, 'APPROVE')}>授权 AI</Button>}</Space>,
    };
  })];

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: '研发知识库' }, { title: '资料上传' }]}
      title="资料上传"
      description="文件解析完成后进入人工校对，确认后发布。"
      leftTitle="基础分类"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="资料范围" required><Select value={libraryScope} onChange={setLibraryScope} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} /></Form.Item>
        <Form.Item label="保存分类" required help="同分类内文件名相似时会提示作为新版本上传"><Select allowClear value={categoryId} onChange={setCategoryId} placeholder="选择知识库分类" options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
        <Form.Item label="标签"><Input value={tagsText} onChange={(event) => setTagsText(event.target.value)} placeholder="多个标签用逗号分隔" /></Form.Item>
        <Form.Item label="来源信息"><Input.TextArea rows={3} value={sourceDescription} onChange={(event) => setSourceDescription(event.target.value)} placeholder="资料来源、提供方或获取背景" /></Form.Item>
        <Form.Item label="关联项目 / 阶段 / 任务" extra="批量上传时会应用到每个逻辑文档；新版本默认继承原文档关系。"><ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} /></Form.Item>
        <Form.Item label="权限可见" required><Select defaultValue="研发部可见" options={[{ value: '研发部可见', label: '研发部可见' }, { value: '全员可见', label: '全员可见' }, { value: '项目组可见', label: '项目组可见' }]} /></Form.Item>
      </Form>}
      accept=".pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.csv,.txt,.md,.png,.jpg,.jpeg,.tif,.tiff,.wav,.mp3,.m4a,.aac,.flac,.ogg,.opus"
      multiple files={fileList} onFilesChange={setFileList}
      onRemoveFile={(file) => setFileList((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFileList([])}
      uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
      uploadHint="支持 PDF / Office / CSV / TXT / 图片 / 音频，可批量上传，单个文件最大100M。"
      submitLabel="开始上传" submitIcon={<UploadOutlined />} onSubmit={() => void upload()} submitting={uploading}
      rightTitle="已上传文件" rightCount={page.total + taskRecords.length}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PROCESSING', label: '解析中' }, { key: 'READY', label: '解析完成' }, { key: 'FAILED', label: '失败' }]}
      activeFilter={status} onFilterChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword} onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }} searchPlaceholder="搜索文件名称"
      records={records} recordsLoading={loading} pagination={page} onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
    />
  </>;
}
