import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { downloadFile, stageFile } from '@/services/files/file-api';
import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import {
  qualityApi,
  type QualityCategory,
  type QualityType,
  type QualityTypeId,
  type QualityUpload,
  type QualityVisibility,
} from '@/services/quality/quality-api';

type QualityCategoryOption = QualityCategory & { typeId: QualityTypeId };

const accept = '.pdf,.doc,.docx,.xls,.xlsx,.csv,.jpg,.jpeg,.png,.tif,.tiff';
const supportedExtensions = /\.(pdf|doc|docx|xls|xlsx|csv|jpg|jpeg|png|tif|tiff)$/i;
const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'PARSING', label: '解析中' },
  { key: 'COMPLETED', label: '已解析' },
  { key: 'FAILED', label: '失败' },
];
const statusView: Record<string, { label: string; color: string }> = {
  DRAFT_CREATED: { label: '已解析', color: 'success' },
  UPLOADED: { label: '已上传', color: 'default' },
  PARSING: { label: '解析中', color: 'processing' },
  FAILED: { label: '失败', color: 'error' },
  QUEUED: { label: '排队中', color: 'processing' },
};
const progressDescriptions: Record<string, string> = {
  UPLOADED: '等待开始解析',
  QUEUED: '等待解析任务',
  PARSING: '正在读取并解析源文件',
  DRAFT_CREATED: '解析完成，已生成品管草稿',
  FAILED: '解析失败，可点击重试',
};
const visibilityOptions = [
  { value: 'ALL', label: '全员可见' },
  { value: 'RND', label: '研发部可见' },
  { value: 'QUALITY', label: '品管部可见' },
  { value: 'PROJECT', label: '项目组可见' },
];

function formatUploadStatus(upload: QualityUpload) {
  const status = statusView[upload.status] || { label: upload.status || '未知', color: 'default' };
  const progress = upload.status === 'FAILED' || upload.status === 'UPLOADED'
    ? 0
    : upload.status === 'QUEUED' ? 10
      : upload.status === 'DRAFT_CREATED' ? 100 : 50;
  const description = progressDescriptions[upload.status] || '等待处理';
  return { status, progress, description };
}

function visibilityText(value?: string) {
  const option = visibilityOptions.find((item) => item.value === value);
  return option?.label || value || '全员可见';
}

function fileTypeText(name: string) {
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'doc' || extension === 'docx') return 'Word';
  if (extension === 'xls' || extension === 'xlsx' || extension === 'csv') return 'Excel/CSV';
  if (extension === 'pdf') return 'PDF';
  if (['jpg', 'jpeg', 'png', 'tif', 'tiff'].includes(extension || '')) return '图片';
  return '文件';
}

function formatSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function QualityUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [types, setTypes] = useState<QualityType[]>([]);
  const [categories, setCategories] = useState<QualityCategoryOption[]>([]);
  const [categoryId, setCategoryId] = useState<string>();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [projectRelations, setProjectRelations] = useState<ProjectRelationTarget[]>([]);
  const [visibility, setVisibility] = useState<QualityVisibility>('ALL');
  const [uploads, setUploads] = useState<QualityUpload[]>([]);
  const [keyword, setKeyword] = useState('');
  const [uploadStatus, setUploadStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string>();
  const [deletingId, setDeletingId] = useState<string>();
  const [retryingId, setRetryingId] = useState<string>();

  const categoryOptions = useMemo(
    () =>
      types.map((type) => ({
        label: type.name,
        options: categories
          .filter((category) => category.typeId === type.id)
          .sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
          .map((category) => ({ value: category.id, label: category.name })),
      })),
    [categories, types],
  );

  const loadCatalog = useCallback(async () => {
    try {
      const loadedTypes = await qualityApi.types();
      setTypes(loadedTypes);
      const typeCategoryPairs = await Promise.all(
        loadedTypes.map(async (type) => {
          const categoryItems = await qualityApi.categories(type.id);
          return categoryItems.map((category) => ({ ...category, typeId: type.id }));
        }),
      );
      const flattened = typeCategoryPairs.flat();
      setCategories(flattened);
      setCategoryId((current) => current ?? flattened[0]?.id);
    } catch {
      message.error('品管分类加载失败');
    }
  }, [message]);

  const loadUploads = useCallback(async (targetPage = page.current, targetPageSize = page.pageSize) => {
    setLoading(true);
    try {
      const result = await qualityApi.uploads({
        keyword: keyword || undefined,
        status:
          uploadStatus === 'ALL'
            ? undefined
            : uploadStatus === 'COMPLETED'
              ? 'DRAFT_CREATED'
              : uploadStatus,
        page: targetPage,
        size: targetPageSize,
      });
      setUploads(result.items);
      setPage((current) => ({
        ...current,
        total: result.total,
        current: result.page,
        pageSize: result.size,
      }));
    } catch {
      message.error('已上传文件加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, uploadStatus]);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  const validate: UploadProps['beforeUpload'] = (file) => {
    const name = file.name.toLowerCase();
    if (!supportedExtensions.test(name) || file.size === 0) {
      void message.error('仅支持 PDF / DOC / DOCX / XLS / XLSX / CSV / JPG / JPEG / PNG / TIF / TIFF 文件，且文件不能为空');
      return Upload.LIST_IGNORE;
    }
    const signature = `${file.name.toLowerCase()}|${file.size}|${file.lastModified}`;
    if (
      files.some((item) => {
        const existed = item.originFileObj;
        return (
          existed &&
          `${existed.name.toLowerCase()}|${existed.size}|${existed.lastModified}` === signature
        );
      })
    ) {
      void message.warning(`“${file.name}”已经在待上传队列中`);
      return Upload.LIST_IGNORE;
    }
    return false;
  };

  const submit = async () => {
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) {
      void message.warning('请先选择品管源文件');
      return;
    }
    if (!categoryId) {
      void message.warning('请选择保存位置');
      return;
    }
    const selected = projectRelations.find((item) => item.projectId);
    if (visibility === 'PROJECT' && !selected?.projectId) {
      void message.warning('项目组可见必须先关联项目');
      return;
    }
    setUploading(true);
    let failed = 0;
    try {
      for (const file of sourceFiles) {
        try {
          const staged = await stageFile(file, 'QUALITY_SOURCE');
          const submitted = await qualityApi.createUpload({
            fileId: staged.fileId,
            categoryId,
            originalName: staged.originalName,
            contentType: staged.contentType,
            size: staged.size,
            sha256: staged.sha256,
            projectId: selected?.projectId,
            projectName: selected?.projectName,
            stageId: selected?.stageId,
            stageName: selected?.stageName,
            taskId: selected?.taskId,
            taskName: selected?.taskName,
            visibility,
          });
          // The API returns HTTP 200 even when source parsing has failed so
          // the upload ledger can retain the failed file and offer retry.
          // Do not report that case as a successful submission.
          if (submitted.status === 'FAILED') {
            failed += 1;
            void message.error(
              `${file.name}：解析失败${submitted.errorMessage ? `：${submitted.errorMessage}` : ''}`,
            );
          }
        } catch (error) {
          failed += 1;
          void message.error(
            error instanceof Error ? `${file.name}：${error.message}` : `${file.name} 上传失败`,
          );
        }
      }
      setFiles([]);
      setPage((current) => ({ ...current, current: 1 }));
      await loadUploads();
      if (failed) {
        void message.warning(
          `${sourceFiles.length - failed} 个文件已提交，${failed} 个文件上传失败`,
        );
      } else {
        void message.success(`已提交 ${sourceFiles.length} 个文件`);
      }
    } finally {
      setUploading(false);
    }
  };

  const removeUpload = (upload: QualityUpload) => {
    Modal.confirm({
      title: '删除上传记录？',
      content: `将删除“${upload.originalName}”的上传台账记录，已生成的品管草稿和源文件不会被删除。`,
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        setDeletingId(upload.id);
        try {
          await qualityApi.deleteUpload(upload.id);
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

  const retryUpload = async (upload: QualityUpload) => {
    setRetryingId(upload.id);
    try {
      await qualityApi.retryUpload(upload.id);
      message.success(`“${upload.originalName}”已重新提交解析`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : '品管文件重试失败');
    } finally {
      setRetryingId(undefined);
      // Refresh page 1 explicitly; relying on the state update above can leave
      // the request on the previous page and make a successful upload appear
      // to be missing when the user had been browsing a later page.
      await loadUploads(1, page.pageSize);
    }
  };

  useEffect(() => {
    void loadUploads();
  }, [loadUploads, page.current, page.pageSize, uploadStatus, keyword]);

  useEffect(() => {
    if (!uploads.some((item) => ['UPLOADED', 'QUEUED', 'PARSING'].includes(item.status))) return;
    const timer = window.setInterval(() => void loadUploads(), 2000);
    return () => window.clearInterval(timer);
  }, [loadUploads, uploads]);

  const records: UploadWorkspaceRecord[] = uploads.map((upload) => {
    const status = formatUploadStatus(upload);
    const path =
      [upload.projectName, upload.stageName, upload.taskName].filter(Boolean).join(' · ') ||
      '未关联项目';
    return {
      id: upload.id,
      name: upload.originalName,
      icon: <FileTextOutlined />,
      meta: `${fileTypeText(upload.originalName)} · ${formatSize(upload.size)} · ${new Date(upload.createdAt).toLocaleString('zh-CN')}`,
      detail: `保存位置：${upload.categoryName || '未分类'} · ${path} · ${visibilityText(upload.visibility)} · 文件大小：${formatSize(upload.size)} · ${status.description}${upload.errorMessage ? ` · 失败原因：${upload.errorMessage}` : ''}`,
      status: status.status,
      progress: status.progress,
      actions: (
        <Space size={4}>
          <Button
            type="link"
            icon={<EyeOutlined />}
            disabled={!upload.generatedRecordId}
            onClick={() =>
              upload.generatedRecordId && navigate(`/quality/records/${upload.generatedRecordId}`)
            }
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
                .then(() => message.success('源文件下载已开始'))
                .catch(() => message.error('源文件下载失败'))
                .finally(() => setDownloadingId(undefined));
            }}
          >
            下载
          </Button>
          <Button
            type="link"
            icon={<ReloadOutlined />}
            loading={retryingId === upload.id}
            onClick={() => void retryUpload(upload)}
          >
            重试
          </Button>
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            loading={deletingId === upload.id}
            onClick={() => removeUpload(upload)}
          >
            删除
          </Button>
        </Space>
      ),
    };
  });

  return (
    <UploadWorkspace
        breadcrumbs={[{ title: '品管部数据中心' }, { title: '数据上传' }]}
        title="品管部数据上传"
        description="上传原始文件后自动生成品管数据草稿，并可与项目/阶段/任务绑定追溯。"
        headerActions={
          <Button type="primary" onClick={() => navigate('/quality/view')}>
            品管部数据列表
          </Button>
        }
        leftTitle="基础分类"
        classification={
          <Form layout="vertical" component={false}>
            <Form.Item label="保存位置" required>
              <Select
                value={categoryId}
                options={categoryOptions}
                onChange={setCategoryId}
                placeholder={categoryOptions.length ? '选择保存分类' : '暂无分类，请先维护业务分类'}
              />
            </Form.Item>
            <Form.Item label="关联项目 / 阶段 / 任务">
              <ProjectRelationPicker value={projectRelations} onChange={setProjectRelations} multiple={false} />
            </Form.Item>
            <Form.Item label="权限可见">
              <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
            </Form.Item>
          </Form>
        }
        accept={accept}
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
        onSubmit={() => void submit()}
        submitting={uploading}
        submitDisabled={!categoryId}
        rightTitle="已上传文件"
        rightCount={page.total}
        rightFilters={statusFilters}
        activeFilter={uploadStatus}
        onFilterChange={(key) => {
          setUploadStatus(key);
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
  );
}
