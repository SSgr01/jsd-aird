import { DownloadOutlined, EyeOutlined, FileTextOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Form, Input, Select, Space, Typography } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { downloadPreviewFile } from '@/components/file-preview';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { stageFile } from '@/services/files';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument, type UploadPreflight } from '@/services/knowledge';

const statusLabels: Record<string, [string, string]> = {
  QUEUED: ['排队中', 'blue'], PROCESSING: ['解析中', 'processing'], READY: ['待校对', 'green'],
  FAILED: ['解析失败', 'red'], REJECTED: ['已驳回', 'red'], PENDING_PROVIDER: ['解析服务暂不可用', 'orange'],
};
const aiLabels: Record<string, [string, string]> = {
  PENDING: ['待授权', 'gold'], APPROVED: ['已授权', 'green'], REJECTED: ['已拒绝', 'red'], REVOKED: ['已撤销', 'orange'],
};
const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

export function KnowledgeLibraryPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<KnowledgeDocument[]>([]);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [aiStatus, setAiStatus] = useState<string>();
  const [libraryScope, setLibraryScope] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [categoryId, setCategoryId] = useState<string>();
  const [tagsText, setTagsText] = useState('');
  const [sourceDescription, setSourceDescription] = useState('');
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await knowledgeApi.list({ keyword: keyword || undefined, status: status === 'ALL' ? undefined : status, aiStatus, page: page.current, size: page.pageSize });
      setItems(result.items);
      setPage((current) => ({ ...current, current: result.page, pageSize: result.size, total: result.total }));
    } catch (error) { void message.error(error instanceof Error ? error.message : '知识库加载失败'); }
    finally { setLoading(false); }
  }, [aiStatus, keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);
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

  const upload = async () => {
    const files = fileList.flatMap((item) => item.originFileObj ? [item.originFileObj] : []);
    if (!files.length) { void message.warning('请选择文件'); return; }
    if (!categoryId) { void message.warning('请选择保存分类'); return; }
    setUploading(true);
    try {
      let succeeded = 0; let duplicates = 0; let failed = 0;
      const tags = tagsText.split(/[,，]/).map((item) => item.trim()).filter(Boolean);
      for (const file of files) {
        try {
          const staged = await stageFile(file, 'KNOWLEDGE');
          const preflight = await knowledgeApi.preflight(staged.fileId, categoryId);
          if (preflight.decision === 'EXACT_DUPLICATE') { duplicates += 1; continue; }
          const resolution = preflight.decision === 'POSSIBLE_VERSION' ? await chooseVersionResolution(preflight) : { resolution: 'NEW_DOCUMENT' as const };
          await knowledgeApi.createGoverned({ fileId: staged.fileId, title: file.name.replace(/\.[^.]+$/, ''), libraryScope, categoryId, tags, resolution: resolution.resolution, targetDocumentId: resolution.targetDocumentId, sourceInfo: { description: sourceDescription.trim(), originalName: file.name } });
          succeeded += 1;
        } catch { failed += 1; }
      }
      setFileList([]);
      await load();
      if (duplicates || failed) void message.warning(`${succeeded} 个文件已进入解析队列，${duplicates} 个完全重复，${failed} 个失败`);
      else void message.success(`已提交 ${succeeded} 个文件；解析完成后进入人工校对`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '文件上传失败'); }
    finally { setUploading(false); }
  };

  const grant = (item: KnowledgeDocument, action: 'APPROVE' | 'REVOKE') => {
    modal.confirm({
      title: action === 'APPROVE' ? `允许“${item.title}”用于 AI 问答？` : `撤销“${item.title}”的 AI 使用授权？`,
      content: action === 'APPROVE' ? '授权作用于整个文档，并自动覆盖后续修订和新文件版本。' : '撤销后将取消待执行任务并清除该文档已有向量；关键词检索不受影响。',
      okText: action === 'APPROVE' ? '确认授权' : '确认撤销', cancelText: '取消',
      onOk: async () => { await knowledgeApi.grant(item.id, action); void message.success(action === 'APPROVE' ? '文档已获得 AI 授权' : '文档 AI 授权已撤销'); await load(); },
    });
  };

  const descriptor = (item: KnowledgeDocument) => ({ fileName: item.originalName, contentType: item.contentType, size: item.size, load: () => knowledgeApi.contentBlob(item.id) });
  const downloadDocument = async (item: KnowledgeDocument) => {
    try { await downloadPreviewFile(descriptor(item)); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };
  const records: UploadWorkspaceRecord[] = items.map((item) => {
    const state = statusLabels[item.status] || [item.status, 'default'];
    const ai = aiLabels[item.aiStatus] || [item.aiStatus, 'default'];
    return {
      id: item.id, name: item.title, icon: <FileTextOutlined />,
      meta: `${item.categoryName || '未分类'} · ${item.originalName} · ${formatSize(item.size)}`,
      detail: `更新于 ${new Date(item.updatedAt).toLocaleString('zh-CN')} · AI ${ai[0]}`,
      status: { label: state[0], color: state[1] },
      actions: <Space size={4} wrap><Button type="link" icon={<EyeOutlined />} onClick={() => navigate(`/knowledge/documents/${item.id}`)}>查看</Button><Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadDocument(item)}>下载</Button>{item.aiStatus === 'APPROVED' ? <Button type="link" danger onClick={() => grant(item, 'REVOKE')}>撤销 AI</Button> : <Button type="link" icon={<SafetyCertificateOutlined />} disabled={!item.currentPublicationId || item.reviewStatus !== 'PUBLISHED'} onClick={() => grant(item, 'APPROVE')}>授权 AI</Button>}</Space>,
    };
  });

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: '研发知识库' }, { title: '资料上传' }]}
      title="资料上传"
      description="文件解析完成后先进入人工校对；确认发布后才建立关键词索引，获得 AI 授权后才建立向量。"
      leftTitle="基础分类"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="资料范围" required><Select value={libraryScope} onChange={setLibraryScope} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} /></Form.Item>
        <Form.Item label="保存分类" required help="同分类内文件名相似时会提示作为新版本上传"><Select allowClear value={categoryId} onChange={setCategoryId} placeholder="选择知识库分类" options={categories.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
        <Form.Item label="标签"><Input value={tagsText} onChange={(event) => setTagsText(event.target.value)} placeholder="多个标签用逗号分隔" /></Form.Item>
        <Form.Item label="来源信息"><Input.TextArea rows={3} value={sourceDescription} onChange={(event) => setSourceDescription(event.target.value)} placeholder="资料来源、提供方或获取背景" /></Form.Item>
        <Form.Item label="权限可见" required><Select defaultValue="研发部可见" options={[{ value: '研发部可见', label: '研发部可见' }, { value: '全员可见', label: '全员可见' }, { value: '项目组可见', label: '项目组可见' }]} /></Form.Item>
        <Form.Item label="AI 使用状态"><Select allowClear placeholder="全部状态" value={aiStatus} onChange={(value) => { setAiStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={Object.entries(aiLabels).map(([value, [label]]) => ({ value, label }))} /></Form.Item>
      </Form>}
      accept=".pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.csv,.txt,.md,.png,.jpg,.jpeg,.tif,.tiff,.wav,.mp3,.m4a,.aac,.flac,.ogg,.opus"
      multiple files={fileList} onFilesChange={setFileList}
      onRemoveFile={(file) => setFileList((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFileList([])}
      uploadMainText="拖拽文件到此处，或点击选择文件"
      uploadHint="支持 PDF / Office / CSV / TXT / 图片 / 音频，支持批量上传与逐文件重复判定。"
      submitLabel="开始上传" submitIcon={<UploadOutlined />} onSubmit={() => void upload()} submitting={uploading}
      rightTitle="已上传文件" rightCount={page.total}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PROCESSING', label: '解析中' }, { key: 'READY', label: '待校对' }, { key: 'FAILED', label: '失败' }]}
      activeFilter={status} onFilterChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword} onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }} searchPlaceholder="搜索文件名称"
      records={records} recordsLoading={loading} pagination={page} onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
    />
  </>;
}
