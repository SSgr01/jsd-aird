import {
  FileImageOutlined,
  MessageOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { App, Button, Form, Input, Select, Space, Typography } from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  FilePreviewModal,
  type FilePreviewDescriptor,
} from '@/components/file-preview';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import { downloadFile, fetchFileBlob, stageFile } from '@/services/files';
import { spectrumApi, type SpectrumCategory, type SpectrumChart, type SpectrumUploadAttempt } from '@/services/spectrum';
import { listExperiments, type ExperimentSummary } from '@/services/experiments/experiment-api';
import { projectResourceApi, type ProjectRelationTarget } from '@/services/project/project-resource-api';
import { generateUUID } from '@/utils/uuid';

const attemptStatusLabels: Record<SpectrumUploadAttempt['status'], [string, string]> = {
  SUCCEEDED: ['已保存', 'success'],
  FAILED: ['失败', 'error'],
  DUPLICATE: ['文件重复', 'warning'],
  CANCELLED: ['已取消', 'default'],
};
const formatSize = (size: number) =>
  size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / 1024 / 1024).toFixed(1)} MB`;

export function SpectrumUploadPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [categories, setCategories] = useState<SpectrumCategory[]>([]);
  const [categoryId, setCategoryId] = useState<string>();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [sampleName, setSampleName] = useState('');
  const [batchNo, setBatchNo] = useState('');
  const [testConditions, setTestConditions] = useState('');
  const [metadata, setMetadata] = useState<Record<string, unknown>>({});
  const [referenceTargets, setReferenceTargets] = useState<ProjectRelationTarget[]>([]);
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
  const [retryingRequestId, setRetryingRequestId] = useState<string>();
  const [deletingRequestId, setDeletingRequestId] = useState<string>();
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const selectedCategory = useMemo(
    () => categories.find((item) => item.id === categoryId),
    [categories, categoryId],
  );

  const loadAttempts = useCallback(async () => {
    setLoading(true);
    try {
      setAttempts(await spectrumApi.uploadAttempts(200));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '上传记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

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
    void loadAttempts();
  }, [loadAttempts]);

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
    const referenceFailures: string[] = [];
    const selectedReferenceTargets = referenceTargets.filter((target) => target.projectId);
    try {
      for (const file of files) {
        const requestId = generateUUID();
        const uploadContext = {
          title: file.name.replace(/\.[^.]+$/, '').slice(0, 260),
          sampleName: sampleName.trim() || undefined,
          batchNo: batchNo.trim() || undefined,
          testConditions: testConditions.trim() || undefined,
          metadata,
          referenceTargets: selectedReferenceTargets,
        };
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
            uploadContext,
          }).catch(() => undefined);
          continue;
        }
        let stagedFileId: string | undefined;
        try {
          const staged = await stageFile(file, 'SPC_CHART');
          stagedFileId = staged.fileId;
          const chart = await spectrumApi.createChart({
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
            uploadContext,
          });
          succeeded += 1;
          if (selectedReferenceTargets.length) {
            try {
              await projectResourceApi.addReferences('SPECTRUM_CHART', chart.id, selectedReferenceTargets,
                `${chart.categoryName}${chart.sampleName ? ` · ${chart.sampleName}` : ''}`);
            } catch (referenceError) {
              referenceFailures.push(`${file.name}：${referenceError instanceof Error ? referenceError.message : '加入项目参考资料失败'}`);
            }
          }
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
              uploadContext,
            }).catch(() => undefined);
          }
        }
      }
      setFileList([]);
      await Promise.all([loadAttempts(), spectrumApi.categories().then(setCategories)]);
      if (referenceFailures.length) {
        void message.error(`${referenceFailures.length} 个图谱已上传，但未加入项目参考资料：${referenceFailures[0]}`);
      }
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

  const requestStopUploads = () => {
    stopUploadRef.current = true;
    setStopPendingUploads(true);
    void message.info('当前文件处理完成后，将停止后续文件上传并记录取消状态');
  };

  const runRetry = async (attempt: SpectrumUploadAttempt, duplicateOverride: boolean) => {
    if (!attempt.fileObjectId || !attempt.categoryId) {
      void message.warning('这条记录没有可重试的源文件，请重新选择文件上传');
      return;
    }
    const context = attempt.uploadContext || {};
    const contextString = (key: string) => typeof context[key] === 'string' ? context[key] as string : undefined;
    const contextObject = (key: string): Record<string, unknown> =>
      context[key] && typeof context[key] === 'object' && !Array.isArray(context[key])
        ? context[key] as Record<string, unknown>
        : {};
    const targets = Array.isArray(context.referenceTargets)
      ? context.referenceTargets.filter((target): target is ProjectRelationTarget =>
        Boolean(target && typeof target === 'object' && 'projectId' in target))
      : [];
    const requestId = generateUUID();
    setRetryingRequestId(attempt.requestId);
    try {
      let stagedFileId: string;
      try {
        const sourceBlob = await fetchFileBlob(attempt.fileObjectId);
        const sourceFile = new File([sourceBlob], attempt.originalName, {
          type: sourceBlob.type || 'application/octet-stream',
        });
        stagedFileId = (await stageFile(sourceFile, 'SPC_CHART')).fileId;
      } catch (error) {
        await spectrumApi.recordUploadAttempt({
          requestId,
          parentRequestId: attempt.requestId,
          categoryId: attempt.categoryId,
          fileObjectId: attempt.fileObjectId,
          originalName: attempt.originalName,
          size: attempt.size,
          status: 'FAILED',
          errorCode: 'RETRY_STAGE_FAILED',
          errorMessage: error instanceof Error ? error.message : '源文件读取或暂存失败',
          uploadContext: context,
        });
        throw error;
      }
      const chart = await spectrumApi.createChart({
        requestId,
        parentRequestId: attempt.requestId,
        fileId: stagedFileId,
        title: contextString('title') || attempt.originalName.replace(/\.[^.]+$/, ''),
        categoryId: attempt.categoryId,
        sampleName: contextString('sampleName'),
        batchNo: contextString('batchNo'),
        testConditions: contextString('testConditions'),
        metadata: contextObject('metadata'),
        originalName: attempt.originalName,
        size: attempt.size,
        duplicateOverride,
        uploadContext: context,
      });
      if (targets.length) {
        await projectResourceApi.addReferences('SPECTRUM_CHART', chart.id, targets,
          `${chart.categoryName}${chart.sampleName ? ` · ${chart.sampleName}` : ''}`);
      }
      void message.success(duplicateOverride ? '已作为新图谱重新保存' : '图谱重试成功');
      await loadAttempts();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '图谱重试失败');
      await loadAttempts();
    } finally {
      setRetryingRequestId(undefined);
    }
  };

  const retryAttempt = (attempt: SpectrumUploadAttempt) => {
    const override = attempt.status === 'SUCCEEDED' || attempt.status === 'DUPLICATE';
    if (!override) {
      void runRetry(attempt, false);
      return;
    }
    modal.confirm({
      title: attempt.status === 'SUCCEEDED' ? '作为新图谱重新上传？' : '忽略重复提示并保存为新图谱？',
      content: '系统会创建新的图谱记录，已有图谱和文件都会保留。',
      okText: '继续',
      cancelText: '取消',
      onOk: () => runRetry(attempt, true),
    });
  };

  const hideAttempt = (attempt: SpectrumUploadAttempt) => {
    modal.confirm({
      title: '删除这条上传记录？',
      content: '只会从上传记录中隐藏，不会删除源文件、已生成的图谱或项目参考资料。',
      okText: '删除记录',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeletingRequestId(attempt.requestId);
        try {
          await spectrumApi.hideUploadAttempt(attempt.requestId);
          await loadAttempts();
          void message.success('上传记录已删除');
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '删除上传记录失败');
        } finally {
          setDeletingRequestId(undefined);
        }
      },
    });
  };

  const filteredAttempts = useMemo(() => attempts.filter((attempt) => {
    if (status !== 'ALL' && attempt.status !== status) return false;
    if (keyword && !attempt.originalName.toLocaleLowerCase().includes(keyword.toLocaleLowerCase())) return false;
    return true;
  }), [attempts, keyword, status]);
  const visibleAttempts = filteredAttempts.slice((page.current - 1) * page.pageSize, page.current * page.pageSize);
  const records: UploadWorkspaceRecord[] = visibleAttempts.map((attempt) => {
    const state = attemptStatusLabels[attempt.status];
    const categoryName = categories.find((category) => category.id === attempt.categoryId)?.name || '图谱';
    const context = attempt.uploadContext || {};
    const sampleName = typeof context.sampleName === 'string' ? context.sampleName : '';
    const detail = attempt.errorMessage
      ? `${attempt.errorCode ? `${attempt.errorCode}：` : ''}${attempt.errorMessage}`
      : `${sampleName ? `样品：${sampleName} · ` : ''}${new Date(attempt.createdAt).toLocaleString('zh-CN')}`;
    return {
      id: attempt.requestId,
      name: attempt.originalName,
      icon: <FileImageOutlined />,
      meta: `${categoryName} · ${formatSize(attempt.size)}`,
      detail,
      status: { label: state[0], color: state[1] },
      commonActions: {
        view: {
          disabled: !attempt.chartId,
          title: attempt.chartId ? '预览图谱' : '失败记录没有可预览的图谱',
          onClick: () => {
            if (!attempt.chartId) return;
            void spectrumApi.chart(attempt.chartId).then((chart) => setPreviewFile(descriptor(chart)))
              .catch((error) => void message.error(error instanceof Error ? error.message : '图谱预览加载失败'));
          },
        },
        download: {
          disabled: !attempt.fileObjectId,
          title: attempt.fileObjectId ? '下载源文件' : '源文件暂不可下载',
          onClick: () => {
            if (attempt.fileObjectId) void downloadFile(attempt.fileObjectId, attempt.originalName)
              .catch((error) => void message.error(error instanceof Error ? error.message : '源文件下载失败'));
          },
        },
        retry: {
          disabled: !attempt.fileObjectId || !attempt.categoryId,
          loading: retryingRequestId === attempt.requestId,
          title: attempt.fileObjectId ? '使用原参数重新处理' : '记录中没有可重试的源文件',
          onClick: () => retryAttempt(attempt),
        },
        delete: {
          loading: deletingRequestId === attempt.requestId,
          onClick: () => hideAttempt(attempt),
        },
      },
      actions: (
        attempt.chartId ? <Button type="link" icon={<MessageOutlined />}
          onClick={() => navigate(`/spectrum/chat?chartIds=${attempt.chartId}`)}>AI 对话</Button> : null
      ),
    };
  });

  return (
    <>
      <UploadWorkspace
        breadcrumbs={[{ title: 'AI图谱中心' }, { title: '图谱上传' }]}
        title="图谱上传"
        description="上传图谱文件并补充样品信息，保存后即可开始 AI 分析。"
        headerActions={<Space>
          {stopPendingUploads && <Typography.Text type="secondary">正在停止后续文件…</Typography.Text>}
          <Button onClick={() => navigate('/spectrum/view')}>查看图谱分类</Button>
        </Space>}
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
            <Form.Item label="加入项目参考资料">
              <ProjectRelationPicker
                value={referenceTargets}
                onChange={setReferenceTargets}
                emptyText="尚未选择项目参考资料"
                addText="添加参考资料"
                removeText="移除参考资料"
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
        rightCount={filteredAttempts.length}
        rightFilters={[
          { key: 'ALL', label: '全部' },
          { key: 'SUCCEEDED', label: '已保存' },
          { key: 'FAILED', label: '失败' },
          { key: 'DUPLICATE', label: '重复' },
          { key: 'CANCELLED', label: '已取消' },
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
        recordsLoading={loading && attempts.length === 0}
        pagination={{ ...page, total: filteredAttempts.length }}
        onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
      />
      <FilePreviewModal
        open={Boolean(previewFile)}
        file={previewFile}
        onClose={() => setPreviewFile(undefined)}
      />
    </>
  );
}
