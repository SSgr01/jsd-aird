import { InboxOutlined, RightOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Empty, Select, Space, Tag, Typography, Upload } from 'antd';
import type { UploadFile } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { dataApi, dataTypeOptions, type DataTemplateOption, type DataType } from '@/services/data/data-api';

export function DataUploadPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [target, setTarget] = useState<DataType>('MATERIAL');
  const [templates, setTemplates] = useState<DataTemplateOption[]>([]);
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => { void dataApi.listTemplates(target).then((items) => setTemplates(items.filter((item) => item.format === 'XLSX'))).catch((error) => void message.error(error instanceof Error ? error.message : '模板加载失败')); }, [target, message]);
  const chosen = useMemo(() => templates.find((item) => item.versionId === templateVersionId), [templateVersionId, templates]);
  const submit = async () => {
    const file = files[0]?.originFileObj;
    if (!chosen || !file) { void message.warning('请选择已发布模板和数据文件'); return; }
    setLoading(true);
    try {
      const staged = await dataApi.stageSource(file);
      const create = async (duplicateOverride: boolean) => dataApi.createJob({ sourceFileId: staged.fileId, templateVersionId: chosen.versionId, targetDataType: target, duplicateOverride });
      try {
        const job = await create(false);
        navigate(`/data/import-jobs/${job.id}`);
      } catch (error) {
        if (error instanceof Error && error.message.includes('历史任务')) {
          modal.confirm({
            title: '文件已成功导入过',
            content: `${error.message}。是否明确创建一次重复导入？重复导入不会覆盖历史修订。`,
            okText: '确认重复导入',
            cancelText: '取消',
            onOk: async () => {
              setLoading(true);
              try {
                const job = await create(true);
                navigate(`/data/import-jobs/${job.id}`);
              } catch (retryError) {
                void message.error(retryError instanceof Error ? retryError.message : '重复导入创建失败');
              } finally {
                setLoading(false);
              }
            },
          });
        } else throw error;
      }
    } catch (error) { void message.error(error instanceof Error ? error.message : '创建导入任务失败'); }
    finally { setLoading(false); }
  };
  return <div className="business-page">
    <div className="page-heading"><div><Typography.Title level={2}>数据上传</Typography.Title><Typography.Text type="secondary">选择已发布的数据中心模板，上传后按 Sheet、字段和质量问题逐步确认。</Typography.Text></div><Button onClick={() => navigate('/data/view')}>查看正式数据</Button></div>
    <Card className="content-card" title="1. 选择数据类型和模板">
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Select value={target} options={dataTypeOptions} onChange={(value) => { setTarget(value); setTemplateVersionId(undefined); }} style={{ width: 260 }} aria-label="数据类型" />
        {templates.length ? <div className="template-card-grid">{templates.map((item) => <button type="button" key={item.versionId} className="template-choice" aria-current={templateVersionId === item.versionId} onClick={() => setTemplateVersionId(item.versionId)}><span className="format-badge excel">▦</span><span className="template-choice-content"><strong>{item.name}</strong><small>{item.templateCode} · V{item.versionNo}</small><span>{item.category || '未分类'}</span></span><Tag color="green">已发布</Tag></button>)}</div> : <Empty description="暂无适用的已发布数据模板" />}
      </Space>
    </Card>
    <Card className="content-card" title="2. 上传数据文件">
      <Alert type="info" showIcon message="首期支持 XLS、XLSX、CSV；原文件会保留并用于后续来源追溯。" style={{ marginBottom: 16 }} />
      <Upload.Dragger accept=".xls,.xlsx,.csv" maxCount={1} beforeUpload={() => false} fileList={files} onChange={({ fileList }) => setFiles(fileList)}><p className="ant-upload-drag-icon"><InboxOutlined /></p><p>拖入或选择 XLS / XLSX / CSV 文件</p></Upload.Dragger>
      <Button type="primary" size="large" block icon={<RightOutlined />} loading={loading} disabled={!chosen || !files.length} onClick={() => void submit()} style={{ marginTop: 18 }}>创建导入任务</Button>
    </Card>
  </div>;
}
