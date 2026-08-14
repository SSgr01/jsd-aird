import { App, Button, DatePicker, Form, Input, Modal, Popconfirm, Space, Tag } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, TeamOutlined, EyeOutlined, FileTextOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useEffect, useMemo, useState } from 'react';

import {
  archiveMeetingToKb,
  createMeeting,
  deleteMeeting,
  listMeetings,
  updateMeeting,
  type MeetingMinutes,
  type MeetingMinutesInput,
} from '@/services/project/meeting-api';

import './meeting-minutes-tab.css';

interface Props {
  projectId: string;
}

interface CreateFormValues {
  title: string;
  attendeesText?: string;
  summary?: string;
  occurredAt?: dayjs.Dayjs;
}

function formatDate(value?: string) {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD') : value;
}

function attendeesToText(value: string[]) {
  return value.join('、');
}

function parseAttendees(value?: string): string[] {
  if (!value) return [];
  return value.split(/[、，,]/).map((s) => s.trim()).filter(Boolean);
}

export function MeetingMinutesTab({ projectId }: Props) {
  const { message } = App.useApp();
  const [items, setItems] = useState<MeetingMinutes[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editing, setEditing] = useState<MeetingMinutes | null>(null);
  const [viewing, setViewing] = useState<MeetingMinutes | null>(null);
  const [form] = Form.useForm<CreateFormValues>();

  const load = async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listMeetings(projectId, { page: 1, size: 50 });
      setItems(data.items ?? []);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '会议纪要加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ occurredAt: dayjs() });
    setModalOpen(true);
  };

  const openEdit = (record: MeetingMinutes) => {
    setEditing(record);
    form.resetFields();
    form.setFieldsValue({
      title: record.title,
      attendeesText: attendeesToText(record.attendees ?? []),
      summary: record.summary,
      occurredAt: record.occurredAt ? dayjs(record.occurredAt) : dayjs(),
    });
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditing(null);
    form.resetFields();
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (!projectId) {
      message.error('缺少项目 ID，无法保存会议纪要');
      return;
    }
    const attendees = parseAttendees(values.attendeesText);
    const payload: MeetingMinutesInput = {
      projectId,
      title: values.title.trim(),
      attendees,
      summary: values.summary?.trim() || undefined,
      occurredAt: values.occurredAt ? values.occurredAt.toISOString() : undefined,
      version: editing?.version,
    };
    setSubmitting(true);
    try {
      if (editing) {
        await updateMeeting(editing.id, payload);
        message.success('会议纪要已更新');
      } else {
        await createMeeting(projectId, payload);
        message.success('会议纪要已创建');
      }
      closeModal();
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (record: MeetingMinutes) => {
    try {
      await deleteMeeting(record.id, record.version);
      message.success('会议纪要已删除');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '删除失败');
    }
  };

  const handleArchive = async (record: MeetingMinutes) => {
    try {
      await archiveMeetingToKb(record.id);
      message.success('已归档到知识库');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '归档失败');
    }
  };

  const sortedItems = useMemo(
    () => [...items].sort((a, b) => (b.occurredAt ?? '').localeCompare(a.occurredAt ?? '')),
    [items],
  );

  return (
    <div className="pm-mm-tab">
      <div className="pm-mm-tab-head">
        <div className="pm-mm-tip">按项目归档所有会议纪要，支持新增、编辑、查看与归档到知识库。</div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新增会议纪要
        </Button>
      </div>

      {loading ? (
        <div className="pm-mm-empty">加载中…</div>
      ) : sortedItems.length === 0 ? (
        <div className="pm-mm-empty">
          <FileTextOutlined style={{ fontSize: 28, color: '#bfbfbf' }} />
          <div>暂无会议纪要</div>
        </div>
      ) : (
        <ul className="pm-mm-list">
          {sortedItems.map((item) => (
            <li className="pm-mm-item" key={item.id}>
              <div className="pm-mm-item-head">
                <div className="pm-mm-title">
                  {item.title}
                  {item.archivedToKb ? <Tag color="green" style={{ marginLeft: 8 }}>已归档</Tag> : null}
                </div>
                <div className="pm-mm-date">
                  <span className="pm-mm-date-icon" aria-hidden>📅</span>
                  {formatDate(item.occurredAt)}
                </div>
              </div>

              {(item.attendees ?? []).length > 0 ? (
                <div className="pm-mm-attendees">
                  <TeamOutlined style={{ marginRight: 6, color: '#8c8c8c' }} />
                  {item.attendees.join('、')}
                </div>
              ) : null}

              {item.summary ? <div className="pm-mm-summary">{item.summary}</div> : null}

              <div className="pm-mm-actions">
                <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setViewing(item)}>
                  查看
                </Button>
                <Button type="link" size="small" icon={<FileTextOutlined />} onClick={() => handleArchive(item)} disabled={item.archivedToKb}>
                  归档知识库
                </Button>
                <Popconfirm title="确认删除该会议纪要？" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => handleDelete(item)}>
                  <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </div>
            </li>
          ))}
        </ul>
      )}

      <Modal
        title={editing ? '编辑会议纪要' : '新增会议纪要'}
        open={modalOpen}
        onCancel={closeModal}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        width={640}
        destroyOnClose
      >
        <Form form={form} layout="vertical" requiredMark>
          <Form.Item name="title" label="会议主题" rules={[{ required: true, message: '请输入会议主题' }, { max: 200, message: '最多 200 个字符' }]}>
            <Input placeholder="例如：内部技术评审会" maxLength={200} />
          </Form.Item>
          <Form.Item name="attendeesText" label="参会人员" tooltip="多个姓名用 、 或 ， 分隔">
            <Input placeholder="例如：张三、李四" />
          </Form.Item>
          <Form.Item name="occurredAt" label="会议日期">
            <DatePicker style={{ width: '100%' }} format="YYYY-MM-DD" />
          </Form.Item>
          <Form.Item name="summary" label="会议总结">
            <Input.TextArea rows={5} placeholder="请输入会议总结" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="会议纪要详情"
        open={!!viewing}
        onCancel={() => setViewing(null)}
        footer={
          <Space>
            <Button onClick={() => setViewing(null)}>关闭</Button>
            {viewing ? (
              <>
                <Button
                  icon={<FileTextOutlined />}
                  onClick={() => handleArchive(viewing)}
                  disabled={viewing.archivedToKb}
                >
                  归档知识库
                </Button>
                <Button
                  type="primary"
                  icon={<EditOutlined />}
                  onClick={() => {
                    const record = viewing;
                    setViewing(null);
                    openEdit(record);
                  }}
                >
                  编辑
                </Button>
              </>
            ) : null}
          </Space>
        }
        width={640}
      >
        {viewing ? (
          <div className="pm-mm-view">
            <div className="pm-mm-row">
              <div className="pm-mm-row-label">会议标题</div>
              <div className="pm-mm-row-value">{viewing.title || '—'}</div>
            </div>
            <div className="pm-mm-row">
              <div className="pm-mm-row-label">会议日期</div>
              <div className="pm-mm-row-value">{formatDate(viewing.occurredAt)}</div>
            </div>
            <div className="pm-mm-row">
              <div className="pm-mm-row-label">参会人员</div>
              <div className="pm-mm-row-value">{viewing.attendees?.length ? viewing.attendees.join('、') : '—'}</div>
            </div>
            <div className="pm-mm-row">
              <div className="pm-mm-row-label">纪要内容</div>
              <div className="pm-mm-row-value" style={{ whiteSpace: 'pre-wrap' }}>{viewing.summary || '—'}</div>
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  );
}