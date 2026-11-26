import { FormModal } from '@/components/form-dialog/FormModal';
import { Can } from '@/components/auth/Can';
import { App, Button, Col, DatePicker, Empty, Form, Input, Pagination, Popconfirm, Row, Select, Space, Spin, Tooltip } from 'antd';
import { AppstoreOutlined, DeleteOutlined, PlusOutlined, UnorderedListOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import {
  createProjectCommunication, deleteProjectCommunication, listProjectCommunications,
  updateProjectCommunication, type ProjectCommunication,
} from '@/services/project/communication-api';
import './project-communications-tab.css';

interface FormValues {
  occurredAt: dayjs.Dayjs;
  communicationMethod: string;
  communicator: string;
  content: string;
}
type CustomField = { key: string; value: string };

export function ProjectCommunicationsTab({ projectId }: { projectId: string }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const [items, setItems] = useState<ProjectCommunication[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<ProjectCommunication | null>(null);
  const [viewMode, setViewMode] = useState<'card' | 'table'>('card');
  const [customFields, setCustomFields] = useState<CustomField[]>([]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    void listProjectCommunications(projectId, page).then((result) => {
      if (active) { setItems(result.items); setTotal(result.total); }
    }).catch((error: unknown) => {
      if (active) message.error(error instanceof Error ? error.message : '沟通记录加载失败');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [projectId, page, message]);

  const reload = async () => {
    const result = await listProjectCommunications(projectId, page);
    setItems(result.items);
    setTotal(result.total);
  };

  const showForm = (record?: ProjectCommunication) => {
    setEditing(record ?? null);
    setCustomFields(Object.entries(record?.customFields ?? {}).map(([key, value]) => ({ key, value: String(value ?? '') })));
    form.setFieldsValue(record ? {
      occurredAt: dayjs(record.occurredAt), communicationMethod: record.communicationMethod,
      communicator: record.communicator, content: record.content,
    } : { occurredAt: dayjs(), communicationMethod: '微信', communicator: '', content: '' });
    setOpen(true);
  };

  const save = async () => {
    try {
      const values = await form.validateFields();
      const input = {
        occurredAt: values.occurredAt.toISOString(), communicationMethod: values.communicationMethod.trim(),
        communicator: values.communicator.trim(), content: values.content.trim(),
        customFields: Object.fromEntries(customFields.map(({ key, value }) => [key.trim(), value] as const).filter(([key]) => key)),
        version: editing?.version,
      };
      setSaving(true);
      if (editing) await updateProjectCommunication(projectId, editing.id, input);
      else await createProjectCommunication(projectId, input);
      message.success(editing ? '沟通记录已更新' : '沟通记录已创建');
      setOpen(false);
      setEditing(null);
      if (page !== 1) setPage(1);
      else await reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '保存失败');
    } finally { setSaving(false); }
  };

  const remove = async (record: ProjectCommunication) => {
    try {
      await deleteProjectCommunication(projectId, record.id, record.version);
      message.success('沟通记录已删除');
      if (items.length === 1 && page > 1) setPage(page - 1);
      else await reload();
    } catch (error) { message.error(error instanceof Error ? error.message : '删除失败'); }
  };

  const actions = (record: ProjectCommunication) => <div className="pm-communication-actions">
    <Can permission="project.communication.update"><Button type="link" size="small" onClick={() => showForm(record)}>编辑</Button></Can>
    <Can permission="project.communication.delete"><Popconfirm title="删除该沟通记录？" okText="删除" cancelText="取消" onConfirm={() => void remove(record)}>
      <Button type="text" size="small" danger>删除</Button>
    </Popconfirm></Can>
  </div>;

  return <div className="pm-communication-tab">
    <div className="pm-communication-head">
      <h3>沟通记录 <small>{total} 条</small></h3>
      <Space size={8}>
        <Can permission="project.communication.create"><Button type="primary" icon={<PlusOutlined />} onClick={() => showForm()}>新建沟通记录</Button></Can>
        <Space.Compact>
          <Tooltip title="竖版视图"><Button aria-label="竖版视图" className={viewMode === 'card' ? 'pm-communication-view active' : 'pm-communication-view'} icon={<AppstoreOutlined />} onClick={() => setViewMode('card')} /></Tooltip>
          <Tooltip title="表格视图"><Button aria-label="表格视图" className={viewMode === 'table' ? 'pm-communication-view active' : 'pm-communication-view'} icon={<UnorderedListOutlined />} onClick={() => setViewMode('table')} /></Tooltip>
        </Space.Compact>
      </Space>
    </div>
    <div className="pm-communication-body" aria-busy={loading}>
      {loading ? <div className="pm-communication-loading"><Spin /></div> : items.length === 0 ?
        <Empty description="暂无沟通记录" /> : viewMode === 'table' ?
        <div className="pm-communication-table-wrap"><table className="pm-communication-table">
          <thead><tr><th>沟通时间</th><th>沟通方式</th><th>沟通人</th><th>操作</th></tr></thead>
          <tbody>{items.map((item) => <tr key={item.id}>
            <td>{dayjs(item.occurredAt).format('YYYY-MM-DD HH:mm')}</td>
            <td>{item.communicationMethod}</td><td>{item.communicator}</td><td>{actions(item)}</td>
          </tr>)}</tbody>
        </table></div> :
        <div className="pm-communication-timeline">{items.map((item) => <div className="pm-communication-item" key={item.id}>
          <div className="pm-communication-marker"><span className="pm-communication-dot" /><span className="pm-communication-line" /></div>
          <div className="pm-communication-content">
            <div className="pm-communication-time">{dayjs(item.occurredAt).format('YYYY-MM-DD HH:mm')}</div>
            <div className="pm-communication-meta"><span><em>沟通方式：</em>{item.communicationMethod}</span><span><em>沟通人：</em>{item.communicator}</span></div>
            <div className="pm-communication-text">{item.content}</div>
            {Object.keys(item.customFields ?? {}).length > 0 && <div className="pm-communication-custom-values">
              {Object.entries(item.customFields).map(([key, value]) => <span key={key}><em>{key}：</em>{String(value ?? '')}</span>)}
            </div>}
            {actions(item)}
          </div>
        </div>)}</div>}
      {total > 20 && <Pagination className="pm-communication-pagination" current={page} pageSize={20} total={total} showSizeChanger={false} onChange={setPage} />}
    </div>
    <FormModal form={form} title={editing ? '编辑沟通记录' : '添加沟通记录'} open={open}
      onCancel={() => { setOpen(false); setEditing(null); }} onOk={() => void save()} confirmLoading={saving} okText="保存" width={820} destroyOnClose>
      <Form form={form} layout="vertical">
        <Row gutter={24}>
          <Col xs={24} md={8}><Form.Item name="occurredAt" label="沟通时间" rules={[{ required: true, message: '请选择沟通时间' }]}><DatePicker showTime format="YYYY/MM/DD HH:mm" style={{ width: '100%' }} /></Form.Item></Col>
          <Col xs={24} md={8}><Form.Item name="communicationMethod" label="沟通方式" rules={[{ required: true, message: '请选择沟通方式' }]}>
            <Select options={['微信', '电话', '邮件', '会议', '现场拜访', '样品寄送'].map((value) => ({ value, label: value }))} />
          </Form.Item></Col>
          <Col xs={24} md={8}><Form.Item name="communicator" label="沟通人" rules={[{ required: true, whitespace: true, message: '请输入沟通人' }, { max: 100 }]}><Input maxLength={100} placeholder="请输入沟通人" /></Form.Item></Col>
        </Row>
        <Form.Item name="content" label="沟通内容" rules={[{ required: true, whitespace: true, message: '请输入沟通内容' }]}><Input.TextArea rows={4} placeholder="请输入沟通内容" /></Form.Item>
        <div className="pm-communication-divider"><span>自定义字段</span></div>
        <div className="pm-communication-fields">
          {customFields.map((field, index) => <div className="pm-communication-field" key={index}>
            <Input value={field.key} placeholder="字段名" onChange={(event) => setCustomFields(customFields.map((item, i) => i === index ? { ...item, key: event.target.value } : item))} />
            <Input value={field.value} placeholder="字段值" onChange={(event) => setCustomFields(customFields.map((item, i) => i === index ? { ...item, value: event.target.value } : item))} />
            <Button danger icon={<DeleteOutlined />} aria-label="删除字段" onClick={() => setCustomFields(customFields.filter((_, i) => i !== index))} />
          </div>)}
          <Button icon={<PlusOutlined />} onClick={() => setCustomFields([...customFields, { key: '', value: '' }])}>新增字段</Button>
        </div>
      </Form>
    </FormModal>
  </div>;
}
