import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, Checkbox, Form, Modal, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { usePermission } from '@/components/auth/usePermission';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { downloadFile, stageFile } from '@/services/files/file-api';
import { templateApi } from '@/services/templates/template-api';
import {
  productionUploadApi,
  type ProductionUpload,
  type ProductionUploadVisibility,
} from '@/services/production-orders/production-upload-api';

const visibilityOptions: Array<{ value: ProductionUploadVisibility; label: string }> = [
  { value: 'ALL', label: '全员可见' },
  { value: 'QUALITY', label: '品管部可见' },
  { value: 'PROJECT', label: '项目组可见' },
];

const saveLocationOptions = [{ value: 'PRODUCTION_ORDER', label: '生产单' }];

const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'PARSING', label: '解析中' },
  { key: 'PARSED', label: '已解析' },
  { key: 'FAILED', label: '失败' },
];

const recognitionStageLabels: Record<string, string> = {
  QUEUED: '等待解析任务',
  LOADING_SOURCE: '正在读取源文件',
  PARSING_STRUCTURE: '正在分析文件结构',
  MATCHING_TEMPLATE: '正在匹配生产单模板',
  WAITING_TEMPLATE: '等待选择模板',
  EXTRACTING_VALUES: '正在提取字段',
  RECOGNIZING_IMAGE_VALUES: '正在识别图片字段',
  BUILDING_PREVIEW: '正在生成预览',
  REVIEW_REQUIRED: '解析完成',
  COMPLETED: '解析完成',
  FAILED: '解析失败',
};

function visibilityText(value: ProductionUploadVisibility) {
  return visibilityOptions.find((item) => item.value === value)?.label || '全员可见';
}

function recognitionStageText(upload: ProductionUpload) {
  const stage = upload.currentStage ? recognitionStageLabels[upload.currentStage] : undefined;
  if (stage) return stage;
  if (upload.status === 'FAILED') return '解析失败';
  if (['REVIEW_REQUIRED', 'SAVED', 'PUBLISHED'].includes(upload.status)) return '解析完成';
  return '等待解析';
}

function isViewableUpload(upload: ProductionUpload) {
  return (
    ['SAVED', 'PUBLISHED'].includes(upload.status) ||
    (upload.status === 'REVIEW_REQUIRED' &&
      [
        'EXACT_MANIFEST',
        'SIMILAR_AUTO',
        'USER_SELECTED_TEMPLATE',
        'NO_TEMPLATE',
        'USER_REVIEW',
      ].includes(upload.matchMode ?? ''))
  );
}

async function readProductionMetadata(file: File) {
  const fallback = { productionName: file.name.replace(/\.[^.]+$/, '') };
  if (!/\.xlsx$/i.test(file.name)) return fallback;
  try {
    const XLSX = await import('xlsx');
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true });
    const cells = workbook.SheetNames.flatMap((name) => {
      const sheet = workbook.Sheets[name];
      if (!sheet) return [];
      return XLSX.utils
        .sheet_to_json<unknown[]>(sheet, { header: 1, defval: '', raw: true })
        .slice(0, 300)
        .flatMap((row, rowIndex) =>
          row.map((value, columnIndex) => ({ value, rowIndex, columnIndex })),
        );
    });
    const text = (value: unknown) => {
      if (value instanceof Date) {
        const year = value.getFullYear();
        const month = String(value.getMonth() + 1).padStart(2, '0');
        const day = String(value.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      }
      if (value == null) return '';
      if (
        typeof value === 'string' ||
        typeof value === 'number' ||
        typeof value === 'boolean' ||
        typeof value === 'bigint'
      ) {
        return String(value).trim();
      }
      return '';
    };
    const normalize = (value: string) => value.replace(/[\s：:（）()_-]/g, '').toLowerCase();
    const findValue = (labels: string[]) => {
      const normalizedLabels = labels.map(normalize);
      const labelCell = cells.find((cell) => {
        const value = normalize(text(cell.value));
        return value && normalizedLabels.some((label) => value === label || value.includes(label));
      });
      if (!labelCell) return undefined;

      // Support both the common "label | value" layout and a vertical
      // "label" followed by its value.  The previous implementation flattened
      // non-empty cells before indexing, so a blank cell anywhere before the
      // label shifted the lookup and silently returned the wrong field.
      const sameRow = cells
        .filter(
          (cell) =>
            cell.rowIndex === labelCell.rowIndex && cell.columnIndex > labelCell.columnIndex,
        )
        .sort((left, right) => left.columnIndex - right.columnIndex)
        .find((cell) => text(cell.value));
      if (sameRow) return text(sameRow.value);
      const sameColumn = cells
        .filter(
          (cell) =>
            cell.columnIndex === labelCell.columnIndex && cell.rowIndex > labelCell.rowIndex,
        )
        .sort((left, right) => left.rowIndex - right.rowIndex)
        .find((cell) => text(cell.value));
      if (sameColumn) return text(sameColumn.value);

      // Inline labels such as "订单号：PO-001" have no adjacent value cell.
      const inline = text(labelCell.value).match(/[：:]\s*(.+)$/);
      return inline?.[1]?.trim() || undefined;
    };
    const normalizeDate = (value?: string) => {
      if (!value) return value;
      const match = value.match(/^(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})/);
      const [, year = '', month = '', day = ''] = match ?? [];
      return match ? `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}` : value;
    };
    return {
      productionName: fallback.productionName,
      orderNo: findValue(['订单号', '生产单号', '单号']),
      productName: findValue(['品名', '产品名称', '产品']),
      category: findValue(['类别', '产品类别']),
      manufactureDate: normalizeDate(findValue(['制造日期', '生产日期', '日期'])),
    };
  } catch {
    return fallback;
  }
}

export function ProductionOrderUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canCreate = usePermission('production.create') && usePermission('ops.file.upload');
  const canDelete = usePermission('production.delete');
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [saveLocation, setSaveLocation] = useState('PRODUCTION_ORDER');
  const [visibility, setVisibility] = useState<ProductionUploadVisibility>('ALL');
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [replaceExisting, setReplaceExisting] = useState(false);
  const [templates, setTemplates] = useState<
    Array<{
      versionId: string;
      currentPublishedVersionId?: string;
      currentPublishedVersionNo?: number;
      templateCode: string;
      name: string;
      versionNo: number;
    }>
  >([]);
  const [uploads, setUploads] = useState<ProductionUpload[]>([]);
  const [keyword, setKeyword] = useState('');
  const [uploadStatus, setUploadStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string>();
  const [deletingId, setDeletingId] = useState<string>();
  const [retryingId, setRetryingId] = useState<string>();

  const loadUploads = useCallback(async () => {
    setLoading(true);
    try {
      const result = await productionUploadApi.list({
        keyword: keyword || undefined,
        status: uploadStatus === 'ALL' ? undefined : uploadStatus,
        page: page.current,
        size: page.pageSize,
      });
      setUploads(result.items);
      setPage((current) => ({
        ...current,
        current: result.page,
        pageSize: result.size,
        total: result.total,
      }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单上传记录加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, uploadStatus]);

  useEffect(() => {
    void templateApi
      .list({ format: 'XLSX', status: 'PUBLISHED', page: 1, size: 100 })
      .then((result) => setTemplates(result.items))
      .catch((error) => {
        void message.error(error instanceof Error ? error.message : '已发布模板加载失败');
      });
  }, [message]);

  useEffect(() => {
    void loadUploads();
  }, [loadUploads]);

  const validateFile: UploadProps['beforeUpload'] = (file) => {
    if (!/\.(xlsx|docx|png|jpe?g|gif|webp|bmp|tiff?)$/i.test(file.name) || file.size === 0) {
      void message.error('生产单支持 XLSX、DOCX 或图片文件，且文件不能为空');
      return Upload.LIST_IGNORE;
    }
    const signature = `${file.name.toLowerCase()}|${file.size}|${file.lastModified}`;
    if (
      files.some((item) => {
        const existing = item.originFileObj;
        return (
          existing &&
          `${existing.name.toLowerCase()}|${existing.size}|${existing.lastModified}` === signature
        );
      })
    ) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  useEffect(() => {
    if (
      !uploads.some((item) =>
        ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'].includes(item.status),
      )
    )
      return;
    const timer = window.setInterval(() => void loadUploads(), 2000);
    return () => window.clearInterval(timer);
  }, [loadUploads, uploads]);

  const submit = async () => {
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) {
      void message.warning('请先选择生产单文件');
      return;
    }
    const relation = projectRelations.find((item) => item.projectId) ?? {};
    setUploading(true);
    let failed = 0;
    for (const file of sourceFiles) {
      try {
        const staged = await stageFile(file, 'PRODUCTION_SOURCE');
        const metadata = await readProductionMetadata(file);
        await productionUploadApi.create({
          fileId: staged.fileId,
          sourceType: /\.xlsx$/i.test(file.name) ? 'XLSX' : 'PHOTO',
          templateVersionId,
          replaceExisting,
          ...metadata,
          ...relation,
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
    setPage((current) => ({ ...current, current: 1 }));
    await loadUploads();
    if (failed) {
      void message.warning(`${sourceFiles.length - failed} 个文件已上传，${failed} 个文件上传失败`);
    } else {
      void message.success(`已上传 ${sourceFiles.length} 个生产单文件`);
    }
  };

  const retry = async (upload: ProductionUpload) => {
    setRetryingId(upload.id);
    try {
      await productionUploadApi.retry(upload.id);
      void message.success(`“${upload.originalName}”已重新提交解析`);
      await loadUploads();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重复解析失败');
    } finally {
      setRetryingId(undefined);
    }
  };

  const removeUpload = (upload: ProductionUpload) => {
    Modal.confirm({
      title: '删除上传记录？',
      content: ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'].includes(upload.status)
        ? `将取消“${upload.originalName}”的解析任务并删除上传记录，原始文件不会被物理删除。`
        : `将删除“${upload.originalName}”的上传记录，原始文件不会被物理删除。`,
      okText: ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'].includes(upload.status)
        ? '取消解析并删除'
        : '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeletingId(upload.id);
        try {
          await productionUploadApi.delete(upload.id);
          message.success('上传记录已删除');
          await loadUploads();
        } catch (error) {
          message.error(error instanceof Error ? error.message : '上传记录删除失败');
        } finally {
          setDeletingId(undefined);
        }
      },
    });
  };

  const records: UploadWorkspaceRecord[] = uploads.map((upload) => ({
    id: upload.id,
    name: upload.originalName,
    icon: <FileTextOutlined />,
    meta:
      [upload.productionName, upload.orderNo, upload.productName, upload.category]
        .filter(Boolean)
        .join(' · ') || '生产单文件',
    detail: `保存位置：生产单 · 关联项目：${[upload.projectName, upload.stageName, upload.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityText(upload.visibility)} · ${recognitionStageText(upload)} · 已完成 ${['REVIEW_REQUIRED', 'SAVED', 'PUBLISHED'].includes(upload.status) ? 100 : (upload.recognitionProgress ?? 0)}%${upload.failureMessage ? ` · 失败原因：${upload.failureMessage}` : ''}`,
    status:
      upload.status === 'FAILED'
        ? { label: '识别失败', color: 'error' }
        : ['REVIEW_REQUIRED', 'SAVED', 'PUBLISHED'].includes(upload.status)
          ? { label: '已解析', color: 'success' }
          : {
              label: `${recognitionStageText(upload)}${upload.recognitionProgress ? ` ${upload.recognitionProgress}%` : ''}`,
              color: 'processing',
            },
    // Keep the progress track visible for every uploaded record, matching the
    // quality-upload workspace. Terminal records are complete; active records
    // use the recognition progress reported by the parser.
    progress: ['REVIEW_REQUIRED', 'SAVED', 'PUBLISHED'].includes(upload.status)
      ? 100
      : upload.status === 'FAILED'
        ? 0
        : (upload.recognitionProgress ?? 0),
    actions: (
      <Space size={2}>
        <Button
          type="link"
          icon={<EyeOutlined />}
          disabled={!isViewableUpload(upload)}
          onClick={() => navigate(`/production-orders/uploads/${upload.id}/workspace`)}
        >
          查看
        </Button>
        <Button
          type="link"
          icon={<DownloadOutlined />}
          loading={downloadingId === upload.id}
          onClick={() => {
            setDownloadingId(upload.id);
            void downloadFile(upload.fileId, upload.originalName)
              .then(() => message.success('文件下载已开始'))
              .catch((error) =>
                message.error(error instanceof Error ? error.message : '文件下载失败'),
              )
              .finally(() => setDownloadingId(undefined));
          }}
        >
          下载
        </Button>
        {canCreate && (
          <Button
            type="link"
            icon={<ReloadOutlined />}
            loading={retryingId === upload.id}
            onClick={() => void retry(upload)}
          >
            重试
          </Button>
        )}
        {canDelete && (
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            loading={deletingId === upload.id}
            disabled={Boolean(deletingId)}
            onClick={() => removeUpload(upload)}
          >
            删除
          </Button>
        )}
      </Space>
    ),
  }));

  return (
    <>
      <UploadWorkspace
        breadcrumbs={[{ title: '生产单管理' }, { title: '生产单上传' }]}
        title="生产单上传"
        description="关联项目和设置可见权限后，上传 XLSX、DOCX 或生产单图片。"
        headerActions={
          <Button type="primary" onClick={() => navigate('/production-orders/list')}>
            生产单列表
          </Button>
        }
        leftTitle="上传设置"
        classification={
          <Form layout="vertical" component={false}>
            <Form.Item label="保存位置" required>
              <Select
                value={saveLocation}
                onChange={setSaveLocation}
                options={saveLocationOptions}
              />
            </Form.Item>
            <Form.Item label="关联项目 / 阶段 / 任务">
              <ProjectRelationPicker
                value={projectRelations}
                onChange={setProjectRelations}
                multiple={false}
              />
            </Form.Item>
            <Form.Item label="权限可见">
              <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
            </Form.Item>
            <Form.Item label="生产单模板（可选）">
              <Select
                allowClear
                value={templateVersionId}
                onChange={setTemplateVersionId}
                placeholder="可不选择，直接按原文件解析"
                options={templates.map((item) => ({
                  value: item.currentPublishedVersionId ?? item.versionId,
                  label: `${item.templateCode} · ${item.name} · V${item.currentPublishedVersionNo ?? item.versionNo}`,
                }))}
              />
            </Form.Item>
            <Form.Item>
              <Checkbox
                checked={replaceExisting}
                onChange={(event) => setReplaceExisting(event.target.checked)}
              >
                同内容文件已存在时重新上传并替换旧记录
              </Checkbox>
            </Form.Item>
          </Form>
        }
        accept=".xlsx,.docx,.png,.jpg,.jpeg,.gif,.webp,.bmp,.tif,.tiff"
        uploadDisabled={!canCreate}
        beforeUpload={validateFile}
        multiple
        files={files}
        onFilesChange={setFiles}
        onRemoveFile={(file) =>
          setFiles((current) => current.filter((item) => item.uid !== file.uid))
        }
        onClearFiles={() => setFiles([])}
        uploadMainText="拖拽文件到此处，或点击选择文件"
        uploadHint="支持 XLSX、DOCX 或图片；模板可不选，原文件会直接生成可查看记录，模板仅用于辅助字段提取。"
        submitLabel="开始上传"
        submitIcon={<CloudUploadOutlined />}
        onSubmit={() => void submit()}
        submitting={uploading}
        submitDisabled={!canCreate}
        fileRequired={false}
        rightTitle="已上传文件"
        rightCount={page.total}
        rightFilters={statusFilters}
        activeFilter={uploadStatus}
        onFilterChange={(value) => {
          setUploadStatus(value);
          setPage((current) => ({ ...current, current: 1 }));
        }}
        searchValue={keyword}
        onSearchChange={(value) => {
          setKeyword(value);
          setPage((current) => ({ ...current, current: 1 }));
        }}
        searchPlaceholder="搜索文件名称"
        records={records}
        recordsLoading={loading}
        pagination={{ current: page.current, pageSize: page.pageSize, total: page.total }}
        onPageChange={(current, pageSize) => setPage((value) => ({ ...value, current, pageSize }))}
      />
    </>
  );
}
