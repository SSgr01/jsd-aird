import { Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';

import {
  createRequirement,
  deleteRequirement,
  getRequirements,
  updateRequirement,
  type Requirement,
  type RequirementInput,
} from '@/services/partners/crm-api';

import './customer-requirement-tab.css';

interface Props {
  projectId: string;
  projectName?: string;
  partnerId?: string;
}

interface CreateFormValues {
  title?: string;
  rawRequirement?: string;
  urgency?: Requirement['urgency'];
  raisedAt?: string;
  deliveryDate?: string;
  status: Requirement['status'];
  customStatusName?: string;
}

const URGENCY_COLORS: Record<string, string> = {
  HIGH: 'red',
  MEDIUM: 'orange',
  LOW: 'blue',
};

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'default',
  COMMUNICATING: 'blue',
  CONFIRMED: 'cyan',
  PENDING: 'orange',
  DONE: 'green',
  CANCELED: 'red',
  IN_PROJECT: 'geekblue',
  COMPLETED: 'green',
  CANCELLED: 'red',
};

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  COMMUNICATING: '需求沟通',
  CONFIRMED: '需求确认',
  PENDING: '需求待定',
  DONE: '已完成',
  CANCELED: '已取消',
  IN_PROJECT: '已立项',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
};

function formatUrgency(value?: string) {
  if (!value) return '—';
  return value === 'HIGH' ? '高' : value === 'MEDIUM' ? '中' : value === 'LOW' ? '低' : value;
}

function formatStatus(value?: string) {
  if (!value) return '—';
  return STATUS_LABELS[value] ?? value;
}

export function CustomerRequirementTab({ projectId, projectName, partnerId }: Props) {
  const [items, setItems] = useState<Requirement[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [mode, setMode] = useState<'create' | 'edit'>('create');
  const [editingRecord, setEditingRecord] = useState<Requirement | null>(null);
  const [messageApi, holder] = message.useMessage();
  const [form] = Form.useForm<CreateFormValues>();
  const [customFieldRows, setCustomFieldRows] = useState<{ key: number; name?: string; value?: string }[]>([]);

  const load = async () => {
    setLoading(true);
    try {
      const data = await getRequirements({ projectId, page: 1, size: 100 });
      setItems(data.items ?? []);
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '客户需求加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [projectId]);

  const fillCustomFields = (record?: Requirement | null) => {
    const raw = record?.customFields;
    const rows = raw
      ? Object.entries(raw).map(([name, value]) => ({
          key: Date.now() + Math.random(),
          name: String(name ?? ''),
          value: String(value ?? ''),
        }))
      : [];
    setCustomFieldRows(rows);
  };

  const openCreate = () => {
    setMode('create');
    setEditingRecord(null);
    form.resetFields();
    form.setFieldsValue({
      status: 'DRAFT',
      urgency: 'MEDIUM',
      raisedAt: new Date().toISOString().slice(0, 10),
    });
    fillCustomFields(null);
    setModalOpen(true);
  };

  const openEdit = (record: Requirement) => {
    setMode('edit');
    setEditingRecord(record);
    form.resetFields();
    form.setFieldsValue({
      title: record.title ?? '',
      rawRequirement: record.rawRequirement,
      urgency: record.urgency,
      raisedAt: record.raisedAt,
      deliveryDate: record.deliveryDate,
      status: record.status,
      customStatusName: record.customStatusName,
    });
    fillCustomFields(record);
    setModalOpen(true);
  };

  const addCustomField = () => {
    setCustomFieldRows((rows) => [...rows, { key: Date.now() + rows.length, name: '', value: '' }]);
  };

  const updateCustomField = (key: number, field: 'name' | 'value', v: string) => {
    setCustomFieldRows((rows) => rows.map((r) => (r.key === key ? { ...r, [field]: v ?? '' } : r)));
  };

  const removeCustomField = (key: number) => {
    setCustomFieldRows((rows) => rows.filter((r) => r.key !== key));
  };
  void customFieldRows;

  const handleDelete = async (record: Requirement) => {
    try {
      await deleteRequirement(record.id, record.version ?? 0);
      messageApi.success('客户需求已删除');
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '删除失败');
    }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const customFields: Record<string, string> = {};
    const rows: { name?: string; value?: string }[] = customFieldRows ?? [];
    for (const row of rows) {
      const name = String(row?.name ?? '').trim();
      if (name) {
        const val = row?.value ?? '';
        customFields[name] = String(val);
      }
    }
    setSubmitting(true);
    try {
      const payload: RequirementInput = {
        partnerId: partnerId ?? '',
        title: values.title?.trim() ?? '',
        rawRequirement: values.rawRequirement?.trim() || undefined,
        status: values.status,
        customStatusName: values.customStatusName?.trim() || undefined,
        urgency: values.urgency,
        raisedAt: values.raisedAt,
        deliveryDate: values.deliveryDate,
        projectId: projectId ?? undefined,
        metrics: [],
        customFields: (Object.keys(customFields).length ? (customFields) : undefined),
        version: mode === 'edit' && editingRecord ? editingRecord.version ?? 0 : 0,
      };
      if (mode === 'edit' && editingRecord) {
        await updateRequirement(editingRecord.id, payload);
        messageApi.success('客户需求已更新');
      } else {
        await createRequirement(payload);
        messageApi.success('客户需求已创建');
      }
      setModalOpen(false);
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const columns = useMemo<ColumnsType<Requirement>>(
    () => [
      { title: '需求名称', dataIndex: 'title', key: 'title', width: 240 },
      { title: '提出日期', dataIndex: 'raisedAt', key: 'raisedAt', width: 120, render: (v) => v || '—' },
      { title: '预计完成日期', dataIndex: 'deliveryDate', key: 'deliveryDate', width: 130, render: (v) => v || '—' },
      {
        title: '紧急程度',
        dataIndex: 'urgency',
        key: 'urgency',
        width: 100,
        render: (v?: string) =>
          v ? <Tag color={URGENCY_COLORS[v] ?? 'default'}>{formatUrgency(v)}</Tag> : <span className="pm-muted">—</span>,
      },
      {
        title: '状态',
        dataIndex: 'status',
        key: 'status',
        width: 110,
        render: (v: string) => <Tag color={STATUS_COLORS[v] ?? 'default'}>{formatStatus(v)}</Tag>,
      },
      {
        title: '操作',
        key: 'actions',
        width: 130,
        fixed: 'right',
        render: (_, record) => (
          <Space size={0}>
            <Button type="link" size="small" onClick={() => openEdit(record)}>
              编辑
            </Button>
            <Popconfirm
              title="确认删除该客户需求？"
              okText="删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => handleDelete(record)}
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [],
  );

  return (
    <div className="pm-cr-tab">
      {holder}
      <div className="pm-cr-tab-head">
        <div className="pm-cr-tab-tip">
          记录需求日期、处理状态、关联项目（当前项目：{projectName ?? projectId}）和需求详情。
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建需求
        </Button>
      </div>

      <Table<Requirement>
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={false}
        size="middle"
        scroll={{ x: 800 }}
        locale={{ emptyText: '暂无客户需求' }}
      />

      <Modal
        title={mode === 'edit' ? '编辑客户需求' : '新建客户需求'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={720}
        destroyOnClose
        styles={{ body: { paddingTop: 16 } }}
      >
        <Form form={form} layout="vertical" requiredMark>
          <Form.Item
            name="title"
            label="需求名称"
            rules={[{ required: true, message: '请输入需求名称' }, { max: 200, message: '最多 200 个字符' }]}
          >
            <Input placeholder="未命名记录" maxLength={200} />
          </Form.Item>

          <div className="pm-cr-grid-2">
            <Form.Item name="raisedAt" label="提出日期">
              <Input type="date" />
            </Form.Item>
            <Form.Item name="deliveryDate" label="预计完成日期">
              <Input type="date" />
            </Form.Item>
            <Form.Item name="urgency" label="紧急程度">
              <Select
                placeholder="请选择"
                options={[
                  { value: 'HIGH', label: '高' },
                  { value: 'MEDIUM', label: '中' },
                  { value: 'LOW', label: '低' },
                ]}
              />
            </Form.Item>
            <Form.Item name="status" label="状态" rules={[{ required: true, message: '请选择状态' }]}>
              <Select
                placeholder="请选择"
                options={[
                  { value: 'DRAFT', label: '草稿' },
                  { value: 'CONFIRMED', label: '已确认' },
                  { value: 'IN_PROJECT', label: '已立项' },
                  { value: 'COMPLETED', label: '已完成' },
                  { value: 'CANCELLED', label: '已取消' },
                ]}
              />
            </Form.Item>
          </div>

          <Form.Item
            name="customStatusName"
            label="自定义状态名称"
            rules={[{ max: 100, message: '最多 100 个字符' }]}
          >
            <Input placeholder="自定义状态名称" maxLength={100} />
          </Form.Item>

          <Form.Item name="rawRequirement" label="需求总结">
            <Input.TextArea rows={4} placeholder="请输入需求总结" />
          </Form.Item>

          <div className="pm-cr-custom-fields">
            <div className="pm-cr-custom-title">自定义字段</div>
            {customFieldRows.map((row) => (
              <div className="pm-cr-field-row" key={row.key}>
                <Input
                  placeholder="字段名称"
                  value={row.name ?? ''}
                  maxLength={50}
                  onChange={(e) => updateCustomField(row.key, 'name', e.target.value)}
                />
                <Input
                  placeholder="字段值"
                  value={row.value ?? ''}
                  onChange={(e) => updateCustomField(row.key, 'value', e.target.value)}
                />
                <Button
                  type="text"
                  danger
                  icon={<span style={{ fontSize: 16, lineHeight: 1 }}>&times;</span>}
                  onClick={() => removeCustomField(row.key)}
                  aria-label="删除字段"
                />
              </div>
            ))}
            <Button type="dashed" block icon={<PlusOutlined />} onClick={addCustomField}>
              新增字段
            </Button>
          </div>
        </Form>
      </Modal>
    </div>
  );
}
