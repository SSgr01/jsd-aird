import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Badge,
  Button,
  Flex,
  Input,
  message,
  Pagination,
  Select,
  Table,
  Typography,
} from 'antd';
import { SearchOutlined, ReloadOutlined, EyeOutlined } from '@ant-design/icons';
import {
  getProjects,
  getStages,
  getTasks,
  getTaskOwners,
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

import './task-page.css';

const { Title, Text } = Typography;

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

export default function TaskPage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [tasks, setTasks] = useState<ProjectTask[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<TaskQuery>({ ...defaultQuery });

  const [projects, setProjects] = useState<Project[]>([]);
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [owners, setOwners] = useState<string[]>([]);

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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const columns = [
    {
      title: '任务编号',
      dataIndex: 'taskCode',
      width: 200,
    },
    {
      title: '任务名称',
      dataIndex: 'name',
      ellipsis: true,
    },
    {
      title: '所属项目',
      dataIndex: 'projectName',
      ellipsis: true,
      render: (value: string | undefined) => value || '—',
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
        const color = value === 'HIGH' ? 'red' : value === 'MEDIUM' ? 'orange' : 'green';
        return value ? <Badge color={color} text={label} /> : '—';
      },
    },
    {
      title: '实验数',
      dataIndex: 'experimentCount',
      width: 90,
      align: 'center' as const,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => {
        const label = formatTaskStatus(value);
        const color = value === 'COMPLETED' ? 'success' : value === 'IN_PROGRESS' ? 'processing' : 'default';
        return <Badge status={color as any} text={label} />;
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_: unknown, record: ProjectTask) => (
        <Button type="link" icon={<EyeOutlined />} onClick={() => handleView(record)}>
          查看
        </Button>
      ),
    },
  ];

  return (
    <Flex vertical gap={16} style={{ padding: 24 }}>
      <Flex justify="space-between" align="flex-start">
        <div>
          <Title level={4} style={{ marginBottom: 4 }}>
            任务
          </Title>
          <Text type="secondary">集中查看并筛选全部项目任务，数据来自项目管理原有项目结构。</Text>
        </div>
        <Button type="primary" onClick={() => navigate('/projects/list')}>
          项目列表
        </Button>
      </Flex>

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

        <Table
          className="pm-task-page"
          rowKey="id"
          loading={loading}
          size="small"
          columns={columns}
          dataSource={tasks}
          pagination={false}
          scroll={{ x: 1100 }}
        />

        <Flex justify="flex-end" style={{ marginTop: 16 }}>
          <Pagination
            current={query.page}
            pageSize={query.size}
            total={total}
            showSizeChanger
            pageSizeOptions={[10, 20, 30, 50]}
            showTotal={(t) => `共 ${t} 条`}
            onChange={(page, size) => setQuery((prev) => ({ ...prev, page, size }))}
          />
        </Flex>
    </Flex>
  );
}
