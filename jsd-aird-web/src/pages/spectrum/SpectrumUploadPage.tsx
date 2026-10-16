import {
  DownloadOutlined,
  EyeOutlined,
  FileImageOutlined,
  MessageOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { App, Button, Form, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  downloadPreviewFile,
  FilePreviewModal,
  type FilePreviewDescriptor,
} from '@/components/file-preview';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { stageFile } from '@/services/files';
import { spectrumApi, type SpectrumCategory, type SpectrumChart, type SpectrumUploadAttempt } from '@/services/spectrum';
import { listExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { generateUUID } from '@/utils/uuid';

const statusLabels: Record<string, [string, string]> = {
  READY: ['可分析', 'success'],
  DELETED: ['已删除', 'default'],
};
const formatSize = (size: number) =>
  size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

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
  const [experiments, setExperiments] = useState<ExperimentSummary[]>([]);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const uploadingRef = useRef(false);
  const [stopPendingUploads, setStopPendingUploads] = useState(false);
  const stopUploadRef = useRef(false);
  const [attempts, setAttempts] = useState<SpectrumUploadAttempt[]>([]);
  const [attemptsOpen, setAttemptsOpen] = useState(false);
  const [attemptsLoading, setAttemptsLoading] = useState(false);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const selectedCategory = useMemo(
    () => categories.find((item) => item.id === categoryId),
    [categories, categoryId],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await spectrumApi.listCharts({
        keyword: keyword || undefined,
        status: status === 'ALL' ? undefined : status,
        page: page.current,
        size: page.pageSize,
      });
      setItems(result.items);
      setPage((value) => ({
        ...value,
        current: result.page,
        pageSize: result.size,
        total: result.total,
      }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '图谱列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => {
    void spectrumApi
      .categories()
      .then((result) => {
        setCategories(result);
        setCategoryId((current) => current || result[0]?.id);
      })
      .catch(() => setCategories([]));
  }, []);
  useEffect(() => {
    void listExperiments({ page: 1, size: 100 })
      .then((result) => setExperiments(result.items))
      .catch(() => setExperiments([]));
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const updateMetadata = (key: string, value: string) =>
    setMetadata((current) => ({ ...current, [key]: value }));
  const metadataValue = (key: string) => {
    const value = metadata[key];
    return typeof value === 'string' ? value : '';
  };

  const descriptor = (item: SpectrumChart): FilePreviewDescriptor => ({
    fileName: item.originalName,
    contentType: item.contentType,
    size: item.size,
    load: () => spectrumApi.previewBlob(item.id),
    downloadUrl: spectrumApi.contentUrl(item.id),
  });

  const upload = async () => {
    if (uploadingRef.current) return;
    const files = fileList.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!files.length) {
      void message.warning('请选择 PDF 或图谱图片');
      return;
    }
    if (!categoryId) {
      void message.warning('请选择图谱分类');
      return;
    }
    setUploading(true);
    uploadingRef.current = true;
    setStopPendingUploads(false);
    stopUploadRef.current = false;
    let succeeded = 0;
    let failed = 0;
    let cancelled = 0;
    try {
      for (const file of files) {
        const requestId = generateUUID();
        if (stopUploadRef.current) {
          cancelled += 1;
          await spectrumApi.recordUploadAttempt({
            requestId,
            categoryId,
            originalName: file.name,
            size: file.size || 0,
            status: 'CANCELLED',
            errorCode: 'USER_CANCELLED',
            errorMessage: '用户停止了后续文件上传',
          }).catch(() => undefined);
          continue;
        }
        let stagedFileId: string | undefined;
        try {
          const staged = await stageFile(file, 'SPC_CHART');
          stagedFileId = staged.fileId;
          await spectrumApi.createChart({
            requestId,
            fileId: staged.fileId,
            title: file.name.replace(/\.[^.]+$/, '').slice(0, 260),
            categoryId,
            sampleName: sampleName.trim() || undefined,
            batchNo: batchNo.trim() || undefined,
            testConditions: testConditions.trim() || undefined,
            metadata,
            originalName: file.name,
            size: file.size || 0,
          });
          succeeded += 1;
        } catch (error) {
          failed += 1;
          // The chart API records its own failed or duplicate outcome. This client record covers
          // failures that happen before a staged file exists (for example, local upload rejection).
          if (!stagedFileId) {
            await spectrumApi.recordUploadAttempt({
              requestId,
              categoryId,
              originalName: file.name,
              size: file.size || 0,
              status: 'FAILED',
              errorCode: 'UPLOAD_STAGE_FAILED',
              errorMessage: error instanceof Error ? error.message : '文件上传暂存失败',
            }).catch(() => undefined);
          }
        }
      }
      setFileList([]);
      await Promise.all([load(), spectrumApi.categories().then(setCategories)]);
      await loadAttempts();
      if (failed || cancelled) void message.warning(`${succeeded} 个图谱已保存，${failed} 个失败，${cancelled} 个已取消`);
      else void message.success(`已保存 ${succeeded} 个图谱，可直接用于 AI 对话`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '图谱上传失败');
    } finally {
      setUploading(false);
      uploadingRef.current = false;
      setStopPendingUploads(false);
    }
  };

  const loadAttempts = async () => {
    setAttemptsLoading(true);
    try { setAttempts(await spectrumApi.uploadAttempts(100)); }
    catch (error) { void message.error(error instanceof Error ? error.message : '上传记录加载失败'); }
    finally { setAttemptsLoading(false); }
  };

  const requestStopUploads = () => {
    stopUploadRef.current = true;
    setStopPendingUploads(true);
    void message.info('当前文件处理完成后，将停止后续文件上传并记录取消状态');
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
      actions: (
        <Space size={4} wrap>
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => setPreviewFile(descriptor(item))}
          >
            查看
          </Button>
          <Button
            type="link"
            icon={<DownloadOutlined />}
            onClick={() => void downloadPreviewFile(descriptor(item))}
          >
            下载
          </Button>
          <Button
            type="link"
            icon={<MessageOutlined />}
            onClick={() => navigate(`/spectrum/chat?chartIds=${item.id}`)}
          >
            AI 对话
          </Button>
        </Space>
      ),
    };
  });

  return (
    <>
      <UploadWorkspace
        breadcrumbs={[{ title: 'AI图谱中心' }, { title: '图谱上传' }]}
        title="图谱上传"
        description="上传图谱文件并补充样品信息，保存后即可开始 AI 分析。"
        headerActions={<Space><Button onClick={() => { setAttemptsOpen(true); void loadAttempts(); }}>上传记录</Button><Button onClick={() => navigate('/spectrum/view')}>查看图谱分类</Button></Space>}
        leftTitle="图谱分类与信息"
        classification={
          <Form layout="vertical" component={false}>
            <Form.Item label="图谱分类" required>
              <Select
                value={categoryId}
                onChange={(value) => {
                  setCategoryId(value);
                  setMetadata({});
                }}
                placeholder="选择 IR / UV / HPLC 等分类"
                options={categories.map((item) => ({ value: item.id, label: item.name }))}
              />
            </Form.Item>
            <Form.Item label="样品名称">
              <Input
                maxLength={260}
                value={sampleName}
                onChange={(event) => setSampleName(event.target.value)}
                placeholder="可选，如竞品 A、单峰参考 01"
              />
            </Form.Item>
            <Form.Item label="批号">
              <Input
                maxLength={160}
                value={batchNo}
                onChange={(event) => setBatchNo(event.target.value)}
                placeholder="可选"
              />
            </Form.Item>
            <Form.Item label="关联 ELN 实验">
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                value={metadata.experimentId as string | undefined}
                onChange={(value) =>
                  setMetadata((current) => ({ ...current, experimentId: value || undefined }))
                }
                placeholder="可选，选择实验后形成稳定关联"
                options={experiments.map((item) => ({
                  value: item.id,
                  label: `${item.experimentNo} · ${item.title}`,
                }))}
              />
            </Form.Item>
            <Form.Item label="测试条件">
              <Input.TextArea
                maxLength={10000}
                rows={2}
                value={testConditions}
                onChange={(event) => setTestConditions(event.target.value)}
                placeholder="仪器、范围、溶剂、倍率等，已知则填写"
              />
            </Form.Item>
            {selectedCategory?.fields.map((field) => (
              <Form.Item key={field.key} label={field.label}>
                <Input
                  value={metadataValue(field.key)}
                  onChange={(event) => updateMetadata(field.key, event.target.value)}
                  placeholder="可选"
                />
              </Form.Item>
            ))}
            {selectedCategory?.analysisHint && (
              <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                当前分类分析提示：{selectedCategory.analysisHint}
              </Typography.Paragraph>
            )}
          </Form>
        }
        accept=".pdf,.png,.jpg,.jpeg,.tif,.tiff"
        multiple
        files={fileList}
        onFilesChange={setFileList}
        onRemoveFile={(file) =>
          setFileList((current) => current.filter((item) => item.uid !== file.uid))
        }
        onClearFiles={() => setFileList([])}
        uploadMainText="拖拽文件到此处/点击选择文件/点击拍照上传"
        uploadHint="支持 PDF / PNG / JPG / JPEG / TIF / TIFF，可批量上传，单文件最大 100 M。"
        submitLabel="保存并开始使用"
        submitIcon={<UploadOutlined />}
        onSubmit={() => void upload()}
        submitting={uploading}
        onCancelSubmit={requestStopUploads}
        uploadDisabled={uploading}
        rightTitle="已上传图谱"
        rightCount={page.total}
        rightFilters={[
          { key: 'ALL', label: '全部' },
          { key: 'READY', label: '可分析' },
        ]}
        activeFilter={status}
        onFilterChange={(value) => {
          setStatus(value);
          setPage((current) => ({ ...current, current: 1 }));
        }}
        searchValue={keyword}
        onSearchChange={(value) => {
          setKeyword(value);
          setPage((current) => ({ ...current, current: 1 }));
        }}
        searchPlaceholder="搜索图谱名称"
        records={records}
        recordsLoading={loading && items.length === 0}
        pagination={page}
        onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
      />
      <FilePreviewModal
        open={Boolean(previewFile)}
        file={previewFile}
        onClose={() => setPreviewFile(undefined)}
      />
      <Modal open={attemptsOpen} title="图谱上传记录" width={900} footer={<Button onClick={() => setAttemptsOpen(false)}>关闭</Button>} onCancel={() => setAttemptsOpen(false)}>
        <Table<SpectrumUploadAttempt>
          rowKey="requestId"
          size="small"
          loading={attemptsLoading}
          dataSource={attempts}
          pagination={{ pageSize: 8 }}
          scroll={{ x: 760 }}
          columns={[
            { title: '文件', dataIndex: 'originalName', width: 220, ellipsis: true },
            { title: '结果', dataIndex: 'status', width: 100, render: (value: SpectrumUploadAttempt['status']) => <Tag color={value === 'SUCCEEDED' ? 'success' : value === 'CANCELLED' ? 'default' : 'error'}>{{ SUCCEEDED: '成功', FAILED: '失败', DUPLICATE: '重复', CANCELLED: '已取消' }[value]}</Tag> },
            { title: '问题详情', dataIndex: 'errorMessage', render: (value?: string, record?: SpectrumUploadAttempt) => value ? <Typography.Text type="danger">{record?.errorCode ? `${record.errorCode}：` : ''}{value}</Typography.Text> : '—' },
            { title: '记录时间', dataIndex: 'createdAt', width: 170, render: (value: string) => new Date(value).toLocaleString('zh-CN') },
          ]}
        />
        {stopPendingUploads && <Typography.Text type="secondary">正在完成当前文件，后续文件会逐条记录为已取消。</Typography.Text>}
      </Modal>
    </>
  );
}
