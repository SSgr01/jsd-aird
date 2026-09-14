import { HistoryOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { Alert, Button, DatePicker, Empty, Input, Pagination, Select, Skeleton, Tag, Timeline } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import dayjs from '@/utils/dayjs';
import { getProjectLogs, type ProjectAuditLog, type ProjectLogAction, type ProjectLogObjectType } from '@/services/project/project-api';

const objectLabels: Record<string, string> = { PROJECT: '项目', PROJECT_STAGE: '项目阶段', PROJECT_TASK: '项目任务', EXPERIMENT: '实验', PROJECT_EXPERIMENT: '实验', PROJECT_DOCUMENT: '项目文档', CUSTOMER_REQUIREMENT: '客户需求' };
const actionLabels: Record<string, string> = {
  CREATE: '新增', CREATED: '新增', UPDATE: '编辑', UPDATED: '编辑', REOPEN: '重新打开', DELETE: '删除', DELETED: '删除',
  REORDER: '调整顺序', RENAMED: '重命名', COPIED: '复制', PUBLISHED: '发布', DRAFT_SAVED: '保存草稿',
  REVISION_CREATED: '创建修订版本', ROLLED_BACK: '回滚', SUBMIT: '提交审核', SUBMITTED: '提交审核', VOID: '作废', VOIDED: '作废',
};
const actionColors: Record<string, string> = { CREATE: 'green', UPDATE: 'blue', REOPEN: 'orange', DELETE: 'red', REORDER: 'purple' };
const actionFilterOptions = [
  { value: 'CREATE', label: '新增' }, { value: 'UPDATE', label: '编辑' }, { value: 'REOPEN', label: '重新打开' },
  { value: 'DELETE', label: '删除' }, { value: 'REORDER', label: '调整顺序' },
];

const detailLabels: Record<string, string> = {
  CREATE: '已创建', CREATED: '已创建', UPDATE: '已更新', UPDATED: '已更新', DELETE: '已删除', DELETED: '已删除',
  DRAFT_SAVED: '保存草稿', PUBLISHED: '已发布', REVISION_CREATED: '创建修订版本', ROLLED_BACK: '已回滚',
};
const objectNameReplacements: Array<[RegExp, string]> = [
  [/\boverdue\b/gi, '逾期'], [/\bacceptance\b/gi, '验收'], [/\btask\b/gi, '任务'],
  [/\btemp\b/gi, '临时'], [/\bstep\b/gi, '阶段'], [/\bproject\b/gi, '项目'],
  [/\bdocument\b/gi, '文档'], [/\btemplate\b/gi, '模板'], [/\btest\b/gi, '测试'],
  [/\breport\b/gi, '报告'], [/\bmaterial\b/gi, '资料'], [/\bmeeting\b/gi, '会议'],
];

function formatAction(value?: string) {
  const normalized = value?.trim().toUpperCase() ?? '';
  if (actionLabels[normalized]) return actionLabels[normalized];
  if (normalized.endsWith('_CREATED')) return '新增';
  if (normalized.endsWith('_UPDATED') || normalized.endsWith('_SAVED')) return '编辑';
  if (normalized.endsWith('_DELETED')) return '删除';
  if (normalized.startsWith('STATUS_')) return '状态变更';
  return normalized ? '操作' : '—';
}

function formatOperator(value?: string) {
  const normalized = value?.trim() ?? '';
  return normalized || '系统';
}

function formatObjectName(value: string | undefined, objectType: string) {
  const name = value?.trim() ?? '';
  if (!name) return objectLabels[objectType] ?? '当前记录';
  return objectNameReplacements.reduce((result, [pattern, replacement]) => result.replace(pattern, replacement), name);
}

function formatDetail(value: string | undefined, action?: string) {
  const detail = value?.trim() ?? '';
  if (!detail) return '';
  const normalized = detail.toUpperCase();
  if (detailLabels[normalized]) return detailLabels[normalized];
  try {
    const parsed = JSON.parse(detail) as Record<string, unknown>;
    const message = [parsed.detail, parsed.message, parsed.reason].find((item): item is string => typeof item === 'string' && item.trim().length > 0);
    if (message) return formatDetail(message, action);
    return formatAction(action);
  } catch {
    return /^[A-Z][A-Z0-9_ -]*$/.test(detail) ? formatAction(detail) : detail;
  }
}

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
        options={[{ value: 'PROJECT', label: '项目' }, { value: 'PROJECT_STAGE', label: '项目阶段' }, { value: 'PROJECT_TASK', label: '项目任务' }, { value: 'EXPERIMENT', label: '实验' }, { value: 'PROJECT_DOCUMENT', label: '项目文档' }]} />
      <Select allowClear value={action} placeholder="全部操作" onChange={(value) => { setAction(value); setPage(1); }}
        options={actionFilterOptions} />
      <Input allowClear value={operator} placeholder="操作人" onChange={(event) => setOperator(event.target.value)} />
      <DatePicker.RangePicker value={dates} onChange={(value) => { setDates(value); setPage(1); }} />
      <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
    </div>
    {loading ? <Skeleton active paragraph={{ rows: 8 }} /> : error ?
      <Alert type="error" showIcon message="无法加载项目日志" description={error} action={<Button size="small" onClick={() => void load()}>重试</Button>} /> :
      items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无符合条件的项目日志" /> :
      <Timeline className="pm-log-timeline" items={items.map((item) => ({ dot: <HistoryOutlined />, children:
        <article className="pm-log-item">
          <div className="pm-log-title"><strong>{formatOperator(item.operator)}</strong><span>{formatAction(item.action)}</span>
            <Tag>{objectLabels[item.objectType] ?? '业务对象'}</Tag><Tag color={actionColors[item.action] ?? 'blue'}>{formatAction(item.action)}</Tag>
            <time>{dayjs(item.createdAt).format('YYYY-MM-DD HH:mm:ss')}</time></div>
          <div className="pm-log-object">{formatObjectName(item.objectName, item.objectType)}</div>{item.detail && <p>{formatDetail(item.detail, item.action)}</p>}
        </article> }))} />}
    {total > 0 && <Pagination className="pm-log-pagination" current={page} pageSize={10} total={total}
      showTotal={(value) => `共 ${value} 条日志`} onChange={setPage} />}
  </div>;
}
