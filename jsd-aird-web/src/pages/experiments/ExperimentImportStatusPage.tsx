import { ArrowLeftOutlined, DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Progress, Result, Space, Spin, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { downloadFile } from '@/services/files/file-api';
import { getExperimentImport, retryExperimentImport, type ExperimentImportJob } from '@/services/experiments/experiment-api';

/** Status-only page for free uploads. The editable Univer workspace is the
 * generated experiment itself; this page intentionally has no field mapping
 * panels or recognition decisions. */
export function ExperimentImportStatusPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [job, setJob] = useState<ExperimentImportJob>();
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState(false);

  const load = useCallback(async (show = true) => {
    if (show) setLoading(true);
    try {
      const next = await getExperimentImport(id);
      setJob(next);
      if (next.status === 'COMPLETED' && next.experimentId) {
        navigate(`/experiments/${next.experimentId}`, { replace: true });
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '自由上传任务加载失败');
    } finally {
      if (show) setLoading(false);
    }
  }, [id, message, navigate]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    if (!job || !['PARSING', 'QUEUED'].includes(job.status)) return undefined;
    const timer = window.setInterval(() => void load(false), 1600);
    return () => window.clearInterval(timer);
  }, [job, load]);

  if (loading && !job) return <div style={{ padding: 80, textAlign: 'center' }}><Spin size="large" /></div>;
  if (!job) return <Result status="error" title="自由上传任务不存在" extra={<Button onClick={() => navigate('/experiments/upload')}>返回实验上传</Button>} />;

  const progress = job.status === 'COMPLETED' ? 100 : job.status === 'FAILED' ? 0 : Math.max(0, Math.min(99, job.progress || 0));
  return <section className="workspace-shell" style={{ padding: 24 }} aria-label="自由上传任务">
    <Space direction="vertical" size={18} style={{ width: '100%', maxWidth: 880, margin: '0 auto' }}>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/experiments/upload')}>返回实验上传</Button>
      <Typography.Title level={2} style={{ margin: 0 }}>{job.sourceFileName}</Typography.Title>
      <Space wrap><Tag color="blue">自由上传</Tag><Tag>{job.sourceFormat}</Tag><Tag>{job.visibility === 'PROJECT' ? '项目组可见' : job.visibility === 'QUALITY' ? '品管部可见' : '全员可见'}</Tag></Space>
      {job.status === 'PARSING' ? <Alert type="info" showIcon message="正在生成实验工作台" /> : null}
      {job.status === 'FAILED' ? <Alert type="error" showIcon message="自由上传解析失败" description={job.errorMessage || '请重试或检查原文件。'} /> : null}
      <Progress percent={progress} status={job.status === 'FAILED' ? 'exception' : job.status === 'COMPLETED' ? 'success' : 'active'} />
      <Typography.Text type="secondary">{job.currentStage || (job.status === 'COMPLETED' ? '实验草稿已创建' : '等待后台处理')}</Typography.Text>
      <Space>
        <Button icon={<DownloadOutlined />} onClick={() => void downloadFile(job.sourceFileId, job.sourceFileName).catch((error) => void message.error(error instanceof Error ? error.message : '原文件下载失败'))}>下载原文件</Button>
        <Button icon={<ReloadOutlined />} loading={retrying} disabled={!['FAILED', 'COMPLETED', 'CANCELLED'].includes(job.status)} onClick={async () => { setRetrying(true); try { await retryExperimentImport(job.id); await load(); void message.success('已提交重新解析'); } catch (error) { void message.error(error instanceof Error ? error.message : '重试失败'); } finally { setRetrying(false); } }}>重新解析</Button>
        {job.experimentId ? <Button type="primary" onClick={() => navigate(`/experiments/${job.experimentId}`)}>打开 Univer 实验工作台</Button> : null}
      </Space>
    </Space>
  </section>;
}
