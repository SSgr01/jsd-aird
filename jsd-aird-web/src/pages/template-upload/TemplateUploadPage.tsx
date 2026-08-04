import {
  CheckCircleOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  InboxOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Progress,
  Space,
  Table,
  Tag,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type { TemplateFormat } from '@/features/template-workspace/types';
import {
  templateApi,
  type CreateTemplateInput,
  type TemplateImportJob,
} from '@/services/templates/template-api';

const accepted = '.xlsx,.docx';

export function TemplateUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [jobs, setJobs] = useState<TemplateImportJob[]>([]);
  const [uploading, setUploading] = useState(false);
  const [selectedJob, setSelectedJob] = useState<TemplateImportJob>();
  const [retryingJobId, setRetryingJobId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [form] = Form.useForm<CreateTemplateInput>();

  const load = useCallback(async () => {
    try {
      setJobs(await templateApi.listImports());
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别记录加载失败');
    }
  }, [message]);

  useEffect(() => {
    void load();
  }, [load]);

  const running = useMemo(
    () => jobs.some((job) => !['PARSED', 'FAILED'].includes(job.status)),
    [jobs],
  );

  useEffect(() => {
    if (!running) return;
    const timer = window.setInterval(() => void load(), 2000);
    return () => window.clearInterval(timer);
  }, [load, running]);

  const startUpload = async () => {
    const file = files[0]?.originFileObj;
    if (!file) {
      void message.warning('请先选择 Excel 或 Word 文件');
      return;
    }
    const format: TemplateFormat = file.name.toLowerCase().endsWith('.xlsx') ? 'XLSX' : 'DOCX';
    setUploading(true);
    try {
      const staged = await templateApi.stageOfficeFile(file);
      await templateApi.createImport(staged.fileId, format);
      setFiles([]);
      await load();
      void message.success('文件已接收，系统正在自动识别');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '文件上传失败');
    } finally {
      setUploading(false);
    }
  };

  const openCreate = (job: TemplateImportJob) => {
    setSelectedJob(job);
    form.setFieldsValue({
      name: job.sourceFileName.replace(/\.(xlsx|docx)$/i, ''),
      format: job.format,
    });
  };

  const createTemplate = async () => {
    if (!selectedJob) return;
    const input = await form.validateFields();
    setCreating(true);
    try {
      const workspace = await templateApi.create({
        ...input,
        format: selectedJob.format,
        importJobId: selectedJob.id,
      });
      navigate(`/templates/${workspace.versionId}/workspace?importJobId=${selectedJob.id}`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板草稿生成失败');
    } finally {
      setCreating(false);
    }
  };

  const retryRecognition = async (job: TemplateImportJob) => {
    setRetryingJobId(job.id);
    try {
      await templateApi.createImport(job.sourceFileId, job.format);
      await load();
      void message.success('已重新开始识别');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '重新识别失败');
    } finally {
      setRetryingJobId(undefined);
    }
  };

  return (
    <div className="business-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>从文件创建模板</Typography.Title>
          <Typography.Text type="secondary">
            上传后系统自动识别字段、业务分组和表格区域；进入工作台后只需确认少量不确定内容。
          </Typography.Text>
        </div>
        <Button onClick={() => navigate('/templates/library')}>模板中心</Button>
      </div>

      <Card className="content-card upload-card">
        <Upload.Dragger
          accept={accepted}
          maxCount={1}
          fileList={files}
          beforeUpload={() => false}
          onChange={({ fileList }) => setFiles(fileList.slice(-1))}
        >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">拖入 Excel 或 Word 文件</p>
          <p className="ant-upload-hint">原始版式会完整保留，系统会在后台自动分析可填写内容。</p>
        </Upload.Dragger>
        <div className="upload-actions">
          <Button
            type="primary"
            size="large"
            loading={uploading}
            disabled={!files.length}
            onClick={() => void startUpload()}
          >
            开始识别
          </Button>
        </div>
      </Card>

      <Card className="content-card" title="识别记录">
        <Table
          rowKey="id"
          dataSource={jobs}
          pagination={{ pageSize: 8, showSizeChanger: false }}
          columns={[
            {
              title: '文件',
              dataIndex: 'sourceFileName',
              render: (value: string, job) => (
                <Space>
                  {job.format === 'XLSX' ? <FileExcelOutlined className="excel-icon" /> : <FileWordOutlined className="word-icon" />}
                  <Typography.Text>{value}</Typography.Text>
                </Space>
              ),
            },
            {
              title: '识别进度',
              width: 260,
              render: (_, job) => (
                <div className="recognition-progress-cell">
                  <Progress
                    percent={job.progress}
                    size="small"
                    status={job.status === 'FAILED' ? 'exception' : job.status === 'PARSED' ? 'success' : 'active'}
                  />
                  <Typography.Text type="secondary" className="progress-stage">
                    {job.status === 'PARSED' ? '识别完成，可以查看结果' : stageLabel(job.currentStage)}
                  </Typography.Text>
                </div>
              ),
            },
            {
              title: '识别结果',
              width: 210,
              render: (_, job) => (
                <Space size={4} wrap>
                  {job.status === 'PARSED' && <Tag color="success" icon={<CheckCircleOutlined />}>完成</Tag>}
                  {job.suggestionCount > 0 && <Tag color="blue">识别 {job.suggestionCount} 项</Tag>}
                  {job.pendingSuggestionCount > 0 && <Tag color="gold">待确认 {job.pendingSuggestionCount} 项</Tag>}
                  {job.status === 'FAILED' && <Tag color="error">识别失败</Tag>}
                </Space>
              ),
            },
            {
              title: '时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (value: string) => new Date(value).toLocaleString('zh-CN'),
            },
            {
              title: '操作',
              width: 190,
              render: (_, job) => (
                <Space size={2}>
                  <Button type="link" disabled={job.status !== 'PARSED'} onClick={() => openCreate(job)}>
                    查看识别结果
                  </Button>
                  <Button
                    type="link"
                    icon={<ReloadOutlined />}
                    loading={retryingJobId === job.id}
                    disabled={!['PARSED', 'FAILED'].includes(job.status)}
                    onClick={() => void retryRecognition(job)}
                  >
                    重试
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="确认模板信息"
        open={Boolean(selectedJob)}
        okText="进入工作台"
        cancelText="取消"
        confirmLoading={creating}
        onOk={() => void createTemplate()}
        onCancel={() => setSelectedJob(undefined)}
      >
        <Alert
          type="success"
          showIcon
          message="系统已完成初步识别"
          description={selectedJob ? `识别出 ${selectedJob.suggestionCount} 项内容；不确定的部分会在 Excel 旁边用中文问题引导确认。` : undefined}
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]}>
            <Input autoFocus maxLength={200} />
          </Form.Item>
          <Form.Item name="category" label="模板分类">
            <Input placeholder="例如：生产记录、检验记录" maxLength={120} />
          </Form.Item>
          <Form.Item name="purpose" label="业务用途">
            <Input.TextArea rows={3} placeholder="说明这个模板用于什么业务场景" maxLength={300} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function stageLabel(stage?: string) {
  const labels: Record<string, string> = {
    LOADING_FILE: '正在读取文件',
    READING_STRUCTURE: '正在分析表格结构',
    RECOGNIZING_FIELDS: '正在识别业务字段',
    RECOGNIZING_COMPLEX_REGIONS: '正在识别明细表和复杂区域',
    RECOGNIZING_WORKBOOK_SEMANTICS: '正在理解整份工作簿',
    AI_RECOGNITION: '正在理解业务含义',
    BUILDING_DRAFT: '正在生成可编辑模板',
    CHECKING_RESULT: '正在检查识别结果',
    PERSISTING_RESULT: '正在保存识别结果',
  };
  return labels[stage ?? ''] ?? '等待后台处理';
}
