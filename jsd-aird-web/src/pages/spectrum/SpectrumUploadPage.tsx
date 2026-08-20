import { DownloadOutlined, EyeOutlined, FileImageOutlined, MessageOutlined, UploadOutlined } from '@ant-design/icons';
import { App, Button, Form, Input, Select, Space, Typography } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { downloadPreviewFile, FilePreviewModal, type FilePreviewDescriptor } from '@/components/file-preview';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { stageFile } from '@/services/files';
import { spectrumApi, type SpectrumCategory, type SpectrumChart } from '@/services/spectrum';

const statusLabels: Record<string, [string, string]> = {
  READY: ['可分析', 'success'], DELETED: ['已删除', 'default'],
};
const formatSize = (size: number) => size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

export function SpectrumUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [categories, setCategories] = useState<SpectrumCategory[]>([]);
  const [categoryId, setCategoryId] = useState<string>();
  const [items, setItems] = useState<SpectrumChart[]>([]);
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [sampleName, setSampleName] = useState('');
  const [batchNo, setBatchNo] = useState('');
  const [testConditions, setTestConditions] = useState('');
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const selectedCategory = useMemo(() => categories.find((item) => item.id === categoryId), [categories, categoryId]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await spectrumApi.listCharts({ keyword: keyword || undefined, status: status === 'ALL' ? undefined : status, page: page.current, size: page.pageSize });
      setItems(result.items);
      setPage((value) => ({ ...value, current: result.page, pageSize: result.size, total: result.total }));
    } catch (error) { void message.error(error instanceof Error ? error.message : '图谱列表加载失败'); }
    finally { setLoading(false); }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => {
    void spectrumApi.categories().then((result) => {
      setCategories(result);
      setCategoryId((current) => current || result[0]?.id);
    }).catch(() => setCategories([]));
  }, []);
  useEffect(() => { void load(); }, [load]);

  const updateMetadata = (key: string, value: string) => setMetadata((current) => ({ ...current, [key]: value }));
  const metadataValue = (key: string) => {
    const value = metadata[key];
    return typeof value === 'string' ? value : '';
  };

  const descriptor = (item: SpectrumChart): FilePreviewDescriptor => ({
    fileName: item.originalName, contentType: item.contentType, size: item.size, load: () => spectrumApi.contentBlob(item.id),
  });

  const upload = async () => {
    const files = fileList.flatMap((item) => item.originFileObj ? [item.originFileObj] : []);
    if (!files.length) { void message.warning('请选择 PDF 或图谱图片'); return; }
    if (!categoryId) { void message.warning('请选择图谱分类'); return; }
    setUploading(true);
    let succeeded = 0; let failed = 0;
    try {
      for (const file of files) {
        try {
          const staged = await stageFile(file, 'SPC_CHART');
          await spectrumApi.createChart({
            fileId: staged.fileId,
            title: file.name.replace(/\.[^.]+$/, ''),
            categoryId,
            sampleName: sampleName.trim() || undefined,
            batchNo: batchNo.trim() || undefined,
            testConditions: testConditions.trim() || undefined,
            metadata,
          });
          succeeded += 1;
        } catch { failed += 1; }
      }
      setFileList([]);
      await Promise.all([load(), spectrumApi.categories().then(setCategories)]);
      if (failed) void message.warning(`${succeeded} 个图谱已保存，${failed} 个失败或重复`);
      else void message.success(`已保存 ${succeeded} 个图谱，可直接用于 AI 对话`);
    } catch (error) { void message.error(error instanceof Error ? error.message : '图谱上传失败'); }
    finally { setUploading(false); }
  };

  const records: UploadWorkspaceRecord[] = items.map((item) => {
    const state = statusLabels[item.status] || [item.status, 'default'];
    return {
      id: item.id,
      name: item.title,
      icon: <FileImageOutlined />,
      meta: `${item.categoryName} · ${item.originalName} · ${formatSize(item.size)}`,
      detail: `${item.sampleName || '未填写样品'}${item.batchNo ? ` · ${item.batchNo}` : ''} · ${item.pageCount} 页 · ${new Date(item.updatedAt).toLocaleString('zh-CN')}`,
      status: { label: state[0], color: state[1] },
      actions: <Space size={4} wrap>
        <Button type="link" icon={<EyeOutlined />} onClick={() => setPreviewFile(descriptor(item))}>查看</Button>
        <Button type="link" icon={<DownloadOutlined />} onClick={() => void downloadPreviewFile(descriptor(item))}>下载</Button>
        <Button type="link" icon={<MessageOutlined />} onClick={() => navigate(`/spectrum/chat?chartIds=${item.id}`)}>AI 对话</Button>
      </Space>,
    };
  });

  return <>
    <UploadWorkspace
      breadcrumbs={[{ title: 'AI图谱中心' }, { title: '图谱上传' }]}
      title="图谱上传"
      description="客户仅需提供 PDF 或图谱图片；上传后不依赖 CSV 原始数据，可直接选择图谱进入 AI 分析。"
      headerActions={<Button onClick={() => navigate('/spectrum/view')}>查看图谱分类</Button>}
      leftTitle="图谱分类与信息"
      classification={<Form layout="vertical" component={false}>
        <Form.Item label="图谱分类" required>
          <Select value={categoryId} onChange={(value) => { setCategoryId(value); setMetadata({}); }} placeholder="选择 IR / UV / HPLC 等分类" options={categories.map((item) => ({ value: item.id, label: item.name }))} />
        </Form.Item>
        <Form.Item label="样品名称"><Input value={sampleName} onChange={(event) => setSampleName(event.target.value)} placeholder="可选，如竞品 A、单峰参考 01" /></Form.Item>
        <Form.Item label="批号"><Input value={batchNo} onChange={(event) => setBatchNo(event.target.value)} placeholder="可选" /></Form.Item>
        <Form.Item label="测试条件"><Input.TextArea rows={2} value={testConditions} onChange={(event) => setTestConditions(event.target.value)} placeholder="仪器、范围、溶剂、倍率等，已知则填写" /></Form.Item>
        {selectedCategory?.fields.map((field) => <Form.Item key={field.key} label={field.label}><Input value={metadataValue(field.key)} onChange={(event) => updateMetadata(field.key, event.target.value)} placeholder="可选" /></Form.Item>)}
        {selectedCategory?.analysisHint && <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>当前分类分析提示：{selectedCategory.analysisHint}</Typography.Paragraph>}
      </Form>}
      accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff"
      multiple files={fileList} onFilesChange={setFileList}
      onRemoveFile={(file) => setFileList((current) => current.filter((item) => item.uid !== file.uid))}
      onClearFiles={() => setFileList([])}
      uploadMainText="拖拽 PDF 或图谱图片到此处"
      uploadHint="支持 PDF / PNG / JPG / JPEG / TIF / TIFF，单文件最大 100 MB。"
      submitLabel="保存并开始使用" submitIcon={<UploadOutlined />} onSubmit={() => void upload()} submitting={uploading}
      rightTitle="已上传图谱" rightCount={page.total}
      rightFilters={[{ key: 'ALL', label: '全部' }, { key: 'READY', label: '可分析' }]}
      activeFilter={status} onFilterChange={(value) => { setStatus(value); setPage((current) => ({ ...current, current: 1 })); }}
      searchValue={keyword} onSearchChange={(value) => { setKeyword(value); setPage((current) => ({ ...current, current: 1 })); }} searchPlaceholder="搜索图谱名称"
      records={records} recordsLoading={loading} pagination={page} onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
    />
    <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
  </>;
}
