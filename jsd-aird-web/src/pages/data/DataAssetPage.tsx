import { ArrowLeftOutlined, DownloadOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Descriptions, Empty, Space, Spin, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { dataApi, dataTypeOptions, type DataAssetDetail, type DataRevision, type DataSourceAnchor, type TrainingDataset } from '@/services/data/data-api';
import { FilePreviewModal, downloadPreviewFile, type FilePreviewDescriptor } from '@/components/file-preview';

const json = (value: unknown) => <Typography.Text code>{JSON.stringify(value ?? {}, null, 2)}</Typography.Text>;

export function DataAssetPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const { id = '' } = useParams();
  const [asset, setAsset] = useState<DataAssetDetail>();
  const [revisions, setRevisions] = useState<DataRevision[]>([]);
  const [sources, setSources] = useState<DataSourceAnchor[]>([]);
  const [trainingDataset, setTrainingDataset] = useState<TrainingDataset>();
  const [loading, setLoading] = useState(true);
  const [previewFile, setPreviewFile] = useState<FilePreviewDescriptor>();

  const load = async () => {
    setLoading(true);
    try {
      const [detail, revisionList, sourceList] = await Promise.all([dataApi.getAsset(id), dataApi.listRevisions(id), dataApi.listSources(id)]);
      setAsset(detail);
      setRevisions(revisionList);
      setSources(sourceList);
      if (detail.importJobId) { try { setTrainingDataset(await dataApi.getTrainingDatasetForJob(detail.importJobId)); } catch { setTrainingDataset(undefined); } }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [id]);

  if (loading || !asset) return <div className="business-page"><Spin /></div>;

  const resolveSourceFile = async (): Promise<FilePreviewDescriptor> => {
    const source = sources.find((item) => item.revisionId === asset.currentRevisionId) || sources[0];
    if (!source) throw new Error('该数据资产没有可用的原始文件');
    const job = asset.importJobId ? await dataApi.getJob(asset.importJobId) : undefined;
    return { fileName: job?.sourceFileName || `${asset.displayName || asset.assetKey || 'data-asset'}-原始文件`, load: () => dataApi.sourceBlob(source.fileId) };
  };
  const previewSource = async () => {
    try { setPreviewFile(await resolveSourceFile()); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原始数据文件加载失败'); }
  };
  const downloadSource = async () => {
    try { await downloadPreviewFile(await resolveSourceFile()); void message.success('原文件下载已开始'); }
    catch (error) { void message.error(error instanceof Error ? error.message : '原始数据文件下载失败'); }
  };

  return (
    <div className="business-page">
      <div className="page-heading">
        <div><Typography.Title level={2}>{asset.displayName || '数据资产详情'}</Typography.Title><Typography.Text type="secondary">{asset.assetKey}</Typography.Text></div>
        <Space><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/data/view')}>返回数据查看</Button></Space>
      </div>
      <Card className="content-card">
        <Descriptions column={3} bordered size="small">
          <Descriptions.Item label="数据类型">{dataTypeOptions.find((item) => item.value === asset.targetDataType)?.label || asset.targetDataType}</Descriptions.Item>
          <Descriptions.Item label="资产编码">{asset.assetKey}</Descriptions.Item>
          <Descriptions.Item label="状态"><Tag color="green">{asset.status}</Tag></Descriptions.Item>
          <Descriptions.Item label="当前修订">{asset.currentRevisionId || '—'}</Descriptions.Item>
          <Descriptions.Item label="导入批次">{asset.importJobId || '—'}</Descriptions.Item>
          <Descriptions.Item label="模板版本">{asset.templateVersionId || '—'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Card className="content-card" title="数据快照">
        <Space direction="vertical" style={{ width: '100%' }}><Typography.Text strong>原始值</Typography.Text>{json(asset.rawData)}<Typography.Text strong>标准化值</Typography.Text>{json(asset.normalizedData)}<Typography.Text strong>人工修订值</Typography.Text>{json(asset.correctedData)}</Space>
      </Card>
      <Card className="content-card" title="建模数据准备" extra={trainingDataset ? <Tag color={trainingDataset.status === 'APPROVED' ? 'green' : 'blue'}>{trainingDataset.status}</Tag> : undefined}>
        {trainingDataset ? <Space wrap><Typography.Text type="secondary">长表候选已固化为独立快照。</Typography.Text><Tag>记录 {trainingDataset.recordCount}</Tag><Tag color="green">可训练记录 {trainingDataset.eligibleRecordCount}</Tag><Button onClick={() => navigate(`/data/import-jobs/${trainingDataset.importJobId || asset.importJobId}`)}>查看导入任务</Button></Space> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该资产尚未生成建模数据集" />}
      </Card>
      <Card className="content-card" title="修订记录">
        <Table rowKey="id" pagination={false} dataSource={revisions} locale={{ emptyText: <Empty description="暂无修订记录" /> }} columns={[
          { title: '修订号', dataIndex: 'revisionNo', render: (value: number) => `V${value}` }, { title: '导入批次', dataIndex: 'importJobId' }, { title: '模板版本', dataIndex: 'templateVersionId' }, { title: '数据哈希', dataIndex: 'dataHash' }, { title: '创建时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        ]} />
      </Card>
      <Card className="content-card" title="来源锚点" extra={<Space><Button icon={<EyeOutlined />} onClick={() => void previewSource()} disabled={!sources.length}>预览原文件</Button><Button icon={<DownloadOutlined />} onClick={() => void downloadSource()} disabled={!sources.length}>下载原文件</Button></Space>}>
        <Table rowKey="id" pagination={{ pageSize: 10 }} dataSource={sources} locale={{ emptyText: <Empty description="暂无来源锚点" /> }} columns={[
          { title: '字段', dataIndex: 'fieldCode' }, { title: 'Sheet', dataIndex: 'sheetName' }, { title: '行', dataIndex: 'rowNumber' }, { title: '列', dataIndex: 'columnName' }, { title: '单元格', dataIndex: 'address' }, { title: '原值', dataIndex: 'rawValue', render: (value: unknown) => JSON.stringify(value) },
        ]} />
      </Card>
      <FilePreviewModal open={Boolean(previewFile)} file={previewFile} onClose={() => setPreviewFile(undefined)} />
    </div>
  );
}
