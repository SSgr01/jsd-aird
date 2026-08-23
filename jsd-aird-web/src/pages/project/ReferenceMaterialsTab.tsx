import { DatabaseOutlined, FileSearchOutlined, FileTextOutlined } from '@ant-design/icons';
import { App, Button, Input, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Can } from '@/components/auth/Can';
import { getProjectStages, getStageTasks, type ProjectStage, type ProjectTask } from '@/services/project/project-api';
import {
  REFERENCE_SOURCE_OPTIONS,
  REFERENCE_STATUS_OPTIONS,
  listReferenceMaterials,
  removeReferenceMaterial,
  restoreReferenceMaterial,
  type ReferenceMaterial,
  type ReferenceMaterialQuery,
} from '@/services/project/reference-api';

import './reference-materials-tab.css';

export function ReferenceMaterialsTab({ projectId }: { projectId: string }) {
  const { message, modal } = App.useApp();
  const navigate = useNavigate();
  const [items, setItems] = useState<ReferenceMaterial[]>([]);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<ReferenceMaterialQuery>({ status: 'ACTIVE', page: 1, size: 20 });
  const [total, setTotal] = useState(0);
  const [stages, setStages] = useState<ProjectStage[]>([]);
  const [tasks, setTasks] = useState<ProjectTask[]>([]);

  useEffect(() => { void getProjectStages(projectId).then(setStages).catch(() => setStages([])); }, [projectId]);
  useEffect(() => {
    if (!filters.stageId) { setTasks([]); return; }
    void getStageTasks(filters.stageId).then(setTasks).catch(() => setTasks([]));
  }, [filters.stageId]);

  const load = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listReferenceMaterials(projectId, filters);
      setItems(data.items ?? []);
      setTotal(data.total);
    } catch (reason) {
      void message.error(reason instanceof Error ? reason.message : '参考资料加载失败');
    } finally { setLoading(false); }
  }, [filters, message, projectId]);

  useEffect(() => { void load(); }, [load]);
  const addedByOptions = useMemo(() => [...new Map(items.map((item) => [item.addedBy, item.addedByName || item.addedBy])).entries()]
    .map(([value, label]) => ({ value, label })), [items]);

  const source = (record: ReferenceMaterial) => {
    if (!record.sourceAvailable) return;
    navigate(record.resourceType === 'KNOWLEDGE_DOCUMENT'
      ? `/knowledge/documents/${record.resourceId}`
      : `/data/import-jobs/${record.resourceId}`);
  };
  const changeStatus = (record: ReferenceMaterial) => {
    const restoring = record.status === 'REMOVED';
    modal.confirm({
      title: restoring ? `恢复“${record.title}”？` : `移除“${record.title}”？`,
      content: restoring ? '恢复后重新显示在活动资料参考中。' : '仅移除项目资料参考，普通项目关联会保留。',
      okText: restoring ? '恢复' : '移除',
      okButtonProps: { danger: !restoring },
      onOk: async () => {
        if (restoring) await restoreReferenceMaterial(record.id);
        else await removeReferenceMaterial(projectId, record.id);
        await load();
      },
    });
  };

  const columns: ColumnsType<ReferenceMaterial> = [
    { title: '来源', dataIndex: 'sourceModule', width: 130, render: (value: string) => <Tag color={value === 'KNOWLEDGE' ? 'blue' : 'purple'} icon={value === 'KNOWLEDGE' ? <FileTextOutlined /> : <DatabaseOutlined />}>{value === 'KNOWLEDGE' ? '研发知识库' : '数据中心'}</Tag> },
    { title: '标题与摘要', dataIndex: 'title', render: (_: string, record) => <div className="pm-ref-title-cell"><div className="pm-ref-title">{record.title}</div><div className="pm-ref-summary">{record.summary || record.originalName || '—'}</div>{!record.sourceAvailable && <Tag color="warning">来源已不可用</Tag>}</div> },
    { title: '阶段/任务', width: 180, render: (_: unknown, record) => [record.stageName, record.taskName].filter(Boolean).join(' / ') || '项目级' },
    { title: '添加人', dataIndex: 'addedByName', width: 110, render: (value?: string, record?: ReferenceMaterial) => value || record?.addedBy || '—' },
    { title: '添加时间', dataIndex: 'addedAt', width: 160, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm') },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: string) => <Tag color={value === 'ACTIVE' ? 'success' : 'default'}>{value === 'ACTIVE' ? '有效' : '已移除'}</Tag> },
    { title: '操作', fixed: 'right', width: 190, render: (_: unknown, record) => <Space size={0}><Button type="link" disabled={!record.sourceAvailable} onClick={() => source(record)}>查看来源</Button><Can permission="project.assign"><Button type="link" danger={record.status === 'ACTIVE'} onClick={() => changeStatus(record)}>{record.status === 'ACTIVE' ? '移除' : '恢复'}</Button></Can></Space> },
  ];

  return <div className="pm-ref-tab">
    <div className="pm-ref-filters">
      <Input.Search allowClear placeholder="搜索标题、文件名或摘要" style={{ minWidth: 250 }} value={filters.keyword} onChange={(event) => setFilters((current) => ({ ...current, keyword: event.target.value || undefined, page: 1 }))} />
      <Select allowClear placeholder="来源" style={{ minWidth: 140 }} options={REFERENCE_SOURCE_OPTIONS} value={filters.source} onChange={(sourceValue) => setFilters((current) => ({ ...current, source: sourceValue, page: 1 }))} />
      <Select allowClear placeholder="阶段" style={{ minWidth: 150 }} options={stages.map((item) => ({ value: item.id, label: item.name }))} value={filters.stageId} onChange={(stageId) => setFilters((current) => ({ ...current, stageId, taskId: undefined, page: 1 }))} />
      <Select allowClear placeholder="任务" style={{ minWidth: 150 }} disabled={!filters.stageId} options={tasks.map((item) => ({ value: item.id, label: item.name }))} value={filters.taskId} onChange={(taskId) => setFilters((current) => ({ ...current, taskId, page: 1 }))} />
      <Select allowClear placeholder="添加人" style={{ minWidth: 120 }} options={addedByOptions} value={filters.addedBy} onChange={(addedBy) => setFilters((current) => ({ ...current, addedBy, page: 1 }))} />
      <Select placeholder="状态" style={{ minWidth: 110 }} options={REFERENCE_STATUS_OPTIONS} value={filters.status} onChange={(status) => setFilters((current) => ({ ...current, status, page: 1 }))} />
      <Can permission="project.assign"><Button type="primary" icon={<FileSearchOutlined />} onClick={() => navigate(`/knowledge/search?projectId=${projectId}&reference=1`)}>从文件检索添加</Button></Can>
    </div>
    <Table rowKey="id" columns={columns} dataSource={items} loading={loading} size="middle" scroll={{ x: 1050 }} locale={{ emptyText: <Typography.Text type="secondary">暂无真实资料参考</Typography.Text> }} pagination={{ current: filters.page, pageSize: filters.size, total, showSizeChanger: true, onChange: (page, size) => setFilters((current) => ({ ...current, page, size })) }} />
  </div>;
}
