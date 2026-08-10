import {
  CheckCircleOutlined,
  DeleteOutlined,
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
  Collapse,
  Form,
  Input,
  Modal,
  Progress,
  Select,
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
  type RecognitionCall,
  type RecognitionSuggestion,
  type TemplateImportJob,
  type TemplateCategory,
} from '@/services/templates/template-api';

import { buildDisplaySuggestions, recognitionCounts } from './recognition-display';

const accepted = '.xlsx,.docx';

export function TemplateUploadPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [jobs, setJobs] = useState<TemplateImportJob[]>([]);
  const [uploading, setUploading] = useState(false);
  const [selectedJob, setSelectedJob] = useState<TemplateImportJob>();
  const [viewingJob, setViewingJob] = useState<TemplateImportJob>();
  const [viewSuggestions, setViewSuggestions] = useState<RecognitionSuggestion[]>([]);
  const [viewCalls, setViewCalls] = useState<RecognitionCall[]>([]);
  const [viewLoading, setViewLoading] = useState(false);
  const [deletingJobId, setDeletingJobId] = useState<string>();
  const [retryingJobId, setRetryingJobId] = useState<string>();
  const [creating, setCreating] = useState(false);
  const [categories, setCategories] = useState<TemplateCategory[]>([]);
  const [form] = Form.useForm<CreateTemplateInput>();
  const displaySuggestions = useMemo(
    () => buildDisplaySuggestions(viewSuggestions),
    [viewSuggestions],
  );

  const load = useCallback(async () => {
    try {
      const [imports, categoryItems] = await Promise.all([
        templateApi.listImports(),
        templateApi.listCategories(),
      ]);
      setJobs(imports);
      setCategories(categoryItems);
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

  const openRecognition = async (job: TemplateImportJob) => {
    setViewingJob(job);
    setViewLoading(true);
    try {
      const [suggestions, calls] = await Promise.all([
        templateApi.listRecognitionSuggestions(job.id),
        templateApi.listRecognitionCalls(job.id),
      ]);
      setViewSuggestions(suggestions);
      setViewCalls(calls);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别详情加载失败');
    } finally {
      setViewLoading(false);
    }
  };

  const deleteRecognition = async (job: TemplateImportJob) => {
    setDeletingJobId(job.id);
    try {
      await templateApi.deleteImport(job.id);
      if (viewingJob?.id === job.id) setViewingJob(undefined);
      await load();
      void message.success('识别记录已删除');
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '识别记录删除失败');
    } finally {
      setDeletingJobId(undefined);
    }
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
      if (job.generatedTemplateVersionId && job.workspaceHash) {
        await templateApi.retryImport(job.id, job.workspaceHash);
      } else {
        await templateApi.createImport(job.sourceFileId, job.format);
      }
      await load();
      void message.success(
        job.generatedTemplateVersionId && job.workspaceHash
          ? '已按当前已保存草稿重新识别，历史运行会继续保留'
          : '已重新开始识别',
      );
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
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
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
                  {job.format === 'XLSX' ? (
                    <FileExcelOutlined className="excel-icon" />
                  ) : (
                    <FileWordOutlined className="word-icon" />
                  )}
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
                    status={
                      job.status === 'FAILED'
                        ? 'exception'
                        : job.status === 'PARSED' && recognitionStatus(job) === 'COMPLETE'
                          ? 'success'
                          : job.status === 'PARSED'
                            ? 'normal'
                            : 'active'
                    }
                  />
                  <Typography.Text type="secondary" className="progress-stage">
                    {job.status === 'PARSED' ? recognitionLabel(job) : stageLabel(job.currentStage)}
                  </Typography.Text>
                </div>
              ),
            },
            {
              title: '识别结果',
              width: 210,
              render: (_, job) => (
                <Space size={4} wrap>
                  {job.status === 'PARSED' && recognitionStatus(job) === 'COMPLETE' && (
                    <Tag color="success" icon={<CheckCircleOutlined />}>
                      完成
                    </Tag>
                  )}
                  {job.status === 'PARSED' && recognitionStatus(job) === 'REVIEW_REQUIRED' && (
                    <Tag color="warning">识别待复核</Tag>
                  )}
                  {pendingFieldCount(job) > 0 && (
                    <Tag color="gold">待确认字段 {pendingFieldCount(job)} 项</Tag>
                  )}
                  {pendingFieldCount(job) === 0 && reviewableFieldCount(job) > 0 && (
                    <Tag color="blue">识别字段 {reviewableFieldCount(job)} 项</Tag>
                  )}
                  {structureConflictCount(job) > 0 && (
                    <Tag color="orange">结构冲突 {structureConflictCount(job)} 组</Tag>
                  )}
                  {qualityIssueCount(job) > 0 && (
                    <Tag color="red">质量问题 {qualityIssueCount(job)} 项</Tag>
                  )}
                  {job.retryCount > 0 && (
                    <Tag color="default">已重识别 {job.retryCount} 次</Tag>
                  )}
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
                  <Button
                    type="link"
                    disabled={job.status !== 'PARSED'}
                    onClick={() => void openRecognition(job)}
                  >
                    查看识别结果
                  </Button>
                  <Button
                    type="link"
                    disabled={job.status !== 'PARSED'}
                    onClick={() => openCreate(job)}
                  >
                    创建模板
                  </Button>
                  <Button
                    type="link"
                    icon={<ReloadOutlined />}
                    loading={retryingJobId === job.id}
                    disabled={!['PARSED', 'FAILED'].includes(job.status)}
                    title={`已重识别 ${job.retryCount} 次；调用模型失败时会自动重试瞬时错误`}
                    onClick={() => void retryRecognition(job)}
                  >
                    重试
                  </Button>
                  <Button
                    type="link"
                    danger
                    icon={<DeleteOutlined />}
                    loading={deletingJobId === job.id}
                    disabled={!['PARSED', 'FAILED'].includes(job.status)}
                    onClick={() =>
                      Modal.confirm({
                        title: `删除“${job.sourceFileName}”的识别记录？`,
                        content:
                          '只删除识别记录和审计数据，不删除原始 Excel 文件；已生成模板的记录不能删除。',
                        okText: '删除记录',
                        okButtonProps: { danger: true },
                        cancelText: '取消',
                        onOk: () => deleteRecognition(job),
                      })
                    }
                  />
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
          type={selectedJob ? recognitionAlertType(selectedJob) : 'info'}
          showIcon
          message={selectedJob ? recognitionLabel(selectedJob) : '系统已完成初步识别'}
          description={selectedJob ? recognitionDescription(selectedJob) : undefined}
          style={{ marginBottom: 16 }}
        />
        <Form form={form} layout="vertical">
          <Form.Item
            name="name"
            label="模板名称"
            rules={[{ required: true, message: '请输入模板名称' }]}
          >
            <Input autoFocus maxLength={200} />
          </Form.Item>
          <Form.Item name="category" label="模板分类">
            <Select
              allowClear
              placeholder="选择分类（可在模板列表中管理）"
              options={categories.map((item) => ({ value: item.name, label: item.name }))}
            />
          </Form.Item>
          <Form.Item name="purpose" label="业务用途">
            <Input.TextArea rows={3} placeholder="说明这个模板用于什么业务场景" maxLength={300} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={viewingJob ? `识别结果：${viewingJob.sourceFileName}` : '识别结果'}
        open={Boolean(viewingJob)}
        width={960}
        footer={
          viewingJob ? (
            <Space>
              <Button onClick={() => setViewingJob(undefined)}>关闭</Button>
              <Button
                type="primary"
                onClick={() => {
                  const job = viewingJob;
                  setViewingJob(undefined);
                  if (job) openCreate(job);
                }}
              >
                填写模板信息并创建
              </Button>
            </Space>
          ) : null
        }
        onCancel={() => setViewingJob(undefined)}
      >
        {viewLoading ? (
          <Progress percent={60} status="active" showInfo={false} />
        ) : (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Alert
              type={viewingJob ? recognitionAlertType(viewingJob) : 'info'}
              showIcon
              message={viewingJob ? recognitionLabel(viewingJob) : '识别结果'}
              description={
                viewingJob
                  ? `${recognitionDescription(viewingJob)} 共 ${viewCalls.length} 次模型调用。`
                  : undefined
              }
            />
            <Table
              size="small"
              rowKey="id"
              pagination={{ pageSize: 8 }}
              dataSource={displaySuggestions}
              columns={[
                {
                  title: '字段/区域',
                  dataIndex: 'label',
                },
                { title: '类型', dataIndex: 'type' },
                { title: '状态', dataIndex: 'decision' },
                { title: '位置', dataIndex: 'location' },
                { title: '说明', dataIndex: 'details' },
              ]}
            />
            <Collapse
              items={viewCalls.map((call) => ({
                key: call.id,
                label: `${call.status} · ${call.phase} · 第 ${call.attempt} 次 · ${call.durationMs}ms`,
                children: (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    {call.errorMessage && (
                      <Alert
                        type="error"
                        message={`${call.errorType || '调用错误'}：${call.errorMessage}`}
                      />
                    )}
                    <Typography.Text strong>完整脱敏请求</Typography.Text>
                    <pre className="recognition-audit-payload">
                      {JSON.stringify(call.requestPayload, null, 2)}
                    </pre>
                    <Typography.Text strong>完整脱敏响应</Typography.Text>
                    <pre className="recognition-audit-payload">
                      {JSON.stringify(call.responsePayload, null, 2)}
                    </pre>
                  </Space>
                ),
              }))}
            />
          </Space>
        )}
      </Modal>
    </div>
  );
}

function recognitionStatus(job: TemplateImportJob) {
  if (job.status === 'FAILED') return 'FAILED';
  if (job.recognitionRunStatus === 'PARTIAL') return 'REVIEW_REQUIRED';
  return (
    job.recognitionSummary?.recognitionStatus ?? job.result.recognitionStatus ?? 'REVIEW_REQUIRED'
  );
}

function recognitionLabel(job: TemplateImportJob) {
  switch (recognitionStatus(job)) {
    case 'COMPLETE':
      return '识别完成';
    case 'FAILED':
      return '识别失败';
    case 'NO_PHYSICAL_TABLE':
      return '未发现可识别区域';
    case 'REVIEW_REQUIRED':
      return job.recognitionRunStatus === 'PARTIAL' ? '部分识别完成，待复核' : '解析完成，识别待复核';
    default:
      return job.status === 'PARSED' ? '解析完成，识别待复核' : stageLabel(job.currentStage);
  }
}

function recognitionAlertType(job: TemplateImportJob): 'success' | 'error' | 'warning' | 'info' {
  const status = recognitionStatus(job);
  if (status === 'FAILED') return 'error';
  if (status === 'COMPLETE') return 'success';
  if (status === 'REVIEW_REQUIRED') return 'warning';
  return 'info';
}

function reviewableFieldCount(job: TemplateImportJob) {
  return recognitionCounts(job).fields;
}

function pendingFieldCount(job: TemplateImportJob) {
  return recognitionCounts(job).pending;
}

function structureConflictCount(job: TemplateImportJob) {
  return recognitionCounts(job).conflicts;
}

function qualityIssueCount(job: TemplateImportJob) {
  return recognitionCounts(job).quality;
}

function recognitionDescription(job: TemplateImportJob) {
  const counts = recognitionCounts(job);
  const retry = job.retryCount > 0 ? `已重识别 ${job.retryCount} 次` : '尚未人工重识别';
  return `识别字段 ${counts.fields} 项，其中待确认 ${counts.pending} 项；结构候选 ${counts.structures} 组，结构冲突 ${counts.conflicts} 组，质量问题 ${counts.quality} 项；${retry}。`;
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
