import { AppstoreOutlined, ReloadOutlined, SearchOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Breadcrumb, Button, DatePicker, Empty, Input, Pagination, Popconfirm, Progress, Select, Space, Spin, Table, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import dayjs from '@/utils/dayjs';

import { deleteStage, formatStageStatus, getProjects, getStages, stageStatuses, type ProjectStage, type StageStatus } from '@/services/project/project-api';
import './project-pages.css';
import './phase-page.css';
import '@/styles/management-list.css';

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZE_OPTIONS = [10, 20, 30, 50];
type RangeValue = Parameters<NonNullable<React.ComponentProps<typeof DatePicker.RangePicker>['onChange']>>[0];
type ViewMode = 'card' | 'list';

const isMobileViewport = () => typeof window !== 'undefined'
  && window.matchMedia?.('(max-width: 720px)').matches === true;

function statusTagClass(status: StageStatus) {
  const normalized = status === 'PENDING' ? 'not_started' : status.toLowerCase();
  return `pm-status-tag status-${normalized}`;
}

export function PhasePage() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<ProjectStage[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [view, setView] = useState<ViewMode>(() => (isMobileViewport() ? 'card' : 'list'));
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [projectId, setProjectId] = useState<string>();
  const [status, setStatus] = useState<StageStatus>();
  const [owner, setOwner] = useState<string>();
  const [dates, setDates] = useState<RangeValue>(null);
  const [projects, setProjects] = useState<{ value: string; label: string }[]>([]);
  const [deletingStageId, setDeletingStageId] = useState<string>();
  const [messageApi, holder] = message.useMessage();

  useEffect(() => {
    void getProjects({ page: 1, size: 200 })
      .then((result) => setProjects(result.items.map((project) => ({ value: project.id, label: `${project.projectCode} ${project.name}` }))))
      .catch(() => setProjects([]));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await getStages({ keyword: keyword.trim() || undefined, projectId, status, owner,
        plannedFrom: dates?.[0]?.format('YYYY-MM-DD'), plannedTo: dates?.[1]?.format('YYYY-MM-DD'), page, size: pageSize });
      setRows(result.items); setTotal(result.total);
    } catch (reason) { messageApi.error(reason instanceof Error ? reason.message : '阶段查询失败'); }
    finally { setLoading(false); }
  }, [dates, keyword, messageApi, owner, page, pageSize, projectId, status]);

  useEffect(() => { void load(); }, [load]);

  const owners = [...new Set(rows.map(({ owner: value }) => value).filter((value): value is string => Boolean(value)))];
  const handleDelete = async (stage: ProjectStage) => {
    setDeletingStageId(stage.id);
    try {
      await deleteStage(stage.id, stage.version);
      messageApi.success('阶段已删除');
      await load();
    } catch (reason) {
      messageApi.error(reason instanceof Error ? reason.message : '阶段删除失败');
    } finally {
      setDeletingStageId(undefined);
    }
  };

  const columns: ColumnsType<ProjectStage> = [
    { title: '阶段编号', dataIndex: 'stageCode', className: 'pm-code-column', width: 200 },
    { title: '阶段名称', dataIndex: 'name', className: 'pm-name-column', render: (value: string, row) => <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>{value}</Button> },
    { title: '所属项目', dataIndex: 'projectName', className: 'pm-project-column', render: (value: string) => <div className="pm-phase-project">{value}</div> },
    { title: '序号', dataIndex: 'orderNo', align: 'center', width: 100, render: (value: number) => `第 ${value} 阶段` },
    { title: '负责人', dataIndex: 'owner', width: 120, render: (value?: string) => value || '—' },
    { title: '开始日期', dataIndex: 'plannedStart', align: 'center', width: 130, render: (value?: string) => value ? dayjs(value).format('YYYY-MM-DD') : '—' },
    { title: '任务完成', key: 'tasks', align: 'center', width: 110, render: (_, row) => {
      const completed = Math.max(0, row.taskCount - row.openTaskCount);
      return `${completed} / ${row.taskCount}`;
    } },
    { title: '进度', key: 'progress', width: 160, render: (_, row) => {
      const completed = Math.max(0, row.taskCount - row.openTaskCount);
      const percent = row.taskCount > 0 ? Math.round((completed / row.taskCount) * 100) : 0;
      return <Progress percent={percent} size="small" strokeColor="#1677ff" />;
    } },
    { title: '状态', dataIndex: 'status', width: 110, render: (value: StageStatus) => <Tag className={statusTagClass(value)}>{formatStageStatus(value)}</Tag> },
    { title: '操作', key: 'actions', width: 120, render: (_, row) => (
      <Space className="management-table-actions" size={0}>
        <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>查看</Button>
        <Popconfirm
          title="确认删除该阶段？"
          description={row.taskCount > 0 ? '阶段下存在任务，不能删除。' : '删除后不可恢复。'}
          disabled={row.taskCount > 0}
          onConfirm={() => void handleDelete(row)}
        >
          <Button type="link" danger disabled={row.taskCount > 0 || deletingStageId === row.id} loading={deletingStageId === row.id}>
            删除
          </Button>
        </Popconfirm>
      </Space>
    ) },
  ];

  const reset = () => { setKeyword(''); setProjectId(undefined); setStatus(undefined); setOwner(undefined); setDates(null); setPage(1); };

  return (
    <div className="pm-page pm-unified-list-page pm-phase-page">
      {holder}
      <div className="pm-page-head">
        <div>
          <Breadcrumb items={[{ title: '项目管理' }, { title: '阶段' }]} />
          <h3>项目阶段</h3>
          <p>跨项目查询、跟踪并定位研发阶段。</p>
        </div>
      </div>
      <div className="pm-phase-toolbar">
        <Space size={8}>
          <Button type="primary" onClick={() => navigate('/projects/list')}>项目列表</Button>
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
      <div className="pm-phase-filters">
        <Input prefix={<SearchOutlined />} placeholder="搜索阶段、项目名称或编号" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(1); }} allowClear />
        <Select placeholder="全部项目" value={projectId} options={projects} onChange={(value) => { setProjectId(value); setPage(1); }} allowClear showSearch optionFilterProp="label" />
        <Select placeholder="全部状态" value={status} options={stageStatuses} onChange={(value) => { setStatus(value); setPage(1); }} allowClear />
        <Select placeholder="全部负责人" value={owner} options={owners.map((value) => ({ value, label: value }))} onChange={(value) => { setOwner(value); setPage(1); }} allowClear />
        <DatePicker.RangePicker value={dates} onChange={(value) => { setDates(value); setPage(1); }} />
        <Button icon={<ReloadOutlined />} onClick={reset}>重置</Button>
      </div>
      <div className="pm-phase-content">
        {view === 'card' ? (
          <Spin spinning={loading}>
            <div className="pm-card-grid pm-phase-card-grid">
              {rows.map((row) => {
                const completed = Math.max(0, row.taskCount - row.openTaskCount);
                const percent = row.taskCount > 0 ? Math.round((completed / row.taskCount) * 100) : 0;
                return (
                  <div className="pm-card pm-phase-card" key={row.id}>
                    <div className="pm-card-head">
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <h4 className="pm-card-title" title={row.name}>
                          <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>{row.name}</Button>
                        </h4>
                        <div className="pm-card-code">{row.stageCode}</div>
                      </div>
                      <Tag className={statusTagClass(row.status)}>{formatStageStatus(row.status)}</Tag>
                    </div>
                    <div className="pm-card-info-grid">
                      <div className="pm-card-info-item">
                        <span className="pm-card-info-label">所属项目</span>
                        <strong className="pm-card-info-value" title={row.projectName}>{row.projectName}</strong>
                      </div>
                      <div className="pm-card-info-item">
                        <span className="pm-card-info-label">负责人</span>
                        <strong className="pm-card-info-value">{row.owner || '—'}</strong>
                      </div>
                      <div className="pm-card-info-item">
                        <span className="pm-card-info-label">阶段序号</span>
                        <strong className="pm-card-info-value">第 {row.orderNo} 阶段</strong>
                      </div>
                      <div className="pm-card-info-item">
                        <span className="pm-card-info-label">开始日期</span>
                        <strong className="pm-card-info-value">{row.plannedStart ? dayjs(row.plannedStart).format('YYYY-MM-DD') : '—'}</strong>
                      </div>
                    </div>
                    <div className="pm-phase-card-progress">
                      <div><span>任务进度</span><strong>{completed} / {row.taskCount}</strong></div>
                      <Progress percent={percent} size="small" strokeColor="#1677ff" />
                    </div>
                    <div className="pm-card-actions">
                      <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>查看</Button>
                      <Popconfirm
                        title="确认删除该阶段？"
                        description={row.taskCount > 0 ? '阶段下存在任务，不能删除。' : '删除后不可恢复。'}
                        disabled={row.taskCount > 0}
                        onConfirm={() => void handleDelete(row)}
                      >
                        <Button type="link" danger disabled={row.taskCount > 0 || deletingStageId === row.id} loading={deletingStageId === row.id}>删除</Button>
                      </Popconfirm>
                    </div>
                  </div>
                );
              })}
              {!loading && !rows.length && <Empty description="暂无符合条件的阶段" />}
            </div>
          </Spin>
        ) : (
          <div className="pm-table-scroll">
            <Table<ProjectStage> rowKey="id" columns={columns} dataSource={rows} loading={loading} size="small" scroll={{ x: 1510 }}
              locale={{ emptyText: <Empty description="暂无符合条件的阶段" /> }}
              pagination={false} />
          </div>
        )}
      </div>
      <div className="pm-phase-pagination">
        <Pagination
          current={page}
          pageSize={pageSize}
          total={total}
          showSizeChanger
          pageSizeOptions={PAGE_SIZE_OPTIONS}
          showTotal={(value) => `共 ${value} 个阶段`}
          onChange={(nextPage, nextSize) => {
            setPage(nextPage);
            if (nextSize && nextSize !== pageSize) {
              setPageSize(nextSize);
              setPage(1);
            }
          }}
        />
      </div>
    </div>
  );
}
