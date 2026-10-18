import { ArrowLeftOutlined, DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Descriptions, Empty, Progress, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { downloadFile } from '@/services/files';
import { templateApi, type RecognitionSuggestion, type TemplateImportJob } from '@/services/templates/template-api';

export function TemplateImportDetailPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { importJobId } = useParams();
  const [job, setJob] = useState<TemplateImportJob>();
  const [suggestions, setSuggestions] = useState<RecognitionSuggestion[]>([]);
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(async () => {
    if (!importJobId) return;
    setLoading(true);
    try {
      const [current, fields] = await Promise.all([
        templateApi.getImport(importJobId),
        templateApi.listRecognitionSuggestions(importJobId),
      ]);
      setJob(current);
      setSuggestions(fields);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板导入详情加载失败');
    } finally { setLoading(false); }
  }, [importJobId, message]);

  useEffect(() => { void load(); }, [load]);

  const retry = async () => {
    if (!job) return;
    setRetrying(true);
    try {
      await templateApi.retryImport(job.id, 'ORIGINAL_FILE');
      await load();
      void message.success('已重新提交识别，历史运行会保留');
    } catch (error) { void message.error(error instanceof Error ? error.message : '重新识别失败'); }
    finally { setRetrying(false); }
  };

  if (loading && !job) return <div className="upload-workspace-page"><Progress percent={55} status="active" showInfo={false} /></div>;
  if (!job) return <div className="upload-workspace-page"><Empty description="模板上传记录不存在或已移除" /><Button onClick={() => navigate('/templates/upload')}>返回模板上传</Button></div>;

  const active = !['PARSED', 'FAILED', 'CANCELLED'].includes(job.status);
  const status = job.status === 'FAILED' ? <Tag color="error">识别失败</Tag>
    : job.status === 'PARSED' ? <Tag color={job.recognitionSummary?.recognitionStatus === 'COMPLETE' ? 'success' : 'warning'}>{job.recognitionSummary?.recognitionStatus === 'COMPLETE' ? '已识别' : '待复核'}</Tag>
      : <Tag color="processing">识别中</Tag>;

  return <div className="upload-workspace-page">
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Space wrap>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/templates/upload')}>返回模板上传</Button>
        <Button icon={<DownloadOutlined />} onClick={() => downloadFile(job.originalSourceFileId || job.sourceFileId, job.sourceFileName)}>下载原文件</Button>
        <Button icon={<ReloadOutlined />} loading={retrying} disabled={active} onClick={() => void retry()}>重新识别</Button>
        {job.generatedTemplateVersionId && <Button type="primary" onClick={() => navigate(`/templates/${job.generatedTemplateVersionId}/workspace?importJobId=${job.id}`)}>进入模板工作台</Button>}
      </Space>
      <Card>
        <Typography.Title level={3} style={{ marginTop: 0 }}>{job.sourceFileName}</Typography.Title>
        <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} bordered size="small">
          <Descriptions.Item label="处理状态">{status}</Descriptions.Item>
          <Descriptions.Item label="模板分类">{job.categoryName || '未分类'}</Descriptions.Item>
          <Descriptions.Item label="文件格式">{job.format}</Descriptions.Item>
          <Descriptions.Item label="上传时间">{new Date(job.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
          <Descriptions.Item label="识别运行次数">{(job.retryCount || 0) + 1}</Descriptions.Item>
          <Descriptions.Item label="识别字段数">{job.suggestionCount}</Descriptions.Item>
        </Descriptions>
        <Progress style={{ marginTop: 20 }} percent={job.progress} status={active ? 'active' : job.status === 'FAILED' ? 'exception' : 'normal'} />
        {job.lastError && <Alert style={{ marginTop: 16 }} showIcon type="error" message="处理失败原因" description={job.lastError} />}
      </Card>
      <Card title={`识别到的内容（${suggestions.length}）`}>
        <Table<RecognitionSuggestion> rowKey="id" size="small" dataSource={suggestions} pagination={{ pageSize: 10 }} scroll={{ x: 700 }} columns={[
          { title: '字段名称', dataIndex: 'payload', render: (payload: RecognitionSuggestion['payload']) => payload.fieldName || payload.groupName || '—' },
          { title: '编码', dataIndex: 'payload', render: (payload: RecognitionSuggestion['payload']) => payload.fieldCode || '—' },
          { title: '类型', dataIndex: 'suggestionType', width: 150 },
          { title: '位置', dataIndex: 'payload', render: (payload: RecognitionSuggestion['payload']) => {
            const position = [payload.locator?.address, payload.locator?.range, payload.locator?.cellRange]
              .filter((value): value is string => typeof value === 'string' && value.length > 0).join(' · ');
            return position || '—';
          } },
          { title: '置信度', dataIndex: 'confidence', width: 100, render: (value: number) => `${Math.round(value * 100)}%` },
          { title: '确认状态', dataIndex: 'decision', width: 110 },
        ]} />
      </Card>
    </Space>
  </div>;
}
