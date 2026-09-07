import {
  AppstoreOutlined,
  DeleteOutlined,
  CopyOutlined,
  DownloadOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import {
  Breadcrumb,
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
import { Link, useSearchParams } from 'react-router-dom';
import * as XLSX from 'xlsx';
import dayjs from '@/utils/dayjs';
import './project-pages.css';
import './project-list.css';
import '@/pages/partners/partner-prototype.css';
import '@/styles/management-list.css';
import {
  copyProjects,
  createProject,
  deleteProjects,
  formatProjectPriority,
  formatProjectStatus,
  getProject,
  getProjects,
  exportProjects,
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

const DEFAULT_PAGE_SIZE = 10;

function priorityClass(value: ProjectPriority) {
  return `pm-tag priority-${value.toLowerCase()}`;
}

function statusClass(value: ProjectStatus) {
  return `pm-tag status-${value.toLowerCase()}`;
}

export function ProjectListPage() {
  const [rows, setRows] = useState<Project[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [view, setView] = useState<ViewMode>('list');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const [keyword, setKeyword] = useState('');
  const [dateRange, setDateRange] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [owner, setOwner] = useState<string>();
  const [priority, setPriority] = useState<ProjectPriority>();
  const [status, setStatus] = useState<ProjectStatus>();

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [detail, setDetail] = useState<Project | null>(null);
  const [pendingFormValues, setPendingFormValues] = useState<Partial<ProjectFormValues> | null>(null);
  const [form] = Form.useForm<ProjectFormValues>();

  const [msg, holder] = message.useMessage();
  const [searchParams, setSearchParams] = useSearchParams();

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
        size: pageSize,
      });
      setRows(result.items);
      setTotal(result.total);
    } catch (error) {
      setRows([]);
      setTotal(0);
      msg.error(error instanceof Error ? error.message : '项目列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, owner, priority, status, dateRange, page, pageSize, msg]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (searchParams.get('create') !== '1') return;
    setPendingFormValues({
      priority: 'MEDIUM',
      status: 'NOT_STARTED',
      teamMembers: [],
      customFields: [],
    });
    setDrawerOpen(true);
    setSearchParams((current) => {
      current.delete('create');
      return current;
    }, { replace: true });
  }, [searchParams, setSearchParams]);

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

  const handleExport = async () => {
    setExporting(true);
    try {
      if (selected.size > 0) {
        const projects = await Promise.all([...selected].map(async (id) => {
          const current = rows.find((item) => item.id === id);
          return current ?? getProject(id);
        }));
        const data = projects.map((project) => ({
          项目编号: project.projectCode,
          项目名称: project.name,
          所属客户: project.partnerName || '',
          负责人: project.owner || '',
          开始日期: project.startDate || '',
          结束日期: project.endDate || '',
          优先级: formatProjectPriority(project.priority),
          状态: formatProjectStatus(project.status),
          团队成员: project.teamMembers?.join('、') || '',
        }));
        const ws = XLSX.utils.json_to_sheet(data);
        const wb = XLSX.utils.book_new();
        XLSX.utils.book_append_sheet(wb, ws, '项目列表');
        XLSX.writeFile(wb, `项目列表_选中_${dayjs().format('YYYYMMDD_HHmmss')}.xlsx`);
        msg.success(`已导出 ${projects.length} 个选中项目`);
      } else {
        await exportProjects({
          keyword: keyword || undefined,
          owner,
          priority,
          status,
          startDateFrom: dateRange?.[0]?.format('YYYY-MM-DD') || undefined,
          startDateTo: dateRange?.[1]?.format('YYYY-MM-DD') || undefined,
        });
        msg.success('项目查询结果已导出');
      }
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '项目列表导出失败');
    } finally {
      setExporting(false);
    }
  };

  const handleCopy = async () => {
    if (!selected.size) return;
    try {
      await copyProjects([...selected]);
      msg.success('项目已复制');
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '复制失败');
    }
  };

  const handleDelete = () => {
    if (!selected.size) return;
    Modal.confirm({
      title: '删除选中的项目？',
      content: `已选择 ${selected.size} 个项目，删除后不可恢复。`,
      okText: '删除',
      okType: 'danger',
      onOk: async () => {
        try {
          await deleteProjects([...selected]);
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
    setPendingFormValues(null);
  };

  const openCreate = () => {
    setPendingFormValues({
      priority: 'MEDIUM',
      status: 'NOT_STARTED',
      teamMembers: [],
      customFields: [],
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
    };
  };

  const handleSubmit = async () => {
    let values: ProjectFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const input = buildInput(values);

    setSubmitting(true);
    try {
      await createProject(input);
      msg.success('项目已创建');
      setPage(1);
      closeDrawer();
      await load();
    } catch (error) {
      msg.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="pm-page pm-unified-list-page pm-project-list-page">
      {holder}
      <div className="pm-page-head pm-project-page-intro">
        <div>
          <Breadcrumb items={[{ title: '项目管理' }, { title: '项目列表' }]} />
          <h3>项目列表</h3>
          <p>统一管理研发项目、项目阶段、任务及关联实验。</p>
        </div>
      </div>

      <div className="pm-filter-row">
        <Input
          prefix={<SearchOutlined />}
          placeholder="项目名称 / 项目编号 / 公司名称"
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
        <Button className="pm-filter-reset" icon={<ReloadOutlined />} onClick={reset}>
          重置
        </Button>
      </div>

      <div className="pm-batch-row">
        <div className="pm-batch-summary">
          <span className="pm-selected">已选 {selected.size} 项</span>
          {selected.size > 0 && (
            <Button type="link" size="small" onClick={() => setSelected(new Set())}>
              清除已选
            </Button>
          )}
        </div>
        <div className="pm-batch-actions">
          <Button icon={<DownloadOutlined />} loading={exporting} onClick={() => void handleExport()}>
            导出
          </Button>
          <Button icon={<CopyOutlined />} disabled={!selected.size} onClick={handleCopy}>
            复制
          </Button>
          <Button danger disabled={!selected.size} onClick={handleDelete}>
            删除
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建项目
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

      <div className="pm-content" aria-busy={loading}>
        {view === 'card' ? (
          <>
            {rows.length > 0 && (
              <div className="pm-card-grid-head">
                <Checkbox
                  checked={allSelected}
                  indeterminate={indeterminate}
                  onChange={toggleSelectAll}
                />
                <span>全选当前页</span>
              </div>
            )}
            <div className="pm-card-grid">
              {rows.map((row) => (
                <div
                  key={row.id}
                  className={selected.has(row.id) ? 'pm-card pm-card-checked' : 'pm-card'}
                >
                  <div className="pm-card-head">
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <h4 className="pm-card-title" title={row.name}>
                        <Link to={`/projects/${row.id}`}>{row.name}</Link>
                      </h4>
                      <div className="pm-card-code">
                        {row.projectCode}
                      </div>
                    </div>
                    <Checkbox
                      checked={selected.has(row.id)}
                      onChange={() => toggleSelect(row.id)}
                    />
                  </div>
                  <div className="pm-card-info-grid">
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">负责人</span>
                      <strong className="pm-card-info-value" title={row.owner}>{row.owner ?? '—'}</strong>
                    </div>
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">开始日期</span>
                      <strong className="pm-card-info-value">{row.startDate ?? '未设置'}</strong>
                    </div>
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">团队</span>
                      <strong className="pm-card-info-value">{(row.teamMembers ?? []).length} 人</strong>
                    </div>
                  </div>
                  <div className="pm-card-tags">
                    <span className={priorityClass(row.priority)}>{formatProjectPriority(row.priority)}</span>
                    <span className={statusClass(row.status)}>{formatProjectStatus(row.status)}</span>
                  </div>
                  <div className="pm-card-actions">
                    <Link to={`/projects/${row.id}`}>查看</Link>
                    <Popconfirm title="确认删除该项目？" description="删除后不可恢复。" onConfirm={() => handleDeleteRow(row.id)}>
                      <Button type="link" danger>
                        删除
                      </Button>
                    </Popconfirm>
                  </div>
                </div>
              ))}
            </div>
          </>
        ) : (
          <div style={{ overflow: 'auto' }}>
            <table className="pm-table">
              <thead>
                <tr>
                  <th>
                    <Checkbox checked={allSelected} indeterminate={indeterminate} onChange={toggleSelectAll} />
                  </th>
                  <th className="pm-code-column">项目编号</th>
                  <th className="pm-name-column pm-project-name-column">项目名称</th>
                  <th className="pm-owner-column">负责人</th>
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
                    <td className="pm-code-column">{row.projectCode}</td>
                    <td className="pm-name-column pm-project-name-column">
                      <Link className="pm-name-link" to={`/projects/${row.id}`} title={row.name}>
                        {row.name}
                      </Link>
                    </td>
                    <td className="pm-owner-column">{row.owner ?? '—'}</td>
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
                      <div className="cm-row-actions management-table-actions">
                        <Link to={`/projects/${row.id}`}>查看</Link>
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
          <div className="pm-pagination-right">
            <span>共 {total} 个项目</span>
            <Pagination
              current={page}
              pageSize={pageSize}
              total={total}
              showSizeChanger
              pageSizeOptions={[10, 20, 30, 50]}
              onChange={(p, s) => {
                // Changing the page size is a new query; restart at page 1 so
                // a previously selected page cannot leave the list empty.
                setPage(s !== pageSize ? 1 : p);
                setPageSize(s);
              }}
            />
          </div>
        </div>
      </div>

      <Modal
        title="新建项目"
        open={drawerOpen}
        onCancel={closeDrawer}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText="创建"
        cancelText="取消"
        afterOpenChange={handleModalAfterOpenChange}
        width={760}
      >
        <Form form={form} layout="vertical">
          <div className="pm-form-basics">
            <div className="pm-section-head">
              <h4 className="pm-section-title">基础信息</h4>
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
                <Input placeholder="例如 JSD-PM-20260810-JFSE2" maxLength={64} />
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
                <Select
                  style={{ width: 220 }}
                  placeholder="请选择"
                  options={projectStatuses.map(({ value, label }) => ({ value, label }))}
                />
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
            <div className="pm-form-desc">
              <Form.Item name="background" label="项目描述">
                <Input.TextArea rows={4} placeholder="请输入项目描述" />
              </Form.Item>
            </div>
          </div>

          <Form.List name="customFields">
            {(fields, { remove }) => (
              <div className="pm-custom-fields-block">
                {fields.length > 0 && (
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
                          <Button type="link" danger icon={<DeleteOutlined />} onClick={() => remove(name)} />
                        </div>
                      ))}
                    </div>
                  </div>
                )}
                <Button
                  type="dashed"
                  size="small"
                  icon={<PlusOutlined />}
                  style={{ marginTop: fields.length > 0 ? 12 : 0 }}
                  onClick={() => form.setFieldsValue({ customFields: [...(form.getFieldValue('customFields') ?? []), { key: '', value: '' }] })}
                >
                  添加字段
                </Button>
              </div>
            )}
          </Form.List>
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
