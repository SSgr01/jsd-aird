import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Card, Descriptions, Empty, Space, Spin, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { dataApi, dataTypeOptions, type DataAssetDetail, type DataRevision, type DataSourceAnchor } from '@/services/data/data-api';

const json = (value: unknown) => <Typography.Text code>{JSON.stringify(value ?? {}, null, 2)}</Typography.Text>;

export function DataAssetPage() {
  const navigate = useNavigate();
  const { id = '' } = useParams();
  const [asset, setAsset] = useState<DataAssetDetail>();
  const [revisions, setRevisions] = useState<DataRevision[]>([]);
  const [sources, setSources] = useState<DataSourceAnchor[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const [detail, revisionList, sourceList] = await Promise.all([dataApi.getAsset(id), dataApi.listRevisions(id), dataApi.listSources(id)]);
      setAsset(detail);
      setRevisions(revisionList);
      setSources(sourceList);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [id]);

  if (loading || !asset) return <div className="business-page"><Spin /></div>;

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
      <Card className="content-card" title="修订记录">
        <Table rowKey="id" pagination={false} dataSource={revisions} locale={{ emptyText: <Empty description="暂无修订记录" /> }} columns={[
          { title: '修订号', dataIndex: 'revisionNo', render: (value: number) => `V${value}` }, { title: '导入批次', dataIndex: 'importJobId' }, { title: '模板版本', dataIndex: 'templateVersionId' }, { title: '数据哈希', dataIndex: 'dataHash' }, { title: '创建时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
        ]} />
      </Card>
      <Card className="content-card" title="来源锚点">
        <Table rowKey="id" pagination={{ pageSize: 10 }} dataSource={sources} locale={{ emptyText: <Empty description="暂无来源锚点" /> }} columns={[
          { title: '字段', dataIndex: 'fieldCode' }, { title: 'Sheet', dataIndex: 'sheetName' }, { title: '行', dataIndex: 'rowNumber' }, { title: '列', dataIndex: 'columnName' }, { title: '单元格', dataIndex: 'address' }, { title: '原值', dataIndex: 'rawValue', render: (value: unknown) => JSON.stringify(value) },
        ]} />
      </Card>
    </div>
  );
}
