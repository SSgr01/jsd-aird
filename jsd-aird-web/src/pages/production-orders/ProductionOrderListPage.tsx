import {
  AppstoreOutlined,
  DeleteOutlined,
  FileExcelOutlined,
  FileWordOutlined,
  PlusOutlined,
  RedoOutlined,
  ReloadOutlined,
  UnorderedListOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Breadcrumb,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { ProjectRelationPicker } from '@/components/project-relations/ProjectRelationPicker';
import type { ProjectRelationTarget } from '@/services/project/project-resource-api';
import { usePermission } from '@/components/auth/usePermission';
import { templateApi } from '@/services/templates/template-api';
import {
  productionUploadApi,
  productionOrderRecordApi,
  type ProductionUpload,
  type ProductionUploadVisibility,
} from '@/services/production-orders/production-upload-api';
import { productionOrderApi } from '@/services/production-orders/production-order-api';
import './production-order-list.css';
import '@/styles/management-list.css';

const visibilityText: Record<ProductionUploadVisibility, string> = {
  ALL: '全员可见',
  QUALITY: '品管部可见',
  PROJECT: '项目组可见',
};

const statusOptions = [
  { value: 'SAVED', label: '已保存' },
  { value: 'PUBLISHED', label: '已发布' },
];

const statusText: Record<string, string> = Object.fromEntries(
  [
    ...statusOptions,
    // Legacy rows may still carry REVIEW_REQUIRED until they are migrated;
    // present them as saved drafts in the business list.
    { value: 'REVIEW_REQUIRED', label: '已保存' },
  ].map((item) => [item.value, item.label]),
);

type ViewMode = 'card' | 'list';

const renderCellText = (value?: string | null, strong = false) => {
  const text = value || '—';
  return (
    <Typography.Text
      className="production-order-cell-text"
      strong={strong}
      ellipsis={{ tooltip: text }}
    >
      {text}
    </Typography.Text>
  );
};

export function ProductionOrderListPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const canCreate = usePermission('production.create');
  const canUpdate = usePermission('production.update');
  const canDelete = usePermission('production.delete');
  const [items, setItems] = useState<ProductionUpload[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string>();
  const [page, setPage] = useState({ current: 1, pageSize: 20, total: 0 });
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, ProductionUpload>>({});
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [view, setView] = useState<ViewMode>('list');
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [renameSaving, setRenameSaving] = useState(false);
  const [renaming, setRenaming] = useState<ProductionUpload>();
  const [renameName, setRenameName] = useState('');
  const [renameRelations, setRenameRelations] = useState<ProjectRelationTarget[]>([]);
  const [templateOptions, setTemplateOptions] = useState<
    Array<{
      versionId: string;
      currentPublishedVersionId?: string;
      currentPublishedVersionNo?: number;
      templateCode: string;
      name: string;
      versionNo: number;
    }>
  >([]);
  const [createForm] = Form.useForm<{
    orderNo: string;
    templateVersionId: string;
    quantity?: number;
    unitCode?: string;
    plannedDate?: string;
  }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await productionUploadApi.list({
        keyword: keyword || undefined,
        status: status || undefined,
        viewableOnly: true,
        page: page.current,
        size: page.pageSize,
      });
      setItems(result.items);
      setPage((current) => ({
        ...current,
        current: result.page,
        pageSize: result.size,
        total: result.total,
      }));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, message, page.current, page.pageSize, status]);

  useEffect(() => {
    void load();
  }, [load]);

  const startEdit = () => {
    setDrafts(Object.fromEntries(items.map((item) => [item.id, { ...item }])));
    setEditing(true);
  };

  const deleteSelected = () => {
    if (!selectedKeys.length) return;
    Modal.confirm({
      title: `确认删除 ${selectedKeys.length} 条生产单记录？`,
      content: '删除后记录会进入作废状态，原始文件不会被物理删除。',
      okText: '确认删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await Promise.all(selectedKeys.map((id) => productionUploadApi.delete(id)));
          setSelectedKeys([]);
          void message.success('选中的生产单记录已作废');
          await load();
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '批量删除失败');
        }
      },
    });
  };

  const deleteItem = (item: ProductionUpload) => {
    Modal.confirm({
      title: `确认删除生产单“${item.productionName || item.orderNo || item.originalName}”？`,
      content: '删除后记录会进入作废状态，原始文件不会被物理删除。',
      okText: '删除',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await productionUploadApi.delete(item.id);
          setSelectedKeys((current) => current.filter((id) => id !== item.id));
          void message.success('生产单记录已作废');
          await load();
        } catch (error) {
          void message.error(error instanceof Error ? error.message : '删除失败');
        }
      },
    });
  };

  const openRename = (item: ProductionUpload) => {
    setRenaming(item);
    setRenameName(item.productionName || item.orderNo || item.originalName);
    setRenameRelations(
      item.projectId
        ? [
            {
              projectId: item.projectId,
              projectName: item.projectName,
              stageId: item.stageId,
              stageName: item.stageName,
              taskId: item.taskId,
              taskName: item.taskName,
            },
          ]
        : [],
    );
    setRenameOpen(true);
  };

  const saveRename = async () => {
    if (!renaming || !renameName.trim()) {
      message.warning('请输入生产单名称');
      return;
    }
    const relation = renameRelations[0];
    setRenameSaving(true);
    try {
      await productionUploadApi.rename(renaming.id, {
        revision: renaming.lockVersion,
        name: renameName.trim(),
        projectId: relation?.projectId,
        projectName: relation?.projectName,
        stageId: relation?.stageId,
        stageName: relation?.stageName,
        taskId: relation?.taskId,
        taskName: relation?.taskName,
      });
      message.success('生产单名称和项目关联已更新');
      setRenameOpen(false);
      setRenaming(undefined);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '生产单信息保存失败');
    } finally {
      setRenameSaving(false);
    }
  };

  const openCreate = async () => {
    setCreateOpen(true);
    try {
      const result = await templateApi.list({
        format: 'XLSX',
        status: 'PUBLISHED',
        page: 1,
        size: 100,
      });
      setTemplateOptions(result.items);
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '已发布模板加载失败');
    }
  };

  const createOrder = async () => {
    try {
      const values = await createForm.validateFields();
      setCreating(true);
      const workspace = await productionOrderApi.create({
        ...values,
        templateVersionId: values.templateVersionId,
      });
      setCreateOpen(false);
      createForm.resetFields();
      void message.success('生产单草稿已创建');
      navigate(`/production-orders/${workspace.id}/workspace`);
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) return;
      void message.error(error instanceof Error ? error.message : '生产单创建失败');
    } finally {
      setCreating(false);
    }
  };

  const updateDraft = (id: string, field: keyof ProductionUpload, value: string) => {
    setDrafts((current) => ({
      ...current,
      [id]: { ...(current[id] ?? items.find((item) => item.id === id)!), [field]: value },
    }));
  };

  const saveTable = async () => {
    setSaving(true);
    try {
      await productionOrderRecordApi.saveBatch({
        records: items.map((item) => {
          const draft = drafts[item.id] ?? item;
          return {
            id: item.id,
            lockVersion: item.lockVersion ?? 0,
            productionName: draft.productionName,
            orderNo: draft.orderNo,
            productName: draft.productName,
            category: draft.category,
            manufactureDate: draft.manufactureDate,
          };
        }),
      });
      void message.success('生产单表格已保存');
      setEditing(false);
      setDrafts({});
      await load();
    } catch (error) {
      void message.error(error instanceof Error ? error.message : '生产单表格保存失败');
    } finally {
      setSaving(false);
    }
  };

  const resetFilters = () => {
    setKeyword('');
    setStatus(undefined);
    setPage((current) => ({ ...current, current: 1 }));
  };

  const changeView = (nextView: ViewMode) => {
    if (nextView !== view) setSelectedKeys([]);
    setView(nextView);
  };

  const toggleSelected = (id: string) => {
    setSelectedKeys((current) =>
      current.includes(id) ? current.filter((key) => key !== id) : [...current, id],
    );
  };

  const renderProductionCard = (item: ProductionUpload) => {
    const relation =
      [item.projectName, item.stageName, item.taskName].filter(Boolean).join(' · ') || '未关联项目';
    const statusColor =
      item.status === 'PUBLISHED' ? 'success' : item.status === 'FAILED' ? 'error' : 'processing';
    return (
      <article className="production-order-card" key={item.id}>
        <div className="production-order-card-head">
          <span className="production-order-card-icon">
            {/\.docx?$/i.test(item.originalName) ? <FileWordOutlined /> : <FileExcelOutlined />}
          </span>
          <div className="production-order-card-title">
            <Typography.Text
              strong
              ellipsis={{ tooltip: item.productionName || item.originalName }}
            >
              {item.productionName || item.originalName}
            </Typography.Text>
            <Typography.Text type="secondary" ellipsis={{ tooltip: item.originalName }}>
              {item.originalName}
            </Typography.Text>
          </div>
          <Tag color={statusColor}>{statusText[item.status] || item.status}</Tag>
          <Checkbox
            aria-label={`选择生产单 ${item.productionName || item.orderNo || item.originalName}`}
            checked={selectedKeys.includes(item.id)}
            onChange={() => toggleSelected(item.id)}
          />
        </div>
        <div className="production-order-card-fields">
          <div>
            <span>订单号</span>
            <Typography.Text ellipsis={{ tooltip: item.orderNo || '—' }}>
              {item.orderNo || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>品名</span>
            <Typography.Text ellipsis={{ tooltip: item.productName || '—' }}>
              {item.productName || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>类别</span>
            <Typography.Text ellipsis={{ tooltip: item.category || '—' }}>
              {item.category || '—'}
            </Typography.Text>
          </div>
          <div>
            <span>制造日期</span>
            <Typography.Text>{item.manufactureDate || '—'}</Typography.Text>
          </div>
          <div className="production-order-card-field-wide">
            <span>关联项目 / 阶段 / 任务</span>
            <Typography.Text ellipsis={{ tooltip: relation }}>{relation}</Typography.Text>
          </div>
          <div>
            <span>权限可见</span>
            <Typography.Text>{visibilityText[item.visibility]}</Typography.Text>
          </div>
        </div>
        <div className="production-order-card-meta">
          上传于 {new Date(item.createdAt).toLocaleString('zh-CN')}
        </div>
        <div className="production-order-card-actions">
          <Button
            type="link"
            onClick={() => navigate(`/production-orders/uploads/${item.id}/workspace`)}
          >
            查看
          </Button>
          <Button type="link" disabled={!canUpdate} onClick={() => openRename(item)}>
            重命名
          </Button>
          <Button type="link" danger disabled={!canDelete} onClick={() => deleteItem(item)}>
            删除
          </Button>
        </div>
      </article>
    );
  };

  return (
    <>
      <div className="business-page production-order-page pm-unified-list-page">
        <div className="page-heading production-order-page-intro">
          <div>
            <Breadcrumb items={[{ title: '生产单管理' }, { title: '生产单查看' }]} />
            <Typography.Title level={2}>生产单查看</Typography.Title>
            <Typography.Text type="secondary">
              查看已上传的生产单资料，当前不进入模板填写或提交流程。
            </Typography.Text>
          </div>
        </div>

        <Card className="content-card filter-card production-order-filter">
          <Space wrap>
            <Input.Search
              allowClear
              placeholder="生产单名称、订单号、品名或文件名"
              value={keyword}
              onChange={(event) => {
                setKeyword(event.target.value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              style={{ width: 'min(300px, 100%)' }}
            />
            <Select
              allowClear
              placeholder="全部状态"
              value={status}
              onChange={(value) => {
                setStatus(value);
                setPage((current) => ({ ...current, current: 1 }));
              }}
              options={statusOptions}
              style={{ width: 'min(140px, 100%)' }}
            />
            <Button icon={<RedoOutlined />} onClick={resetFilters}>
              重置
            </Button>
          </Space>
        </Card>

        <div className="production-order-toolbar pm-batch-row">
          <div className="pm-batch-summary">
            <span className="pm-selected">已选 {selectedKeys.length} 条</span>
            {selectedKeys.length > 0 && (
              <Button type="link" size="small" onClick={() => setSelectedKeys([])}>
                清除已选
              </Button>
            )}
          </div>
          <div className="pm-batch-actions">
            {editing ? (
              <>
                <Button
                  onClick={() => {
                    setEditing(false);
                    setDrafts({});
                  }}
                >
                  取消
                </Button>
                <Button type="primary" loading={saving} onClick={() => void saveTable()}>
                  保存
                </Button>
              </>
            ) : (
              <Button onClick={startEdit} disabled={!items.length || !canUpdate}>
                编辑表格
              </Button>
            )}
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={editing || !canCreate}
              onClick={() => void openCreate()}
            >
              新建生产单
            </Button>
            <Button
              icon={<UploadOutlined />}
              disabled={editing || !canCreate}
              onClick={() => navigate('/production-orders/upload')}
            >
              上传生产单
            </Button>
            <Button
              danger
              icon={<DeleteOutlined />}
              disabled={!selectedKeys.length || editing || !canDelete}
              onClick={deleteSelected}
            >
              批量作废
            </Button>
            <Space.Compact className="production-order-view-toggle">
              <Button
                aria-label="卡片视图"
                aria-pressed={view === 'card'}
                title="卡片视图"
                type={view === 'card' ? 'primary' : 'default'}
                icon={<AppstoreOutlined />}
                disabled={editing}
                onClick={() => changeView('card')}
              />
              <Button
                aria-label="列表视图"
                aria-pressed={view === 'list'}
                title="列表视图"
                type={view === 'list' ? 'primary' : 'default'}
                icon={<UnorderedListOutlined />}
                disabled={editing}
                onClick={() => changeView('list')}
              />
            </Space.Compact>
            <Button icon={<ReloadOutlined />} onClick={() => void load()}>
              刷新
            </Button>
          </div>
        </div>

        {view === 'card' ? (
          <Card className="content-card production-order-card-view">
            {loading ? (
              <div className="production-order-card-loading">
                <Spin />
              </div>
            ) : items.length ? (
              <div className="production-order-card-grid">{items.map(renderProductionCard)}</div>
            ) : (
              <Empty description="暂无已保存的生产单" />
            )}
            {!loading && items.length > 0 && (
              <Pagination
                className="production-order-card-pagination"
                current={page.current}
                pageSize={page.pageSize}
                total={page.total}
                showSizeChanger
                showTotal={(total) => `共 ${total} 条`}
                onChange={(current, pageSize) =>
                  setPage((value) => ({ ...value, current, pageSize }))
                }
              />
            )}
          </Card>
        ) : (
          <Card
            className="content-card production-order-table-card"
            styles={{ body: { padding: 0 } }}
          >
            <Table
              rowKey="id"
              rowSelection={{
                selectedRowKeys: selectedKeys,
                onChange: (keys) => setSelectedKeys(keys.map(String)),
                getCheckboxProps: () => ({ disabled: editing }),
              }}
              loading={loading}
              dataSource={items}
              locale={{ emptyText: <Empty description="暂无已保存的生产单" /> }}
              tableLayout="fixed"
              pagination={{
                current: page.current,
                pageSize: page.pageSize,
                total: page.total,
                showSizeChanger: true,
                onChange: (current, pageSize) =>
                  setPage((value) => ({ ...value, current, pageSize })),
              }}
              columns={[
                {
                  title: '生产单名称',
                  dataIndex: 'productionName',
                  width: 280,
                  render: (value: string, item: ProductionUpload) =>
                    editing ? (
                      <Input
                        value={drafts[item.id]?.productionName ?? value ?? ''}
                        onChange={(event) =>
                          updateDraft(item.id, 'productionName', event.target.value)
                        }
                      />
                    ) : (
                      renderCellText(value, true)
                    ),
                },
                {
                  title: '订单号',
                  dataIndex: 'orderNo',
                  width: 135,
                  render: (value: string, item: ProductionUpload) =>
                    editing ? (
                      <Input
                        value={drafts[item.id]?.orderNo ?? value ?? ''}
                        onChange={(event) => updateDraft(item.id, 'orderNo', event.target.value)}
                      />
                    ) : (
                      renderCellText(value)
                    ),
                },
                {
                  title: '品名',
                  dataIndex: 'productName',
                  width: 180,
                  render: (value: string, item: ProductionUpload) =>
                    editing ? (
                      <Input
                        value={drafts[item.id]?.productName ?? value ?? ''}
                        onChange={(event) =>
                          updateDraft(item.id, 'productName', event.target.value)
                        }
                      />
                    ) : (
                      renderCellText(value)
                    ),
                },
                {
                  title: '类别',
                  dataIndex: 'category',
                  width: 135,
                  render: (value: string, item: ProductionUpload) =>
                    editing ? (
                      <Input
                        value={drafts[item.id]?.category ?? value ?? ''}
                        onChange={(event) => updateDraft(item.id, 'category', event.target.value)}
                      />
                    ) : (
                      renderCellText(value)
                    ),
                },
                {
                  title: '制造日期',
                  dataIndex: 'manufactureDate',
                  width: 135,
                  render: (value: string, item: ProductionUpload) =>
                    editing ? (
                      <Input
                        type="date"
                        value={drafts[item.id]?.manufactureDate ?? value ?? ''}
                        onChange={(event) =>
                          updateDraft(item.id, 'manufactureDate', event.target.value)
                        }
                      />
                    ) : (
                      renderCellText(value)
                    ),
                },
                {
                  title: '关联项目 / 阶段 / 任务',
                  width: 320,
                  render: (_: unknown, item: ProductionUpload) =>
                    renderCellText(
                      [item.projectName, item.stageName, item.taskName]
                        .filter(Boolean)
                        .join(' · ') || '未关联项目',
                    ),
                },
                {
                  title: '权限可见',
                  width: 120,
                  render: (value: ProductionUploadVisibility) => <Tag>{visibilityText[value]}</Tag>,
                },
                {
                  title: '业务状态',
                  dataIndex: 'status',
                  width: 110,
                  render: (value: string) => (
                    <Tag
                      color={
                        value === 'PUBLISHED'
                          ? 'success'
                          : value === 'FAILED'
                            ? 'error'
                            : 'processing'
                      }
                    >
                      {statusText[value] || value}
                    </Tag>
                  ),
                },
                {
                  title: '文件',
                  width: 240,
                  render: (_: unknown, item: ProductionUpload) => (
                    <Space className="production-order-file-cell">
                      {/\.docx?$/i.test(item.originalName) ? (
                        <FileWordOutlined className="excel-icon" />
                      ) : (
                        <FileExcelOutlined className="excel-icon" />
                      )}
                      <Typography.Text
                        className="production-order-cell-text"
                        ellipsis={{ tooltip: item.originalName }}
                      >
                        {item.originalName}
                      </Typography.Text>
                    </Space>
                  ),
                },
                {
                  title: '上传时间',
                  width: 170,
                  render: (_: unknown, item: ProductionUpload) =>
                    renderCellText(new Date(item.createdAt).toLocaleString('zh-CN')),
                },
                {
                  title: '操作',
                  width: 150,
                  render: (_: unknown, item: ProductionUpload) => (
                    <Space className="management-table-actions" size={0}>
                      <Button
                        type="link"
                        disabled={editing}
                        onClick={() => navigate(`/production-orders/uploads/${item.id}/workspace`)}
                      >
                        查看
                      </Button>
                      <Button
                        type="link"
                        disabled={editing || !canUpdate}
                        onClick={() => openRename(item)}
                      >
                        重命名
                      </Button>
                      <Button
                        type="link"
                        danger
                        disabled={editing || !canDelete}
                        onClick={() => deleteItem(item)}
                      >
                        删除
                      </Button>
                    </Space>
                  ),
                },
              ]}
              scroll={{ x: 'max-content' }}
            />
          </Card>
        )}
      </div>
      <Modal
        title="新建生产单"
        open={createOpen}
        confirmLoading={creating}
        okText="创建并进入工作台"
        cancelText="取消"
        onOk={() => void createOrder()}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
        }}
        destroyOnHidden
      >
        <Form form={createForm} layout="vertical">
          <Form.Item
            name="orderNo"
            label="生产单号"
            rules={[{ required: true, whitespace: true, message: '请输入生产单号' }]}
          >
            <Input placeholder="例如 PO-20260824-001" maxLength={80} />
          </Form.Item>
          <Form.Item
            name="templateVersionId"
            label="生产模板"
            rules={[{ required: true, message: '请选择已发布的 XLSX 模板' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择已发布模板"
              options={templateOptions.map((item) => ({
                value: item.currentPublishedVersionId ?? item.versionId,
                label: `${item.templateCode} · ${item.name} · V${item.currentPublishedVersionNo ?? item.versionNo}`,
              }))}
            />
          </Form.Item>
          <Space style={{ width: '100%' }} align="start">
            <Form.Item name="quantity" label="计划数量">
              <InputNumber min={0} precision={3} style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="unitCode" label="单位">
              <Select
                allowClear
                style={{ width: 120 }}
                options={[
                  { value: 'kg', label: 'kg' },
                  { value: 't', label: 't' },
                  { value: 'L', label: 'L' },
                  { value: 'pcs', label: 'pcs' },
                ]}
              />
            </Form.Item>
            <Form.Item name="plannedDate" label="计划日期">
              <Input type="date" />
            </Form.Item>
          </Space>
          {!templateOptions.length && (
            <Typography.Text type="warning">
              当前没有已发布的 XLSX 模板，请先到模板中心完成发布。
            </Typography.Text>
          )}
        </Form>
      </Modal>
      <Modal
        open={renameOpen}
        title="重命名并关联项目"
        width={720}
        confirmLoading={renameSaving}
        okText="保存"
        cancelText="取消"
        onCancel={() => setRenameOpen(false)}
        onOk={() => void saveRename()}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Form.Item label="生产单名称" style={{ marginBottom: 0 }}>
            <Input
              value={renameName}
              maxLength={200}
              placeholder="请输入生产单名称"
              onChange={(event) => setRenameName(event.target.value)}
            />
          </Form.Item>
          <Form.Item label="关联项目 / 阶段 / 任务" style={{ marginBottom: 0 }}>
            <ProjectRelationPicker
              value={renameRelations}
              onChange={setRenameRelations}
              multiple={false}
            />
          </Form.Item>
        </Space>
      </Modal>
    </>
  );
}
