import { AppstoreOutlined, ReloadOutlined, SearchOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Breadcrumb,
  Button,
  Empty,
  Input,
  message,
  Pagination,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  getProjects,
  getStages,
  getTasks,
  getTaskOwners,
  deleteProjectTask,
  formatProjectPriority,
  formatTaskStatus,
  projectPriorities,
  taskStatuses,
  type Project,
  type ProjectPriority,
  type ProjectStage,
  type ProjectTask,
  type TaskQuery,
} from '@/services/project/project-api';

import './project-pages.css';
import './task-page.css';
import '@/styles/management-list.css';

type ViewMode = 'card' | 'list';

const isMobileViewport = () => typeof window !== 'undefined'
  && window.matchMedia?.('(max-width: 720px)').matches === true;

const defaultQuery: TaskQuery = {
  keyword: '',
  projectId: undefined,
  stageId: undefined,
  status: undefined,
  owner: undefined,
  priority: undefined,
  page: 1,
  size: 10,
};

function priorityColor(value?: ProjectPriority) {
  return value === 'HIGH' ? 'red' : value === 'MEDIUM' ? 'gold' : value === 'LOW' ? 'green' : 'default';
}

function statusTagClass(value?: string) {
  const normalized = value === 'COMPLETED'
    ? 'completed'
    : value === 'IN_PROGRESS'
      ? 'in_progress'
      : value === 'CANCELLED'
        ? 'cancelled'
        : 'not_started';
  return `pm-status-tag status-${normalized}`;
}

export default function TaskPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<TaskQuery>({ ...defaultQuery });
  const [view, setView] = useState<ViewMode>(() => (isMobileViewport() ? 'card' : 'list'));

  const [projects, setProjects] = useState<Project[]>([]);
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [owners, setOwners] = useState<string[]>([]);
  const [deletingTaskId, setDeletingTaskId] = useState<string>();

  // Load filter options
  useEffect(() => {
    const loadOptions = async () => {
      try {
        const [projectRes, ownerRes] = await Promise.all([
          getProjects({ page: 1, size: 1000 }),
          getTaskOwners(),
        ]);
        setProjects(projectRes.items);
        setOwners(ownerRes);
      } catch (e) {
        message.error('加载筛选选项失败');
      }
    };
    loadOptions();
  }, []);

  // Load stages when selected project changes
  useEffect(() => {
    const loadStages = async () => {
      if (!query.projectId) {
        setStages([]);
        return;
      }
      try {
        const res = await getStages({ projectId: query.projectId, page: 1, size: 1000 });
        setStages(res.items);
      } catch (e) {
        setStages([]);
      }
    };
    loadStages();
  }, [query.projectId]);

  // Load task list
  const loadTasks = async (q: TaskQuery) => {
    setLoading(true);
    try {
      const res = await getTasks(q);
      setTasks(res.items);
      setTotal(res.total);
    } catch (e) {
      message.error('加载任务列表失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTasks(query);
  }, [query.page, query.size, query.keyword, query.projectId, query.stageId, query.status, query.owner, query.priority]);

  const handleFilterChange = <K extends keyof TaskQuery>(key: K, value: TaskQuery[K]) => {
    setQuery((prev) => {
      const next = { ...prev, [key]: value, page: 1 };
      if (key === 'projectId') {
        next.stageId = undefined;
      }
      return next;
    });
  };

  const handleReset = () => {
    setQuery({ ...defaultQuery });
  };

  const handleView = (task: ProjectTask) => {
    if (task.projectId) {
      navigate(`/projects/${task.projectId}?section=tasks&stageId=${task.stageId}&taskId=${task.id}`);
    }
  };

  const handleDelete = async (task: ProjectTask) => {
    setDeletingTaskId(task.id);
    try {
      await deleteProjectTask(task.id, task.version);
      message.success('任务已删除');
      await loadTasks(query);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '任务删除失败');
    } finally {
      setDeletingTaskId(undefined);
    }
  };

  const priorityOptions = useMemo(
    () => [{ value: '', label: '全部优先级' }, ...projectPriorities],
    [],
  );
  const statusOptions = useMemo(() => [{ value: '', label: '全部状态' }, ...taskStatuses], []);

  const projectOptions = useMemo(
    () => [
      { value: '', label: '全部项目' },
      ...projects.map((p) => ({ value: p.id, label: `${p.name} (${p.projectCode})` })),
    ],
    [projects],
  );

  const stageOptions = useMemo(
    () => [
      { value: '', label: query.projectId ? '全部阶段' : '请先选择项目' },
      ...stages.map((s) => ({ value: s.id, label: s.name })),
    ],
    [stages, query.projectId],
  );

  const ownerOptions = useMemo(
    () => [
      { value: '', label: '全部执行人' },
      ...owners.map((o) => ({ value: o, label: o })),
    ],
    [owners],
  );

  const columns: ColumnsType<ProjectTask> = [
    {
      title: '任务编号',
      dataIndex: 'taskCode',
      className: 'pm-code-column',
      width: 200,
    },
    {
      title: '任务名称',
      dataIndex: 'name',
      className: 'pm-name-column',
      render: (value: string, record: ProjectTask) => (
        <Button type="link" className="pm-task-name-link" onClick={() => handleView(record)}>
          {value}
        </Button>
      ),
    },
    {
      title: '所属项目',
      dataIndex: 'projectName',
      className: 'pm-project-column',
      render: (value: string | undefined) => <div className="pm-task-project">{value || '—'}</div>,
    },
    {
      title: '所属阶段',
      dataIndex: 'stageName',
      width: 160,
      render: (value: string | undefined) => value || '—',
    },
    {
      title: '执行人',
      dataIndex: 'owner',
      width: 120,
      render: (value: string | undefined) => value || '—',
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      width: 100,
      render: (value: string | undefined) => {
        const label = formatProjectPriority(value as 'HIGH' | 'MEDIUM' | 'LOW');
        return value ? <Tag color={priorityColor(value as ProjectPriority)}>{label}</Tag> : '—';
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => {
        const label = formatTaskStatus(value);
        return <Tag className={statusTagClass(value)}>{label}</Tag>;
      },
    },
    {
      title: '实验数',
      dataIndex: 'experimentCount',
      width: 90,
      align: 'center' as const,
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_: unknown, record: ProjectTask) => (
        <Space className="management-table-actions" size={0}>
          <Button type="link" onClick={() => handleView(record)}>查看</Button>
          <Popconfirm
            title="确认删除该任务？"
            description={record.experimentCount > 0 ? '任务下存在实验，不能删除。' : '删除后不可恢复。'}
            disabled={record.experimentCount > 0}
            onConfirm={() => void handleDelete(record)}
          >
            <Button type="link" danger disabled={record.experimentCount > 0 || deletingTaskId === record.id} loading={deletingTaskId === record.id}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="pm-page pm-unified-list-page pm-task-page">
      <div className="pm-page-head">
        <div>
          <Breadcrumb items={[{ title: '项目管理' }, { title: '任务' }]} />
          <h3>任务</h3>
          <p>集中查看并筛选全部项目任务，数据来自项目管理原有项目结构。</p>
        </div>
      </div>
      <div className="pm-task-toolbar">
        <Space size={8}>
          <Button type="primary" onClick={() => navigate('/projects/list')}>
            项目列表
          </Button>
          <Space.Compact>
            <Tooltip title="卡片视图">
              <Button
                aria-label="卡片视图"
                className={view === 'card' ? 'pm-view-btn active' : 'pm-view-btn'}
                icon={<AppstoreOutlined />}
                onClick={() => setView('card')}
              />
            </Tooltip>
            <Tooltip title="列表视图">
              <Button
                aria-label="列表视图"
                className={view === 'list' ? 'pm-view-btn active' : 'pm-view-btn'}
                icon={<UnorderedListOutlined />}
                onClick={() => setView('list')}
              />
            </Tooltip>
          </Space.Compact>
        </Space>
      </div>

      <div className="pm-task-filters">
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索任务名称、编号或目标"
          allowClear
          value={query.keyword}
          onChange={(e) => {
            handleFilterChange('keyword', e.target.value || undefined);
          }}
        />
        <Select
          placeholder="全部项目"
          value={query.projectId || ''}
          options={projectOptions}
          onChange={(v) => handleFilterChange('projectId', v || undefined)}
          showSearch
          optionFilterProp="label"
          allowClear
        />
        <Select
          placeholder="全部阶段"
          value={query.stageId || ''}
          options={stageOptions}
          onChange={(v) => handleFilterChange('stageId', v || undefined)}
          disabled={!query.projectId}
          showSearch
          optionFilterProp="label"
          allowClear
        />
        <Select
          placeholder="全部状态"
          value={query.status || ''}
          options={statusOptions}
          onChange={(v) => handleFilterChange('status', v || undefined)}
          allowClear
        />
        <Select
          placeholder="全部执行人"
          value={query.owner || ''}
          options={ownerOptions}
          onChange={(v) => handleFilterChange('owner', v || undefined)}
          showSearch
          optionFilterProp="label"
          allowClear
        />
        <Select
          placeholder="全部优先级"
          value={query.priority || ''}
          options={priorityOptions}
          onChange={(v) => handleFilterChange('priority', (v || undefined) as ProjectPriority | undefined)}
          allowClear
        />
        <Button icon={<ReloadOutlined />} onClick={handleReset}>
          重置
        </Button>
      </div>

      <div className="pm-task-content">
        {view === 'card' ? (
          <Spin spinning={loading}>
            <div className="pm-card-grid pm-task-card-grid">
              {tasks.map((task) => (
                <div className="pm-card pm-task-card" key={task.id}>
                  <div className="pm-card-head">
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <h4 className="pm-card-title" title={task.name}>
                        <Button type="link" onClick={() => handleView(task)}>{task.name}</Button>
                      </h4>
                      <div className="pm-card-code">{task.taskCode}</div>
                    </div>
                    <div className="pm-card-tags">
                      {task.priority && <Tag color={priorityColor(task.priority)}>{formatProjectPriority(task.priority)}</Tag>}
                      <Tag className={statusTagClass(task.status)}>{formatTaskStatus(task.status)}</Tag>
                    </div>
                  </div>
                  <div className="pm-card-info-grid">
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">所属项目</span>
                      <strong className="pm-card-info-value" title={task.projectName}>{task.projectName || '—'}</strong>
                    </div>
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">所属阶段</span>
                      <strong className="pm-card-info-value" title={task.stageName}>{task.stageName || '—'}</strong>
                    </div>
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">执行人</span>
                      <strong className="pm-card-info-value">{task.owner || '—'}</strong>
                    </div>
                    <div className="pm-card-info-item">
                      <span className="pm-card-info-label">实验数</span>
                      <strong className="pm-card-info-value">{task.experimentCount}</strong>
                    </div>
                  </div>
                  <div className="pm-card-actions">
                    <Button type="link" onClick={() => handleView(task)}>查看</Button>
                    <Popconfirm
                      title="确认删除该任务？"
                      description={task.experimentCount > 0 ? '任务下存在实验，不能删除。' : '删除后不可恢复。'}
                      disabled={task.experimentCount > 0}
                      onConfirm={() => void handleDelete(task)}
                    >
                      <Button type="link" danger disabled={task.experimentCount > 0 || deletingTaskId === task.id} loading={deletingTaskId === task.id}>删除</Button>
                    </Popconfirm>
                  </div>
                </div>
              ))}
              {!loading && !tasks.length && <Empty description="暂无符合条件的任务" />}
            </div>
          </Spin>
        ) : (
          <div className="pm-table-scroll">
            <Table
              rowKey="id"
              loading={loading}
              size="small"
              columns={columns}
              dataSource={tasks}
              pagination={false}
              scroll={{ x: 1360 }}
            />
          </div>
        )}
      </div>

      <div className="pm-task-pagination">
        <Pagination
          current={query.page}
          pageSize={query.size}
          total={total}
          showSizeChanger
          pageSizeOptions={[10, 20, 30, 50]}
          showTotal={(t) => `共 ${t} 条`}
          onChange={(page, size) => setQuery((prev) => ({ ...prev, page, size }))}
        />
      </div>
    </div>
  );
}
