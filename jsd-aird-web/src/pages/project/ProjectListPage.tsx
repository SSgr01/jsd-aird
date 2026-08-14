import {
  AppstoreOutlined,
  CopyOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import {
  Button,
  Checkbox,
  DatePicker,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Select,
  Space,
  Tooltip,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import dayjs from '@/utils/dayjs';
import './project-pages.css';
import '@/pages/partners/partner-prototype.css';
import {
  copyProjects,
  createProject,
  deleteProjects,
  formatProjectPriority,
  formatProjectStatus,
  getProjects,
  updateProject,
  projectPriorities,
  projectStatuses,
  type Project,
  type ProjectInput,
  type ProjectPriority,
  type ProjectStatus,
} from '@/services/project/project-api';

type ViewMode = 'card' | 'list';

interface CustomField {
  key: string;
  value: string;
}

interface ProjectFormValues {
  name: string;
  projectCode?: string;
  owner?: string;
  startDate?: dayjs.Dayjs | null;
  priority?: ProjectPriority;
  status?: ProjectStatus;
  teamMembers?: string[];
  background?: string;
  customFields?: CustomField[];
}

const PAGE_SIZE = 10;

function priorityClass(value: ProjectPriority) {
  return `pm-tag priority-${value.toLowerCase()}`;
}

function statusClass(value: ProjectStatus) {
  return `pm-tag status-${value.toLowerCase()}`;
}

function ellipsisName(name: string, max = 18) {
  return name.length > max ? `${name.slice(0, max)}...` : name;
}

export function ProjectListPage() {
  const [rows, setRows] = useState<Project[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [view, setView] = useState<ViewMode>('list');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const [keyword, setKeyword] = useState('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [owner, setOwner] = useState<string>();
  const [priority, setPriority] = useState<ProjectPriority>();
  const [status, setStatus] = useState<ProjectStatus>();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingProject, setEditingProject] = useState<Project | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [detail, setDetail] = useState<Project | null>(null);
  const [pendingFormValues, setPendingFormValues] = useState<Partial<ProjectFormValues> | null>(null);
  const [form] = Form.useForm<ProjectFormValues>();

  const [msg, holder] = message.useMessage();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getProjects({
        keyword: keyword || undefined,
        owner,
        priority,
        status,
        startDateFrom: dateRange?.[0]?.format('YYYY-MM-DD') || undefined,
        startDateTo: dateRange?.[1]?.format('YYYY-MM-DD') || undefined,
        page,
        size: PAGE_SIZE,
      });
      setRows(result.items);
      setTotal(result.total);
      setSelected((prev) => {
        const next = new Set<string>();
        for (const id of prev) {
          if (result.items.some((r) => r.id === id)) next.add(id);
        }
        return next;
      });
    } catch (error) {
      setRows([]);
      setTotal(0);
      msg.error(error instanceof Error ? error.message : '项目列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, owner, priority, status, dateRange, page, msg]);

  useEffect(() => {
    void load();
  }, [load]);

  // 当 Modal 打开完成（动画结束）后再回写表单值，避免 destroyOnClose 导致 Form 还未挂载时赋值被丢弃
  const handleModalAfterOpenChange = (open: boolean) => {
    if (open && pendingFormValues) {
      form.resetFields();
      form.setFieldsValue(pendingFormValues as ProjectFormValues);
      setPendingFormValues(null);
    }
  };

  const owners = useMemo(
    () => [...new Set(rows.map((r) => r.owner).filter(Boolean))] as string[],
    [rows],
  );

  const allSelected = rows.length > 0 && rows.every((r) => selected.has(r.id));
  const indeterminate = rows.some((r) => selected.has(r.id)) && !allSelected;

  const toggleSelectAll = () => {
    if (allSelected) {
      setSelected((prev) => {
        const next = new Set(prev);
        for (const r of rows) next.delete(r.id);
        return next;
      });
    } else {
      setSelected((prev) => {
        const next = new Set(prev);
        for (const r of rows) next.add(r.id);
        return next;
      });
    }
  };

  const toggleSelect = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const reset = () => {
    setKeyword('');
    setDateRange(null);
    setOwner(undefined);
    setPriority(undefined);
    setStatus(undefined);
    setPage(1);
    setSelected(new Set());
  };

  const selectedRows = useMemo(
    () => rows.filter((r) => selected.has(r.id)),
    [rows, selected],
  );

  const handleCopy = async () => {
    if (!selectedRows.length) return;
    try {
      await copyProjects(selectedRows.map((r) => r.id));
      msg.success('项目已复制');
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '复制失败');
    }
  };

  const handleDelete = () => {
    if (!selectedRows.length) return;
    Modal.confirm({
      title: '删除选中的项目？',
      content: `已选择 ${selectedRows.length} 个项目，删除后不可恢复。`,
      okText: '删除',
      okType: 'danger',
      onOk: async () => {
        try {
          await deleteProjects(selectedRows.map((r) => r.id));
          msg.success('项目已删除');
          await load();
        } catch {
          msg.error('删除失败');
        }
      },
    });
  };

  const handleDeleteRow = async (id: string) => {
    try {
      await deleteProjects([id]);
      msg.success('项目已删除');
      await load();
    } catch {
      msg.error('删除失败');
    }
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditingProject(null);
    setPendingFormValues(null);
  };

  const openCreate = () => {
    setEditingProject(null);
    setPendingFormValues({
      priority: 'MEDIUM',
      status: 'PENDING',
      teamMembers: [],
      customFields: [],
    });
    setDrawerOpen(true);
  };

  const openEdit = (row: Project) => {
    setEditingProject(row);
    setPendingFormValues({
      name: row.name,
      projectCode: row.projectCode,
      owner: row.owner,
      startDate: row.startDate ? dayjs(row.startDate) : null,
      priority: row.priority,
      status: row.status,
      teamMembers: row.teamMembers ?? [],
      background: row.background,
      customFields: Object.entries(row.customFields ?? {}).map(([key, value]) => ({
        key,
        value: String(value ?? ''),
      })),
    });
    setDrawerOpen(true);
  };

  const buildInput = (values: ProjectFormValues): ProjectInput => {
    const customFields: Record<string, unknown> = {};
    (values.customFields ?? []).forEach(({ key, value }) => {
      if (key.trim()) customFields[key.trim()] = value;
    });
    return {
      name: values.name.trim(),
      projectCode: values.projectCode?.trim() || undefined,
      owner: values.owner?.trim() || undefined,
      startDate: values.startDate?.format('YYYY-MM-DD') || undefined,
      priority: values.priority,
      status: values.status,
      teamSize: (values.teamMembers ?? []).length,
      background: values.background?.trim() || undefined,
      customFields: Object.keys(customFields).length ? customFields : undefined,
      teamMembers: values.teamMembers ?? [],
      version: editingProject?.version,
    };
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const input = buildInput(values);

    setSubmitting(true);
    try {
      if (editingProject) {
        await updateProject(editingProject.id, input);
        msg.success('项目已保存');
      } else {
        await createProject(input);
        msg.success('项目已创建');
        setPage(1);
      }
      closeDrawer();
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const renderTags = (row: Project) => (
    <div className="pm-card-tags">
      <span className={priorityClass(row.priority)}>{formatProjectPriority(row.priority)}</span>
      <span className={statusClass(row.status)}>{formatProjectStatus(row.status)}</span>
    </div>
  );

  return (
    <div className="pm-page">
      {holder}
      <div className="pm-page-head">
        <div>
          <h3>项目列表</h3>
          <p>统一管理研发项目、项目阶段、任务及关联实验。</p>
        </div>
      </div>

      <div className="pm-toolbar">
        <div className="pm-toolbar-left">
          <span className="pm-selected">已选 {selected.size} 项</span>
        </div>
        <div className="pm-toolbar-right">
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建项目
          </Button>
          <Button icon={<CopyOutlined />} disabled={!selected.size} onClick={handleCopy}>
            复制
          </Button>
          <Button danger disabled={!selected.size} onClick={handleDelete}>
            删除
          </Button>
          <Space.Compact>
            <Tooltip title="卡片视图">
              <Button
                className={view === 'card' ? 'pm-view-btn active' : 'pm-view-btn'}
                icon={<AppstoreOutlined />}
                onClick={() => setView('card')}
              />
            </Tooltip>
            <Tooltip title="列表视图">
              <Button
                className={view === 'list' ? 'pm-view-btn active' : 'pm-view-btn'}
                icon={<UnorderedListOutlined />}
                onClick={() => setView('list')}
              />
            </Tooltip>
          </Space.Compact>
        </div>
      </div>

      <div className="pm-filter">
        <Input
          prefix={<SearchOutlined />}
          placeholder="项目名称 / 项目编号"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
          allowClear
        />
        <DatePicker.RangePicker
          value={dateRange}
          onChange={(v) => {
            setDateRange(v);
            setPage(1);
          }}
          placeholder={['开始日期', '结束日期']}
        />
        <Select
          placeholder="全部负责人"
          value={owner}
          onChange={(v) => {
            setOwner(v);
            setPage(1);
          }}
          allowClear
          options={owners.map((value) => ({ value, label: value }))}
        />
        <Select
          placeholder="全部优先级"
          value={priority}
          onChange={(v) => {
            setPriority(v);
            setPage(1);
          }}
          allowClear
          options={projectPriorities.map(({ value, label }) => ({ value, label }))}
        />
        <Select
          placeholder="全部状态"
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          allowClear
          options={projectStatuses.map(({ value, label }) => ({ value, label }))}
        />
        <Button icon={<ReloadOutlined />} onClick={reset}>
          重置
        </Button>
      </div>

      <div className="pm-content" aria-busy={loading}>
        {view === 'card' ? (
          <div className="pm-card-grid">
            {rows.map((row) => (
              <div
                key={row.id}
                className={selected.has(row.id) ? 'pm-card pm-card-checked' : 'pm-card'}
              >
                <div className="pm-card-head">
                  <div style={{ minWidth: 0 }}>
                    <h4 className="pm-card-title" title={row.name}>
                      <Link to={`/projects/${row.id}`}>{row.name}</Link>
                    </h4>
                    <div className="pm-card-code">{row.projectCode}</div>
                  </div>
                  <Checkbox
                    checked={selected.has(row.id)}
                    onChange={() => toggleSelect(row.id)}
                  />
                </div>
                {renderTags(row)}
                <div className="pm-card-row">
                  <span>开始日期</span>
                  <strong>{row.startDate ?? '未设置'}</strong>
                </div>
                <div className="pm-card-row">
                  <span>团队</span>
                  <strong>{(row.teamMembers ?? []).length} 人</strong>
                </div>
                <div className="pm-card-row">
                  <span>负责人</span>
                  <strong>{row.owner ?? '未设置负责人'}</strong>
                </div>
                <div className="cm-row-actions">
                  <Link to={`/projects/${row.id}`}>查看</Link>
                  <button className="cm-link-button" onClick={() => openEdit(row)}>
                    编辑
                  </button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ overflow: 'auto' }}>
            <table className="pm-table">
              <thead>
                <tr>
                  <th>
                    <Checkbox checked={allSelected} indeterminate={indeterminate} onChange={toggleSelectAll} />
                  </th>
                  <th>项目编号</th>
                  <th>项目名称</th>
                  <th>负责人</th>
                  <th>开始日期</th>
                  <th>优先级</th>
                  <th>状态</th>
                  <th>团队成员</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id}>
                    <td>
                      <Checkbox
                        checked={selected.has(row.id)}
                        onChange={() => toggleSelect(row.id)}
                      />
                    </td>
                    <td>{row.projectCode}</td>
                    <td>
                      <Link className="pm-name-link" to={`/projects/${row.id}`} title={row.name}>
                        {ellipsisName(row.name)}
                      </Link>
                    </td>
                    <td>{row.owner ?? '—'}</td>
                    <td>{row.startDate ?? '—'}</td>
                    <td>
                      <span className={priorityClass(row.priority)}>
                        {formatProjectPriority(row.priority)}
                      </span>
                    </td>
                    <td>
                      <span className={statusClass(row.status)}>{formatProjectStatus(row.status)}</span>
                    </td>
                    <td>{(row.teamMembers ?? []).length} 人</td>
                    <td>
                      <div className="cm-row-actions">
                        <Link to={`/projects/${row.id}`}>查看</Link>
                        <button className="cm-link-button" onClick={() => openEdit(row)}>
                          编辑
                        </button>
                        <Popconfirm title="确认删除该项目？" description="删除后不可恢复。" onConfirm={() => handleDeleteRow(row.id)}>
                          <Button type="link" size="small" danger>
                            删除
                          </Button>
                        </Popconfirm>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {!loading && !rows.length && <Empty className="pm-empty" description="没有符合筛选条件的项目" />}

        <div className="pm-pagination">
          <span>共 {total} 个项目</span>
          <Pagination
            current={page}
            pageSize={PAGE_SIZE}
            total={total}
            showSizeChanger={false}
            showQuickJumper
            onChange={(p) => setPage(p)}
          />
        </div>
      </div>

      <Modal
        title={editingProject ? '编辑项目' : '新建项目'}
        open={drawerOpen}
        onCancel={closeDrawer}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText={editingProject ? '保存' : '创建'}
        cancelText="取消"
        afterOpenChange={handleModalAfterOpenChange}
        width={760}
      >
        <Form form={form} layout="vertical">
          <div className="pm-form-basics">
            <div className="pm-section-head">
              <h4 className="pm-section-title">基础信息</h4>
              <Button
                type="dashed"
                size="small"
                icon={<PlusOutlined />}
                onClick={() => {
                  const list = form.getFieldValue('customFields') as CustomField[] | undefined;
                  form.setFieldsValue({ customFields: [...(list ?? []), { key: '', value: '' }] });
                }}
              >
                添加字段
              </Button>
            </div>

            <div className="pm-form-grid">
              <Form.Item
                name="projectCode"
                label={
                  <span>
                    项目编号 <span className="pm-field-tag">系统字段</span>
                  </span>
                }
                extra="留空时由系统自动生成"
              >
                <Input placeholder="例如 JSD-PM-20260810-JFSE2" maxLength={64} disabled={Boolean(editingProject)} />
              </Form.Item>
              <Form.Item
                name="name"
                label="项目名称"
                rules={[{ required: true, message: '请输入项目名称' }, { max: 300, message: '最多 300 个字符' }]}
              >
                <Input placeholder="请输入项目名称" />
              </Form.Item>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="请输入负责人" maxLength={100} />
              </Form.Item>
              <Form.Item name="startDate" label="开始日期">
                <DatePicker style={{ width: '100%' }} placeholder="请选择开始日期" />
              </Form.Item>
              <Form.Item name="priority" label="优先级">
                <Select placeholder="请选择" options={projectPriorities.map(({ value, label }) => ({ value, label }))} />
              </Form.Item>
              <Form.Item name="status" label="项目状态">
                <Select placeholder="请选择" options={projectStatuses.map(({ value, label }) => ({ value, label }))} />
              </Form.Item>
              <Form.Item name="teamMembers" label="团队成员" className="pm-form-span-2">
                <Select
                  mode="tags"
                  placeholder="输入姓名后按回车"
                  tokenSeparators={[',', '，', '\n']}
                  style={{ width: '100%' }}
                  allowClear
                />
              </Form.Item>
            </div>
          </div>

          <Form.Item
            name="customFields"
            style={{ marginBottom: 0 }}
          >
            <Form.List name="customFields">
              {(fields, { remove }) =>
                fields.length > 0 ? (
                  <div className="pm-custom-fields">
                    <div className="pm-section-head">
                      <h4 className="pm-section-title">自定义字段</h4>
                    </div>
                    <div className="pm-form-grid">
                      {fields.map(({ key, name, ...rest }) => (
                        <div key={key} className="pm-custom-field-row" {...rest}>
                          <Form.Item name={[name, 'key']} rules={[{ required: true, message: '请输入字段名' }]}>
                            <Input placeholder="字段名" />
                          </Form.Item>
                          <Form.Item name={[name, 'value']}>
                            <Input placeholder="字段值" />
                          </Form.Item>
                          <Button type="link" danger onClick={() => remove(name)}>
                            删除
                          </Button>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null
              }
            </Form.List>
          </Form.Item>

          <div className="pm-form-desc">
            <Form.Item name="background" label="项目描述">
              <Input.TextArea rows={4} placeholder="请输入项目描述" />
            </Form.Item>
          </div>
        </Form>
      </Modal>

      <Drawer
        title={detail?.name}
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        width={520}
      >
        {detail && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="项目编号">{detail.projectCode}</Descriptions.Item>
            <Descriptions.Item label="关联客户">{detail.partnerName ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="负责人">{detail.owner ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="开始日期">{detail.startDate ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="结束日期">{detail.endDate ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="优先级">
              <span className={priorityClass(detail.priority)}>
                {formatProjectPriority(detail.priority)}
              </span>
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <span className={statusClass(detail.status)}>{formatProjectStatus(detail.status)}</span>
            </Descriptions.Item>
            <Descriptions.Item label="团队">{detail.teamSize} 人</Descriptions.Item>
            <Descriptions.Item label="项目背景">{detail.background || '—'}</Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
}
