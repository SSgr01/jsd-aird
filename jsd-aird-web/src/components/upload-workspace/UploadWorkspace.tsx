import {
  DeleteOutlined,
  FileExcelOutlined,
  FileImageOutlined,
  FilePdfOutlined,
  FileTextOutlined,
  FileWordOutlined,
  InboxOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import {
  Breadcrumb,
  Button,
  Empty,
  Input,
  Pagination,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { BreadcrumbProps, UploadFile } from 'antd';
import type { ReactNode } from 'react';

export interface UploadWorkspaceFilter {
  key: string;
  label: string;
  count?: number;
}

export interface UploadWorkspaceRecord {
  id: string;
  name: string;
  icon?: ReactNode;
  meta?: ReactNode;
  detail?: ReactNode;
  status?: { label: string; color?: string };
  progress?: number;
  actions?: ReactNode;
}

export interface UploadWorkspaceProps {
  breadcrumbs: BreadcrumbProps['items'];
  title: string;
  description?: string;
  headerActions?: ReactNode;
  leftTitle: string;
  classification: ReactNode;
  accept: string;
  showDropzone?: boolean;
  showPreview?: boolean;
  fileRequired?: boolean;
  multiple?: boolean;
  maxCount?: number;
  files: UploadFile[];
  onFilesChange: (files: UploadFile[]) => void;
  onRemoveFile: (file: UploadFile) => void;
  onClearFiles: () => void;
  uploadMainText?: string;
  uploadHint?: string;
  uploadIcon?: ReactNode;
  previewEmptyText?: string;
  submitLabel: string;
  submitIcon?: ReactNode;
  onSubmit: () => void;
  submitting?: boolean;
  submitDisabled?: boolean;
  rightTitle: string;
  rightCount?: number;
  rightFilters?: UploadWorkspaceFilter[];
  activeFilter?: string;
  onFilterChange?: (key: string) => void;
  searchValue?: string;
  onSearchChange?: (value: string) => void;
  searchPlaceholder?: string;
  records: UploadWorkspaceRecord[];
  recordsLoading?: boolean;
  pagination?: { current: number; pageSize: number; total: number };
  onPageChange?: (page: number, pageSize: number) => void;
}

function fileIcon(name: string) {
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'xlsx' || extension === 'xls' || extension === 'csv') return <FileExcelOutlined />;
  if (extension === 'doc' || extension === 'docx') return <FileWordOutlined />;
  if (extension === 'pdf') return <FilePdfOutlined />;
  if (['png', 'jpg', 'jpeg', 'gif', 'tif', 'tiff'].includes(extension || '')) return <FileImageOutlined />;
  return <FileTextOutlined />;
}

function formatSize(size?: number) {
  if (!size) return '大小未知';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

export function UploadWorkspace({
  breadcrumbs,
  title,
  description,
  headerActions,
  leftTitle,
  classification,
  accept,
  showDropzone = true,
  showPreview = true,
  fileRequired = true,
  multiple = false,
  maxCount,
  files,
  onFilesChange,
  onRemoveFile,
  onClearFiles,
  uploadMainText = '拖拽文件到此处，或点击选择文件',
  uploadHint,
  uploadIcon,
  previewEmptyText = '暂无待上传文件，点击上方区域选择文件',
  submitLabel,
  submitIcon,
  onSubmit,
  submitting = false,
  submitDisabled = false,
  rightTitle,
  rightCount,
  rightFilters,
  activeFilter,
  onFilterChange,
  searchValue = '',
  onSearchChange,
  searchPlaceholder = '搜索文件名称',
  records,
  recordsLoading = false,
  pagination,
  onPageChange,
}: UploadWorkspaceProps) {
  return (
    <div className="upload-workspace-page">
      <div className="upload-workspace-heading">
        <div>
          <Breadcrumb items={breadcrumbs} />
          <Typography.Title level={2}>{title}</Typography.Title>
          {description && <Typography.Text type="secondary">{description}</Typography.Text>}
        </div>
        {headerActions && <div className="upload-workspace-heading-actions">{headerActions}</div>}
      </div>

      <div className="upload-workspace-grid">
        <section className="upload-workspace-panel upload-workspace-panel-left" aria-labelledby="upload-workspace-left-title">
          <div className="upload-workspace-section-title" id="upload-workspace-left-title">{leftTitle}</div>
          <div className="upload-workspace-classification">{classification}</div>

          {showDropzone ? <Upload.Dragger
            className="upload-workspace-drop"
            accept={accept}
            multiple={multiple}
            maxCount={maxCount}
            fileList={files}
            showUploadList={false}
            beforeUpload={() => false}
            onChange={({ fileList }) => onFilesChange(fileList)}
          >
            <div className="upload-workspace-drop-icon">{uploadIcon || <InboxOutlined />}</div>
            <div className="upload-workspace-drop-main">{uploadMainText}</div>
            {uploadHint && <div className="upload-workspace-drop-hint">{uploadHint}</div>}
          </Upload.Dragger> : <div className="upload-workspace-mode-placeholder"><FileTextOutlined /><span>当前录入方式无需上传文件，请完成左侧信息后继续。</span></div>}

          {showPreview ? <div className="upload-workspace-preview-heading">
            <div className="upload-workspace-section-title">文件预览区 <span>({files.length})</span></div>
            <Space size={8} wrap>
              <Button onClick={onClearFiles} disabled={!files.length}>清空</Button>
              <Button
                type="primary"
                icon={submitIcon}
                loading={submitting}
                disabled={submitDisabled || (fileRequired && !files.length)}
                onClick={onSubmit}
              >
                {submitLabel}
              </Button>
            </Space>
          </div> : <div className="upload-workspace-mode-actions"><Button type="primary" icon={submitIcon} loading={submitting} disabled={submitDisabled} onClick={onSubmit}>{submitLabel}</Button></div>}

          {showPreview && <div className="upload-workspace-preview-list" aria-live="polite">
            {files.length ? files.map((file) => (
              <div className="upload-workspace-preview-item" key={file.uid}>
                <span className="upload-workspace-file-icon">{fileIcon(file.name)}</span>
                <span className="upload-workspace-file-main">
                  <Typography.Text ellipsis={{ tooltip: file.name }}>{file.name}</Typography.Text>
                  <Typography.Text type="secondary">{formatSize(file.size)}</Typography.Text>
                </span>
                <Button
                  type="text"
                  danger
                  aria-label={`移除 ${file.name}`}
                  icon={<DeleteOutlined />}
                  onClick={() => onRemoveFile(file)}
                />
              </div>
            )) : <div className="upload-workspace-empty-preview"><FileTextOutlined />{previewEmptyText}</div>}
          </div>}
        </section>

        <section className="upload-workspace-panel upload-workspace-panel-right" aria-labelledby="upload-workspace-right-title">
          <div className="upload-workspace-right-heading">
            <div className="upload-workspace-section-title" id="upload-workspace-right-title">
              {rightTitle} <span>({rightCount ?? records.length})</span>
            </div>
            {onSearchChange && (
              <Input
                allowClear
                prefix={<SearchOutlined />}
                aria-label={searchPlaceholder}
                placeholder={searchPlaceholder}
                value={searchValue}
                onChange={(event) => onSearchChange(event.target.value)}
              />
            )}
          </div>

          {rightFilters && rightFilters.length > 0 && (
            <div className="upload-workspace-filters" role="tablist" aria-label={`${rightTitle}筛选`}>
              {rightFilters.map((filter) => (
                <button
                  type="button"
                  key={filter.key}
                  role="tab"
                  aria-selected={activeFilter === filter.key}
                  className={activeFilter === filter.key ? 'is-active' : undefined}
                  onClick={() => onFilterChange?.(filter.key)}
                >
                  {filter.label}{filter.count === undefined ? '' : ` ${filter.count}`}
                </button>
              ))}
            </div>
          )}

          <div className="upload-workspace-records" aria-live="polite">
            {recordsLoading ? <div className="upload-workspace-records-loading"><Spin /></div> : records.length ? records.map((record) => (
              <article className="upload-workspace-record" key={record.id}>
                <div className="upload-workspace-record-main">
                  <span className="upload-workspace-record-icon">{record.icon || <FileTextOutlined />}</span>
                  <div className="upload-workspace-record-content">
                    <Typography.Text strong ellipsis={{ tooltip: record.name }}>{record.name}</Typography.Text>
                    {record.meta && <Typography.Text type="secondary">{record.meta}</Typography.Text>}
                    {record.detail && <Typography.Text type="secondary">{record.detail}</Typography.Text>}
                    {record.progress !== undefined && <div className="upload-workspace-progress"><span style={{ width: `${Math.min(100, Math.max(0, record.progress))}%` }} /></div>}
                  </div>
                  {record.status && <Tag color={record.status.color}>{record.status.label}</Tag>}
                </div>
                {record.actions && <div className="upload-workspace-record-actions">{record.actions}</div>}
              </article>
            )) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`暂无${rightTitle}`} />}
          </div>

          {pagination && onPageChange && (
            <Pagination
              className="upload-workspace-pagination"
              current={pagination.current}
              pageSize={pagination.pageSize}
              total={pagination.total}
              showSizeChanger
              showTotal={(total) => `共 ${total} 条`}
              onChange={onPageChange}
            />
          )}
        </section>
      </div>
    </div>
  );
}
