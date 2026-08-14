import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  FileAddOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  InboxOutlined,
} from '@ant-design/icons';
import dayjs from '@/utils/dayjs';
import { httpClient } from '@/services/http/client';
import type { ApiResponse } from '@/types/api';
import { projectDocumentApi } from '@/services/project/project-document-api';
import { templateApi } from '@/services/templates/template-api';
import TemplateVersionPreview from '@/features/template-workspace/TemplateVersionPreview';
import type { TemplateListItem } from '@/features/template-workspace/types';
import type {
  CreateProjectDocumentInput,
  ProjectDocumentFormat,
  ProjectDocumentSummary,
} from '@/services/project/project-document-api';

type CreateMode = 'TEMPLATE' | 'BLANK' | 'IMPORT';

function formatTag(format: ProjectDocumentFormat) {
  if (format === 'DOCX') return { color: 'blue', text: 'Word', icon: <FileWordOutlined /> };
  if (format === 'XLSX') return { color: 'green', text: 'Excel', icon: <FileExcelOutlined /> };
  return { color: 'default', text: '文件', icon: <InboxOutlined /> };
}

const sourceLabels: Record<ProjectDocumentSummary['source'], string> = {
  TEMPLATE: '按模板新增',
  BLANK: '空白新增',
  IMPORT: '导入文件',
};

const statusMeta: Record<ProjectDocumentSummary['status'], { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PUBLISHED: { color: 'green', label: '已发布' },
  ARCHIVED: { color: 'orange', label: '已归档' },
};

export default function ProjectDocumentsTab({
  projectId,
  projectCode,
  projectName,
}: {
  projectId: string;
  projectCode?: string;
  projectName?: string;
}) {
  const [documents, setDocuments] = useState<ProjectDocumentSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [activeDoc, setActiveDoc] = useState<ProjectDocumentSummary | null>(null);
  const navigate = useNavigate();

  const loadDocuments = async () => {
    setLoading(true);
    try {
      const list = await projectDocumentApi.list(projectId);
      setDocuments(list);
    } catch (e) {
      message.error('加载项目文档失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadDocuments();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const createDocument = async (input: CreateProjectDocumentInput) => {
    const id = await projectDocumentApi.create(projectId, input);
    message.success('文档已创建');
    await loadDocuments();
    return id;
  };

  const removeDocument = async (doc: ProjectDocumentSummary) => {
    Modal.confirm({
      title: '删除文档',
      content: `确定删除文档「${doc.title}」吗？删除后不可恢复。`,
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await projectDocumentApi.remove(projectId, doc.id);
          if (activeDoc?.id === doc.id) setActiveDoc(null);
          message.success('文档已删除');
          await loadDocuments();
        } catch (e) {
          message.error('删除文档失败');
        }
      },
    });
  };

  const projectLabel = [projectCode, projectName].filter(Boolean).join(' · ');

  const columns: ColumnsType<ProjectDocumentSummary> = [
    {
      title: '文档名称',
      dataIndex: 'title',
      ellipsis: true,
      render: (value: string, doc) => (
        <a onClick={() => navigate(`/projects/${projectId}/documents/${doc.id}`)}>
          {formatTag(doc.format).icon} {value}
        </a>
      ),
    },
    {
      title: '格式',
      dataIndex: 'format',
      width: 90,
      render: (value: ProjectDocumentFormat) => formatTag(value).text,
    },
    {
      title: '创建来源',
      dataIndex: 'source',
      width: 120,
      render: (value: ProjectDocumentSummary['source']) => sourceLabels[value] ?? value,
    },
    {
      title: '来源模板',
      dataIndex: 'templateName',
      width: 180,
      ellipsis: true,
      render: (value?: string) => value || '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: ProjectDocumentSummary['status']) => {
        const meta = statusMeta[value];
        return <Tag color={meta?.color}>{meta?.label ?? value}</Tag>;
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 160,
      render: (value: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—'),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, doc) => (
        <Space size={0}>
          <Button
            size="small"
            type="link"
            onClick={() => navigate(`/projects/${projectId}/documents/${doc.id}`)}
          >
            查看
          </Button>
          <Button
            size="small"
            type="link"
            danger
            onClick={() => removeDocument(doc)}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div className="pm-documents-tab">
      <div className="pm-documents-toolbar">
        <span className="pm-documents-count">共 {documents.length} 份文档</span>
        <Button type="primary" icon={<FileAddOutlined />} onClick={() => setModalOpen(true)}>
          新建文档
        </Button>
      </div>

      <Spin spinning={loading}>
      {documents.length === 0 ? (
        <div className="pm-documents-empty">
          <Empty description="暂无项目文档，点击「新建文档」开始">
            <Button type="primary" icon={<FileAddOutlined />} onClick={() => setModalOpen(true)}>
              新建文档
            </Button>
          </Empty>
        </div>
      ) : activeDoc ? (
        <div className="pm-documents-viewer">
          <div className="pm-documents-viewer-head">
            <span className="pm-documents-viewer-title">
              {formatTag(activeDoc.format).icon} {activeDoc.title}
            </span>
            <Space size={8}>
              <Button size="small" onClick={() => setActiveDoc(null)}>
                返回列表
              </Button>
            </Space>
          </div>
          {activeDoc.templateVersionId ? (
            <TemplateVersionPreview
              versionId={activeDoc.templateVersionId}
            />
          ) : activeDoc.fileObjectId ? (
            <iframe
              className="pm-documents-frame"
              title={activeDoc.title}
              src={projectDocumentApi.contentUrl(activeDoc.fileObjectId)}
            />
          ) : (
            <div className="pm-documents-frame-tip">（暂无文件内容；基于模板创建的文档可直接查看模板内容）</div>
          )}
        </div>
      ) : (
        <div className="pm-documents-table-wrap">
          <Table<ProjectDocumentSummary>
            rowKey="id"
            size="middle"
            pagination={false}
            dataSource={documents}
            columns={columns}
            onRow={(doc) => ({
              style: { cursor: 'pointer' },
              onDoubleClick: () => navigate(`/projects/${projectId}/documents/${doc.id}`),
            })}
          />
        </div>
      )}
      </Spin>

      <NewDocumentModal
        open={modalOpen}
        projectLabel={projectLabel}
        projectId={projectId}
        onCancel={() => setModalOpen(false)}
        onCreate={createDocument}
      />
    </div>
  );
}

interface NewDocumentModalProps {
  open: boolean;
  projectLabel: string;
  projectId: string;
  onCancel: () => void;
  onCreate: (input: CreateProjectDocumentInput) => Promise<string>;
}

function NewDocumentModal({ open, projectLabel, projectId, onCancel, onCreate }: NewDocumentModalProps) {
  const [mode, setMode] = useState<CreateMode>('TEMPLATE');
  const [form] = Form.useForm();
  const [templates, setTemplates] = useState<TemplateListItem[]>([]);
  const [loadingTpl, setLoadingTpl] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [fileObjectId, setFileObjectId] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const isTemplate = mode === 'TEMPLATE';
  const isBlank = mode === 'BLANK';
  const isImport = mode === 'IMPORT';

  // 监听 mode 切换，重置 mode 专属字段
  useEffect(() => {
    if (!open) return;
    form.setFieldsValue({ templateId: undefined, title: undefined, format: 'DOCX' });
    setFile(null);
    setFileObjectId(null);
    setUploading(false);
    // 切到模板模式时从模板中心接口读取已发布的文档模板（DOCX / XLSX）
    if (mode === 'TEMPLATE') {
      setLoadingTpl(true);
      templateApi
        .list()
        .then((res) => {
          const filtered = res.items.filter(
            (t) => t.format === 'DOCX' || t.format === 'XLSX',
          );
          setTemplates(filtered);
        })
        .finally(() => setLoadingTpl(false));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, open]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (isImport && !fileObjectId) {
      message.warning(uploading ? '文件上传中，请稍候' : '请先选择并上传要导入的文件');
      return;
    }
    setSubmitting(true);
    try {
      const selectedTpl = templates.find((t) => t.templateId === values.templateId);
      const lowerName = file?.name.toLowerCase() ?? '';
      const importedFormat: ProjectDocumentFormat = lowerName.endsWith('.xlsx') || lowerName.endsWith('.xls')
        ? 'XLSX' : lowerName.endsWith('.docx') || lowerName.endsWith('.doc') ? 'DOCX' : 'OTHER';
      const format: ProjectDocumentFormat =
        isImport ? importedFormat : (selectedTpl?.format ?? (values.format as ProjectDocumentFormat));
      if (isImport && format === 'OTHER') {
        message.error('仅支持导入 Word 或 Excel 文件');
        return;
      }
      if (isImport && fileObjectId) {
        await projectDocumentApi.importDocument(projectId, {
          title: values.title?.trim() || file?.name || '未命名文档',
          format: format as 'DOCX' | 'XLSX',
          fileObjectId,
        });
        message.success('文件已上传至 MinIO，解析并保存为项目文档');
      } else {
        await onCreate({
          title: values.title?.trim() || selectedTpl?.name || file?.name || '未命名文档',
          format,
          source: mode,
          templateId: selectedTpl?.templateId,
          templateVersionId: selectedTpl?.versionId,
          fileObjectId: fileObjectId ?? undefined,
        });
      }
      form.resetFields();
      setFile(null);
      setFileObjectId(null);
      setMode('TEMPLATE');
      onCancel();
    } finally {
      setSubmitting(false);
    }
  };

  const handleCancel = () => {
    form.resetFields();
    setFile(null);
    setFileObjectId(null);
    setUploading(false);
    setMode('TEMPLATE');
    onCancel();
  };

  return (
    <Modal
      title="新建项目文档"
      open={open}
      onCancel={handleCancel}
      width={620}
      footer={[
        <Button key="cancel" onClick={handleCancel}>
          取消
        </Button>,
        <Button key="submit" type="primary" loading={submitting} onClick={handleSubmit}>
          确认新建
        </Button>,
      ]}
      destroyOnClose
    >
      <Radio.Group
        value={mode}
        onChange={(e) => setMode(e.target.value)}
        optionType="button"
        buttonStyle="solid"
        options={[
          { label: '按模板新增', value: 'TEMPLATE' },
          { label: '空白新增', value: 'BLANK' },
          { label: '导入文件', value: 'IMPORT' },
        ]}
        className="pm-new-doc-mode-tabs"
      />

      <Form form={form} layout="vertical" className="pm-new-doc-form">
        <Form.Item name="title" label="文档名称">
          <Input placeholder="选择模板或文件后自动填写" maxLength={120} />
        </Form.Item>

        <Form.Item label="当前项目">
          <Input value={projectLabel} disabled />
        </Form.Item>

        {isTemplate && (
          <Form.Item
            name="templateId"
            label="选择项目文档模板"
            rules={[{ required: true, message: '请选择文档模板' }]}
          >
            <Select
              loading={loadingTpl}
              placeholder={loadingTpl ? '正在从模板中心读取…' : '请选择已发布的文档模板'}
              notFoundContent={loadingTpl ? '加载中…' : '模板中心暂无已发布的文档模板'}
              options={templates.map((t) => ({
                value: t.templateId,
                label: `${t.name}（v${t.versionNo}） · ${t.format === 'DOCX' ? 'Word' : 'Excel'}`,
              }))}
            />
          </Form.Item>
        )}

        {isBlank && (
          <Form.Item
            name="format"
            label="文档格式"
            initialValue="DOCX"
            rules={[{ required: true, message: '请选择文档格式' }]}
          >
            <Select
              options={[
                { value: 'DOCX', label: 'Word 文档' },
                { value: 'XLSX', label: 'Excel 文档' },
              ]}
            />
          </Form.Item>
        )}

        {isImport && (
          <Form.Item label="选择文件" required>
            <Upload
              accept=".docx,.xlsx"
              maxCount={1}
              beforeUpload={async (f) => {
                setUploading(true);
                try {
                  const body = new FormData();
                  body.append('file', f);
                  const response = await httpClient.post<
                    ApiResponse<{ fileId: string; sha256: string; status: 'STAGED' }>
                  >('/api/v2/files/staged?kind=PROJECT_DOCUMENT', body);
                  const staged = response.data.data;
                  setFile(f);
                  setFileObjectId(staged.fileId);
                  form.setFieldsValue({ title: f.name });
                } catch (e) {
                  message.error('文件上传失败');
                  setFile(null);
                  setFileObjectId(null);
                } finally {
                  setUploading(false);
                }
                return false;
              }}
              onRemove={() => {
                setFile(null);
                setFileObjectId(null);
                return true;
              }}
              fileList={
                file
                  ? [{ uid: '-1', name: file.name, status: uploading ? 'uploading' : 'done' }]
                  : []
              }
            >
              <Button icon={<InboxOutlined />} loading={uploading}>
                选择文件
              </Button>
            </Upload>
            {file ? (
              <div style={{ marginTop: 8, color: uploading ? '#1459d9' : '#52637a' }}>
                {uploading ? '上传中…' : `已选择：${file.name}`}
              </div>
            ) : (
              <div className="pm-new-doc-tip">支持 DOCX、XLSX；文件将上传到 MinIO，解析后保存为项目文档。</div>
            )}
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
