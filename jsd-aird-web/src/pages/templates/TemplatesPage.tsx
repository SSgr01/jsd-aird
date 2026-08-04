import {
  CopyOutlined,
  DeleteOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  MoreOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Card,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import type {
  TemplateFormat,
  TemplateListItem,
  TemplateStatus,
} from '@/features/template-workspace/types';
import { templateApi, type CreateTemplateInput } from '@/services/templates/template-api';

const statusLabels: Record<TemplateStatus, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'gold' },
  PUBLISHED: { label: '已发布', color: 'green' },
  RETIRED: { label: '已停用', color: 'default' },
};

export function TemplatesPage() {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<TemplateListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [format, setFormat] = useState<TemplateFormat>();
  const [status, setStatus] = useState<TemplateStatus>();
  const [category, setCategory] = useState('全部模板');
  const [form] = Form.useForm<CreateTemplateInput>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await templateApi.list({ keyword: keyword || undefined, format, status });
      setItems(page.items);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [format, keyword, message, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const categories = useMemo(() => {
    const counts = new Map<string, number>();
    for (const item of items) counts.set(item.category || '未分类', (counts.get(item.category || '未分类') || 0) + 1);
    return [{ name: '全部模板', count: items.length }, ...Array.from(counts, ([name, count]) => ({ name, count }))];
  }, [items]);
  const displayedItems = category === '全部模板' ? items : items.filter((item) => (item.category || '未分类') === category);

  const create = async () => {
    const input = await form.validateFields();
    setCreating(true);
    try {
      const workspace = await templateApi.create(input);
      setCreateOpen(false);
      form.resetFields();
      navigate(`/templates/${workspace.versionId}/workspace`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '模板创建失败');
    } finally {
      setCreating(false);
    }
  };

  const createRevision = async (record: TemplateListItem) => {
    try {
      const workspace = await templateApi.createRevision(record.versionId);
      void message.success('已创建新的修订草稿');
      navigate(`/templates/${workspace.versionId}/workspace`);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '创建修订草稿失败');
    }
  };

  const confirmDeleteDraft = (record: TemplateListItem) => modal.confirm({
    title: `删除草稿“${record.name}”？`,
    content: '字段、映射和当前草稿快照引用将被删除；Excel 原文件及仍被引用的对象不会立即删除。',
    okText: '删除草稿',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      await templateApi.deleteDraft(record.versionId);
      void message.success('草稿已删除');
      await load();
    },
  });

  const confirmRetire = (record: TemplateListItem) => modal.confirm({
    title: `停用模板“${record.name}”？`,
    content: '停用后不能再用于新生产单，历史版本、已有生产单和审计记录都会保留。',
    okText: '停用模板',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: async () => {
      await templateApi.retire(record.templateId);
      void message.success('模板已停用');
      await load();
    },
  });

  return (
    <Space direction="vertical" size={16} className="page-stack">
      <Card className="content-card">
        <Space direction="vertical" size={4}>
          <Typography.Title level={2} className="page-title">
            模板查看
          </Typography.Title>
          <Typography.Text type="secondary">
            统一查看、筛选和维护 Excel、Word 业务模板。
          </Typography.Text>
        </Space>
      </Card>

      <div className="category-strip" aria-label="模板分类">
        {categories.map((item) => <button type="button" key={item.name} aria-current={category === item.name} onClick={() => setCategory(item.name)}><span>{item.name}</span><strong>{item.count}</strong></button>)}
      </div>

      <Card className="content-card">
        <Space wrap size={12}>
          <Input.Search
            allowClear
            aria-label="搜索模板"
            placeholder="模板名称或编码"
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onSearch={() => void load()}
            style={{ width: 260 }}
          />
          <Select
            allowClear
            aria-label="筛选模板格式"
            placeholder="全部格式"
            value={format}
            onChange={setFormat}
            options={[
              { value: 'XLSX', label: 'Excel' },
              { value: 'DOCX', label: 'Word' },
            ]}
            style={{ width: 140 }}
          />
          <Select
            allowClear
            aria-label="筛选模板状态"
            placeholder="全部状态"
            value={status}
            onChange={setStatus}
            options={Object.entries(statusLabels).map(([value, item]) => ({
              value,
              label: item.label,
            }))}
            style={{ width: 140 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            空白创建
          </Button>
        </Space>
      </Card>

      <Card className="content-card" styles={{ body: { padding: 0 } }}>
        <Table
          rowKey="versionId"
          loading={loading}
          dataSource={displayedItems}
          locale={{ emptyText: <Empty description="暂无模板，可从空白 Excel 或 Word 开始" /> }}
          pagination={{ pageSize: 20, showSizeChanger: false }}
          onRow={(record) => ({
            onDoubleClick: () => navigate(`/templates/${record.versionId}/workspace`),
          })}
          columns={[
            {
              title: '模板',
              dataIndex: 'name',
              render: (_, record) => (
                <Space>
                  {record.format === 'XLSX' ? (
                    <FileExcelOutlined style={{ color: '#047857' }} />
                  ) : (
                    <FileWordOutlined style={{ color: '#2563eb' }} />
                  )}
                  <span>
                    <Typography.Text strong>{record.name}</Typography.Text>
                    <span className="binding-path">{record.templateCode}</span>
                  </span>
                </Space>
              ),
            },
            {
              title: '业务用途',
              dataIndex: 'purpose',
              render: (value: string | undefined) => value || '—',
            },
            {
              title: '分类',
              dataIndex: 'category',
              render: (value: string | undefined) => value || '—',
            },
            {
              title: '版本',
              dataIndex: 'versionNo',
              width: 90,
              render: (value: number) => <span className="template-stat">V{value}</span>,
            },
            {
              title: '状态',
              dataIndex: 'status',
              width: 110,
              render: (value: TemplateStatus) => (
                <Tag color={statusLabels[value].color}>{statusLabels[value].label}</Tag>
              ),
            },
            {
              title: '问题',
              dataIndex: 'issueCount',
              width: 90,
              render: (value: number) =>
                value ? <Tag color="error">{value} 项</Tag> : <Tag color="success">通过</Tag>,
            },
            {
              title: '最近更新',
              dataIndex: 'updatedAt',
              width: 180,
              render: (value: string) => new Date(value).toLocaleString('zh-CN'),
            },
            {
              title: '操作',
              key: 'action',
              width: 170,
              render: (_, record) => (
                <Space size={0} onClick={(event) => event.stopPropagation()}>
                  <Button
                    type="link"
                    onClick={() => navigate(`/templates/${record.versionId}/workspace`)}
                  >
                    {record.status === 'DRAFT' ? '编辑模板' : '查看模板'}
                  </Button>
                  <Dropdown
                    trigger={['click']}
                    menu={{
                      items: record.status === 'DRAFT'
                        ? [{ key: 'delete', danger: true, icon: <DeleteOutlined />, label: '删除草稿' }]
                        : [
                            { key: 'revision', icon: <CopyOutlined />, label: '新建修订版' },
                            ...(record.status === 'PUBLISHED'
                              ? [{ key: 'retire', danger: true, icon: <StopOutlined />, label: '停用模板' }]
                              : []),
                          ],
                      onClick: ({ key }) => {
                        if (key === 'delete') confirmDeleteDraft(record);
                        if (key === 'revision') void createRevision(record);
                        if (key === 'retire') confirmRetire(record);
                      },
                    }}
                  >
                    <Button type="text" aria-label="更多模板操作" icon={<MoreOutlined />} />
                  </Dropdown>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="创建空白模板"
        open={createOpen}
        confirmLoading={creating}
        okText="创建并进入工作台"
        cancelText="取消"
        onOk={() => void create()}
        onCancel={() => setCreateOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" initialValues={{ format: 'XLSX' }}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]}>
            <Input autoFocus maxLength={200} />
          </Form.Item>
          <Form.Item name="format" label="编辑格式" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'XLSX', label: 'Excel 模板' },
                { value: 'DOCX', label: 'Word 模板' },
              ]}
            />
          </Form.Item>
          <Form.Item name="purpose" label="业务用途">
            <Input maxLength={160} />
          </Form.Item>
          <Form.Item name="category" label="分类">
            <Input maxLength={120} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
