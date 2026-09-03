import { PlusOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Card, Input, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useCallback, useEffect, useState } from 'react';

import { templateApi, type StandardFieldOption, type StandardFieldRequest } from '@/services/templates/template-api';

export function StandardDictionaryPage() {
  const [fields, setFields] = useState<StandardFieldOption[]>([]);
  const [requests, setRequests] = useState<StandardFieldRequest[]>([]);
  const [templates, setTemplates] = useState<Array<{ versionId: string; templateCode: string; name: string }>>([]);
  const [displayName, setDisplayName] = useState('');
  const [valueType, setValueType] = useState('string');
  const [templateVersionId, setTemplateVersionId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [msg, holder] = message.useMessage();

  const load = useCallback(async () => {
    const [nextFields, nextRequests, templatePage] = await Promise.all([
      templateApi.searchStandardFields(),
      templateApi.listStandardFieldRequests('PENDING'),
      templateApi.list({ status: 'PUBLISHED', page: 1, size: 100 }),
    ]);
    setFields(nextFields);
    setRequests(nextRequests);
    const options = templatePage.items.map((item) => ({
      versionId: item.versionId,
      templateCode: item.templateCode,
      name: item.name,
    }));
    setTemplates(options);
    setTemplateVersionId((current) => current && options.some((item) => item.versionId === current)
      ? current : options[0]?.versionId);
  }, []);

  useEffect(() => {
    void load().catch((error) => msg.error(error instanceof Error ? error.message : '标准字段字典加载失败'));
  }, [load, msg]);

  const requestField = async () => {
    if (!displayName.trim()) return msg.warning('请输入标准字段名称');
    if (!templateVersionId) return msg.warning('请选择一个已发布模板作为业务引用');
    setSaving(true);
    try {
      await templateApi.requestStandardField({
        templateVersionId,
        displayName: displayName.trim(),
        valueType,
        uiType: 'TEXT',
        groupCode: 'CUSTOM',
        description: '通过系统设置标准字段字典管理入口申请',
      });
      setDisplayName('');
      msg.success('标准字段申请已提交');
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '标准字段申请失败');
    } finally {
      setSaving(false);
    }
  };

  const approve = async (request: StandardFieldRequest) => {
    try {
      await templateApi.approveStandardFieldRequest(request.id, {
        reviewComment: '系统设置字典管理员复核通过',
      });
      msg.success(`标准字段“${request.displayName}”已批准并生效`);
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '标准字段审批失败');
    }
  };

  return (
    <div className="inventory-page system-dictionary-page">
      {holder}
      <div className="inventory-heading">
        <div>
          <Typography.Title level={2} style={{ margin: 0 }}>基础字典</Typography.Title>
          <Typography.Paragraph type="secondary" style={{ margin: '6px 0 0' }}>
            维护标准字段名称、类型和版本，模板识别与数据导入共用同一套字典。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
      </div>
      <Card title="新增标准字段申请" extra={<PlusOutlined />}>
        <Space wrap>
          <Input
            value={displayName}
            placeholder="例如：订单号"
            aria-label="标准字段名称"
            onChange={(event) => setDisplayName(event.target.value)}
            style={{ width: 240 }}
          />
          <Select
            value={valueType}
            aria-label="标准字段类型"
            onChange={setValueType}
            options={[
              { value: 'string', label: '文本' },
              { value: 'number', label: '数字' },
              { value: 'date', label: '日期' },
            ]}
            style={{ width: 120 }}
          />
          <Select
            value={templateVersionId}
            aria-label="业务引用模板"
            placeholder="选择已发布模板"
            onChange={setTemplateVersionId}
            options={templates.map((item) => ({
              value: item.versionId,
              label: `${item.templateCode} · ${item.name}`,
            }))}
            style={{ width: 310 }}
          />
          <Button type="primary" loading={saving} onClick={() => void requestField()}>提交申请</Button>
        </Space>
      </Card>
      <Card title={`待审核申请（${requests.length}）`} style={{ marginTop: 16 }}>
        <Table
          rowKey="id"
          pagination={false}
          dataSource={requests}
          locale={{ emptyText: '暂无待审核申请' }}
          columns={[
            { title: '字段名称', dataIndex: 'displayName' },
            { title: '类型', dataIndex: 'valueType' },
            { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color="gold">{value}</Tag> },
            { title: '操作', render: (_: unknown, row: StandardFieldRequest) => <Button type="link" onClick={() => void approve(row)}>批准生效</Button> },
          ]}
        />
      </Card>
      <Card title={`已生效标准字段（${fields.length}）`} extra={<SafetyCertificateOutlined />} style={{ marginTop: 16 }}>
        <Table
          rowKey={(row) => `${row.id}-${row.version}`}
          pagination={{ pageSize: 10 }}
          dataSource={fields}
          columns={[
            { title: '字段编码', dataIndex: 'fieldCode' },
            { title: '显示名称', dataIndex: 'displayName' },
            { title: '版本', dataIndex: 'version', render: (value: number) => `V${value}` },
            { title: '数据类型', dataIndex: 'valueType' },
            { title: '分组', dataIndex: 'groupName', render: (value?: string) => value || '未分组' },
          ]}
        />
      </Card>
    </div>
  );
}
