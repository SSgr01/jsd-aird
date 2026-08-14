import { BookOutlined, ExperimentOutlined, FilePdfOutlined, MessageOutlined } from '@ant-design/icons';
import { App, Button, Input, Select, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useEffect, useMemo, useState } from 'react';

import {
  REFERENCE_SOURCE_OPTIONS,
  REFERENCE_STAGE_OPTIONS,
  REFERENCE_STATUS_OPTIONS,
  listReferenceMaterials,
  removeReferenceMaterial,
  type ReferenceMaterial,
  type ReferenceMaterialQuery,
} from '@/services/reference/reference-api';

import './reference-materials-tab.css';

interface Props {
  projectId: string;
}

const TYPE_ICON: Record<
  ReferenceMaterial['type'],
  { icon: React.ReactNode; color: string; label: string }
> = {
  PDF: { icon: <FilePdfOutlined />, color: '#1677ff', label: 'PDF' },
  ARTICLE: { icon: <BookOutlined />, color: '#722ed1', label: '文献' },
  SUGGESTION: { icon: <MessageOutlined />, color: '#52c41a', label: '建议' },
  TECH_PATH: { icon: <ExperimentOutlined />, color: '#13c2c2', label: '技术路径' },
};

function formatDate(value?: string) {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : value;
}

export function ReferenceMaterialsTab({ projectId }: Props) {
  const { message } = App.useApp();
  const [items, setItems] = useState<ReferenceMaterial[]>([]);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<ReferenceMaterialQuery>({});

  const load = async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listReferenceMaterials(projectId, { ...filters, page: 1, size: 50 });
      setItems(data.items ?? []);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '参考资料加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, filters.keyword, filters.source, filters.stage, filters.addedBy, filters.status]);

  const addedByOptions = useMemo(() => {
    const set = new Set(items.map((item) => item.addedBy));
    return Array.from(set).map((name) => ({ value: name, label: name }));
  }, [items]);

  const handleRemove = async (record: ReferenceMaterial) => {
    try {
      await removeReferenceMaterial(projectId, record.id);
      message.success('已移除');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '移除失败');
    }
  };

  const renderActions = (record: ReferenceMaterial) => {
    const actions: React.ReactNode[] = [];

    if (record.originalUrl) {
      actions.push(
        <Button key="original" type="link" size="small" href={record.originalUrl} target="_blank" rel="noreferrer">
          查看原文
        </Button>,
      );
    }

    if (record.sourceUrl) {
      const label = record.type === 'TECH_PATH' ? '查看回答' : '查看来源';
      actions.push(
        <Button key="source" type="link" size="small" href={record.sourceUrl} target="_blank" rel="noreferrer">
          {label}
        </Button>,
      );
    }

    actions.push(
      <Button key="remove" type="link" size="small" danger onClick={() => handleRemove(record)}>
        移除
      </Button>,
    );

    return <Space size={0}>{actions}</Space>;
  };

  const columns: ColumnsType<ReferenceMaterial> = [
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 80,
      render: (type: ReferenceMaterial['type']) => {
        const config = TYPE_ICON[type];
        return (
          <div className="pm-ref-type" style={{ color: config.color }}>
            {config.icon}
            <span>{config.label}</span>
          </div>
        );
      },
    },
    {
      title: '标题与摘要',
      dataIndex: 'title',
      key: 'title',
      render: (_: string, record: ReferenceMaterial) => (
        <div className="pm-ref-title-cell">
          <div className="pm-ref-title">{record.title}</div>
          <div className="pm-ref-summary">{record.summary}</div>
        </div>
      ),
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 120,
      render: (source: string) => <Tag color="blue">{source}</Tag>,
    },
    {
      title: '添加人',
      dataIndex: 'addedBy',
      key: 'addedBy',
      width: 100,
    },
    {
      title: '添加时间',
      dataIndex: 'addedAt',
      key: 'addedAt',
      width: 150,
      render: (v?: string) => formatDate(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      fixed: 'right',
      render: (_, record) => renderActions(record),
    },
  ];

  return (
    <div className="pm-ref-tab">
      <div className="pm-ref-filters">
        <Input
          allowClear
          placeholder="搜索标题、关键词、来源"
          style={{ minWidth: 260 }}
          value={filters.keyword}
          onChange={(e) => setFilters((s) => ({ ...s, keyword: e.target.value || undefined }))}
        />
        <Select
          allowClear
          placeholder="来源"
          style={{ minWidth: 140 }}
          options={REFERENCE_SOURCE_OPTIONS}
          value={filters.source}
          onChange={(v) => setFilters((s) => ({ ...s, source: v }))}
        />
        <Select
          allowClear
          placeholder="关联阶段/任务"
          style={{ minWidth: 160 }}
          options={REFERENCE_STAGE_OPTIONS}
          value={filters.stage}
          onChange={(v) => setFilters((s) => ({ ...s, stage: v }))}
        />
        <Select
          allowClear
          placeholder="添加人"
          style={{ minWidth: 120 }}
          options={addedByOptions}
          value={filters.addedBy}
          onChange={(v) => setFilters((s) => ({ ...s, addedBy: v }))}
        />
        <Select
          allowClear
          placeholder="状态"
          style={{ minWidth: 100 }}
          options={REFERENCE_STATUS_OPTIONS}
          value={filters.status}
          onChange={(v) => setFilters((s) => ({ ...s, status: v }))}
        />
      </div>

      <Table<ReferenceMaterial>
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={false}
        size="middle"
        scroll={{ x: 900 }}
        locale={{ emptyText: '暂无参考资料' }}
      />
    </div>
  );
}
