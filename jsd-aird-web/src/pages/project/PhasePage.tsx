import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, DatePicker, Empty, Flex, Input, Pagination, Progress, Select, Table, Tag, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import dayjs from '@/utils/dayjs';

import { formatStageStatus, getProjects, getStages, stageStatuses, type ProjectStage, type StageStatus } from '@/services/project/project-api';
import './phase-page.css';

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZE_OPTIONS = [10, 20, 30, 50];
type RangeValue = Parameters<NonNullable<React.ComponentProps<typeof DatePicker.RangePicker>['onChange']>>[0];

function statusColor(status: StageStatus) {
  return status === 'COMPLETED' ? 'green' : status === 'IN_PROGRESS' ? 'blue' : status === 'PENDING' ? 'gold' : 'default';
}

export function PhasePage() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<ProjectStage[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [projectId, setProjectId] = useState<string>();
  const [status, setStatus] = useState<StageStatus>();
  const [owner, setOwner] = useState<string>();
  const [dates, setDates] = useState<RangeValue>(null);
  const [projects, setProjects] = useState<{ value: string; label: string }[]>([]);
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
  const columns: ColumnsType<ProjectStage> = [
    { title: '阶段编号', dataIndex: 'stageCode', width: 200 },
    { title: '阶段名称', dataIndex: 'name', render: (value: string, row) => <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>{value}</Button> },
    { title: '所属项目', dataIndex: 'projectName', render: (value: string, row) => <><strong>{value}</strong><div className="pm-phase-code">{row.projectCode}</div></> },
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
    { title: '状态', dataIndex: 'status', render: (value: StageStatus) => <Tag color={statusColor(value)}>{formatStageStatus(value)}</Tag> },
    { title: '操作', key: 'actions', fixed: 'right', render: (_, row) => <Button type="link" onClick={() => navigate(`/projects/${row.projectId}?section=stages&stageId=${row.id}`)}>查看</Button> },
  ];

  const reset = () => { setKeyword(''); setProjectId(undefined); setStatus(undefined); setOwner(undefined); setDates(null); setPage(1); };

  return (
    <div className="pm-phase-page">
      {holder}
      <Flex justify="space-between" align="flex-start" className="pm-page-head">
        <div><Typography.Title level={2}>项目阶段</Typography.Title><Typography.Paragraph type="secondary">跨项目查询、跟踪并定位研发阶段。</Typography.Paragraph></div>
        <Button type="primary" onClick={() => navigate('/projects/list')}>项目列表</Button>
      </Flex>
      <div className="pm-phase-filters">
        <Input prefix={<SearchOutlined />} placeholder="搜索阶段、项目名称或编号" value={keyword} onChange={(event) => { setKeyword(event.target.value); setPage(1); }} allowClear />
        <Select placeholder="全部项目" value={projectId} options={projects} onChange={(value) => { setProjectId(value); setPage(1); }} allowClear showSearch optionFilterProp="label" />
        <Select placeholder="全部状态" value={status} options={stageStatuses} onChange={(value) => { setStatus(value); setPage(1); }} allowClear />
        <Select placeholder="全部负责人" value={owner} options={owners.map((value) => ({ value, label: value }))} onChange={(value) => { setOwner(value); setPage(1); }} allowClear />
        <DatePicker.RangePicker value={dates} onChange={(value) => { setDates(value); setPage(1); }} />
        <Button icon={<ReloadOutlined />} onClick={reset}>重置</Button>
      </div>
      <Table<ProjectStage> rowKey="id" columns={columns} dataSource={rows} loading={loading} size="small" scroll={{ x: 1100 }}
        locale={{ emptyText: <Empty description="暂无符合条件的阶段" /> }}
        pagination={false} />
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
