import { DownloadOutlined, EyeOutlined, FileTextOutlined, SafetyCertificateOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Form, Select, Space } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';
import { knowledgeApi, type KnowledgeCategory, type KnowledgeDocument } from '@/services/knowledge';

const statusLabels: Record<string, [string, string]> = {
  QUEUED: ['排队中', 'blue'], PROCESSING: ['解析中', 'processing'], READY: ['已就绪', 'green'],
  FAILED: ['处理失败', 'red'], REJECTED: ['已拒绝', 'red'], PENDING_PROVIDER: ['等待 OCR/ASR', 'orange'],
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
  const [status, setStatus] = useState<string>('ALL');
  const [aiStatus, setAiStatus] = useState<string>();
  const [libraryScope, setLibraryScope] = useState<'INTERNAL' | 'EXTERNAL'>('INTERNAL');
  const [categoryId, setCategoryId] = useState<string>();
  const [categories, setCategories] = useState<KnowledgeCategory[]>([]);
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await knowledgeApi.list({
        keyword: keyword || undefined,
        status: status === 'ALL' ? undefined : status,
        aiStatus,
        page: page.current,
        size: page.pageSize,
      });
      setItems(result.items);
      setPage((current) => ({ ...current, current: result.page, pageSize: result.size, total: result.total }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '知识库加载失败');
    } finally {
      setLoading(false);
    }
  }, [aiStatus, keyword, message, page.current, page.pageSize, status]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    setCategoryId(undefined);
    void knowledgeApi.categories(libraryScope).then(setCategories).catch(() => setCategories([]));
  }, [libraryScope]);

  const upload = async () => {
    const files = fileList.flatMap((item) => item.originFileObj ? [item.originFileObj] : []);
    if (!files.length) { void message.warning('请选择文件'); return; }
    setUploading(true);
    try {
      const results = await Promise.allSettled(files.map((file) => knowledgeApi.upload(file, undefined, { libraryScope, categoryId })));
      const failed = results.filter((result) => result.status === 'rejected').length;
      setFileList([]);
      await load();
      if (failed) void message.warning(`${files.length - failed} 个文件已进入解析队列，${failed} 个文件上传失败`);
      else void message.success(`已提交 ${files.length} 个文件，进入解析队列`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '文件上传失败');
    } finally {
      setUploading(false);
    }
  };

  const grant = (item: KnowledgeDocument, action: 'APPROVE' | 'REVOKE') => {
    modal.confirm({
      title: action === 'APPROVE' ? `允许“${item.title}”进入 AI 上下文？` : `撤销“${item.title}”的 AI 使用授权？`,
      content: action === 'APPROVE' ? '文件原文可能被发送到已配置的模型网关，请确认扫描和内容审批结果。' : '撤销后新的 AI 问答不会再使用该文件。',
      okText: action === 'APPROVE' ? '确认授权' : '确认撤销',
      cancelText: '取消',
      onOk: async () => { await knowledgeApi.grant(item.id, action); void message.success('AI 使用状态已更新'); await load(); },
    });
  };

  const fileDescriptor = (item: KnowledgeDocument): FilePreviewDescriptor => ({
    fileName: item.originalName,
    contentType: item.contentType,
    size: item.size,
    load: () => knowledgeApi.contentBlob(item.id, item.currentVersionId),
  });

  const downloadDocument = async (item: KnowledgeDocument) => {
    try { await downloadPreviewFile(fileDescriptor(item)); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原文件下载失败'); }
  };

  const records = useMemo<UploadWorkspaceRecord[]>(() => items.map((item) => {
    const statusLabel = statusLabels[item.status] || [item.status, 'default'];
    const aiLabel = aiLabels[item.aiStatus] || [item.aiStatus, 'default'];
    return {
      id: item.id,
      name: item.title,
      icon: <FileTextOutlined />,
      meta: `${item.documentType} · ${item.originalName} · ${formatSize(item.size)}`,
      detail: `更新于 ${new Date(item.updatedAt).toLocaleString('zh-CN')} · AI ${aiLabel[0]}`,
      status: { label: statusLabel[0], color: statusLabel[1] },
      actions: <Space size={4} wrap>
        <Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(fileDescriptor(item))}>预览</Button>
        <Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadDocument(item)}>下载</Button>
        <Button type="link" onClick={() => navigate(`/knowledge/documents/${item.id}`)}>详情</Button>
        {item.aiStatus === 'APPROVED' ? <Button type="link" danger onClick={() => grant(item, 'REVOKE')}>撤销 AI</Button> : <Button type="link" icon={<SafetyCertificateOutlined />} disabled={item.status !== 'READY'} onClick={() => grant(item, 'APPROVE')}>授权 AI</Button>}
      </Space>,
    };
  }), [items, navigate, message]);

  return (
    <>
      <UploadWorkspace
      breadcrumbs={[{ title: '研发知识库' }, { title: '资料上传' }]}
      title="资料上传"
      description="支持 PDF、Office、CSV、TXT；图片和音频等待 OCR/ASR 适配器配置。"
      headerActions={<Button onClick={() => navigate('/knowledge/search')}>文件检索</Button>}
      leftTitle="基础分类"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="资料范围" required>
          <Select value={libraryScope} onChange={setLibraryScope} options={[{ value: 'INTERNAL', label: '内部资料' }, { value: 'EXTERNAL', label: '外部资料' }]} />
        </Form.Item>
        <Form.Item label="保存分类" help="未选择时自动归入未分类">
          <Select allowClear value={categoryId} onChange={setCategoryId} placeholder="选择知识库分类" options={categories.map((item) => ({ value: item.id, label: item.name }))} />
        </Form.Item>
        <Form.Item label="权限可见" required>
          <Select defaultValue="研发部可见" options={[{ value: '研发部可见', label: '研发部可见' }, { value: '全员可见', label: '全员可见' }, { value: '项目组可见', label: '项目组可见' }]} />
        </Form.Item>
        <Form.Item label="AI 使用状态">
          <Select allowClear placeholder="全部状态" value={aiStatus} onChange={(value) => { setAiStatus(value); setPage((current) => ({ ...current, current: 1 })); }} options={Object.entries(aiLabels).map(([value, [label]]) => ({ value, label }))} />
        </Form.Item>
      </Form>}
      accept=".pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.csv,.txt,.md"
      multiple
      files={fileList}
      onFilesChange={setFileList}
      onRemoveFile={(file) => setFileList((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFileList([])}
      uploadMainText="拖拽文件到此处，或点击选择文件"
      uploadHint="支持 PDF / Word / Excel / PPT / CSV / TXT，支持批量上传。"
      submitLabel="开始上传"
      submitIcon={<UploadOutlined />}
      onSubmit={() => void upload()}
      submitting={uploading}
      rightTitle="已上传文件"
      rightCount={page.total}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'PROCESSING', label: '解析中' }, { key: 'READY', label: '已就绪' }, { key: 'FAILED', label: '失败' }]}
      activeFilter={status}
      onFilterChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword}
      onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchPlaceholder="搜索文件名称"
      records={records}
      recordsLoading={loading}
      pagination={page}
      onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
      />
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    </>
  );
}
