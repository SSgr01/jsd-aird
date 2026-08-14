import { HistoryOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, DatePicker, Empty, Input, Pagination, Select, Skeleton, Tag, Timeline } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import dayjs from '@/utils/dayjs';
import { getProjectLogs, type ProjectAuditLog, type ProjectLogAction, type ProjectLogObjectType } from '@/services/project/project-api';

const objectLabels: Record<string, string> = { PROJECT: '项目', PROJECT_STAGE: '项目阶段', PROJECT_TASK: '项目任务', PROJECT_EXPERIMENT: '项目实验' };
const actionLabels: Record<string, string> = { CREATE: '新增', UPDATE: '编辑', REOPEN: '重新打开', DELETE: '删除', REORDER: '调整顺序' };
const actionColors: Record<string, string> = { CREATE: 'green', UPDATE: 'blue', REOPEN: 'orange', DELETE: 'red', REORDER: 'purple' };

export function ProjectLogsTab({ projectId }: { projectId: string }) {
  const [items, setItems] = useState<ProjectAuditLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [keyword, setKeyword] = useState('');
  const [objectType, setObjectType] = useState<ProjectLogObjectType>();
  const [action, setAction] = useState<ProjectLogAction>();
  const [operator, setOperator] = useState('');
  const [dates, setDates] = useState<[dayjs.Dayjs | null, dayjs.Dayjs | null] | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const result = await getProjectLogs(projectId, {
        keyword: keyword.trim() || undefined, objectType, action, operator: operator.trim() || undefined,
        createdFrom: dates?.[0]?.startOf('day').toISOString(), createdTo: dates?.[1]?.endOf('day').toISOString(),
        page, size: 10,
      });
      setItems(result.items);
      setTotal(result.total);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '项目日志加载失败');
    } finally { setLoading(false); }
  }, [action, dates, keyword, objectType, operator, page, projectId]);

  useEffect(() => { void load(); }, [load]);

  return <div className="pm-log-panel">
    <div className="pm-log-filters">
      <Input allowClear prefix={<SearchOutlined />} value={keyword} placeholder="搜索对象名称或日志内容"
        onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => { setPage(1); void load(); }} />
      <Select allowClear value={objectType} placeholder="全部对象" onChange={(value) => { setObjectType(value); setPage(1); }}
        options={[{ value: 'PROJECT_STAGE', label: '项目阶段' }, { value: 'PROJECT_TASK', label: '项目任务' }, { value: 'PROJECT_EXPERIMENT', label: '项目实验' }]} />
      <Select allowClear value={action} placeholder="全部操作" onChange={(value) => { setAction(value); setPage(1); }}
        options={Object.entries(actionLabels).map(([value, label]) => ({ value, label }))} />
      <Input allowClear value={operator} placeholder="操作人" onChange={(event) => setOperator(event.target.value)} />
      <DatePicker.RangePicker value={dates} onChange={(value) => { setDates(value); setPage(1); }} />
      <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
    </div>
    {loading ? <Skeleton active paragraph={{ rows: 8 }} /> : error ?
      <Alert type="error" showIcon message="无法加载项目日志" description={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} /> :
      items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的项目日志" /> :
      <Timeline className="pm-log-timeline" items={items.map((item) => ({ dot: <HistoryOutlined />, children:
        <article className="pm-log-item">
          <div className="pm-log-title"><strong>{item.operator}</strong><span>{actionLabels[item.action] ?? item.action}</span>
            <Tag>{objectLabels[item.objectType] ?? item.objectType}</Tag><Tag color={actionColors[item.action]}>{actionLabels[item.action] ?? item.action}</Tag>
            <time>{dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss')}</time></div>
          <div className="pm-log-object">{item.objectName || item.objectId}</div>{item.detail && <p>{item.detail}</p>}
        </article> }))} />}
    {total > 0 && <Pagination className="pm-log-pagination" current={page} pageSize={10} total={total}
      showTotal={(value) => `共 ${value} 条日志`} onChange={setPage} />}
  </div>;
}
