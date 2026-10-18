import { ArrowLeftOutlined, FileTextOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Descriptions, Empty, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { knowledgeApi, type KnowledgeUploadTask } from '@/services/knowledge';

export function KnowledgeUploadTaskDetailPage() {
  const { message } = App.useApp();
  const { id } = useParams();
  const navigate = useNavigate();
  const [task, setTask] = useState<KnowledgeUploadTask>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let current = true;
    void knowledgeApi.uploadTasks().then((items) => {
      if (current) setTask(items.find((item) => item.id === id));
    }).catch((error) => {
      if (current) void message.error(error instanceof Error ? error.message : '上传任务加载失败');
    }).finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [id, message]);

  if (loading) return <div className="knowledge-document-page"><Spin /></div>;
  if (!task) return <Empty description="上传记录不存在或已删除">
    <Button onClick={() => navigate('/knowledge/library')}>返回资料上传</Button>
  </Empty>;

  const status = task.status === 'FAILED' ? <Tag color="error">处理失败</Tag>
    : task.status === 'DUPLICATE' ? <Tag color="warning">文件重复</Tag>
      : task.status === 'COMPLETED' ? <Tag color="success">解析完成，待校对</Tag>
        : <Tag color="processing">处理中</Tag>;

  return <div className="knowledge-document-page">
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/knowledge/library')}>返回资料上传</Button>
      <Card>
        <Space align="start" size={14}>
          <FileTextOutlined style={{ fontSize: 28 }} />
          <div>
            <Typography.Title level={3} style={{ marginTop: 0, marginBottom: 4 }}>{task.fileName}</Typography.Title>
            {status}
          </div>
        </Space>
        <Descriptions style={{ marginTop: 20 }} column={1} bordered size="small">
          <Descriptions.Item label="文件大小">{task.size < 1024 * 1024 ? `${Math.ceil(task.size / 1024)} KB` : `${(task.size / 1024 / 1024).toFixed(1)} MB`}</Descriptions.Item>
          <Descriptions.Item label="上传时间">{new Date(task.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
          <Descriptions.Item label="最近更新">{new Date(task.updatedAt).toLocaleString('zh-CN')}</Descriptions.Item>
          <Descriptions.Item label="处理进度">{task.progress}%</Descriptions.Item>
          <Descriptions.Item label="处理说明">{task.message || '暂无'}</Descriptions.Item>
          <Descriptions.Item label="保存分类">{task.uploadContext?.categoryId || '历史记录未保存分类信息'}</Descriptions.Item>
          <Descriptions.Item label="资料范围">{task.uploadContext?.libraryScope || '历史记录未保存范围信息'}</Descriptions.Item>
        </Descriptions>
        {task.message && task.status === 'FAILED' && <Alert style={{ marginTop: 16 }} type="error" showIcon message="处理失败原因" description={task.message} />}
        {task.documentId && <Button style={{ marginTop: 16 }} type="primary" onClick={() => navigate(`/knowledge/documents/${task.documentId}`)}>进入知识文档工作台</Button>}
      </Card>
    </Space>
  </div>;
}
