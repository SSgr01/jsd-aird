import { App, Button, Input, Modal, Row, Col, Select, Space, Table, Tag } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useEffect, useState } from 'react';
import type { ColumnsType } from 'antd/es/table';

import {
  STAGE_OPTIONS,
  STATUS_OPTIONS,
  MATERIAL_CATEGORY_OPTIONS,
  SOURCE_MODULE_OPTIONS,
  listMaterialsByProject,
  listProjectMaterials,
  saveAssociations,
  unlinkProjectMaterial,
  type Material,
  type ProjectMaterial,
} from '@/services/material/material-api';

import './project-materials-tab.css';

interface Props {
  projectId: string;
}

const CATEGORY_COLORS: Record<string, string> = {
  标准: 'blue',
  规范: 'purple',
  图纸: 'cyan',
  文档: 'geekblue',
  其他: 'default',
};

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'default',
  PUBLISHED: 'green',
  ARCHIVED: 'orange',
};

function formatDate(value?: string) {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD') : value;
}

export function ProjectMaterialsTab({ projectId }: Props) {
  const { message } = App.useApp();
  const [items, setItems] = useState<ProjectMaterial[]>([]);
  const [loading, setLoading] = useState(false);
  const [filters, setFilters] = useState<{ category?: string; stage?: string; status?: string; keyword?: string }>({});

  const [submitting, setSubmitting] = useState(false);

  const [assocModalOpen, setAssocModalOpen] = useState(false);
  const [assocKeyword, setAssocKeyword] = useState('');
  const [assocCategory, setAssocCategory] = useState<string | undefined>();
  const [assocOwner, setAssocOwner] = useState<string | undefined>();
  const [assocList, setAssocList] = useState<Material[]>([]);
  const [assocChecked, setAssocChecked] = useState<string[]>([]);
  const [assocLoading, setAssocLoading] = useState(false);

  const load = async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const data = await listProjectMaterials(projectId, { ...filters, page: 1, size: 50 });
      setItems(data.items ?? []);
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '关联资料加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, filters.category, filters.stage, filters.status, filters.keyword]);

  const openLink = () => {
    setAssocKeyword('');
    setAssocCategory(undefined);
    setAssocOwner(undefined);
    setAssocModalOpen(true);
    void loadAssoc();
  };

  const loadAssoc = async () => {
    if (!projectId) return;
    setAssocLoading(true);
    try {
      const data = await listMaterialsByProject({
        projectId,
        keyword: assocKeyword || undefined,
        category: assocCategory,
        owner: assocOwner,
        page: 1,
        size: 100,
      });
      const list = data.items ?? [];
      setAssocList(list);
      setAssocChecked(list.filter((m) => m.linked).map((m) => m.id));
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '资料加载失败');
    } finally {
      setAssocLoading(false);
    }
  };

  const handleAssocSearch = () => {
    void loadAssoc();
  };

  const handleSaveAssoc = async () => {
    setSubmitting(true);
    try {
      await saveAssociations(projectId, assocChecked);
      message.success('关联已保存');
      setAssocModalOpen(false);
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '保存失败');
    } finally {
      setSubmitting(false);
    }
  };

  const handleUnlink = async (record: ProjectMaterial) => {
    try {
      await unlinkProjectMaterial(projectId, record.materialId);
      message.success('已解除关联');
      await load();
    } catch (reason) {
      message.error(reason instanceof Error ? reason.message : '解除关联失败');
    }
  };

  const columns: ColumnsType<ProjectMaterial> = [
    {
      title: '资料类型',
      dataIndex: 'materialCategory',
      key: 'materialCategory',
      width: 110,
      render: (v: string) => <Tag color={CATEGORY_COLORS[v] ?? 'default'}>{v || '—'}</Tag>,
    },
    { title: '编号', dataIndex: 'materialCode', key: 'materialCode', width: 140 },
    { title: '名称', dataIndex: 'materialName', key: 'materialName', width: 220 },
    { title: '来源分类', dataIndex: 'sourceCategory', key: 'sourceCategory', width: 100 },
    { title: '所属阶段', dataIndex: 'stage', key: 'stage', width: 100, render: (v?: string) => v || '—' },
    { title: '来源模块', dataIndex: 'sourceModule', key: 'sourceModule', width: 110 },
    { title: '关联人', dataIndex: 'contactPerson', key: 'contactPerson', width: 110, render: (v?: string) => v || '—' },
    {
      title: '关联时间',
      dataIndex: 'linkedAt',
      key: 'linkedAt',
      width: 130,
      render: (v?: string) => formatDate(v),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (v: string) => <Tag color={STATUS_COLORS[v] ?? 'default'}>{v === 'DRAFT' ? '草稿' : v === 'PUBLISHED' ? '已发布' : v === 'ARCHIVED' ? '已归档' : v}</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      fixed: 'right',
      render: (_, record) => (
        <Button type="link" size="small" danger onClick={() => handleUnlink(record)}>
          解除关联
        </Button>
      ),
    },
  ];

  return (
    <div className="pm-pm-tab">
      <div className="pm-pm-filters">
        <Select
          allowClear
          placeholder="全部资料类型"
          style={{ minWidth: 140 }}
          options={MATERIAL_CATEGORY_OPTIONS}
          value={filters.category}
          onChange={(v) => setFilters((s) => ({ ...s, category: v }))}
        />
        <Input
          allowClear
          placeholder="搜索编号或名称"
          style={{ minWidth: 220 }}
          value={filters.keyword}
          onChange={(e) => setFilters((s) => ({ ...s, keyword: e.target.value || undefined }))}
        />
        <Select
          allowClear
          placeholder="全部阶段"
          style={{ minWidth: 120 }}
          options={STAGE_OPTIONS}
          value={filters.stage}
          onChange={(v) => setFilters((s) => ({ ...s, stage: v }))}
        />
        <Select
          allowClear
          placeholder="全部状态"
          style={{ minWidth: 120 }}
          options={STATUS_OPTIONS}
          value={filters.status}
          onChange={(v) => setFilters((s) => ({ ...s, status: v }))}
        />
        <Space style={{ marginLeft: 'auto' }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={openLink}>
            关联资料
          </Button>
        </Space>
      </div>

      <Table<ProjectMaterial>
        rowKey="id"
        columns={columns}
        dataSource={items}
        loading={loading}
        pagination={false}
        size="middle"
        scroll={{ x: 1200 }}
        locale={{ emptyText: '暂无关联资料' }}
      />

      <Modal
        title="关联项目资料"
        open={assocModalOpen}
        onCancel={() => setAssocModalOpen(false)}
        onOk={handleSaveAssoc}
        confirmLoading={submitting}
        okText="保存关联"
        cancelText="取消"
        width={860}
        destroyOnClose
      >
        <Row gutter={8} style={{ marginBottom: 12 }}>
          <Col flex="auto">
            <Input
              allowClear
              placeholder="编号/名称/文件关键词"
              value={assocKeyword}
              onChange={(e) => setAssocKeyword(e.target.value)}
              onPressEnter={handleAssocSearch}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="全部资料类型"
              style={{ width: 150 }}
              options={MATERIAL_CATEGORY_OPTIONS}
              value={assocCategory}
              onChange={(v) => setAssocCategory(v)}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="全部项目归属"
              style={{ width: 150 }}
              options={SOURCE_MODULE_OPTIONS}
              value={assocOwner}
              onChange={(v) => setAssocOwner(v)}
            />
          </Col>
          <Col>
            <Button type="primary" onClick={handleAssocSearch}>
              查询
            </Button>
          </Col>
        </Row>
        <Table<Material>
          rowKey="id"
          size="middle"
          loading={assocLoading}
          dataSource={assocList}
          pagination={false}
          scroll={{ y: 360 }}
          rowSelection={{
            selectedRowKeys: assocChecked,
            onChange: (keys) => setAssocChecked(keys as string[]),
          }}
          columns={[
            {
              title: '资料',
              key: 'main',
              render: (_: unknown, row: Material) => (
                <div className="pm-link-cell">
                  <div className="pm-link-title">{row.name}</div>
                  <div className="pm-link-sub">{row.code} · {row.sourceModule}</div>
                </div>
              ),
            },
            {
              title: '类型',
              dataIndex: 'category',
              key: 'category',
              width: 120,
            },
            {
              title: '项目归属',
              dataIndex: 'sourceModule',
              key: 'owner',
              width: 160,
            },
          ]}
          locale={{ emptyText: '暂无资料' }}
        />
      </Modal>
    </div>
  );
}