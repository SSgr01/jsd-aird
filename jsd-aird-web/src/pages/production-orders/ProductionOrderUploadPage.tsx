import {
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, TreeSelect, Upload } from 'antd';
import type { UploadFile, UploadProps } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { UploadWorkspace, type UploadWorkspaceRecord } from '@/components/upload-workspace';
import { usePermission } from '@/components/auth/usePermission';
import { getProjectStages, getProjects, getStageTasks } from '@/services/project/project-api';
import { downloadFile, stageFile } from '@/services/files/file-api';
import { templateApi } from '@/services/templates/template-api';
import {
  productionUploadApi,
  type ProductionUpload,
  type ProductionUploadVisibility,
} from '@/services/production-orders/production-upload-api';

type LinkNode = {
  value: string;
  label: string;
  isLeaf?: boolean;
  selectable?: boolean;
  children?: LinkNode[];
};

type LinkMeta = {
  projectId?: string;
  projectName?: string;
  stageId?: string;
  stageName?: string;
  taskId?: string;
  taskName?: string;
};

function findLink(nodes: LinkNode[], target: string, parents: LinkMeta = {}): LinkMeta {
  for (const node of nodes) {
    const [type, id] = node.value.split(':');
    const current = {
      ...parents,
      ...(type === 'project' ? { projectId: id, projectName: node.label } : {}),
      ...(type === 'stage' ? { stageId: id, stageName: node.label } : {}),
      ...(type === 'task' ? { taskId: id, taskName: node.label } : {}),
    };
    if (node.value === target) return current;
    const child = findLink(node.children ?? [], target, current);
    if (child.projectId || child.stageId || child.taskId) return child;
  }
  return {};
}

function attachChildren(nodes: LinkNode[], target: string, children: LinkNode[]): LinkNode[] {
  return nodes.map((node) =>
    node.value === target
      ? { ...node, children }
      : node.children
        ? { ...node, children: attachChildren(node.children, target, children) }
        : node,
  );
}

const visibilityOptions: Array<{ value: ProductionUploadVisibility; label: string }> = [
  { value: 'ALL', label: '全员可见' },
  { value: 'QUALITY', label: '品管部可见' },
  { value: 'PROJECT', label: '项目组可见' },
];

const saveLocationOptions = [{ value: 'PRODUCTION_ORDER', label: '生产单' }];

const statusFilters = [
  { key: 'ALL', label: '全部' },
  { key: 'PARSING', label: '解析中' },
  { key: 'REVIEW_REQUIRED', label: '待复核' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'FAILED', label: '失败' },
];

function visibilityText(value: ProductionUploadVisibility) {
  return visibilityOptions.find((item) => item.value === value)?.label || '全员可见';
}

async function readProductionMetadata(file: File) {
  const fallback = { productionName: file.name.replace(/\.[^.]+$/, '') };
  if (!/\.xlsx$/i.test(file.name)) return fallback;
  try {
    const XLSX = await import('xlsx');
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: false });
    const rows = workbook.SheetNames.flatMap((name) => {
      const sheet = workbook.Sheets[name];
      return sheet ? XLSX.utils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' }) : [];
    }).slice(0, 120);
    const values = rows.flat().map((value) => String(value ?? '').trim()).filter(Boolean);
    const findValue = (labels: string[]) => {
      const label = values.findIndex((value) => labels.some((item) => value.replace(/[：:]/g, '').includes(item)));
      if (label < 0) return undefined;
      const row = rows.flatMap((item) => item.map((value) => String(value ?? '').trim()));
      return row[label + 1] || undefined;
    };
    return {
      productionName: fallback.productionName,
      orderNo: findValue(['订单号', '生产单号', '单号']),
      productName: findValue(['品名', '产品名称', '产品']),
      category: findValue(['类别', '产品类别']),
      manufactureDate: findValue(['制造日期', '生产日期', '日期']),
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
  const [link, setLink] = useState<string>();
  const [linkTree, setLinkTree] = useState<LinkNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const [saveLocation, setSaveLocation] = useState('PRODUCTION_ORDER');
  const [visibility, setVisibility] = useState<ProductionUploadVisibility>('ALL');
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [templates, setTemplates] = useState<Array<{
    versionId: string;
    currentPublishedVersionId?: string;
    currentPublishedVersionNo?: number;
    templateCode: string;
    name: string;
    versionNo: number;
  }>>([]);
  const [uploads, setUploads] = useState<ProductionUpload[]>([]);
  const [keyword, setKeyword] = useState('');
  const [uploadStatus, setUploadStatus] = useState('ALL');
  const [page, setPage] = useState({ current: 1, pageSize: 8, total: 0 });
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string>();
  const [deletingId, setDeletingId] = useState<string>();
  const previousExpanded = useRef<string[]>([]);

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
    void getProjects({ page: 1, size: 200 })
      .then((result) =>
        setLinkTree(
          result.items.map((project) => ({
            value: `project:${project.id}`,
            label: `${project.projectCode}·${project.name}`,
            // A project may legitimately have no stages yet. Keep the project
            // node selectable so the upload can still retain the stable link.
            selectable: true,
            isLeaf: false,
          })),
        ),
      )
      .catch((error) => {
        void message.error(error instanceof Error ? error.message : '项目列表加载失败');
      });
  }, [message]);

  useEffect(() => {
    void templateApi.list({ format: 'XLSX', status: 'PUBLISHED', page: 1, size: 100 })
      .then((result) => setTemplates(result.items))
      .catch((error) => {
        void message.error(error instanceof Error ? error.message : '已发布模板加载失败');
      });
  }, [message]);

  useEffect(() => {
    void loadUploads();
  }, [loadUploads]);

  const expandRelation = (keys: Array<string | number>) => {
    const values = keys.map(String);
    const target = values.find((key) => !previousExpanded.current.includes(key));
    setExpandedKeys(values);
    previousExpanded.current = values;
    if (!target) return;
    const [type, id] = target.split(':');
    if (!id) return;
    const request =
      type === 'project'
        ? getProjectStages(id).then((items) =>
            items.map<LinkNode>((stage) => ({
              value: `stage:${stage.id}`,
              label: stage.name,
              selectable: true,
              isLeaf: false,
            })),
          )
        : type === 'stage'
          ? getStageTasks(id).then((items) =>
              items.map<LinkNode>((task) => ({
                value: `task:${task.id}`,
                label: task.name,
                selectable: true,
                isLeaf: true,
              })),
            )
          : Promise.resolve<LinkNode[]>([]);
    void request
      .then((children) => setLinkTree((current) => attachChildren(current, target, children)))
      .catch((error) => {
        void message.error(error instanceof Error ? error.message : '项目阶段任务加载失败');
      });
  };

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
    if (!uploads.some((item) => ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'].includes(item.status))) return;
    const timer = window.setInterval(() => void loadUploads(), 2000);
    return () => window.clearInterval(timer);
  }, [loadUploads, uploads]);

  const submit = async () => {
    const sourceFiles = files.flatMap((item) => (item.originFileObj ? [item.originFileObj] : []));
    if (!sourceFiles.length) {
      void message.warning('请先选择生产单文件');
      return;
    }
    const relation = link ? findLink(linkTree, link) : {};
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

  const removeUpload = (upload: ProductionUpload) => {
    Modal.confirm({
      title: '删除上传记录？',
      content: `将删除“${upload.originalName}”的上传记录，原始文件不会被物理删除。`,
      okText: '确认删除',
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
    detail: `保存位置：生产单 · 关联项目：${[upload.projectName, upload.stageName, upload.taskName].filter(Boolean).join(' · ') || '未关联项目'} · ${visibilityText(upload.visibility)}`,
    status: upload.status === 'FAILED'
      ? { label: '识别失败', color: 'error' }
      : upload.status === 'REVIEW_REQUIRED'
        ? { label: upload.matchMode === 'USER_REVIEW' ? '待选择模板' : '待复核', color: 'warning' }
        : ['SAVED', 'PUBLISHED'].includes(upload.status)
          ? { label: upload.status === 'PUBLISHED' ? '已发布' : '已保存', color: 'success' }
          : { label: '识别中', color: 'processing' },
    progress: ['QUEUED', 'PARSING', 'MATCHING_TEMPLATE', 'EXTRACTING'].includes(upload.status)
      ? upload.recognitionProgress : undefined,
    actions: (
      <Space size={2}>
        <Button
          type="link"
          icon={<EyeOutlined />}
          disabled={!['REVIEW_REQUIRED', 'SAVED', 'PUBLISHED'].includes(upload.status)}
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
        {canDelete && upload.allowedActions?.includes('DELETE') && (
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
              <TreeSelect
                value={link}
                onChange={setLink}
                allowClear
                placeholder="未关联项目"
                treeData={linkTree}
                treeExpandedKeys={expandedKeys}
                treeNodeFilterProp="label"
                onTreeExpand={expandRelation}
              />
            </Form.Item>
            <Form.Item label="权限可见">
              <Select value={visibility} onChange={setVisibility} options={visibilityOptions} />
            </Form.Item>
            <Form.Item label="生产单模板">
              <Select
                allowClear
                value={templateVersionId}
                onChange={setTemplateVersionId}
                placeholder="未选择，上传后自动匹配"
                options={templates.map((item) => ({
                  value: item.currentPublishedVersionId ?? item.versionId,
                  label: `${item.templateCode} · ${item.name} · V${item.currentPublishedVersionNo ?? item.versionNo}`,
                }))}
              />
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
        uploadHint="支持 XLSX、DOCX 或图片；Excel 自动解析，图片需选择已发布模板后进行识别。"
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
