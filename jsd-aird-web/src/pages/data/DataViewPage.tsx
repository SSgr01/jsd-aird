import { DatabaseOutlined, DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Card, Empty, Input, Modal, Select, Space, Table, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { dataApi, dataTypeOptions, type DataAsset, type DataTemplateOption, type DataType } from '@/services/data/data-api';

export function DataViewPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<DataAsset[]>([]);
  const [targetDataType, setTargetDataType] = useState<DataType>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [exportOpen, setExportOpen] = useState(false);
  const [exportTargetDataType, setExportTargetDataType] = useState<DataType>();
  const [exportTemplates, setExportTemplates] = useState<DataTemplateOption[]>([]);
  const [exportTemplateVersionId, setExportTemplateVersionId] = useState<string>();
  const [exportLoading, setExportLoading] = useState(false);
  const [templateLoading, setTemplateLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await dataApi.listAssets({ targetDataType, keyword: keyword || undefined }));
      setSelectedRowKeys([]);
    } finally {
      setLoading(false);
    }
  }, [keyword, targetDataType]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!exportOpen || !exportTargetDataType) return;
    let active = true;
    setTemplateLoading(true);
    void dataApi.listTemplates(exportTargetDataType).then((templates) => {
      if (active) setExportTemplates(templates.filter((item) => item.format === 'XLSX'));
    }).catch((error: unknown) => {
      if (active) void message.error(error instanceof Error ? error.message : '导出模板加载失败');
    }).finally(() => {
      if (active) setTemplateLoading(false);
    });
    return () => { active = false; };
  }, [exportOpen, exportTargetDataType, message]);

  const selectedItems = items.filter((item) => selectedRowKeys.includes(item.id));
  const openExport = () => {
    const selectedTypes = new Set(selectedItems.map((item) => item.targetDataType));
    if (selectedTypes.size !== 1) {
      void message.warning('请选择同一数据类型的资产');
      return;
    }
    setExportTargetDataType([...selectedTypes][0]);
    setExportTemplateVersionId(undefined);
    setExportOpen(true);
  };

  const downloadExport = async () => {
    if (!exportTargetDataType || !exportTemplateVersionId || selectedItems.length === 0) {
      void message.warning('请选择导出模板');
      return;
    }
    setExportLoading(true);
    try {
      const blob = await dataApi.exportAssets({
        targetDataType: exportTargetDataType,
        templateVersionId: exportTemplateVersionId,
        assetIds: selectedItems.map((item) => item.id),
      });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'data-assets.zip';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setExportOpen(false);
      void message.success(`已生成 ${selectedItems.length} 个资产文件`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '批量文件导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  return (
    <div className="business-page">
      <div className="page-heading">
        <div>
          <Typography.Title level={2}>数据查看</Typography.Title>
          <Typography.Text type="secondary">按固定数据类型查看正式数据资产，并进入版本和来源追溯。</Typography.Text>
        </div>
        <Button type="primary" onClick={() => navigate('/data/upload')}>导入数据</Button>
      </div>
      <Card className="content-card">
        <Space wrap>
          <Select allowClear placeholder="全部数据类型" options={dataTypeOptions} value={targetDataType} onChange={setTargetDataType} style={{ width: 190 }} />
          <Input.Search allowClear placeholder="资产名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} onSearch={() => void load()} style={{ width: 280 }} />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button onClick={() => setSelectedRowKeys(items.map((item) => item.id))} disabled={!items.length}>选择当前结果</Button>
          <Button onClick={() => setSelectedRowKeys([])} disabled={!selectedItems.length}>清空选择</Button>
          <Button type="primary" icon={<DownloadOutlined />} disabled={!selectedItems.length} onClick={openExport}>
            批量导出文件{selectedItems.length ? ` (${selectedItems.length})` : ''}
          </Button>
        </Space>
      </Card>
      <Card className="content-card" styles={{ body: { padding: 0 } }}>
        <Table
          rowKey="id"
          rowSelection={{ selectedRowKeys, onChange: (keys) => setSelectedRowKeys(keys.map(String)) }}
          loading={loading}
          dataSource={items}
          locale={{ emptyText: <Empty description="暂无正式数据资产" /> }}
          onRow={(record) => ({ onDoubleClick: () => navigate(`/data/assets/${record.id}`) })}
          columns={[
            { title: '资产名称', dataIndex: 'displayName', render: (value: string | undefined) => <Space><DatabaseOutlined /><Typography.Text strong>{value || '未命名资产'}</Typography.Text></Space> },
            { title: '数据类型', dataIndex: 'targetDataType', render: (value: DataType) => dataTypeOptions.find((item) => item.value === value)?.label || value },
            { title: '资产编码', dataIndex: 'assetKey' },
            { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value}</Tag> },
            { title: '最近更新', dataIndex: 'updatedAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
            { title: '操作', render: (_, record) => <Button type="link" onClick={() => navigate(`/data/assets/${record.id}`)}>查看详情</Button> },
          ]}
        />
      </Card>
      <Modal
        open={exportOpen}
        title={`批量导出 ${selectedItems.length} 个资产`}
        okText="生成 ZIP"
        cancelText="取消"
        confirmLoading={exportLoading}
        onOk={() => void downloadExport()}
        onCancel={() => setExportOpen(false)}
      >
        <Typography.Paragraph type="secondary">每个正式资产生成一个 XLSX，ZIP 内同时附带 manifest.csv。</Typography.Paragraph>
        <Select
          aria-label="导出模板"
          showSearch
          optionFilterProp="label"
          loading={templateLoading}
          placeholder="选择已发布数据中心 XLSX 模板"
          value={exportTemplateVersionId}
          onChange={setExportTemplateVersionId}
          options={exportTemplates.map((item) => ({ value: item.versionId, label: `${item.name} · ${item.templateCode} · V${item.versionNo}` }))}
          style={{ width: '100%' }}
        />
      </Modal>
    </div>
  );
}
